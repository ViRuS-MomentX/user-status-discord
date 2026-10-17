package ru.virus.manager;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessSpecTest {

    @Test
    void splitKeepsQuotedPaths() {
        assertEquals(List.of("{java}", "-jar", "C:\\Program Files\\bot\\bot.jar", "--x"),
                ProcessSpec.split("{java} -jar \"C:\\Program Files\\bot\\bot.jar\"  --x"));
        assertEquals(List.of("a", ""), ProcessSpec.split("a \"\""));
    }

    @Test
    void joinQuotesSpaces() {
        var command = List.of("{java}", "-jar", "C:\\Program Files\\bot.jar");
        assertEquals("{java} -jar \"C:\\Program Files\\bot.jar\"", ProcessSpec.join(command));
        assertEquals(command, ProcessSpec.split(ProcessSpec.join(command)));
    }

    @Test
    void fromJsonAcceptsCommandLine() {
        var spec = ProcessSpec.fromJson(Map.of("id", "music", "name", "Музыка",
                "command", "{java} -jar music.jar", "autostart", true));

        assertEquals(List.of("{java}", "-jar", "music.jar"), spec.command());
        assertTrue(spec.autostart());
        assertTrue(spec.restart(), "по умолчанию поднимать после падения");
    }

    @Test
    void rejectsBadIds() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProcessSpec("../x", "X", List.of("a"), "", false, true, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ProcessSpec("Big", "X", List.of("a"), "", false, true, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ProcessSpec("ok", " ", List.of("a"), "", false, true, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ProcessSpec("ok", "X", List.of(), "", false, true, false));
    }

    @Test
    void defaultsAreOurBots() {
        var defaults = ProcessSpec.defaults();

        assertEquals(List.of("discord", "parody"), defaults.stream().map(ProcessSpec::id).toList());
        assertTrue(defaults.get(0).command().contains("--no-parody"));
        assertTrue(defaults.get(1).command().contains("--parody-only"));
        assertTrue(defaults.stream().allMatch(spec -> spec.command().contains("--supervised")));
    }
}
