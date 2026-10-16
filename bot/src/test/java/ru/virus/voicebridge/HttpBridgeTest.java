package ru.virus.voicebridge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpBridgeTest {

    HttpBridge bridge;

    @AfterEach
    void down() {
        if (bridge != null) {
            bridge.stop();
        }
    }

    /** Сырой запрос — чтобы подставить любой Host, как это сделал бы чужой сайт. */
    static String get(int port, String host, String token) throws Exception {
        try (var socket = new Socket("127.0.0.1", port)) {
            var request = "GET /voice HTTP/1.1\r\nHost: " + host + "\r\n"
                    + (token == null ? "" : "X-Auth-Token: " + token + "\r\n")
                    + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    int start(String token) throws Exception {
        try (var probe = new java.net.ServerSocket(0)) {
            var port = probe.getLocalPort();
            probe.close();
            bridge = new HttpBridge(new VoiceTracker(1, 2), "127.0.0.1", port, token);
            bridge.start();
            return port;
        }
    }

    @Test
    void answersLocalCallers() throws Exception {
        var port = start("");

        assertTrue(get(port, "127.0.0.1:" + port, null).startsWith("HTTP/1.1 200"));
        assertTrue(get(port, "localhost:" + port, null).startsWith("HTTP/1.1 200"));
    }

    @Test
    void refusesRebindedSite() throws Exception {
        var port = start("");

        var answer = get(port, "evil.example:" + port, null);
        assertTrue(answer.startsWith("HTTP/1.1 403"), answer);
        assertFalse(answer.contains("inVoice"));
    }

    @Test
    void checksToken() throws Exception {
        var port = start("секрет");

        assertTrue(get(port, "127.0.0.1", null).startsWith("HTTP/1.1 401"));
        assertTrue(get(port, "127.0.0.1", "не тот").startsWith("HTTP/1.1 401"));
        assertTrue(get(port, "127.0.0.1", "секрет").startsWith("HTTP/1.1 200"));
    }

    @Test
    void localHostNames() {
        assertTrue(HttpBridge.isLocalHost("127.0.0.1:7373"));
        assertTrue(HttpBridge.isLocalHost("LOCALHOST"));
        assertTrue(HttpBridge.isLocalHost("[::1]:7373"));
        assertTrue(HttpBridge.isLocalHost(null));
        assertFalse(HttpBridge.isLocalHost("127.0.0.1.evil.example"));
        assertFalse(HttpBridge.isLocalHost("localhost.evil.example:7373"));
        assertFalse(HttpBridge.isLocalHost("192.168.1.2:7373"));
    }
}
