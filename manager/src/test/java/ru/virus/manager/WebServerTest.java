package ru.virus.manager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebServerTest {

    @TempDir
    Path home;

    WebServer web;
    Supervisor supervisor;
    final HttpClient http = HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null)).build();

    @BeforeEach
    void up() throws Exception {
        supervisor = new Supervisor(home, "java");
        supervisor.load();
        web = new WebServer(0, supervisor, home.resolve("config.properties"),
                new Autostart(null, "javaw", null), () -> { });
        web.start();
    }

    @AfterEach
    void down() {
        web.stop();
    }

    HttpResponse<String> call(String method, String path, String body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(web.url() + path.substring(1)))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));

        if (token != null) {
            request.header("X-Virus-Token", token);
        }

        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void pageCarriesTokenAndApiDemandsIt() throws Exception {
        var page = call("GET", "/", null, null);
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains(web.token()));
        assertTrue(page.headers().firstValue("X-Frame-Options").isPresent());

        assertEquals(403, call("GET", "/api/state", null, null).statusCode());
        assertEquals(403, call("GET", "/api/state", null, "wrong-token").statusCode());
        assertEquals(200, call("GET", "/api/state", null, web.token()).statusCode());
    }

    @Test
    void foreignHostIsRefused() throws Exception {
        try (var socket = new Socket("127.0.0.1", web.port())) {
            socket.getOutputStream().write(("GET / HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            var answer = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(answer.startsWith("HTTP/1.1 403"), answer);
            assertFalse(answer.contains(web.token()), "ключ не должен уйти чужому сайту");
        }
    }

    @Test
    void noPathEscapesWebFolder() throws Exception {
        assertEquals(404, call("GET", "/..%2Fconfig.properties.example", null, null).statusCode());
        assertEquals(404, call("GET", "/nothing.js", null, null).statusCode());
        assertEquals(200, call("GET", "/app.js", null, null).statusCode());
    }

    @Test
    void configCreateEditAndAddMissing() throws Exception {
        var token = web.token();
        var state = Json.object(call("GET", "/api/config", null, token).body());
        assertEquals(false, state.get("exists"));

        assertEquals(200, call("POST", "/api/config/create", "{}", token).statusCode());
        assertTrue(Files.readString(home.resolve("config.properties")).contains("parody.enabled"));

        var saved = Json.object(call("POST", "/api/config",
                Json.write(Map.of("values", Map.of("parody.chance", "17"))), token).body());
        assertEquals(List.of("parody.chance"), saved.get("changed"));
        assertTrue(Files.readString(home.resolve("config.properties")).contains("parody.chance=17"));

        // Будто файл от старой версии: одной настройки нет
        var text = Files.readString(home.resolve("config.properties")).replace("parody.daily=30\n", "");
        Files.writeString(home.resolve("config.properties"), text);
        assertEquals(1L, Json.object(call("GET", "/api/config", null, token).body()).get("missing"));
        assertEquals(1L, Json.object(call("POST", "/api/config/add-missing", "{}", token).body()).get("added"));
        assertTrue(Files.readString(home.resolve("config.properties")).contains("parody.daily=30"));
    }

    @Test
    void processesCanBeAddedAndRemoved() throws Exception {
        var token = web.token();
        var body = Json.write(Map.of("id", "extra", "name", "Ещё", "command", "{java} -version"));
        assertEquals(200, call("POST", "/api/processes", body, token).statusCode());
        assertTrue(Files.readString(home.resolve("processes.json")).contains("\"extra\""));

        assertEquals(400, call("POST", "/api/processes",
                Json.write(Map.of("id", "BAD ID", "name", "x", "command", "x")), token).statusCode());

        assertEquals(200, call("POST", "/api/processes/extra/delete", "{}", token).statusCode());
        assertFalse(Files.readString(home.resolve("processes.json")).contains("\"extra\""));
        assertEquals(404, call("POST", "/api/processes/extra/start", "{}", token).statusCode());
    }

    @Test
    void changesNeedPost() throws Exception {
        assertEquals(400, call("GET", "/api/processes/parody/start", null, web.token()).statusCode());
    }
}
