package ru.virus.manager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Что и как запускать.
 *
 * <p>В команде можно писать {@code {java}} — та же Java, на которой работает
 * V.I.R.U.S., — и {@code {home}} — его папка. Так описание не ломается, если
 * папку перенести или обновить Java.
 *
 * @param id короткое имя для адресов и файлов: латиница, цифры, дефис
 * @param name как показывать человеку
 * @param command программа и её аргументы
 * @param workdir где запускать; пусто — папка V.I.R.U.S.
 * @param autostart запускать вместе с V.I.R.U.S.
 * @param restart поднимать снова, если упал
 * @param supervised понимает ли команду «stop» на вход — тогда и останавливается по-хорошему
 */
record ProcessSpec(String id, String name, List<String> command, String workdir,
                   boolean autostart, boolean restart, boolean supervised) {

    ProcessSpec {
        if (id == null || !id.matches("[a-z0-9][a-z0-9-]{0,31}")) {
            throw new IllegalArgumentException("id — латиница в нижнем регистре, цифры и дефис, до 32 знаков");
        }

        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("нужно имя");
        }

        if (command == null || command.isEmpty() || command.getFirst().isBlank()) {
            throw new IllegalArgumentException("нужна команда");
        }

        command = List.copyOf(command);
        workdir = workdir == null ? "" : workdir;
    }

    /** Боты из этого репозитория — по умолчанию, пока своих описаний нет. */
    static List<ProcessSpec> defaults() {
        return List.of(
                new ProcessSpec("discord", "Discord-бот и мост", bot("--no-parody"), "", true, true, true),
                new ProcessSpec("parody", "Пародия", bot("--parody-only"), "", true, true, true));
    }

    private static List<String> bot(String mode) {
        // Вывод — в UTF-8: иначе Java на Windows пишет в кодировке консоли,
        // и русские строки приходят в панель кракозябрами
        return List.of("{java}", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-jar", "voice-bridge-bot.jar", "config.properties", mode, "--supervised");
    }

    Map<String, Object> toJson() {
        var json = new LinkedHashMap<String, Object>();
        json.put("id", id);
        json.put("name", name);
        json.put("command", command);
        json.put("workdir", workdir);
        json.put("autostart", autostart);
        json.put("restart", restart);
        json.put("supervised", supervised);
        return json;
    }

    static ProcessSpec fromJson(Map<String, Object> json) {
        var command = new ArrayList<String>();

        if (json.get("command") instanceof List<?> list) {
            for (var part : list) {
                command.add(String.valueOf(part));
            }
        } else if (json.get("command") instanceof String line) {
            command.addAll(split(line));
        }

        return new ProcessSpec(
                String.valueOf(json.getOrDefault("id", "")),
                String.valueOf(json.getOrDefault("name", "")),
                command,
                String.valueOf(json.getOrDefault("workdir", "")),
                Boolean.TRUE.equals(json.get("autostart")),
                !Boolean.FALSE.equals(json.get("restart")),
                Boolean.TRUE.equals(json.get("supervised")));
    }

    /**
     * Делит строку команды на части, как это сделала бы консоль: по пробелам,
     * но с кавычками для путей с пробелами — «C:\Program Files\...».
     */
    static List<String> split(String line) {
        var parts = new ArrayList<String>();
        var current = new StringBuilder();
        var quoted = false;
        var started = false;

        for (var i = 0; i < line.length(); i++) {
            var c = line.charAt(i);

            if (c == '"') {
                quoted = !quoted;
                started = true;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (started) {
                    parts.add(current.toString());
                    current.setLength(0);
                    started = false;
                }
            } else {
                current.append(c);
                started = true;
            }
        }

        if (started) {
            parts.add(current.toString());
        }

        return parts;
    }

    /** Обратно в строку — для поля ввода в панели. */
    static String join(List<String> command) {
        var line = new StringBuilder();

        for (var part : command) {
            if (!line.isEmpty()) {
                line.append(' ');
            }

            line.append(part.isEmpty() || part.matches(".*\\s.*") ? '"' + part + '"' : part);
        }

        return line.toString();
    }
}
