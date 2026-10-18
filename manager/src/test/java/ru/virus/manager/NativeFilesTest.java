package ru.virus.manager;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeFilesTest {

    @Test
    void explorerScriptAsksForModernDialog() {
        var script = NativeFiles.script(false, "Обновление", Path.of("C:/Users/O'Neil/Downloads"), true, null, "zip", "jar");

        assertTrue(script.contains("OpenFileDialog"), script);
        assertTrue(script.contains("$d.Multiselect=$true"), script);
        assertTrue(script.contains("'C:/Users/O''Neil/Downloads'") || script.contains("O''Neil"), script);
        assertTrue(script.contains("*.zip;*.jar"), script);
        assertTrue(NativeFiles.script(true, "Значок", Path.of("C:/x"), false, "virus.png").contains("SaveFileDialog"));
    }

    @Test
    void extensionsFilter() {
        assertTrue(NativeFiles.fits(Path.of("A.ZIP"), "zip"));
        assertTrue(!NativeFiles.fits(Path.of("a.txt"), "zip", "jar"));
    }
}
