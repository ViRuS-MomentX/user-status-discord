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
    private boolean imported;
    private boolean forgotten;
    private long target;
    private String day = "";
    private int sentToday;
    /** Свои последние реплики: чтобы и после перезапуска не повторяться. */
    private final java.util.List<String> said = new java.util.ArrayList<>();

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
            imported = json.getBoolean("imported", false);
            forgotten = json.getBoolean("forgotten", false);
            target = json.getLong("target", 0);
            day = json.getString("day", "");
            sentToday = json.getInt("sentToday", 0);
            said.clear();

            if (json.hasKey("said")) {
                var list = json.getArray("said");

                for (var i = 0; i < list.length(); i++) {
                    said.add(list.getString(i));
                }
            }
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

    /** Забран ли уже экспорт истории. */
    synchronized boolean isImported() {
        return imported;
    }

    synchronized void markImported() {
        imported = true;
        save();
    }

    /**
     * Участник сам стёр свои сообщения командой /parody_forget. Тогда экспорт
     * заново не читается: перезапуск не должен возвращать стёртое.
     */
    synchronized boolean isForgotten() {
        return forgotten;
    }

    synchronized void markForgotten() {
        forgotten = true;
        save();
    }

    /** Кого пародировали в прошлый раз; 0 — неизвестно (старый файл). */
    synchronized long target() {
        return target;
    }

    /** Новый участник — для него ни экспорт не читан, ни «забудь» не говорено. */
    synchronized void setTarget(long id) {
        if (target != id) {
            if (target != 0) {
                imported = false;
                forgotten = false;
            }

            target = id;
            save();
        }
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

    synchronized java.util.List<String> said() {
        return java.util.List.copyOf(said);
    }

    /** Запомнить свою реплику; держим не больше {@code limit} последних. */
    synchronized void addSaid(String text, int limit) {
        said.add(text);

        while (said.size() > limit) {
            said.removeFirst();
        }

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
                .put("imported", imported)
                .put("forgotten", forgotten)
                .put("target", target)
                .put("day", day)
                .put("sentToday", sentToday)
                .put("said", net.dv8tion.jda.api.utils.data.DataArray.fromCollection(said));

        try {
            var temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, json.toString(), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("Не сохранилось состояние пародии в {}: {}", file.getFileName(), e.getMessage());
        }
    }
}
