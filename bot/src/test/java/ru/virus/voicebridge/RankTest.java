package ru.virus.voicebridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RankTest {

    @TempDir
    Path dir;

    @Test
    void minutesTurnIntoCoins() {
        var store = new RankStore(dir.resolve("ranks.json"));

        for (var i = 0; i < 29; i++) {
            assertEquals(0, store.addMinute("u", 30));
        }

        assertEquals(1, store.addMinute("u", 30));
        assertEquals(1, store.get("u").coins);
        assertEquals(0, store.get("u").minutes);
    }

    @Test
    void promotionNeedsEnoughCoinsAndCanBeRefunded() {
        var store = new RankStore(dir.resolve("ranks.json"));
        store.addMinute("u", 1);
        store.addMinute("u", 1);

        assertFalse(store.promote("u", 3));
        assertTrue(store.promote("u", 2));
        assertEquals(0, store.get("u").coins);
        assertEquals(1, store.get("u").rank);

        store.refund("u", 2);
        assertEquals(2, store.get("u").coins);
        assertEquals(0, store.get("u").rank);
    }

    @Test
    void balancesSurviveRestart() {
        var file = dir.resolve("ranks.json");
        var store = new RankStore(file);
        store.addMinute("u", 1);
        store.promote("u", 1);
        store.save();

        var again = new RankStore(file);
        assertEquals(1, again.get("u").rank);
    }

    @Test
    void brokenFileIsNotOverwritten() throws Exception {
        var file = dir.resolve("ranks.json");
        Files.writeString(file, "{\"u\": {\"coins\": 99,");

        var store = new RankStore(file);
        store.addMinute("someone", 30);
        store.save();

        assertEquals("{\"u\": {\"coins\": 99,", Files.readString(dir.resolve("ranks.json.broken")),
                "испорченные балансы должны уцелеть для ручного разбора");
    }

    @Test
    void ladderReadsStepsUntilGap() {
        var props = new Properties();
        props.setProperty("rank.enabled", "true");
        props.setProperty("rank.minutes", "15");
        props.setProperty("rank.1", "111:3");
        props.setProperty("rank.2", "Роль с: двоеточием:5");
        props.setProperty("rank.3", "без цены");
        props.setProperty("rank.4", "444:x");
        props.setProperty("rank.6", "666:1");

        var ladder = RankLadder.from(props);

        assertTrue(ladder.isEnabled());
        assertEquals(15, ladder.getMinutesPerCoin());
        assertEquals(2, ladder.getSteps().size(), "плохие ступени пропускаются, дырка обрывает");
        assertEquals("Роль с: двоеточием", ladder.getSteps().get(1).getRoleRef());
        assertEquals(5, ladder.getSteps().get(1).getPrice());
    }

    @Test
    void ladderDefaults() {
        var props = new Properties();
        props.setProperty("rank.minutes", "0");

        var ladder = RankLadder.from(props);

        assertFalse(ladder.isEnabled());
        assertEquals(30, ladder.getMinutesPerCoin());
        assertEquals("🪙", ladder.getCoinEmoji());
    }
}
