package ru.virus.manager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * config.properties как его видит человек: разделы, подсказки из комментариев,
 * значения. Правка меняет только значения — комментарии, порядок и пустые
 * строки остаются как были, чтобы файл можно было и дальше читать глазами.
 */
final class ConfigFile {

    /** Строка «ключ=значение». Закомментированные не считаем. */
    private static final Pattern ENTRY = Pattern.compile("^(\\s*)([A-Za-z0-9_.\\-]+)(\\s*[=:]\\s*)(.*)$");

    /** Черта, которой в образце обведены заголовки разделов. */
    private static final Pattern RULE = Pattern.compile("^#\\s*-{5,}\\s*$");

    /** Похоже на секрет — в панели прячется под точками. */
    private static final Pattern SECRET = Pattern.compile("(?i).*(token|\\.key$|webhook|secret|password).*");

    /** Поле настройки. */
    record Field(String key, String value, String section, String hint, boolean secret, String kind) {

        Map<String, Object> toJson() {
            var json = new LinkedHashMap<String, Object>();
            json.put("key", key);
            json.put("value", value);
            json.put("section", section);
            json.put("hint", hint);
            json.put("secret", secret);
            json.put("kind", kind);
            return json;
        }
    }

    private final List<String> lines;

    private ConfigFile(List<String> lines) {
        this.lines = new ArrayList<>(lines);
    }

    static ConfigFile parse(String text) {
        // Пустой хвост split отбрасывает — и при записи файл не обрастает пустыми строками
        return new ConfigFile(text.isEmpty() ? List.of() : List.of(text.split("\\R", -1)));
    }

    static ConfigFile read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return new ConfigFile(List.of());
        }

        var text = Files.readString(file, StandardCharsets.UTF_8);

        // Блокнот на Windows любит ставить в начало невидимую метку BOM — без
        // неё первый ключ читался бы как «﻿bot.token»
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }

        return parse(text);
    }

    String text() {
        return String.join("\n", lines);
    }

    /**
     * Пишет файл, оставляя рядом прошлую версию как .bak: если правка из панели
     * что-то сломала, вернуть можно одним переименованием.
     */
    void write(Path file) throws IOException {
        if (Files.isRegularFile(file)) {
            Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"),
                    StandardCopyOption.REPLACE_EXISTING);
        }

        var temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, text(), StandardCharsets.UTF_8);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }

    List<Field> fields() {
        var fields = new ArrayList<Field>();
        var section = "Основное";
        var comment = new ArrayList<String>();

        for (var i = 0; i < lines.size(); i++) {
            var line = lines.get(i);
            var trimmed = line.strip();

            // Заголовок раздела: черта, строка с названием, черта
            if (RULE.matcher(trimmed).matches() && i + 2 < lines.size()
                    && RULE.matcher(lines.get(i + 2).strip()).matches()
                    && lines.get(i + 1).strip().startsWith("#")) {
                section = lines.get(i + 1).strip().replaceFirst("^#\\s*", "");
                comment.clear();
                i += 2;
                continue;
            }

            if (trimmed.isEmpty()) {
                comment.clear();
                continue;
            }

            if (trimmed.startsWith("#") || trimmed.startsWith("!")) {
                comment.add(trimmed.replaceFirst("^[#!]\\s?", ""));
                continue;
            }

            var entry = ENTRY.matcher(line);

            if (entry.matches()) {
                var key = entry.group(2);
                var value = entry.group(4);
                fields.add(new Field(key, value, section, String.join("\n", comment).strip(),
                        SECRET.matcher(key).matches(), kind(value)));
            }

            comment.clear();
        }

        return fields;
    }

    private static String kind(String value) {
        var lower = value.strip().toLowerCase();

        if (lower.equals("true") || lower.equals("false")) {
            return "bool";
        }

        return value.strip().matches("-?\\d{1,9}") ? "number" : "text";
    }

    Map<String, String> values() {
        var values = new LinkedHashMap<String, String>();

        for (var field : fields()) {
            values.put(field.key(), field.value());
        }

        return values;
    }

    /**
     * Меняет значения. Ключи, которых в файле нет, дописываются в конец.
     *
     * @return какие ключи действительно изменились
     */
    List<String> update(Map<String, String> changes) {
        var changed = new ArrayList<String>();
        var left = new LinkedHashMap<>(changes);

        for (var i = 0; i < lines.size(); i++) {
            var entry = ENTRY.matcher(lines.get(i));

            if (!entry.matches() || lines.get(i).strip().startsWith("#")) {
                continue;
            }

            var key = entry.group(2);

            if (!left.containsKey(key)) {
                continue;
            }

            var value = oneLine(left.remove(key));

            if (!value.equals(entry.group(4))) {
                lines.set(i, entry.group(1) + key + entry.group(3) + value);
                changed.add(key);
            }
        }

        for (var rest : left.entrySet()) {
            lines.add(rest.getKey() + "=" + oneLine(rest.getValue()));
            changed.add(rest.getKey());
        }

        return changed;
    }

    /** Перевод строки в значении разорвал бы его на две — и вторая стала бы мусором. */
    private static String oneLine(String value) {
        return value == null ? "" : value.replace("\r", "").replace("\n", " ");
    }

    /**
     * Настройки из образца, которых в файле нет, — с их пояснениями. Нужно
     * после обновления: новая версия бота умеет больше, чем знает старый файл.
     *
     * @return сколько добавлено
     */
    int addMissing(ConfigFile example) {
        var have = new LinkedHashSet<>(values().keySet());
        var block = new ArrayList<String>();
        var comment = new ArrayList<String>();
        var section = "";
        var added = 0;

        for (var i = 0; i < example.lines.size(); i++) {
            var line = example.lines.get(i);
            var trimmed = line.strip();

            if (RULE.matcher(trimmed).matches() && i + 2 < example.lines.size()
                    && RULE.matcher(example.lines.get(i + 2).strip()).matches()) {
                section = example.lines.get(i + 1).strip().replaceFirst("^#\\s*", "");
                comment.clear();
                i += 2;
                continue;
            }

            if (trimmed.isEmpty()) {
                comment.clear();
                continue;
            }

            if (trimmed.startsWith("#")) {
                comment.add(line);
                continue;
            }

            var entry = ENTRY.matcher(line);

            if (entry.matches() && !have.contains(entry.group(2))) {
                block.add("");

                if (!section.isEmpty()) {
                    block.add("# [" + section + "]");
                }

                block.addAll(comment);
                block.add(line);
                have.add(entry.group(2));
                added++;
            }

            comment.clear();
        }

        if (added > 0) {
            lines.add("");
            lines.add("# ---------------------------------------------------------------------------");
            lines.add("# Добавлено V.I.R.U.S. из образца " + LocalDate.now());
            lines.add("# ---------------------------------------------------------------------------");
            lines.addAll(block);
        }

        return added;
    }
}
