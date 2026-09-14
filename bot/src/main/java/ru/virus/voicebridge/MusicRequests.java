package ru.virus.voicebridge;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

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

    /** Ставит в очередь чарт популярного. */
    public void submitTrending(Consumer<String> reply) {
        submitPlaylist("чарт", reply, () -> catalog.trending(playlistSize));
    }

    /** Ставит в очередь подборку по слову — сезонную и любую другую. */
    public void submitTerm(String term, String label, Consumer<String> reply) {
        submitPlaylist(label, reply, () -> catalog.byTerm(term, playlistSize));
    }

    /** Ставит в очередь популярные песни исполнителя. */
    public void submitArtist(String artist, Consumer<String> reply) {
        submitPlaylist(artist, reply, () -> catalog.byArtist(artist, playlistSize));
    }

    /**
     * Общая часть для всех подборок: получить список, проверить, поставить в очередь.
     *
     * <p>Подборка — это плейлист целиком, а не одна просьба, поэтому она всегда идёт
     * в хвост, даже если сейчас что-то играет.
     */
    private void submitPlaylist(String label, Consumer<String> reply, Supplier<List<Song>> source) {
        worker.submit(() -> {
            try {
                List<Song> songs;

                try {
                    songs = source.get();
                } catch (CatalogUnavailableException e) {
                    log.error("{} недоступен: {}", catalog.name(), e.getMessage());
                    reply.accept(catalog.name() + " не отвечает. Попробуй ещё раз через минуту.");
                    return;
                }

                if (songs.isEmpty()) {
                    reply.accept("Ничего не нашлось: " + label + ".");
                    return;
                }

                enqueue(songs, false, label, reply);
            } catch (Exception e) {
                log.error("Не удалось собрать подборку «{}»: {}", label, e.toString());
                reply.accept("Что-то пошло не так при сборке подборки.");
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
            music.getQueue().addRequest(first);
            reply.accept("Следующим будет: **" + first.getInfo().title + "**");
            return;
        }

        music.getQueue().add(first);

        var rest = songs.subList(index, songs.size());
        reply.accept("Играет: **" + first.getInfo().title + "**\n"
                + "Догружаю ещё " + rest.size() + " треков...");

        // Поиски запускаем все разом: каждый — поход в сеть на секунду-другую, и по
        // одному полтора десятка набирались бы полминуты. lavaplayer держит для них
        // десяток потоков и очередь на тысячи заданий, так что пачка ему по силам.
        var pending = new ArrayList<CompletableFuture<AudioTrack>>(rest.size());

        for (var song : rest) {
            pending.add(music.search(song.query()));
        }

        var added = 0;

        // А вот в очередь кладём строго по порядку списка: плейлист должен звучать
        // так, как его подобрали, а не так, как повезло с ответами
        for (var search : pending) {
            var track = search.join();

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
