package ru.virus.manager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigServiceTest {

    @TempDir
    Path home;

    @Test
    void createEditAndAddMissing() throws Exception {
        var file = home.resolve("config.properties");
        var config = new ConfigService(file);

        assertFalse(config.exists());
        assertEquals(0, config.missing());

        config.createFromExample();
        assertTrue(Files.readString(file).contains("parody.enabled"));
        assertThrows(IOException.class, config::createFromExample, "поверх существующего не создаём");

        assertEquals(List.of("parody.chance"), config.save(Map.of("parody.chance", "17")));
        assertTrue(Files.readString(file).contains("parody.chance=17"));
        assertTrue(Files.exists(home.resolve("config.properties.bak")));

        // Будто файл от старой версии: одной настройки нет
        Files.writeString(file, Files.readString(file).replace("parody.daily=30\n", ""));
        assertEquals(1, config.missing());
        assertEquals(1, config.addMissing());
        assertEquals(0, config.missing());
        assertTrue(Files.readString(file).contains("parody.daily=30"));
    }

    @Test
    void rawEditKeepsWindowsLineEndingsOut() throws Exception {
        var file = home.resolve("config.properties");
        var config = new ConfigService(file);

        config.saveRaw("a=1\r\nb=2\r\n");

        assertEquals("a=1\nb=2\n", Files.readString(file));
        assertEquals("2", config.read().values().get("b"));
    }
}
