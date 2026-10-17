package ru.virus.manager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Настройки самого V.I.R.U.S. — virus-settings.json рядом с virus.jar.
 * Не путать с config.properties: тот — настройки ботов.
 */
final class VirusSettings {

    private final Path file;
    private final Map<String, Object> values = new LinkedHashMap<>();

    VirusSettings(Path file) {
        this.file = file;
    }

    synchronized void load() throws IOException {
        values.clear();

        if (Files.isRegularFile(file)) {
            values.putAll(Json.object(Files.readString(file, StandardCharsets.UTF_8)));
        }
    }

    synchronized String text(String key, String fallback) {
        var value = values.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    synchronized boolean flag(String key, boolean fallback) {
        return values.get(key) instanceof Boolean flag ? flag : fallback;
    }

    synchronized void put(Map<String, Object> changes) throws IOException {
        values.putAll(changes);
        var temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, Json.write(values), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
