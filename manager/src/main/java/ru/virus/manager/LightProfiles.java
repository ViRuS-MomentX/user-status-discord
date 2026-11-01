package ru.virus.manager;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Профили подсветки и правила «программа → профиль», как в iCUE, Armoury
 * Crate и Game Integrations у SignalRGB: запустил игру — подсветка
 * переключилась сама, закрыл — вернулась прежняя.
 */
final class LightProfiles {

    record Profile(String name, int mode, Color color, Color color2, int speed) {

        Map<String, Object> toJson() {
            var json = new LinkedHashMap<String, Object>();
            json.put("name", name);
            json.put("mode", mode);
            json.put("color", SignalRgb.hex(color));
            json.put("color2", SignalRgb.hex(color2));
            json.put("speed", speed);
            return json;
        }

        static Profile fromJson(Map<?, ?> json) {
            return new Profile(String.valueOf(json.get("name")),
                    json.get("mode") instanceof Number n ? n.intValue() : 0,
                    Color.decode(String.valueOf(json.containsKey("color") ? json.get("color") : "#39FF8A")),
                    Color.decode(String.valueOf(json.containsKey("color2") ? json.get("color2") : "#8A2BE2")),
                    json.get("speed") instanceof Number n ? n.intValue() : 50);
        }
    }

    private LightProfiles() {
    }

    /**
     * Правила из текста: по строке «игра.exe = Профиль». Пустые строки и
     * строки без «=» пропускаются; «.exe» можно не писать.
     */
    static Map<String, String> parseRules(String text) {
        var rules = new LinkedHashMap<String, String>();

        for (var line : text.split("\\R")) {
            var at = line.indexOf('=');

            if (at <= 0) {
                continue;
            }

            var program = program(line.substring(0, at));
            var profile = line.substring(at + 1).strip();

            if (!program.isEmpty() && !profile.isEmpty()) {
                rules.put(program, profile);
            }
        }

        return rules;
    }

    static String rulesText(Map<String, String> rules) {
        var lines = new ArrayList<String>();
        rules.forEach((program, profile) -> lines.add(program + " = " + profile));
        return String.join("\n", lines);
    }

    /** «C:\\Games\\MarvelRivals.EXE» → «marvelrivals.exe». */
    static String program(String raw) {
        var name = raw.strip().replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return name.isEmpty() || name.endsWith(".exe") ? name : name + ".exe";
    }

    /** Какой профиль включить: правило первой найденной запущенной программы; null — ни одной. */
    static String wanted(Map<String, String> rules, Set<String> running) {
        for (var rule : rules.entrySet()) {
            if (running.contains(rule.getKey())) {
                return rule.getValue();
            }
        }

        return null;
    }

    /** Имена запущенных программ — строчными. */
    static Set<String> running() {
        var names = new java.util.HashSet<String>();
        ProcessHandle.allProcesses().forEach(process -> process.info().command()
                .ifPresent(command -> names.add(program(command))));
        return names;
    }
}
