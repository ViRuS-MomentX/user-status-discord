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
        var lines = new ArrayList<String>();

        for (var entry : entries()) {
            if (lines.size() >= LIMIT) {
                break;
            }

            var shown = seen.apply(entry.id());
            var who = shown == null || shown.isBlank() ? "участник с ID " + entry.id() : "«" + shown + "»";
            var line = new StringBuilder("- ").append(who);

            if (!entry.name().isEmpty()) {
                line.append(": называй «").append(entry.name()).append("»");
            }

            if (!entry.jokes().isEmpty()) {
                line.append(entry.name().isEmpty() ? ": " : ". ").append("Шутить над ним можно так: ")
                        .append(entry.jokes());
            }

            lines.add(line.toString());
        }

        return String.join("\n", lines);
    }
}
