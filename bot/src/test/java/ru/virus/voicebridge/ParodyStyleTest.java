package ru.virus.voicebridge;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParodyStyleTest {

    @Test
    void tooFewSamplesSayNothing() {
        assertEquals("", ParodyStyle.describe(List.of("раз", "два")));
    }

    @Test
    void catchesLowercaseNoDotsBracketsAndEmoji() {
        var samples = new ArrayList<String>();

        for (var i = 0; i < 20; i++) {
            samples.add(i % 2 == 0 ? "ну го катку)))" : "ахах ну ты даёшь 😂");
        }

        var style = ParodyStyle.describe(samples);

        assertTrue(style.contains("строчной"), style);
        assertTrue(style.contains("Точку в конце почти не ставит"), style);
        assertTrue(style.contains(")))"), style);
        assertTrue(style.contains("😂"), style);
        assertTrue(style.contains("пишет коротко"), style);
        assertFalse(style.contains("КАПСОМ"), style);
    }

    @Test
    void catchesShoutingAndProperCase() {
        var samples = new ArrayList<String>();

        for (var i = 0; i < 12; i++) {
            samples.add(i % 3 == 0 ? "ДА ЛАДНО ТЫ ЧЕГО." : "Привет, как дела у всех сегодня.");
        }

        var style = ParodyStyle.describe(samples);

        assertTrue(style.contains("КАПСОМ"), style);
        assertTrue(style.contains("с заглавной"), style);
        assertTrue(style.contains("Эмодзи почти не"), style);
        assertTrue(style.contains("Точку в конце ставит в 100%"), style);
    }
}
