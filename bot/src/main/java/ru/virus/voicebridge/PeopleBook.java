package ru.virus.voicebridge;

import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;

/**
 * Кого как называть и как над кем можно шутить — список, который хозяин
 * ведёт в V.I.R.U.S. (parody-people.json рядом с настройками).
 *
 * <p>Файл перечитывается, когда меняется: поправил в окне — бот учёл со
 * следующей реплики, без перезапуска.
 */
final class PeopleBook {

    private static final Logger log = LoggerFactory.getLogger(PeopleBook.class);

    /** Больше в подсказку не кладём: длинный список вытеснил бы разговор. */
    static final int LIMIT = 40;

    record Entry(long id, String name, String jokes) {
    }

    private final Path file;
    private long stamp = -1;
    private List<Entry> entries = List.of();

    PeopleBook(Path file) {
        this.file = file;
    }

    synchronized List<Entry> entries() {
        try {
            var modified = Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : 0;

            if (modified != stamp) {
                stamp = modified;
                entries = modified == 0 ? List.of() : read();
            }
        } catch (Exception e) {
            log.warn("Не прочитался список участников {}: {}", file.getFileName(), e.getMessage());
        }

        return entries;
    }

    private List<Entry> read() throws java.io.IOException {
        var root = DataObject.fromJson(Files.readString(file, StandardCharsets.UTF_8));
        var list = new ArrayList<Entry>();

        if (root.hasKey("people")) {
            var people = root.getArray("people");

            for (var i = 0; i < people.length(); i++) {
                var item = people.getObject(i);
                var id = parse(item.getString("id", ""));
                var name = item.getString("name", "").strip();
                var jokes = item.getString("jokes", "").strip();

                if (id != 0 && (!name.isEmpty() || !jokes.isEmpty())) {
                    list.add(new Entry(id, name, jokes));
                }
            }
        }

        log.info("Список участников: {}.", list.size());
        return List.copyOf(list);
    }

    private static long parse(String text) {
        try {
            return Long.parseLong(text.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Строки для подсказки ИИ.
     *
     * @param seen как человека видно в чате (имя в Telegram); null — не встречался
     * @return пусто — список пуст
     */
    String describe(LongFunction<String> seen) {
        return describe(seen, null);
    }

    /** @param only кого показывать; null — всех */
    String describe(LongFunction<String> seen, java.util.Set<Long> only) {
        var lines = new ArrayList<String>();

        for (var entry : entries()) {
            if (lines.size() >= LIMIT) {
                break;
            }

            if (only != null && !only.contains(entry.id())) {
                continue;
            }

            // Имя уже стоит в переписке вместо ника — здесь нужно только, как шутить
            if (entry.jokes().isEmpty()) {
                continue;
            }

            var shown = seen.apply(entry.id());
            var who = !entry.name().isEmpty() ? "«" + entry.name() + "»"
                    : shown == null || shown.isBlank() ? "участник с ID " + entry.id() : "«" + shown + "»";
            lines.add("- " + who + ": шутить над ним можно так: " + entry.jokes());
        }

        return String.join("\n", lines);
    }
}
