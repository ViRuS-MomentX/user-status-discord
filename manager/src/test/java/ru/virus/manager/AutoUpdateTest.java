package ru.virus.manager;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoUpdateTest {

    @Test
    void readsLatestReleaseAndDownloadsArchive(@TempDir Path home) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/latest", exchange -> {
            var body = ("{\"tag_name\":\"build-42\",\"draft\":false,\"body\":\"Доктор консоли\",\"assets\":["
                    + "{\"name\":\"other.zip\",\"browser_download_url\":\"" + base + "/other\"},"
                    + "{\"name\":\"V.I.R.U.S.zip\",\"url\":\"" + base + "/zip\",\"browser_download_url\":\"" + base + "/nope\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/zip", exchange -> {
            var body = "PK-архив".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            var update = new AutoUpdate(base + "/latest");
            var release = update.latest();
            assertEquals(42, release.build());
            assertEquals(base + "/zip", release.url());
            assertEquals("Доктор консоли", release.notes());

            assertTrue(AutoUpdate.newer(release, -1), "присланная вручную сборка обновляется до релиза");
            assertTrue(AutoUpdate.newer(release, 41));
            assertFalse(AutoUpdate.newer(release, 42), "та же сборка — не обновляемся");

            var zip = update.download(release, home);
            assertEquals("PK-архив", Files.readString(zip));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void privateRepositoryNeedsTokenAndKeepsItFromTheFileStorage(@TempDir Path home) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var base = "http://127.0.0.1:" + server.getAddress().getPort();
        var seen = new java.util.concurrent.CopyOnWriteArrayList<String>();
        server.createContext("/repos/ViRuS-MomentX/virus-app/releases/latest", exchange -> {
            var auth = exchange.getRequestHeaders().getFirst("Authorization");
            var body = ("{\"tag_name\":\"build-205\",\"assets\":[{\"name\":\"V.I.R.U.S.zip\",\"url\":\"" + base + "/asset\"}]}")
                    .getBytes(StandardCharsets.UTF_8);

            if (auth == null) {
                exchange.sendResponseHeaders(404, -1);
            } else if (!auth.equals("Bearer good")) {
                exchange.sendResponseHeaders(401, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }

            exchange.close();
        });
        server.createContext("/asset", exchange -> {
            seen.add("asset:" + exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().add("Location", base + "/storage");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/storage", exchange -> {
            seen.add("storage:" + exchange.getRequestHeaders().getFirst("Authorization"));
            var body = "PK-приватный".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            var url = base + "/repos/" + AutoUpdate.REPO + "/releases/latest";
            assertThrows(AutoUpdate.TokenException.class, () -> new AutoUpdate(url).latest(), "без токена — просим токен");
            assertThrows(AutoUpdate.TokenException.class, () -> new AutoUpdate(url, () -> "bad").latest(), "чужой токен — тоже");

            var update = new AutoUpdate(url, () -> "good");
            var release = update.latest();
            assertEquals(205, release.build());
            assertEquals("PK-приватный", Files.readString(update.download(release, home)));
            assertEquals(List.of("asset:Bearer good", "storage:null"), seen, "токен — GitHub, но не хранилищу файлов");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void tokenIsReadFromFileNextToTheJar(@TempDir Path home) throws Exception {
        assertTrue(AutoUpdate.readToken(home).isEmpty() || System.getenv("VIRUS_UPDATE_TOKEN") != null);
        Files.writeString(home.resolve(AutoUpdate.TOKEN_FILE), "  github_pat_abc  \n");
        assertEquals("github_pat_abc", AutoUpdate.readToken(home));
    }

    @Test
    void ignoresDraftsAndForeignTags() {
        assertNull(AutoUpdate.parse(java.util.Map.of("tag_name", "build-3", "draft", true)));
        assertNull(AutoUpdate.parse(java.util.Map.of("tag_name", "v1.0", "assets", List.of())));
        assertNull(AutoUpdate.parse(java.util.Map.of("tag_name", "build-3", "assets", List.of())), "без архива");
        // Локально номера нет (−1), на GitHub — номер запуска сборки
        assertTrue(AutoUpdate.current() == -1 || AutoUpdate.current() > 0);
    }

    @Test
    void runningProcessesStartAgainAfterUpdate(@TempDir Path home) throws Exception {
        Files.writeString(home.resolve(Supervisor.RESUME), "nobody\n");
        // Без своих процессов: запущенные боты писали бы журналы, пока JUnit удаляет временную папку
        Files.writeString(home.resolve("processes.json"), "[]");
        var supervisor = new Supervisor(home, "java");
        supervisor.load();
        supervisor.startAutostart();
        assertFalse(Files.exists(home.resolve(Supervisor.RESUME)), "список читается один раз");
    }

    @org.junit.jupiter.api.Test
    void versionIsShownAsBuildNumber() {
        org.junit.jupiter.api.Assertions.assertEquals("Версия: сборка 57", MainWindow.versionText(57));
        org.junit.jupiter.api.Assertions.assertEquals("Версия: своя сборка", MainWindow.versionText(-1));
    }
}
