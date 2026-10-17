package ru.virus.manager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Настройки ботов: прочитать, поправить, добавить недостающее из образца.
 *
 * <p>Образец едет внутри virus.jar — из него создаётся config.properties и
 * добавляются настройки, появившиеся в новых версиях бота.
 */
final class ConfigService {

    /** Где файл — спрашиваем каждый раз: после переезда в папку проекта он уже в другом месте. */
    private final java.util.function.Supplier<Path> where;

    ConfigService(Path file) {
        this(() -> file);
    }

    ConfigService(java.util.function.Supplier<Path> where) {
        this.where = where;
    }

    Path file() {
        return where.get();
    }

    boolean exists() {
        return Files.isRegularFile(file());
    }

    ConfigFile read() throws IOException {
        return ConfigFile.read(file());
    }

    /** @return какие ключи действительно изменились */
    List<String> save(Map<String, String> changes) throws IOException {
        var config = read();
        var changed = config.update(withoutPastedKeys(changes));

        if (!changed.isEmpty()) {
            config.write(file());
        }

        return changed;
    }

    /**
     * Вставили в поле строку целиком — «ключ=значение» — и вышло бы
     * «ключ=ключ=значение». Отрезаем повторённое имя.
     */
    static Map<String, String> withoutPastedKeys(Map<String, String> changes) {
        var clean = new java.util.LinkedHashMap<String, String>();

        changes.forEach((key, value) -> {
            var fixed = value.strip();

            while (fixed.startsWith(key + "=") || fixed.startsWith(key + ":")) {
                fixed = fixed.substring(key.length() + 1).strip();
            }

            clean.put(key, fixed.equals(value.strip()) ? value : fixed);
        });

        return clean;
    }

    String raw() throws IOException {
        return read().text();
    }

    void saveRaw(String text) throws IOException {
        ConfigFile.parse(text.replace("\r\n", "\n")).write(file());
    }

    void createFromExample() throws IOException {
        if (exists()) {
            throw new IOException("config.properties уже есть");
        }

        example().write(file());
    }

    /** Сколько настроек из образца нет в файле. */
    int missing() throws IOException {
        return exists() ? ConfigFile.parse(read().text()).addMissing(example()) : 0;
    }

    int addMissing() throws IOException {
        var config = read();
        var added = config.addMissing(example());

        if (added > 0) {
            config.write(file());
        }

        return added;
    }

    static ConfigFile example() throws IOException {
        try (InputStream in = ConfigService.class.getResourceAsStream("/config.properties.example")) {
            return in == null ? ConfigFile.parse("")
                    : ConfigFile.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
