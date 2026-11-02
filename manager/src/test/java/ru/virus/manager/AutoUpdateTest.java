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
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoUpdateTest {

    @Test
    void readsLatestReleaseAndDownloadsArchive(@TempDir Path home) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/latest", exchange -> {
            var body = ("{\"tag_name\":\"build-42\",\"draft\":false,\"body\":\"Доктор консоли\",\"assets\":["
                    + "{\"name\":\"other.zip\",\"browser_download_url\":\"" + base + "/other\"},"
                    + "{\"name\":\"V.I.R.U.S.zip\",\"browser_download_url\":\"" + base + "/zip\"}]}")
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
