package ru.virus.manager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Доктор, автообновление плагина и оценки реплик. */
class ProductTest {

    @Test
    void doctorLightsUpAfterRepeatsAndGoesQuiet() {
        var doctor = new Doctor();
        var line = "12:00:00 [parody-from-telegram] WARN Parody - Пародийный бот не достучался до Telegram: "
                + "HTTP connect timed out. Пробую через 15 с.";

        assertNull(doctor.feed(line, 0), "один обрыв — ещё не поломка");
        assertNull(doctor.feed(line, 30_000));
        var issue = doctor.feed(line, 60_000);
        assertNotNull(issue);
        assertEquals("Telegram недоступен", issue.title());
        assertEquals("config:bridge.telegram.proxy", issue.action());
        assertNull(doctor.feed(line, 90_000), "уже горит — второй раз не сообщаем");

        assertEquals(1, doctor.active(100_000).size());
        assertTrue(doctor.active(90_000 + Doctor.QUIET_MS + 1).isEmpty(), "затихло — гаснет само");

        assertNotNull(doctor.feed("ИИ не ответил: Ollama не отвечает на http://localhost:11434 — запусти", 0));
        assertNotNull(new Doctor().feed("[Server thread/ERROR]: **** FAILED TO BIND TO PORT!", 0));
        assertNull(new Doctor().feed("Пародия сказала: ну", 0));
    }

    @Test
    void pluginBuildsAreRecognised(@TempDir Path dir) throws Exception {
        assertEquals("VirusSwords", PluginWatch.base(Path.of("VirusSwords-1.0.3.jar")));
        assertEquals("VirusSwords", PluginWatch.base(Path.of("VirusSwords-1.0.3-SNAPSHOT.jar")));
        assertEquals("MyPlugin", PluginWatch.base(Path.of("MyPlugin.jar")));

        var libs = Files.createDirectories(dir.resolve("build/libs"));
        var old = Files.writeString(libs.resolve("VirusSwords-1.0.2.jar"), "old");
        Files.setLastModifiedTime(old, FileTime.fromMillis(1_000_000));
        var fresh = Files.writeString(libs.resolve("VirusSwords-1.0.3.jar"), "new");
        Files.setLastModifiedTime(fresh, FileTime.fromMillis(2_000_000));
        Files.writeString(libs.resolve("VirusSwords-1.0.3-sources.jar"), "src");
        Files.writeString(libs.resolve("Other-9.jar"), "x");

        assertEquals(fresh, PluginWatch.newest(libs, "VirusSwords"));

        assertEquals(Boolean.FALSE, PluginWatch.outcome(
                "[12:00:01 ERROR]: Error occurred while enabling VirusSwords v1.0.3 (Is it up to date?)", "VirusSwords"));
        assertEquals(Boolean.TRUE, PluginWatch.outcome("[12:00:05 INFO]: Done (4.2s)! For help, type \"help\"",
                "VirusSwords"));
        assertNull(PluginWatch.outcome("[12:00:02 INFO]: [VirusSwords] Enabling VirusSwords v1.0.3", "VirusSwords"));
    }

    @Test
    void newBuildIsDeployedOnceWhenServerIsStopped(@TempDir Path home) throws Exception {
        var spec = new ProcessSpec("test-server", "Тестовый сервер", List.of("{java}", "-jar", "{jar}", "nogui"),
                "srv", false, false, true);
        var server = new ManagedProcess(spec, home, "java", null);
        Files.createDirectories(home.resolve("srv"));
        var libs = Files.createDirectories(home.resolve("build/libs"));
        var first = Files.writeString(libs.resolve("VirusSwords-1.0.0.jar"), "v1");

        var said = new CopyOnWriteArrayList<String>();
        var watch = new PluginWatch(server, (text, good) -> said.add(text));
        watch.watch(first);
        watch.stop();

        watch.check();
        watch.check();
        assertTrue(said.isEmpty(), "выбранную сборку повторно не ставим");

        var next = Files.writeString(libs.resolve("VirusSwords-1.0.1.jar"), "v2");
        Files.setLastModifiedTime(next, FileTime.fromMillis(System.currentTimeMillis() + 5000));
        watch.check();
        assertTrue(said.isEmpty(), "сначала ждём, что jar дописан");
        watch.check();

        assertEquals(1, said.size(), said.toString());
        var plugins = server.workdir().resolve("plugins");
        assertTrue(Files.isRegularFile(plugins.resolve("VirusSwords-1.0.1.jar")));
        assertEquals("v2", Files.readString(plugins.resolve("VirusSwords-1.0.1.jar")));

        watch.check();
        watch.check();
        assertEquals(1, said.size(), "та же сборка — второй раз не ставим");
    }

    @Test
    void ratingsRoundTrip(@TempDir Path dir) throws Exception {
        var journal = dir.resolve("journal.jsonl");
        Files.writeString(journal, """
                {"id":11,"at":1700000000000,"to":"Санёк","context":"Санёк: го","reply":"иду"}
                {"id":12,"at":1700000060000,"to":"Петя","context":"","reply":"ну"}
                {"id":13,"at":17000""", StandardCharsets.UTF_8);

        var replies = RepliesPage.read(journal);
        assertEquals(2, replies.size(), "оборванная строка пропускается");
        assertEquals("Санёк", replies.getFirst().to());

        var ratings = dir.resolve("ratings.json");
        RepliesPage.writeRatings(ratings, Map.of(11L, 1, 12L, -1, 13L, 0));
        assertEquals(Map.of(11L, 1, 12L, -1), RepliesPage.readRatings(ratings));
        assertFalse(Files.readString(ratings).contains("13"), "снятая оценка не пишется");
    }

    @Test
    void reportDropsBotReplies() {
        var cleaned = Reporter.withoutSaid("{\"paused\":false,\"said\":[\"Санёк, иди сюда\"],\"sentToday\":3}");
        assertFalse(cleaned.contains("Санёк"), cleaned);
        assertTrue(cleaned.contains("sentToday"), cleaned);
    }
}
