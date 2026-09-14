package ru.virus.voicebridge;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Обработка музыкальных запросов: подобрать, найти на SoundCloud, поставить в очередь.
 *
 * <p>Живёт отдельно от команд, потому что одно и то же нужно и текстовой команде, и
 * кнопкам панели. Куда отправлять сообщения о ходе дела, решает вызывающий — сюда он
 * передаёт получателя текста.
 */
public final class MusicRequests {

    private static final Logger log = LoggerFactory.getLogger(MusicRequests.class);

    private final MusicService music;
    private final MusicCatalog catalog;
    private final int playlistSize;

    /**
     * Поиск ходит в сеть и занимает секунды. В потоке событий JDA это делать нельзя:
     * он один на всего бота, и пока ждёт ответа, стоят и остальные команды.
     */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "music-loader");
        thread.setDaemon(true);
        return thread;
    });

    public MusicRequests(MusicService music, MusicCatalog catalog, int playlistSize) {
        this.music = music;
        this.catalog = catalog;
        this.playlistSize = playlistSize;
    }

    public MusicService getMusic() {
        return music;
    }

    public MusicCatalog getCatalog() {
        return catalog;
    }

    /**
     * Ставит в очередь то, что попросили.
     *
     * @param reply куда слать сообщения о ходе дела
     */
    public void submit(String query, Consumer<String> reply) {
        worker.submit(() -> {
            try {
                // Когда что-то уже играет, просьба означает «поставь это следующим», а не
                // «подбери мне ещё полтора десятка треков»: иначе одна команда во время
                // прослушивания хоронит очередь под чужим плейлистом.
                var busy = music.getQueue().current() != null;

                List<Song> songs;

                try {
                    songs = catalog.playlistFor(query, busy ? 1 : playlistSize);
                } catch (CatalogUnavailableException e) {
                    log.error("{} недоступен: {}", catalog.name(), e.getMessage());
                    reply.accept(catalog.name() + " не отвечает. Попробуй ещё раз через минуту.");
                    return;
                }

                if (songs.isEmpty()) {
                    reply.accept(catalog.name() + " ничего не знает про «" + query + "».");
                    return;
                }

                enqueue(songs, busy, query, reply);
            } catch (Exception e) {
                log.error("Не удалось собрать плейлист по «{}»: {}", query, e.toString());
                reply.accept("Что-то пошло не так при сборке плейлиста.");
            }
        });
    }

    /**
     * Ставит в очередь чарт популярного.
     */
    public void submitTrending(Consumer<String> reply) {
        worker.submit(() -> {
            try {
                List<Song> songs;

                try {
                    songs = catalog.trending(playlistSize);
                } catch (CatalogUnavailableException e) {
                    log.error("{} недоступен: {}", catalog.name(), e.getMessage());
                    reply.accept(catalog.name() + " не отвечает. Попробуй ещё раз через минуту.");
                    return;
                }

                if (songs.isEmpty()) {
                    reply.accept("Чарт получить не вышло.");
                    return;
                }

                // Чарт — это плейлист целиком, а не одна просьба, поэтому он всегда
                // идёт в хвост, даже если сейчас что-то играет
                enqueue(songs, false, "трендовое", reply);
            } catch (Exception e) {
                log.error("Не удалось получить чарт: {}", e.toString());
                reply.accept("Что-то пошло не так при получении чарта.");
            }
        });
    }

    /**
     * Ищет песни на SoundCloud и наполняет очередь.
     *
     * <p>Первый найденный трек ставится отдельно и сразу: ждать, пока найдутся все
     * полтора десятка, значит слушать тишину несколько секунд.
     *
     * @param asNext поставить найденное следующим, а не в хвост
     */
    private void enqueue(List<Song> songs, boolean asNext, String what, Consumer<String> reply) {
        // Идём по списку, пока что-нибудь не найдётся: одна ненайденная песня не повод
        // отменять весь запрос, дальше лежит ещё четырнадцать
        AudioTrack first = null;
        var index = 0;

        while (index < songs.size() && first == null) {
            first = music.search(songs.get(index).query()).join();
            index++;
        }

        if (first == null) {
            reply.accept("На SoundCloud не нашлось ничего по запросу «" + what + "».");
            return;
        }

        if (asNext) {
            music.getQueue().addNext(first);
            reply.accept("Следующим будет: **" + first.getInfo().title + "**");
            return;
        }

        music.getQueue().add(first);
        reply.accept("Играет: **" + first.getInfo().title + "**\n"
                + "Догружаю ещё " + (songs.size() - index) + " треков...");

        var added = 0;
        for (var song : songs.subList(index, songs.size())) {
            var track = music.search(song.query()).join();
            if (track != null) {
                music.getQueue().add(track);
                added++;
            }
        }

        reply.accept("В очереди треков: " + added + ".");
    }

    public void shutdown() {
        worker.shutdownNow();
    }
}
