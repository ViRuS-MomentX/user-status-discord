package ru.virus.manager;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightingCheckTest {

    @Test
    void explainsWhatIsMissing() {
        var gpu = new OpenRgb.Device(0, "GIGABYTE RTX 5070", "видеокарта", 20);
        var keyboard = new OpenRgb.Device(1, "Мышь", "мышь", 0);

        // Нет ни платы, ни памяти — OpenRGB без администратора; мышь не красится; рядом SignalRGB
        var findings = LightingCheck.analyze(List.of(gpu, keyboard), Map.of(), Set.of("signalrgb.exe"), true);
        assertEquals(List.of("admin", "", "close-signal"), findings.stream().map(LightingCheck.Finding::action).toList());

        // Плата есть, но разъём без светодиодов
        var header = new OpenRgb.Zone(0, "JRAINBOW1", 1, 0, 120, 0);
        var board = new OpenRgb.Device(2, "MSI", "материнская плата", 0, List.of(), List.of(header));
        var boardFindings = LightingCheck.analyze(List.of(gpu, board), Map.of(), Set.of(), true);
        assertTrue(boardFindings.stream().anyMatch(f -> f.text().contains("JRAINBOW1")), boardFindings.toString());

        // Размер уже задан — не ругаемся
        assertTrue(LightingCheck.analyze(List.of(gpu, board), Map.of("MSI|JRAINBOW1", 40), Set.of(), true).stream()
                .noneMatch(f -> f.text().contains("JRAINBOW1")));
    }

    @Test
    void namesVendorAppsThatFightForTheLights() {
        assertEquals(List.of("Armoury Crate / Aura", "iCUE"),
                LightingCheck.rivals(Set.of("icue.exe", "lightingservice.exe", "explorer.exe")));
        assertTrue(LightingCheck.rivals(Set.of("javaw.exe", "openrgb.exe")).isEmpty());

        // Под SignalRGB это не наша забота — он сам разбирается с конфликтами
        var gpu = new OpenRgb.Device(0, "GPU", "видеокарта", 20);
        var board = new OpenRgb.Device(1, "MSI", "материнская плата", 10);
        assertTrue(LightingCheck.analyze(List.of(gpu, board), Map.of(), Set.of("icue.exe"), true).stream()
                .anyMatch(f -> f.text().contains("iCUE")));
        assertTrue(LightingCheck.analyze(List.of(gpu, board), Map.of(), Set.of("icue.exe"), false).isEmpty());
    }
}
