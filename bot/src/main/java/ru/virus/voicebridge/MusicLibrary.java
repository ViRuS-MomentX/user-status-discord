package ru.virus.voicebridge;

import net.dv8tion.jda.api.utils.data.DataArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Своя фонотека: папка со скачанными файлами и опись к ней.
 *
 * <p>Скачанное играет всегда: ни таймаутов, ни блокировок, ни «сегодня отвечает через
 * раз». Чем наполнять её — дело канала со ссылками, здесь только хранение и поиск.
 */
public final class MusicLibrary {

    private static final Logger log = LoggerFactory.getLogger(MusicLibrary.class);

    /** Имя описи внутри папки фонотеки. */
    private static final String INDEX = "library.json";

    private final Path folder;

    /**
     * Треки по ссылке-источнику.
     *
     * <p>Ссылка же служит опознавательным знаком: один и тот же ролик, брошенный в канал
     * дважды, не должен скачиваться дважды.
     */
    private final Map<String, LibraryTrack> tracks = new LinkedHashMap<>();

    public MusicLibrary(Path folder) {
        this.folder = folder;
    }

    public Path getFolder() {
        return folder;
    }

    public synchronized int size() {
        return tracks.size();
    }

    public synchronized boolean has(String source) {
        return tracks.containsKey(source);
    }

    public synchronized LibraryTrack get(String source) {
        return tracks.get(source);
    }

    /** Полный путь к файлу трека. */
    public Path fileOf(LibraryTrack track) {
        return folder.resolve(track.file());
    }

    public synchronized void add(LibraryTrack track) {
        tracks.put(track.source(), track);
        save();
    }

    /**
     * Собирает плейлист по запросу.
     *
     * <p>Сначала то, что совпало с запросом, потом остальное того же исполнителя, потом
     * всё прочее. Так одна найденная песня превращается в сеанс, а не в тишину после
     * трёх минут.
     *
     * @return пустой список, если по запросу не нашлось ничего
     */
    public synchronized List<LibraryTrack> playlistFor(String query, int limit) {
        var matched = search(query, limit);

        if (matched.isEmpty()) {
            return List.of();
        }

        var playlist = new ArrayList<>(matched);
        var artist = normalize(matched.get(0).artist());

        // Сначала доливаем тем же исполнителем — это ближе к запросу, чем случайное
        if (!artist.isBlank()) {
            for (var track : tracks.values()) {
                if (playlist.size() >= limit) {
                    break;
                }
                if (!playlist.contains(track) && normalize(track.artist()).equals(artist)) {
                    playlist.add(track);
                }
            }
        }

        if (playlist.size() < limit) {
            var rest = new ArrayList<>(tracks.values());
            rest.removeAll(playlist);
            Collections.shuffle(rest);

            for (var track : rest) {
                if (playlist.size() >= limit) {
                    break;
                }
                playlist.add(track);
            }
        }

        return playlist;
    }

    /**
     * Ищет треки по словам запроса.
     *
     * <p>Считаем, сколько слов запроса нашлось в названии и исполнителе. Точного
     * совпадения не требуем: человек пишет «группа крови», а в файле «Кино - Группа
     * крови (1988)».
     */
    public synchronized List<LibraryTrack> search(String query, int limit) {
        var words = normalize(query).split(" ");
        var scored = new ArrayList<Map.Entry<LibraryTrack, Integer>>();

        for (var track : tracks.values()) {
            var haystack = normalize(track.haystack());
            var score = 0;

            for (var word : words) {
                if (!word.isBlank() && haystack.contains(word)) {
                    score++;
                }
            }

            if (score > 0) {
                scored.add(Map.entry(track, score));
            }
        }

        // Больше совпавших слов — выше; при равенстве короче название, оно ближе к запросу
        scored.sort((left, right) -> {
            var byScore = right.getValue() - left.getValue();
            return byScore != 0 ? byScore : left.getKey().haystack().length() - right.getKey().haystack().length();
        });

        var found = new ArrayList<LibraryTrack>();

        for (var entry : scored) {
            if (found.size() >= limit) {
                break;
            }
            found.add(entry.getKey());
        }

        return found;
    }

    /** Всё подряд, вперемешку: для «включи что-нибудь». */
    public synchronized List<LibraryTrack> shuffled(int limit) {
        var all = new ArrayList<>(tracks.values());
        Collections.shuffle(all);
        return all.subList(0, Math.min(limit, all.size()));
    }

    /** Приводит строку к виду, по которому сравнивать не жалко. */
    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /**
     * Читает опись и выбрасывает записи, файлов которых больше нет.
     */
    public synchronized void load() {
        var index = folder.resolve(INDEX);

        if (!Files.isRegularFile(index)) {
            return;
        }

        try {
            var array = DataArray.fromJson(Files.readString(index, StandardCharsets.UTF_8));
            var lost = 0;

            for (var i = 0; i < array.length(); i++) {
                var track = LibraryTrack.from(array.getObject(i));

                // Файл могли удалить руками — держать его в описи значит однажды
                // наткнуться на него в очереди и получить тишину
                if (Files.isRegularFile(fileOf(track))) {
                    tracks.put(track.source(), track);
                } else {
                    lost++;
                }
            }

            log.info("Фонотека: {} треков в {}.", tracks.size(), folder.toAbsolutePath());

            if (lost > 0) {
                log.warn("Файлов не нашлось: {}. Эти записи убраны из описи.", lost);
                save();
            }
        } catch (Exception e) {
            log.error("Не удалось прочитать опись {}: {}", index.toAbsolutePath(), e.getMessage());
        }
    }

    private void save() {
        var array = DataArray.empty();
        tracks.values().forEach(track -> array.add(track.toJson()));

        try {
            Files.createDirectories(folder);
            Files.writeString(folder.resolve(INDEX), array.toPrettyString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Не удалось записать опись: {}", e.getMessage());
        }
    }
}
