package ru.virus.voicebridge;

import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Журнал реплик бота и оценки хозяина.
 *
 * <p>Бот дописывает сюда каждую свою реплику вместе с тем, на что отвечал.
 * В V.I.R.U.S. хозяин ставит им 👍 или 👎 (ratings.json рядом). Понравившиеся
 * ИИ видит как пример уровня, не понравившиеся — как «так не говори», а
 * похожее на них бот переписывает. Так бот умнеет от оценок, а не от правок
 * в коде.
 *
 * <p>Лежит в parody-memory — в отчёт для разбора не попадает: здесь есть
 * сообщения участников.
 */
final class ParodyJournal {

    private static final Logger log = LoggerFactory.getLogger(ParodyJournal.class);

    /** Сколько реплик хранить: больше — файл пухнет, а старые оценки и так есть. */
    static final int KEEP = 500;

    record Entry(long id, long at, String to, String context, String reply) {
    }

    private final Path journal;
    private final Path ratings;
    private long ratingsStamp = -1;
    private Map<Long, Integer> scores = Map.of();
    private int written;

    ParodyJournal(Path folder) {
        this.journal = folder.resolve("journal.jsonl");
        this.ratings = folder.resolve("ratings.json");
    }

    /** Записать реплику. Сбой записи не мешает боту говорить. */
    synchronized void add(long id, long at, String to, String context, String reply) {
        var line = DataObject.empty()
                .put("id", id)
                .put("at", at)
                .put("to", to)
                .put("context", context)
                .put("reply", reply)
                .toString();

        try {
            Files.createDirectories(journal.getParent());
            Files.writeString(journal, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            if (++written % 50 == 0) {
                trim();
            }
        } catch (IOException e) {
            log.debug("Журнал реплик не записался: {}", e.getMessage());
        }
    }

    /** Оставить последние {@link #KEEP} строк. */
    private void trim() throws IOException {
        var lines = Files.readAllLines(journal, StandardCharsets.UTF_8);

        if (lines.size() <= KEEP) {
            return;
        }

        var temp = journal.resolveSibling(journal.getFileName() + ".tmp");
        Files.write(temp, lines.subList(lines.size() - KEEP, lines.size()), StandardCharsets.UTF_8);
        Files.move(temp, journal, StandardCopyOption.REPLACE_EXISTING);
    }

    synchronized List<Entry> entries() {
        var result = new ArrayList<Entry>();

        if (!Files.isRegularFile(journal)) {
            return result;
        }

        try {
            for (var line : Files.readAllLines(journal, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }

                try {
                    var json = DataObject.fromJson(line);
                    result.add(new Entry(json.getLong("id", 0), json.getLong("at", 0), json.getString("to", ""),
                            json.getString("context", ""), json.getString("reply", "")));
                } catch (RuntimeException ignored) {
                    // оборванная строка (выключили компьютер посреди записи) — пропускаем
                }
            }
        } catch (IOException e) {
            log.debug("Журнал реплик не прочитался: {}", e.getMessage());
        }

        return result;
    }

    /** Оценки хозяина: ID реплики → 1 (👍) или −1 (👎). Перечитываются, когда файл меняется. */
    synchronized Map<Long, Integer> scores() {
        try {
            var stamp = Files.isRegularFile(ratings) ? Files.getLastModifiedTime(ratings).toMillis() : 0;

            if (stamp != ratingsStamp) {
                ratingsStamp = stamp;
                scores = stamp == 0 ? Map.of() : read();
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Оценки реплик не прочитались: {}", e.getMessage());
        }

        return scores;
    }

    private Map<Long, Integer> read() throws IOException {
        var json = DataObject.fromJson(Files.readString(ratings, StandardCharsets.UTF_8));
        var result = new HashMap<Long, Integer>();

        for (var key : json.keys()) {
            try {
                result.put(Long.parseLong(key), Integer.signum(json.getInt(key, 0)));
            } catch (NumberFormatException ignored) {
                // не ID — пропускаем
            }
        }

        return result;
    }

    /** Последние реплики с такой оценкой, самые свежие первыми. */
    List<String> rated(int sign, int limit) {
        var marks = scores();

        if (marks.isEmpty()) {
            return List.of();
        }

        var result = new ArrayList<String>();
        var all = entries();

        for (var i = all.size() - 1; i >= 0 && result.size() < limit; i--) {
            var entry = all.get(i);

            if (marks.getOrDefault(entry.id(), 0) == sign && !result.contains(entry.reply())) {
                result.add(entry.reply());
            }
        }

        return result;
    }
}
