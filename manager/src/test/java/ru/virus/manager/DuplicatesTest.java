package ru.virus.manager;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DuplicatesTest {

    @Test
    void foreignCopiesAreFoundOursAreNot() {
        var lines = new LinkedHashMap<Long, String>();
        lines.put(10L, "\"C:\\Java\\bin\\javaw.exe\" -jar voice-bridge-bot.jar config.properties --parody-only --supervised");
        lines.put(20L, "java -jar C:\\bots\\voice-bridge-bot.jar config.properties");
        lines.put(30L, "java -jar virus.jar");
        lines.put(40L, "java -jar other.jar");
        lines.put(50L, null);

        var copies = Duplicates.pick(lines, Set.of("voice-bridge-bot.jar"), Set.of(10L));

        assertEquals(1, copies.size());
        assertEquals(20L, copies.getFirst().pid());
        assertEquals("бот целиком (Discord и пародия)", copies.getFirst().what());
    }

    @Test
    void modesAreNamed() {
        assertEquals("Пародия", Duplicates.describe("-jar voice-bridge-bot.jar --parody-only", "voice-bridge-bot.jar"));
        assertEquals("Discord-бот и мост", Duplicates.describe("-jar voice-bridge-bot.jar --no-parody", "voice-bridge-bot.jar"));
    }
}
