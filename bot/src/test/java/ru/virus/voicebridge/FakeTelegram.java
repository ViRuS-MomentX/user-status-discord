package ru.virus.voicebridge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Поддельный сервер Bot API: отдаёт подложенные обновления и записывает,
 * что бот отправил.
 */
final class FakeTelegram implements AutoCloseable {

    static final long CHAT = -100500;

    final List<Map<String, String>> sent = new CopyOnWriteArrayList<>();
    final List<Map<String, String>> actions = new CopyOnWriteArrayList<>();
    final List<Long> sentIds = new CopyOnWriteArrayList<>();
    volatile String commands;

    private final HttpServer server;
    private final BlockingQueue<DataObject> updates = new LinkedBlockingQueue<>();
    private final AtomicLong updateId = new AtomicLong(1);
    private final AtomicLong messageId = new AtomicLong(1000);

    FakeTelegram() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Подкладывает сообщение в группу; возвращает его номер. */
    long say(long authorId, String author, String text) {
        return say(CHAT, authorId, author, false, text, null);
    }

    long say(long chat, long authorId, String author, boolean bot, String text, DataObject replyTo) {
        var id = messageId.incrementAndGet();
        var message = DataObject.empty()
                .put("message_id", id)
                .put("chat", DataObject.empty().put("id", chat))
                .put("from", DataObject.empty().put("id", authorId).put("first_name", author)
                        .put("is_bot", bot))
                .put("text", text);

        if (replyTo != null) {
            message.put("reply_to_message", replyTo);
        }

        updates.add(DataObject.empty().put("update_id", updateId.getAndIncrement())
                .put("message", message));
        return id;
    }

    /** Пересланное сообщение. */
    void sayForwarded(long authorId, String author, String text) {
        updates.add(DataObject.empty().put("update_id", updateId.getAndIncrement())
                .put("message", DataObject.empty()
                        .put("message_id", messageId.incrementAndGet())
                        .put("chat", DataObject.empty().put("id", CHAT))
                        .put("from", DataObject.empty().put("id", authorId).put("first_name", author))
                        .put("forward_origin", DataObject.empty().put("type", "hidden_user"))
                        .put("text", text)));
    }

    /** Фото без подписи. */
    void sayPhoto(long authorId, String author) {
        updates.add(DataObject.empty().put("update_id", updateId.getAndIncrement())
                .put("message", DataObject.empty()
                        .put("message_id", messageId.incrementAndGet())
                        .put("chat", DataObject.empty().put("id", CHAT))
                        .put("from", DataObject.empty().put("id", authorId).put("first_name", author))
                        .put("photo", DataArray.empty().add(DataObject.empty().put("file_id", "F")))));
    }

    /** Ответ на сообщение с этим номером — как его видит Telegram. */
    static DataObject original(long id, long authorId, String author, boolean bot, String text) {
        return DataObject.empty()
                .put("message_id", id)
                .put("from", DataObject.empty().put("id", authorId).put("first_name", author)
                        .put("is_bot", bot))
                .put("text", text);
    }

    /** Ждёт, пока бот что-нибудь отправит. */
    Map<String, String> awaitSent(int count, long millis) throws InterruptedException {
        var deadline = System.currentTimeMillis() + millis;

        while (sent.size() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        return sent.size() >= count ? sent.get(count - 1) : null;
    }

    /** Кого забанили: chat_id и user_id. */
    final List<Map<String, String>> bans = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Админы группы — для getChatMember. */
    final java.util.Set<Long> admins = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private void handle(HttpExchange exchange) throws IOException {
        var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        var form = parse(body);
        var path = exchange.getRequestURI().getPath();
        var method = path.substring(path.lastIndexOf('/') + 1);

        DataObject answer = DataObject.empty().put("ok", true);

        switch (method) {
            case "getMe" -> answer.put("result", DataObject.empty()
                    .put("id", 777).put("username", "parody_bot")
                    .put("can_read_all_group_messages", true));
            case "getChat" -> answer.put("result", DataObject.empty().put("id", CHAT));
            case "getUpdates" -> answer.put("result", poll(form));
            case "sendMessage" -> {
                var id = messageId.incrementAndGet();
                sentIds.add(id);
                sent.add(form);
                answer.put("result", DataObject.empty().put("message_id", id));
            }
            case "setMyCommands" -> {
                commands = form.get("commands");
                answer.put("result", true);
            }
            case "banChatMember" -> {
                bans.add(form);
                answer.put("result", true);
            }
            case "getChatMember" -> answer.put("result", DataObject.empty()
                    .put("status", admins.contains(Long.parseLong(form.get("user_id"))) ? "administrator" : "member"));
            case "sendChatAction" -> {
                actions.add(form);
                answer.put("result", true);
            }
            default -> answer = DataObject.empty().put("ok", false).put("description", "нет " + method);
        }

        var bytes = answer.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private DataArray poll(Map<String, String> form) {
        var result = DataArray.empty();

        // Отметка «до какого прочитал» проверяется ботом, а не здесь: сюда приходит
        // только новое, так что отдаём всё, что накопилось
        if ("0".equals(form.get("timeout"))) {
            return result;
        }

        try {
            var first = updates.poll(300, TimeUnit.MILLISECONDS);

            if (first != null) {
                result.add(first);
                DataObject next;

                while ((next = updates.poll()) != null) {
                    result.add(next);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return result;
    }

    private static Map<String, String> parse(String body) {
        var form = new HashMap<String, String>();

        for (var pair : body.split("&")) {
            var eq = pair.indexOf('=');

            if (eq > 0) {
                form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }

        return form;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
