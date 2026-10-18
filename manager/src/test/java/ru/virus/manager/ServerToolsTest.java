package ru.virus.manager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerToolsTest {

    @TempDir
    Path dir;

    @Test
    void findsServerJarAmongOthers() throws Exception {
        Files.writeString(dir.resolve("helper-lib.jar"), "");
        Files.writeString(dir.resolve("paper-1.21.4-232.jar"), "");
        assertEquals("paper-1.21.4-232.jar", ServerTools.serverJar(dir).getFileName().toString());
    }

    @Test
    void recreatesAllThreeDimensions() throws Exception {
        Files.writeString(dir.resolve("server.properties"), "level-name=testworld\n");
        for (var name : List.of("testworld/region", "testworld_nether/DIM-1", "testworld_the_end/DIM1", "plugins")) {
            Files.createDirectories(dir.resolve(name));
        }
        Files.writeString(dir.resolve("testworld/level.dat"), "x");

        assertEquals(3, ServerTools.deleteWorlds(dir));
        assertFalse(Files.exists(dir.resolve("testworld")));
        assertFalse(Files.exists(dir.resolve("testworld_the_end")));
        assertTrue(Files.isDirectory(dir.resolve("plugins")), "плагины остаются");
    }

    @Test
    void levelNameCannotEscapeFolder() throws Exception {
        Files.createDirectories(dir.resolve("server"));
        Files.createDirectories(dir.resolve("precious"));
        Files.writeString(dir.resolve("server/server.properties"), "level-name=../precious\n");

        assertEquals(0, ServerTools.deleteWorlds(dir.resolve("server")));
        assertTrue(Files.isDirectory(dir.resolve("precious")));
    }

    @Test
    void replacesOldPluginBuildButKeepsOthers() throws Exception {
        var plugins = Files.createDirectories(dir.resolve("plugins"));
        Files.writeString(plugins.resolve("VirusSwords-1.0.0.jar"), "old");
        Files.writeString(plugins.resolve("EssentialsX.jar"), "keep");
        Files.createDirectories(plugins.resolve("VirusSwords"));
        Files.createDirectories(plugins.resolve(".paper-remapped"));
        Files.writeString(plugins.resolve(".paper-remapped/VirusSwords-1.0.0.jar"), "cached");
        var build = Files.createDirectories(dir.resolve("build")).resolve("my-new-build.jar");
        Files.writeString(build, "new");

        var removed = ServerTools.replacePlugin(dir, build);

        assertEquals(List.of("VirusSwords-1.0.0.jar"), removed);
        assertEquals("new", Files.readString(plugins.resolve("my-new-build.jar")));
        assertTrue(Files.exists(plugins.resolve("EssentialsX.jar")));
        assertTrue(Files.isDirectory(plugins.resolve("VirusSwords")), "настройки плагина остаются");
        assertFalse(Files.exists(plugins.resolve(".paper-remapped/VirusSwords-1.0.0.jar")), "кэш Paper — тоже");

        // Следующая сборка с другим именем убирает и эту — V.I.R.U.S. её запомнил
        var next = dir.resolve("build/another-name.jar");
        Files.writeString(next, "newer");
        assertEquals(List.of("my-new-build.jar"), ServerTools.replacePlugin(dir, next));
    }

    @Test
    void testServerIsSeededOnce() throws Exception {
        var supervisor = new Supervisor(dir, "java");
        supervisor.load();
        assertTrue(ServerTools.isServer(supervisor.get(ServerTools.ID).spec()));
        assertTrue(Files.isDirectory(dir.resolve("projects/test-server")));

        supervisor.remove(ServerTools.ID);
        var again = new Supervisor(dir, "java");
        again.load();
        assertEquals(null, again.get(ServerTools.ID), "удалённый не возвращается");
    }

    @Test
    void serverCopiedWithItsFolderIsFoundInside() throws Exception {
        var inner = Files.createDirectories(dir.resolve("virus"));
        Files.writeString(inner.resolve("server.properties"), "level-name=world\n");
        Files.writeString(inner.resolve("paper-1.21.11-132.jar"), "");

        assertEquals(inner, ServerTools.serverHome(dir));
        assertEquals(dir.resolve("empty"), ServerTools.serverHome(dir.resolve("empty")));
    }

    @Test
    void emptyServerFolderDoesNotLaunch() throws Exception {
        var supervisor = new Supervisor(dir, "java");
        supervisor.load();
        var server = supervisor.get(ServerTools.ID);

        server.start();

        assertEquals(ManagedProcess.State.FAILED, server.state());
        assertTrue(String.valueOf(server.toJson().get("note")).contains("нет jar"));
    }

    @Test
    void javaGetsUtf8UnlessCommandSaysOtherwise() {
        var server = ManagedProcess.withUtf8(List.of("{java}", "-jar", "{jar}"), List.of("java", "-jar", "paper.jar"));
        assertEquals(List.of("java", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dstdin.encoding=UTF-8",
                "-jar", "paper.jar"), server);

        var bot = List.of("{java}", "-Dstdout.encoding=UTF-8", "-jar", "bot.jar");
        assertEquals(List.of("java", "-Dstdout.encoding=UTF-8", "-jar", "bot.jar"),
                ManagedProcess.withUtf8(bot, List.of("java", "-Dstdout.encoding=UTF-8", "-jar", "bot.jar")));

        assertEquals(List.of("node", "app.js"), ManagedProcess.withUtf8(List.of("node", "app.js"), List.of("node", "app.js")));
    }
}
