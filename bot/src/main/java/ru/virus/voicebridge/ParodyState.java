package ru.virus.voicebridge;

import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;

/**
 * Что должно пережить перезапуск: выключена ли пародия и сколько реплик
 * сказано за сегодня.
 *
 * <p>Выключение особенно: если участник попросил замолчать, бот не должен
 * заговорить снова после первой же перезагрузки компьютера.
 */
final class ParodyState {

    private static final Logger log = LoggerFactory.getLogger(ParodyState.class);

    private final Path file;

    private boolean paused;
    private String day = "";
    private int sentToday;

    ParodyState(Path file) {
        this.file = file;
    }

    synchronized void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }

        try {
            var json = DataObject.fromJson(Files.readString(file, StandardCharsets.UTF_8));
            paused = json.getBoolean("paused", false);
            day = json.getString("day", "");
            sentToday = json.getInt("sentToday", 0);
        } catch (IOException | RuntimeException e) {
            // Не знаем, выключали ли пародию, — значит, считаем, что выключали:
            // молчать по ошибке безобиднее, чем заговорить вопреки просьбе
            paused = true;
            log.error("Не прочиталось состояние пародии из {}: {}. Пародия выключена — "
                    + "включить можно командой /parody_on.", file.getFileName(), e.getMessage());
        }
    }

    synchronized boolean isPaused() {
        return paused;
    }

    synchronized void setPaused(boolean paused) {
        this.paused = paused;
        save();
    }

    synchronized int sentToday(LocalDate today) {
        roll(today);
        return sentToday;
    }

    synchronized void countSent(LocalDate today) {
        roll(today);
        sentToday++;
        save();
    }

    private void roll(LocalDate today) {
        var key = today.toString();

        if (!key.equals(day)) {
            day = key;
            sentToday = 0;
        }
    }

    private void save() {
        var json = DataObject.empty()
                .put("paused", paused)
                .put("day", day)
                .put("sentToday", sentToday);

        try {
            var temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, json.toString(), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("Не сохранилось состояние пародии в {}: {}", file.getFileName(), e.getMessage());
        }
    }
}
