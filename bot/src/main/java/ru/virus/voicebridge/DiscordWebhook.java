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

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

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
    public void send(String name, String avatar, String text, String fileName, byte[] file)
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
            post(payload.toString().getBytes(StandardCharsets.UTF_8), "application/json");
            return;
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

        post(body.toByteArray(), "multipart/form-data; boundary=" + boundary);
    }

    private void post(byte[] body, String contentType) throws IOException {
        var request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() / 100 != 2) {
                throw new IOException("вебхук ответил " + response.statusCode() + ": "
                        + response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("отправка прервана");
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

    /** Похоже ли это на ссылку вебхука Discord. */
    public static boolean looksRight(String url) {
        var lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("https://discord.com/api/webhooks/")
                || lower.startsWith("https://discordapp.com/api/webhooks/")
                || lower.startsWith("https://canary.discord.com/api/webhooks/");
    }
}
