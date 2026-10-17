package ru.virus.manager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutostartTest {

    @Test
    void scriptRunsHiddenInBackground(@TempDir Path startup) throws Exception {
        var jar = Path.of("C:\\My bots\\virus.jar");
        var autostart = new Autostart(startup, "C:\\Program Files\\Java\\bin\\javaw.exe", jar);

        assertFalse(autostart.isEnabled());
        autostart.enable();
        assertTrue(autostart.isEnabled());

        var bytes = Files.readAllBytes(startup.resolve("V.I.R.U.S.vbs"));
        assertTrue(bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE, "нужна метка UTF-16LE, иначе русский путь сломается");

        var script = new String(bytes, StandardCharsets.UTF_16LE);
        assertTrue(script.contains("\"\"C:\\Program Files\\Java\\bin\\javaw.exe\"\""), script);
        assertTrue(script.contains("--background"), script);
        assertTrue(script.contains(", 0, False"), "без окна и без ожидания");

        autostart.disable();
        assertFalse(autostart.isEnabled());
    }

    @Test
    void notWindowsMeansNotSupported() {
        assertFalse(new Autostart(null, "javaw", Path.of("virus.jar")).isSupported());
        assertFalse(new Autostart(Path.of("."), "javaw", null).isSupported());
    }
}
