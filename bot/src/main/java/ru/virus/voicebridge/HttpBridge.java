package ru.virus.voicebridge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.Executors;

/**
 * Крошечный HTTP-сервер, через который CustomRP забирает состояние.
 *
 * <p>Берём com.sun.net.httpserver из самого JDK: одна ручка, отдающая три поля, не стоит
 * ни фреймворка, ни лишних мегабайт в jar.
 */
public final class HttpBridge {

    private static final Logger log = LoggerFactory.getLogger(HttpBridge.class);

    private final VoiceTracker tracker;
    private final String authToken;
    private final HttpServer server;

    /** Слушаем только свою машину — тогда и звать нас должны по её имени. */
    private final boolean loopback;

    public HttpBridge(VoiceTracker tracker, String host, int port, String authToken) throws IOException {
        this.tracker = tracker;
        this.authToken = authToken;
        var address = new InetSocketAddress(host, port);
        this.loopback = address.getAddress() != null && address.getAddress().isLoopbackAddress();
        this.server = HttpServer.create(address, 0);

        server.createContext("/voice", this::handleVoice);
        // Одного потока хватает с запасом: клиент один и ходит раз в несколько секунд.
        server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "voice-bridge-http");
            t.setDaemon(true);
            return t;
        }));
    }

    public void start() {
        server.start();
        InetSocketAddress address = server.getAddress();
        log.info("Эндпоинт поднят: http://{}:{}/voice", address.getHostString(), address.getPort());
    }

    public void stop() {
        server.stop(0);
    }

    private void handleVoice(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"method not allowed\"}");
                return;
            }

            // Защита от подмены адреса (DNS rebinding): чужой сайт в браузере может
            // назначить своему имени адрес 127.0.0.1 и читать эндпоинт как свой —
            // а с ним и то, где ты сидишь. Но в заголовке Host останется его имя.
            // CustomRP и браузер на этой машине зовут нас по 127.0.0.1 или localhost
            if (loopback && !isLocalHost(exchange.getRequestHeaders().getFirst("Host"))) {
                respond(exchange, 403, "{\"error\":\"forbidden host\"}");
                return;
            }

            if (!authToken.isEmpty() && !sameToken(exchange.getRequestHeaders().getFirst("X-Auth-Token"))) {
                respond(exchange, 401, "{\"error\":\"unauthorized\"}");
                return;
            }

            respond(exchange, 200, tracker.getSnapshot().toJson(tracker.isConnected()));
        } finally {
            exchange.close();
        }
    }

    /**
     * Совпадает ли токен — за одно и то же время, чем бы ни отличался.
     *
     * <p>Заголовки сервер читает побайтно, как ISO-8859-1. Обратно в байты —
     * так же, и тогда токен кириллицей, присланный в UTF-8, тоже совпадёт.
     */
    private boolean sameToken(String given) {
        return given != null && MessageDigest.isEqual(
                authToken.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.ISO_8859_1));
    }

    /** Имя из заголовка Host — своя ли это машина. Без заголовка — старый клиент, пускаем. */
    static boolean isLocalHost(String header) {
        if (header == null || header.isBlank()) {
            return true;
        }

        var host = header.trim().toLowerCase(java.util.Locale.ROOT);

        if (host.startsWith("[")) {
            var end = host.indexOf(']');
            host = end > 0 ? host.substring(1, end) : host;
        } else if (host.indexOf(':') == host.lastIndexOf(':') && host.contains(":")) {
            host = host.substring(0, host.indexOf(':'));
        }

        return host.equals("localhost") || host.equals("::1") || host.matches("127(\\.\\d{1,3}){3}");
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        // Ответ меняется каждую секунду — пусть никто его не кэширует.
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
