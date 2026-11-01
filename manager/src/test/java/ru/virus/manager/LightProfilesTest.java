package ru.virus.manager;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightProfilesTest {

    @Test
    void rulesFromTextAndMatching() {
        var rules = LightProfiles.parseRules("""
                MarvelRivals.exe = Игра
                C:\\\\Games\\\\Dota\\\\dota2 = Дота
                без знака равно
                 = пусто
                """);

        assertEquals(Map.of("marvelrivals.exe", "Игра", "dota2.exe", "Дота"), rules);
        assertEquals("Игра", LightProfiles.wanted(rules, Set.of("explorer.exe", "marvelrivals.exe")));
        assertNull(LightProfiles.wanted(rules, Set.of("explorer.exe")), "игры нет — правило молчит");
        assertTrue(LightProfiles.rulesText(rules).contains("marvelrivals.exe = Игра"));
        assertEquals("javaw.exe", LightProfiles.program("C:\\\\Program Files\\\\Java\\\\bin\\\\JAVAW.EXE"));
    }

    @Test
    void profileSurvivesSaving() {
        var profile = new LightProfiles.Profile("Ночь", 1, new Color(0x2050FF), Color.BLACK, 20);
        var back = LightProfiles.Profile.fromJson(profile.toJson());
        assertEquals(profile, back);
        assertFalse(LightProfiles.running().isEmpty(), "список запущенных программ читается");

        // Новый профиль помнит яркость и узор
        var full = new LightProfiles.Profile("Игра", 3, Color.RED, Color.BLUE, 70, 35, true, 2.5);
        assertEquals(full, LightProfiles.Profile.fromJson(full.toJson()));
        // Старый, без яркости, — её не трогает
        assertEquals(-1, LightProfiles.Profile.fromJson(profile.toJson()).brightness());
    }
}
