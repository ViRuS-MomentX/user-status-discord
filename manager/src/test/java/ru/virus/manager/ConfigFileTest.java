package ru.virus.manager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigFileTest {

    static final String TEXT = """
            # Токен бота.
            # Никому не показывай.
            bot.token=abc

            guild.id = 123

            # ---------------------------------------------------------------------------
            # Пародия
            # ---------------------------------------------------------------------------
            # Включить ли.
            parody.enabled=false

            # Ключ ИИ.
            parody.llm.key=
            # parody.old=закомментировано
            parody.chance=5""";

    @Test
    void sectionsHintsAndKinds() {
        var fields = ConfigFile.parse(TEXT).fields();

        assertEquals(List.of("bot.token", "guild.id", "parody.enabled", "parody.llm.key", "parody.chance"),
                fields.stream().map(ConfigFile.Field::key).toList());

        var token = fields.get(0);
        assertEquals("Основное", token.section());
        assertEquals("Токен бота.\nНикому не показывай.", token.hint());
        assertTrue(token.secret());

        var enabled = fields.get(2);
        assertEquals("Пародия", enabled.section());
        assertEquals("bool", enabled.kind());
        assertTrue(fields.get(3).secret(), "parody.llm.key — секрет");
        assertFalse(fields.get(1).secret());
        assertEquals("number", fields.get(4).kind());
        assertEquals("parody.old=закомментировано", fields.get(4).hint(), "комментарий над строкой — её подсказка");
    }

    @Test
    void updateKeepsEverythingElse() {
        var file = ConfigFile.parse(TEXT);
        var changed = file.update(Map.of("guild.id", "999", "parody.enabled", "true",
                "bot.token", "abc", "new.key", "x\ny"));

        assertEquals(List.of("guild.id", "parody.enabled", "new.key"), changed);
        var text = file.text();
        assertTrue(text.contains("guild.id = 999"), "разделитель с пробелами сохранён");
        assertTrue(text.contains("# Никому не показывай."));
        assertTrue(text.contains("# parody.old=закомментировано"), "закомментированное не трогаем");
        assertTrue(text.endsWith("new.key=x y"), "перевод строки не должен разорвать значение");
        assertEquals(TEXT.lines().count() + 1, text.lines().count());
    }

    @Test
    void addMissingBringsHintsAndSection() {
        var mine = ConfigFile.parse("bot.token=abc\nparody.enabled=true");
        var added = mine.addMissing(ConfigFile.parse(TEXT));

        assertEquals(3, added);
        var values = mine.values();
        assertEquals("true", values.get("parody.enabled"), "своё значение не перетирается");
        assertEquals("", values.get("parody.llm.key"));
        assertEquals("5", values.get("parody.chance"));
        assertTrue(mine.text().contains("# Ключ ИИ."));
        assertTrue(mine.text().contains("# [Пародия]"));
        assertEquals(0, mine.addMissing(ConfigFile.parse(TEXT)), "второй раз добавлять нечего");
    }

    @Test
    void writeKeepsBackupAndHandlesBom(@TempDir Path dir) throws Exception {
        var file = dir.resolve("config.properties");
        Files.writeString(file, "\uFEFFbot.token=old", StandardCharsets.UTF_8);

        var config = ConfigFile.read(file);
        assertEquals("old", config.values().get("bot.token"), "метка BOM не должна прилипать к ключу");

        config.update(Map.of("bot.token", "new"));
        config.write(file);

        assertEquals("bot.token=new", Files.readString(file));
        assertEquals("\uFEFFbot.token=old", Files.readString(dir.resolve("config.properties.bak")));
    }
}
