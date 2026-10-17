package ru.virus.voicebridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParodySamplesTest {

    @TempDir
    Path dir;

    @Test
    void importTakesOnlyTargetsOwnSpeech() throws Exception {
        var export = dir.resolve("result.json");
        Files.writeString(export, """
                {"name": "Группа", "messages": [
                  {"type": "message", "from_id": "user42", "text": "всем ку"},
                  {"type": "message", "from_id": "user42",
                   "text": ["смотри ", {"type": "link", "text": "https://x.y"}, " кринж"]},
                  {"type": "message", "from_id": "user5", "text": "это не он"},
                  {"type": "service", "from_id": "user42", "text": "вошёл в группу"},
                  {"type": "message", "from_id": "user42", "text": "/start"},
                  {"type": "message", "from_id": "user42", "text": ""},
                  {"type": "message", "from_id": "user42", "forwarded_from": "Канал",
                   "text": "чужое"},
                  {"type": "message", "from_id": "user42", "via_bot": "@gif", "text": "котик"}
                ]}""");

        var samples = new ParodySamples(dir.resolve("samples.json"));

        assertEquals(2, samples.importExport(export, 42));
        assertEquals(List.of("всем ку", "смотри https://x.y кринж"), samples.all());
    }

    @Test
    void survivesRestart() {
        var file = dir.resolve("samples.json");
        var first = new ParodySamples(file);
        first.add("раз");
        first.add("  два  ");

        var second = new ParodySamples(file);
        second.load();

        assertEquals(List.of("раз", "два"), second.all());
    }

    @Test
    void skipsCommandsAndWalls() {
        var samples = new ParodySamples(dir.resolve("samples.json"));
        samples.add("/stats");
        samples.add("   ");
        samples.add("х".repeat(601));
        samples.add("норм");

        assertEquals(List.of("норм"), samples.all());
    }

    @Test
    void importIsRememberedAcrossRestarts() {
        var file = dir.resolve("state.json");
        var state = new ParodyState(file);
        state.load();
        state.markImported();

        var again = new ParodyState(file);
        again.load();
        org.junit.jupiter.api.Assertions.assertTrue(again.isImported());
    }

    @Test
    void forgetIsRememberedAndNewTargetStartsClean() {
        var file = dir.resolve("state.json");
        var state = new ParodyState(file);
        state.load();
        state.setTarget(42);
        state.markImported();
        state.markForgotten();

        var again = new ParodyState(file);
        again.load();
        org.junit.jupiter.api.Assertions.assertTrue(again.isForgotten());
        assertEquals(42, again.target());

        // Тот же участник — «забудь» в силе; другой — чистый лист
        again.setTarget(42);
        org.junit.jupiter.api.Assertions.assertTrue(again.isForgotten());
        again.setTarget(7);
        org.junit.jupiter.api.Assertions.assertFalse(again.isForgotten());
        org.junit.jupiter.api.Assertions.assertFalse(again.isImported());
    }

    @Test
    void unreadableStateMeansSilence() throws Exception {
        var file = dir.resolve("state.json");
        Files.writeString(file, "мусор");

        var state = new ParodyState(file);
        state.load();
        org.junit.jupiter.api.Assertions.assertTrue(state.isPaused());
    }

    @Test
    void brokenFileDoesNotStopBot() throws Exception {
        var file = dir.resolve("samples.json");
        Files.writeString(file, "{не json");

        var samples = new ParodySamples(file);
        samples.load();

        assertEquals(0, samples.size());
        assertEquals("{не json", Files.readString(dir.resolve("samples.json.broken")));
    }
}
