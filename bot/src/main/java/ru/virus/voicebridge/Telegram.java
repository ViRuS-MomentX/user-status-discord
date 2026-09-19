package ru.virus.voicebridge;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Клиент Telegram Bot API — ровно столько, сколько нужно мосту.
 *
 * <p>Библиотеки здесь не нужны: три запроса и разбор ответа, а лишняя зависимость
 * означала бы лишние мегабайты в jar и ещё одно место, где что-то устареет.
 */
public final class Telegram {

    private static final Logger log = LoggerFactory.getLogger(Telegram.class);

    private static final String API = "https://api.telegram.org/bot";

    /** Сколько Telegram держит соединение, ожидая новых сообщений. */
    private static final int POLL_SECONDS = 25;

    private final HttpClient http;
    private final String token;

    public Telegram(String token) {
        this(token, "");
    }

    /**
     * @param proxy «хост:порт» HTTP-прокси; пустая строка — идти напрямую
     */
    public Telegram(String token, String proxy) {
        this.token = token;

        var builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL);

        if (!proxy.isBlank()) {
            apply(builder, proxy.trim());
        }

        this.http = builder.build();
    }

    /**
     * Настраивает выход через прокси.
     *
     * <p>Строка вида «хост:порт» или «логин:пароль@хост:порт»: у платных прокси вход
     * почти всегда по паролю, и без него настройка была бы бесполезной ровно там,
     * где она нужнее всего.
     */
    private static void apply(HttpClient.Builder builder, String proxy) {
        var address = proxy;
        String user = null;
        String password = null;

        var at = proxy.lastIndexOf('@');

        if (at > 0) {
            var credentials = proxy.substring(0, at);
            address = proxy.substring(at + 1);

            var colon = credentials.indexOf(':');

            if (colon > 0) {
                user = credentials.substring(0, colon);
                password = credentials.substring(colon + 1);
            }
        }

        var colon = address.lastIndexOf(':');

        if (colon <= 0) {
            log.error("bridge.telegram.proxy должен быть «хост:порт» или "
                    + "«логин:пароль@хост:порт», а не «{}». Иду напрямую.", proxy);
            return;
        }

        int port;

        try {
            port = Integer.parseInt(address.substring(colon + 1).trim());
        } catch (NumberFormatException e) {
            log.error("В bridge.telegram.proxy порт должен быть числом: «{}». Иду напрямую.", proxy);
            return;
        }

        builder.proxy(java.net.ProxySelector.of(
                new java.net.InetSocketAddress(address.substring(0, colon).trim(), port)));

        if (user == null) {
            log.info("Telegram через прокси {}.", address);
            return;
        }

        // Java по умолчанию не даёт слать пароль прокси при защищённом соединении.
        // Запрет разумный для чужих сетей, но здесь прокси выбрал сам хозяин бота
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");

        var login = user;
        var secret = password == null ? "" : password;

        builder.authenticator(new java.net.Authenticator() {
            @Override
            protected java.net.PasswordAuthentication getPasswordAuthentication() {
                return getRequestorType() == RequestorType.PROXY
                        ? new java.net.PasswordAuthentication(login, secret.toCharArray())
                        : null;
            }
        });

        log.info("Telegram через прокси {} под именем {}.", address, login);
    }

    /** Проверяет токен и заодно узнаёт имя бота. */
    public String whoAmI() throws IOException {
        var answer = call("getMe", Map.of());
        return answer.getObject("result").getString("username", "");
    }

    /**
     * Шлёт текст.
     *
     * <p>Разметка HTML, поэтому написанное человеком экранируется: иначе угловая скобка
     * в чужом сообщении оборвала бы отправку, а то и подменила оформление.
     */
    public void sendMessage(String chat, String author, String text) throws IOException {
        call("sendMessage", Map.of(
                "chat_id", chat,
                "parse_mode", "HTML",
                "disable_web_page_preview", "true",
                "text", card(author, text)));
    }

    /**
     * Оформляет сообщение из Discord.
     *
     * <p>Имя жирным, сказанное моноширинным. Моноширинный шрифт здесь не украшение:
     * он сразу отделяет пришедшее с той стороны от того, что пишут в самой группе,
     * и заодно не даёт чужой разметке разъехаться по сообщению.
     *
     * <p>Многострочное уходит блоком, однострочное — строкой: блок ради одной фразы
     * занимал бы пол-экрана.
     */
    static String card(String author, String text) {
        var head = "<b>" + escape(author) + "</b> <i>· Discord</i>";

        if (text.isBlank()) {
            return head;
        }

        var body = escape(text);

        return head + "\n" + (text.contains("\n")
                ? "<pre>" + body + "</pre>"
                : "<code>" + body + "</code>");
    }

    /**
     * Шлёт файл вместе с подписью.
     *
     * @param photo отправить как картинку, а не как вложение
     */
    public void sendFile(String chat, String author, String text, String fileName,
                         byte[] data, boolean photo) throws IOException {
        var method = photo ? "sendPhoto" : "sendDocument";
        var field = photo ? "photo" : "document";

        var fields = new LinkedHashMap<String, String>();
        fields.put("chat_id", chat);
        fields.put("parse_mode", "HTML");
        fields.put("caption", card(author, text));

        upload(method, fields, field, fileName, data);
    }

    /**
     * Забирает новые сообщения.
     *
     * <p>Запрос висит до двадцати пяти секунд и возвращается сразу, как что-то пришло:
     * так и узнаём о новом без опроса раз в секунду.
     *
     * @param offset номер, с которого продолжать; ноль — с самого начала
     */
    public List<TelegramMessage> poll(long offset) throws IOException {
        var parameters = new LinkedHashMap<String, String>();
        parameters.put("timeout", String.valueOf(POLL_SECONDS));
        parameters.put("allowed_updates", "[\"message\"]");

        if (offset > 0) {
            parameters.put("offset", String.valueOf(offset));
        }

        var answer = call("getUpdates", parameters, Duration.ofSeconds(POLL_SECONDS + 15));
        var updates = answer.getArray("result");
        var messages = new ArrayList<TelegramMessage>();

        for (var i = 0; i < updates.length(); i++) {
            var message = TelegramMessage.from(updates.getObject(i));

            if (message != null) {
                messages.add(message);
            }
        }

        return messages;
    }

    /**
     * Номер последнего обновления в пачке — даже если переносить в ней было нечего.
     *
     * <p>Без этого служебные обновления забирались бы снова и снова: Telegram отдаёт
     * их, пока не скажешь, что прочитал.
     */
    public long lastUpdateId(long offset) throws IOException {
        var parameters = new LinkedHashMap<String, String>();
        parameters.put("timeout", "0");
        parameters.put("allowed_updates", "[\"message\"]");

        if (offset > 0) {
            parameters.put("offset", String.valueOf(offset));
        }

        var updates = call("getUpdates", parameters).getArray("result");
        var last = offset;

        for (var i = 0; i < updates.length(); i++) {
            last = Math.max(last, updates.getObject(i).getLong("update_id", 0) + 1);
        }

        return last;
    }

    /**
     * Ищет аватарку человека.
     *
     * @return ссылка на картинку или пустая строка, если аватарки нет
     */
    public String avatar(long userId) throws IOException {
        var photos = call("getUserProfilePhotos", Map.of(
                "user_id", String.valueOf(userId),
                "limit", "1")).getObject("result").getArray("photos");

        if (photos.isEmpty()) {
            return "";
        }

        var sizes = photos.getArray(0);

        if (sizes.isEmpty()) {
            return "";
        }

        // Последний размер — самый крупный; Discord всё равно ужмёт его под кружок
        var fileId = sizes.getObject(sizes.length() - 1).getString("file_id", "");

        return fileId.isEmpty() ? "" : fileUrl(fileId);
    }

    /** Прямая ссылка на файл Telegram. */
    public String fileUrl(String fileId) throws IOException {
        var path = call("getFile", Map.of("file_id", fileId))
                .getObject("result").getString("file_path", "");

        if (path.isEmpty()) {
            throw new IOException("Telegram не сказал, где лежит файл");
        }

        return "https://api.telegram.org/file/bot" + token + "/" + path;
    }

    /** Скачивает вложение по его идентификатору. */
    public byte[] download(String fileId, int maxBytes) throws IOException {
        return fetch(fileUrl(fileId), maxBytes);
    }

    /**
     * Читает файл по ссылке, не пуская в память больше дозволенного.
     */
    public byte[] fetch(String url, int maxBytes) throws IOException {
        var request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();

        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                throw new IOException("ответ " + response.statusCode());
            }

            try (var body = response.body()) {
                var buffer = new ByteArrayOutputStream();
                var chunk = new byte[8192];
                int read;

                while ((read = body.read(chunk)) > 0) {
                    buffer.write(chunk, 0, read);

                    // Обрываем на месте: чужой файл не должен решать, сколько нам занять памяти
                    if (buffer.size() > maxBytes) {
                        throw new IOException("файл больше " + (maxBytes / 1024 / 1024) + " МБ");
                    }
                }

                return buffer.toByteArray();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("чтение прервано");
        }
    }

    private DataObject call(String method, Map<String, String> parameters) throws IOException {
        return call(method, parameters, Duration.ofSeconds(30));
    }

    private DataObject call(String method, Map<String, String> parameters, Duration timeout)
            throws IOException {
        var body = new StringBuilder();

        parameters.forEach((key, value) -> {
            if (!body.isEmpty()) {
                body.append('&');
            }
            body.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        });

        var request = HttpRequest.newBuilder(URI.create(API + token + "/" + method))
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        return answer(request, method);
    }

    /**
     * Отправляет файл. Telegram принимает его только многочастной формой.
     */
    private void upload(String method, Map<String, String> fields, String fileField,
                        String fileName, byte[] data) throws IOException {
        var boundary = "vb" + System.nanoTime();
        var body = new ByteArrayOutputStream();

        for (var entry : fields.entrySet()) {
            body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\""
                    + entry.getKey() + "\"\r\n\r\n" + entry.getValue() + "\r\n")
                    .getBytes(StandardCharsets.UTF_8));
        }

        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\""
                + fileField + "\"; filename=\"" + fileName.replace('"', '_')
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.writeBytes(data);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        var request = HttpRequest.newBuilder(URI.create(API + token + "/" + method))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();

        answer(request, method);
    }

    private DataObject answer(HttpRequest request, String method) throws IOException {
        HttpResponse<String> response;

        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("запрос прерван");
        }

        DataObject answer;

        try {
            answer = DataObject.fromJson(response.body());
        } catch (Exception e) {
            throw new IOException(method + ": Telegram ответил не по-человечески ("
                    + response.statusCode() + ")");
        }

        if (!answer.getBoolean("ok", false)) {
            // Отдельный вид: до Telegram мы дошли, и это он нас развернул. Отличать
            // важно — неверный токен лечится человеком, а обрыв связи проходит сам
            throw new RejectedException(method + ": "
                    + answer.getString("description", "отказ без объяснений"));
        }

        return answer;
    }

    /** Telegram ответил и отказал: дело в запросе или в токене, а не в связи. */
    public static final class RejectedException extends IOException {
        private static final long serialVersionUID = 1L;

        public RejectedException(String message) {
            super(message);
        }
    }

    /** Экранирует то, что в HTML значит не то, что написано. */
    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
