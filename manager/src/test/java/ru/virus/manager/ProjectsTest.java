package ru.virus.manager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectsTest {

    @TempDir
    Path home;

    Supervisor supervisor() throws IOException {
        var supervisor = new Supervisor(home, "java");
        supervisor.load();
        return supervisor;
    }

    @Test
    void movesBotIntoProjectAndKeepsOwnFiles() throws Exception {
        Files.writeString(home.resolve("voice-bridge-bot.jar"), "jar");
        Files.writeString(home.resolve("config.properties"), "bot.token=x");
        Files.createDirectories(home.resolve("music"));
        Files.writeString(home.resolve("music/a.mp3"), "a");
        Files.writeString(home.resolve("virus.jar"), "virus");
        Files.createDirectories(home.resolve("logs"));
        var supervisor = supervisor();
        var projects = new Projects(home);

        assertTrue(projects.botInHome());
        assertEquals(home, supervisor.botFolder());

        var moved = projects.moveBot(supervisor);

        var target = home.resolve("projects/voice-bridge");
        assertEquals(3, moved);
        assertTrue(Files.isRegularFile(target.resolve("voice-bridge-bot.jar")));
        assertTrue(Files.isRegularFile(target.resolve("music/a.mp3")));
        assertTrue(Files.isRegularFile(home.resolve("virus.jar")), "своё V.I.R.U.S. остаётся");
        assertTrue(Files.isRegularFile(home.resolve("processes.json")));
        assertFalse(projects.botInHome());
        assertEquals(target.toAbsolutePath().normalize(), supervisor.botFolder().toAbsolutePath().normalize());
        assertTrue(Files.readString(home.resolve("processes.json")).contains("projects/voice-bridge"));
    }

    @Test
    void conflictStopsBeforeTouchingAnything() throws Exception {
        Files.writeString(home.resolve("voice-bridge-bot.jar"), "jar");
        Files.writeString(home.resolve("config.properties"), "new");
        Files.createDirectories(home.resolve("projects/voice-bridge"));
        Files.writeString(home.resolve("projects/voice-bridge/config.properties"), "old");

        assertThrows(IOException.class, () -> new Projects(home).moveBot(supervisor()));
        assertTrue(Files.isRegularFile(home.resolve("voice-bridge-bot.jar")), "ничего не должно было переехать");
        assertEquals("old", Files.readString(home.resolve("projects/voice-bridge/config.properties")));
    }

    @Test
    void projectNamesMustSuitWindows() throws Exception {
        var projects = new Projects(home);

        assertThrows(IOException.class, () -> projects.create("a/b"));
        assertThrows(IOException.class, () -> projects.create(".."));
        assertThrows(IOException.class, () -> projects.create("  "));
        projects.create("My bot");
        assertEquals(List.of(home.resolve("projects/My bot")), projects.list());
    }

    @Test
    void updateUnpackedOverHomeReplacesBotFilesButNotSettings() throws Exception {
        var project = Files.createDirectories(home.resolve("projects/voice-bridge"));
        Files.writeString(project.resolve("voice-bridge-bot.jar"), "old");
        Files.writeString(project.resolve("config.properties"), "мои настройки");
        Files.writeString(home.resolve("voice-bridge-bot.jar"), "new");
        Files.writeString(home.resolve("config.properties.example"), "образец");

        new Projects(home).moveBot(supervisor());

        assertEquals("new", Files.readString(project.resolve("voice-bridge-bot.jar")));
        assertEquals("мои настройки", Files.readString(project.resolve("config.properties")));
        assertFalse(Files.exists(home.resolve("voice-bridge-bot.jar")));
    }
}
