package ru.virus.voicebridge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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

    public HttpBridge(VoiceTracker tracker, String host, int port, String authToken) throws IOException {
        this.tracker = tracker;
        this.authToken = authToken;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);

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

            if (!authToken.isEmpty() && !authToken.equals(exchange.getRequestHeaders().getFirst("X-Auth-Token"))) {
                respond(exchange, 401, "{\"error\":\"unauthorized\"}");
                return;
            }

            respond(exchange, 200, tracker.getSnapshot().toJson(tracker.isConnected()));
        } finally {
            exchange.close();
        }
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
