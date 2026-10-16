package ru.virus.voicebridge;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvatarTest {

    HttpServer server;

    @AfterEach
    void down() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void signatureMatchesWorker() {
        // Посчитано тем же HMAC в Node — так, как считает worker.js
        assertEquals("d24559c8ed8cb01188079a2eba18e396be1f5a9ec93d0ddf7151711498008257",
                Telegram.sign("123:ABC", "profile_photos/file_7.jpg"));
    }

    String serve(int avatarStatus) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/avatar/", exchange -> {
            exchange.sendResponseHeaders(avatarStatus, -1);
            exchange.close();
        });
        server.start();
        // Не localhost, а имя: свой сервер в интернете, как пересыльщик Cloudflare
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void newWorkerGetsLinkWithoutToken() throws Exception {
        var url = new Telegram("123:ABC", "", serve(200)).avatarUrl("profile_photos/file_7.jpg");

        assertFalse(url.contains("123:ABC"), url);
        assertTrue(url.contains("/avatar/d24559c8"), url);
    }

    @Test
    void oldWorkerFallsBackToWorkingLink() throws Exception {
        var url = new Telegram("123:ABC", "", serve(404)).avatarUrl("profile_photos/file_7.jpg");

        assertTrue(url.endsWith("/file/bot123:ABC/profile_photos/file_7.jpg"), url);
    }

    @Test
    void officialApiKeepsTelegramLink() throws Exception {
        var url = new Telegram("123:ABC").avatarUrl("profile_photos/file_7.jpg");

        assertEquals("https://api.telegram.org/file/bot123:ABC/profile_photos/file_7.jpg", url);
    }
}
