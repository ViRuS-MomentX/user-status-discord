package ru.virus.manager;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalRgbTest {

    HttpServer server;
    final List<String> calls = new CopyOnWriteArrayList<>();

    @AfterEach
    void down() {
        if (server != null) {
            server.stop(0);
        }
    }

    SignalRgb fake() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var path = exchange.getRequestURI().getPath();
            calls.add(exchange.getRequestMethod() + " " + path + (body.isEmpty() ? "" : " " + body));
            var answer = switch (path) {
                case "/api/v1/lighting/effects" -> """
                        {"api_version":"1.0","id":1,"method":"/api/v1/lighting/effects","status":"ok","data":{"items":[
                        {"id":"Rainbow Wave.html","type":"effect","attributes":{"name":"Rainbow Wave"},
                         "links":{"apply":"/api/v1/lighting/effects/Rainbow%20Wave.html/apply"}},
                        {"id":"VIRUS.html","type":"effect","attributes":{"name":"VIRUS"},
                         "links":{"apply":"/api/v1/lighting/effects/VIRUS.html/apply"}}]}}""";
                case "/api/v1/lighting" -> """
                        {"status":"ok","data":{"id":"VIRUS.html","type":"current_state",
                         "attributes":{"enabled":true,"global_brightness":70,"name":"VIRUS"}}}""";
                default -> "{\"status\":\"ok\"}";
            };
            var bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new SignalRgb("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    void talksToSignalRgbApi() throws Exception {
        var signal = fake();

        var effects = signal.effects();
        assertEquals(List.of("Rainbow Wave", "VIRUS"), effects.stream().map(SignalRgb.Effect::name).toList());

        var state = signal.state();
        assertTrue(state.enabled());
        assertEquals(70, state.brightness());
        assertEquals("VIRUS", state.effectName());

        signal.apply(effects.getFirst());
        signal.brightness(140);
        signal.enabled(false);
        signal.next();

        assertTrue(calls.contains("POST /api/v1/lighting/effects/Rainbow Wave.html/apply"), calls.toString());
        assertTrue(calls.contains("PATCH /api/v1/lighting/global_brightness {\"global_brightness\":100}"), calls.toString());
        assertTrue(calls.contains("PATCH /api/v1/lighting/enabled {\"enabled\":false}"), calls.toString());
        assertTrue(calls.contains("POST /api/v1/lighting/next"), calls.toString());
    }

    @Test
    void effectAsksVirusForSettings() throws Exception {
        try (var live = new SignalRgb.Live(() -> 0.42)) {
            live.mode = 5;
            live.color = new Color(0xFF0000);
            var port = freePort();
            live.start(port);
            assertFalse(live.heard());

            var response = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/state")).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals("*", response.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
            assertTrue(response.body().contains("\"mode\":5"), response.body());
            assertTrue(response.body().contains("\"color\":\"#ff0000\""), response.body());
            assertTrue(response.body().contains("\"cpu\":0.420"), response.body());
            assertTrue(live.heard());
        }
    }

    static int freePort() throws Exception {
        try (var socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void effectFileAndLink(@TempDir Path dir) throws Exception {
        var html = SignalRgb.effectHtml();
        assertTrue(html.contains("<title>VIRUS</title>"));
        assertTrue(html.contains("127.0.0.1:" + SignalRgb.OUR_PORT));
        // Каждый режим из приложения есть в эффекте
        assertTrue(html.contains("max=\"" + SignalRgb.MODES.getLast().id() + "\""), "ползунок режима до последнего");

        assertTrue(SignalRgb.install(dir));
        assertFalse(SignalRgb.install(dir), "тот же файл второй раз не переписываем");
        assertTrue(Files.isRegularFile(dir.resolve("VIRUS.html")));

        var live = new SignalRgb.Live(() -> 0);
        live.mode = 4;
        live.color2 = new Color(0x0000FF);
        var url = SignalRgb.applyUrl(live);
        assertTrue(url.startsWith("signalrgb://effect/apply/VIRUS?mode=4"), url);
        assertTrue(url.contains("color2=%230000ff"), url);
        assertTrue(url.endsWith("&-silentlaunch-"), url);
    }

    @Test
    void lockedApiFallsBackToLinksAndDiskEffects(@TempDir Path dir) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });
        server.start();
        var signal = new SignalRgb("http://127.0.0.1:" + server.getAddress().getPort());
        org.junit.jupiter.api.Assertions.assertThrows(SignalRgb.Locked.class, signal::effects);

        var library = Files.createDirectories(dir.resolve("VortxEngine/app-2.4/Effects/Neon Shift"));
        Files.writeString(library.resolve("Neon Shift.html"), "<head><title>Neon Shift</title></head><body><canvas id=\"exCanvas\"></canvas></body>");
        Files.writeString(library.resolve("readme.html"), "<head><title>Справка</title></head><body>без холста</body>");
        Files.writeString(dir.resolve("VIRUS.html"), SignalRgb.effectHtml());

        assertEquals(List.of("Neon Shift"), SignalRgb.localEffects(List.of(dir, dir.resolve("missing"))));
        assertEquals("signalrgb://effect/apply/Neon%20Shift?-silentlaunch-", SignalRgb.applyUrl("Neon Shift"));
    }

    @Test
    void brightnessAndPowerGoToTheEffect() throws Exception {
        var live = new SignalRgb.Live(() -> 0);
        live.brightness = 40;
        live.on = false;
        assertTrue(live.json().contains("\"brightness\":40,\"on\":false"), live.json());
        assertTrue(SignalRgb.effectHtml().contains("s.brightness"));
    }
}
