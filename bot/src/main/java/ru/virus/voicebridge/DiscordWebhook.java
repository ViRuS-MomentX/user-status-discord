package ru.virus.voicebridge;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * Отправка в канал Discord через вебхук.
 *
 * <p>Обычный бот пишет всё под одним именем и одной аватаркой. Вебхук позволяет задать
 * их для каждого сообщения — и переписка из Telegram выглядит перепиской живых людей,
 * а не лентой от робота.
 */
public final class DiscordWebhook {

    /** Предел Discord на имя отправителя. */
    private static final int NAME_LIMIT = 80;

    /** Предел на текст одного сообщения. */
    private static final int TEXT_LIMIT = 2000;

    /**
     * Меняется на новый после обрыва, поэтому не final.
     *
     * <p>Java оставляет порванное соединение в пуле, и следующая отправка уходит
     * в ту же дыру. Снаружи это выглядело как «бот иногда не пользуется вебхуком»:
     * он послушно откатывался на запасной путь и писал от своего имени.
     */
    private volatile HttpClient http = build();

    /**
     * Просим HTTP/1.1: по HTTP/2 Java сводит все запросы к хосту в одно соединение,
     * и обрыв роняет их все разом.
     */
    private static HttpClient build() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    private final String url;

    public DiscordWebhook(String url) {
        this.url = url;
    }

    /**
     * Пишет сообщение от имени человека.
     *
     * @param avatar ссылка на аватарку; пустая строка — аватарка самого вебхука
     * @param file содержимое вложения; <code>null</code>, если его нет
     */
    public long send(String name, String avatar, String text, String fileName, byte[] file)
            throws IOException {
        var payload = DataObject.empty()
                .put("username", name(name))
                .put("content", text.length() > TEXT_LIMIT
                        ? text.substring(0, TEXT_LIMIT - 1) + "…" : text)
                // Упоминания обезвреживаем: писавший в Telegram не должен уметь дёрнуть
                // @everyone на сервере, куда его даже не приглашали
                .put("allowed_mentions", DataObject.empty().put("parse", DataArray.empty()));

        if (!avatar.isBlank()) {
            payload.put("avatar_url", avatar);
        }

        if (file == null) {
            return sentId(post(payload.toString().getBytes(StandardCharsets.UTF_8),
                    "application/json"));
        }

        var boundary = "vb" + System.nanoTime();
        var body = new ByteArrayOutputStream();

        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; "
                + "name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n"
                + payload + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; "
                + "name=\"files[0]\"; filename=\"" + fileName.replace('"', '_')
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.writeBytes(file);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        return sentId(post(body.toByteArray(), "multipart/form-data; boundary=" + boundary));
    }

    /**
     * Заменяет клиента после обрыва — вместе с ним уходит пул порванных соединений.
     * Меняем только тот, которым ходили: иначе два потока пересоздадут его дважды.
     */
    private synchronized void renew(HttpClient broken) {
        if (http == broken) {
            http = build();
        }
    }

    /** Номер созданного сообщения из ответа Discord; 0, если разобрать не вышло. */
    private static long sentId(String answer) {
        try {
            return Long.parseLong(DataObject.fromJson(answer).getString("id", "0"));
        } catch (Exception silent) {
            // Номер нужен только ответам. Не разобрали — просто не свяжем эту пару
            return 0;
        }
    }

    /**
     * Отправляет и возвращает ответ Discord.
     *
     * <p>К ссылке добавлен {@code wait=true}: без него Discord отвечает пустотой,
     * не дожидаясь создания сообщения, и узнать его номер неоткуда — а без номера
     * ответы с той стороны не к чему привязать.
     */
    private String post(byte[] body, String contentType) throws IOException {
        var waiting = url.contains("?") ? url + "&wait=true" : url + "?wait=true";
        var request = HttpRequest.newBuilder(URI.create(waiting))
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        var broken = 0;
        var limited = 0;

        while (true) {
            // Discord заранее сказал, что лимит исчерпан, — ждём, а не получаем отказ
            sleepUntil(pauseUntil);
            var client = http;

            try {
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                remember(response);

                // Слишком часто. У вебхука предел — около пяти сообщений за пару
                // секунд, а в живой группе пишут быстрее. Раньше на этом сообщение
                // уходило запасным путём, от имени бота, без ника и аватарки
                if (response.statusCode() == 429 && limited < RATE_LIMIT_RETRIES) {
                    limited++;
                    pauseUntil = System.currentTimeMillis() + retryAfter(response);
                    continue;
                }

                if (response.statusCode() / 100 != 2) {
                    throw new IOException("вебхук ответил " + response.statusCode() + ": "
                            + response.body());
                }

                return response.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("отправка прервана");
            } catch (IOException failure) {
                if (failure.getMessage() != null && failure.getMessage().startsWith("вебхук ответил")) {
                    throw failure;
                }

                // Вторая попытка — на свежем соединении. Обрыв обычно рвёт именно то,
                // что лежало в пуле, и повтор проходит
                renew(client);

                if (broken++ > 0) {
                    throw failure;
                }
            }
        }
    }

    /** Сколько раз подряд ждать по просьбе Discord, прежде чем сдаться. */
    private static final int RATE_LIMIT_RETRIES = 5;

    /** Дольше не ждём: значит, что-то не так, пусть сообщение уйдёт запасным путём. */
    private static final long LONGEST_WAIT_MS = 15_000;

    /** До какого времени Discord просил не писать. */
    private volatile long pauseUntil;

    /** Заголовки лимита: «осталось 0, обнулится через N секунд» — запомним паузу заранее. */
    private void remember(HttpResponse<String> response) {
        var remaining = response.headers().firstValue("X-RateLimit-Remaining").orElse("");
        var resetAfter = response.headers().firstValue("X-RateLimit-Reset-After").orElse("");

        if (remaining.equals("0") && !resetAfter.isBlank()) {
            try {
                pauseUntil = System.currentTimeMillis() + Math.min(LONGEST_WAIT_MS,
                        (long) (Double.parseDouble(resetAfter) * 1000) + 50);
            } catch (NumberFormatException ignored) {
                // непонятный заголовок — обойдёмся без паузы
            }
        }
    }

    /** Сколько ждать после 429: из тела (retry_after в секундах) или заголовка Retry-After. */
    static long retryAfter(HttpResponse<String> response) {
        double seconds = 1;

        try {
            seconds = DataObject.fromJson(response.body()).getDouble("retry_after", 1);
        } catch (RuntimeException e) {
            try {
                seconds = Double.parseDouble(response.headers().firstValue("Retry-After").orElse("1"));
            } catch (NumberFormatException ignored) {
                // так и оставим секунду
            }
        }

        return Math.min(LONGEST_WAIT_MS, Math.max(100, (long) (seconds * 1000) + 100));
    }

    private static void sleepUntil(long moment) throws IOException {
        var wait = moment - System.currentTimeMillis();

        if (wait <= 0) {
            return;
        }

        try {
            Thread.sleep(Math.min(wait, LONGEST_WAIT_MS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("ожидание прервано");
        }
    }

    /**
     * Приводит имя к тому, что Discord примет.
     *
     * <p>Слова «discord» и «clyde» он в именах вебхуков запрещает — сообщение с таким
     * именем просто не отправится. А пустое имя бывает у тех, кто в Telegram скрыл всё.
     */
    static String name(String name) {
        var clean = name.replaceAll("(?i)discord", "disc0rd").replaceAll("(?i)clyde", "clyd3").trim();

        if (clean.isBlank()) {
            clean = "Аноним";
        }

        return clean.length() > NAME_LIMIT ? clean.substring(0, NAME_LIMIT) : clean;
    }

    /**
     * Номер вебхука из его ссылки; 0, если ссылки нет или она не та.
     *
     * <p>Нужен, чтобы отличить свои же сообщения от чужих: под этим номером
     * Discord показывает всё, что бот принёс из Telegram, и отправлять это
     * обратно нельзя — получился бы бесконечный круг.
     */
    public static long idOf(String url) {
        if (!looksRight(url)) {
            return 0;
        }

        // .../webhooks/<номер>/<ключ>
        var parts = url.split("/");

        for (var i = 0; i < parts.length - 1; i++) {
            if (parts[i].equalsIgnoreCase("webhooks")) {
                try {
                    return Long.parseLong(parts[i + 1]);
                } catch (NumberFormatException wrong) {
                    return 0;
                }
            }
        }

        return 0;
    }

    /** Похоже ли это на ссылку вебхука Discord. */
    public static boolean looksRight(String url) {
        var lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("https://discord.com/api/webhooks/")
                || lower.startsWith("https://discordapp.com/api/webhooks/")
                || lower.startsWith("https://canary.discord.com/api/webhooks/");
    }
}
