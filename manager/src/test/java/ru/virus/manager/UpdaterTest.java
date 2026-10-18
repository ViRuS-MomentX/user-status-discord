package ru.virus.manager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdaterTest {

    @TempDir
    Path dir;

    @Test
    void takesOnlyKnownFilesFromArchive() throws Exception {
        var zip = dir.resolve("V.I.R.U.S.zip");

        try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (var name : List.of("V.I.R.U.S./virus.jar", "V.I.R.U.S./voice-bridge-bot.jar",
                    "V.I.R.U.S./config.properties", "V.I.R.U.S./world/level.dat")) {
                out.putNextEntry(new ZipEntry(name));
                out.write(name.getBytes());
                out.closeEntry();
            }
        }

        var home = Files.createDirectories(dir.resolve("home"));
        var staged = Updater.stage(home, List.of(zip));

        assertEquals(List.of("virus.jar", "voice-bridge-bot.jar"), staged);
        assertTrue(Files.isRegularFile(home.resolve("update/virus.jar")));
        assertFalse(Files.exists(home.resolve("update/config.properties")), "настройки из архива не берём");
    }

    @Test
    void unknownFilesAreRefused() throws Exception {
        var other = Files.writeString(dir.resolve("something.jar"), "x");
        assertThrows(IOException.class, () -> Updater.stage(dir, List.of(other)));
    }

    @Test
    void scriptQuotesPaths() {
        var script = Updater.script(42, Path.of("C:/Users/O'Neil/V.I.R.U.S"), "C:/Java/bin/javaw.exe");
        assertTrue(script.startsWith("Wait-Process -Id 42"), script);
        assertTrue(script.contains("'C:/Users/O''Neil/V.I.R.U.S'") || script.contains("O''Neil"), script);
        assertTrue(script.contains("'-jar','virus.jar'"), script);
    }

    @Test
    void serverInputRouting() {
        assertEquals("say всем привет", ProcessPage.forServer("всем привет"));
        assertEquals("op Steve", ProcessPage.forServer("op Steve"));
        assertEquals("tell Steve привет", ProcessPage.forServer("/tell Steve привет"));
    }
}
