package ru.virus.voicebridge;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookRateLimitTest {

    HttpServer server;
    final AtomicInteger calls = new AtomicInteger();

    String serve(int limitedTimes, String retryAfter) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            var n = calls.incrementAndGet();
            var limited = n <= limitedTimes;
            var body = (limited
                    ? "{\"message\": \"The resource is being rate limited.\", \"retry_after\": " + retryAfter + ", \"global\": false}"
                    : "{\"id\": \"555\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(limited ? 429 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/webhooks/1/x";
    }

    @AfterEach
    void down() {
        server.stop(0);
    }

    @Test
    void waitsAsDiscordAsksAndStillSendsAsWebhook() throws Exception {
        var hook = new DiscordWebhook(serve(2, "0.3"));
        var started = System.currentTimeMillis();

        assertEquals(555, hook.send("Вася · Telegram", "", "привет", "", null));

        var took = System.currentTimeMillis() - started;
        assertEquals(3, calls.get());
        assertTrue(took >= 700, "должен был подождать дважды по 0,3 с, а прошло " + took + " мс");
    }

    @Test
    void givesUpAfterManyRefusals() throws Exception {
        var hook = new DiscordWebhook(serve(100, "0.05"));

        var error = assertThrows(IOException.class, () -> hook.send("Вася", "", "привет", "", null));
        assertTrue(error.getMessage().contains("429"), error.getMessage());
        assertEquals(6, calls.get(), "пять ожиданий и последний отказ");
    }
}
