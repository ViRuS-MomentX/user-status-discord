package ru.virus.voicebridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParodyNamesTest {

    @TempDir
    Path dir;

    @Test
    void fillerWordsAreNotNames() {
        var names = new ParodyNames(dir.resolve("names.json"));

        assertNull(names.learn(7, "ну, го"));
        assertNull(names.learn(7, "Короче, я спать"));
        assertNull(names.learn(7, "просто текст без обращения"));
        assertEquals("Димон", names.learn(7, "Димон, ты где?"));
        assertEquals("Санёк", names.learn(7, "Санёк! давай"));
    }

    @Test
    void oneHearingIsNotEnough() {
        var names = new ParodyNames(dir.resolve("names.json"));
        names.seen(7, "Дмитрий");

        names.learn(7, "Димон, го");
        assertEquals(List.of(), names.nicknames(7));

        names.learn(7, "Димон, ну");
        assertEquals(List.of("Димон"), names.nicknames(7));
        assertEquals("Дмитрий — Димон", names.describe(List.of(7L), 10));
    }

    @Test
    void forgetRemovesBoth() {
        var names = new ParodyNames(dir.resolve("names.json"));
        names.teach(7, "Дима");
        names.learn(7, "Дима, го");

        assertTrue(names.forget(7, "Дима"));
        assertEquals(List.of(), names.nicknames(7));
    }

    @Test
    void brokenFileIsSetAside() throws Exception {
        var file = dir.resolve("names.json");
        Files.writeString(file, "{сломано");

        var names = new ParodyNames(file);
        names.load();

        assertTrue(Files.exists(dir.resolve("names.json.broken")));
        assertEquals("", names.report());
    }

    @Test
    void trailingEmojiStripKeepsText() {
        assertEquals("ну го", ParodyStyle.stripTrailingEmoji("ну го 😂👍🏻"));
        assertEquals("ну го )))", ParodyStyle.stripTrailingEmoji("ну го ))) ❤️"));
        assertEquals("😂 в начале", ParodyStyle.stripTrailingEmoji("😂 в начале"));
        assertEquals(0, ParodyStyle.emojiShare(List.of("а", "б")));
        assertEquals(50, ParodyStyle.emojiShare(List.of("а 😂", "б")));
    }
}
