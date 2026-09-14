package ru.virus.voicebridge;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Клиент Last.fm: подсказывает, что играть дальше.
 *
 * <p>Сам звук Last.fm не отдаёт, только названия. Их бот потом ищет на SoundCloud.
 */
public final class LastFm implements MusicCatalog {

    private static final Logger log = LoggerFactory.getLogger(LastFm.class);

    private static final String API = "https://ws.audioscrobbler.com/2.0/";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String key;

    public LastFm(String key) {
        this.key = key;
    }

    @Override
    public boolean isConfigured() {
        return !key.isEmpty();
    }

    @Override
    public String name() {
        return "Last.fm";
    }

    /**
     * Собирает плейлист по тому, что ввёл пользователь.
     *
     * <p>Сначала проверяем, не исполнитель ли это: если Last.fm знает артиста с таким
     * же именем, включаем его популярные песни. Иначе считаем ввод названием песни —
     * ставим её первой, а дальше похожие.
     *
     * @param limit сколько песен нужно всего
     */
    @Override
    public List<Song> playlistFor(String input, int limit) {
        var top = topTracks(input, limit);
        if (!top.isEmpty()) {
            return top;
        }

        var found = searchTrack(input);
        if (found == null) {
            return List.of();
        }

        var playlist = new ArrayList<Song>();
        playlist.add(found);
        playlist.addAll(similar(found.artist, found.title, limit - 1));

        return playlist;
    }

    @Override
    public List<Song> byTerm(String term, int limit) {
        var json = call("track.search", "track", term, "limit", String.valueOf(limit));

        if (json == null) {
            return List.of();
        }

        try {
            var matches = json.getObject("results").getObject("trackmatches").getArray("track");
            var songs = new ArrayList<Song>();

            for (var i = 0; i < matches.length(); i++) {
                var track = matches.getObject(i);
                var name = track.getString("name", "");
                if (!name.isEmpty()) {
                    // Здесь исполнитель приходит строкой, а не объектом, как в других ответах
                    songs.add(new Song(track.getString("artist", ""), name));
                }
            }

            return songs;
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public List<Song> byArtist(String artist, int limit) {
        return topTracks(artist, limit);
    }

    @Override
    public List<Song> trending(int limit) {
        var json = call("chart.gettoptracks", "limit", String.valueOf(limit));

        if (json == null) {
            return List.of();
        }

        try {
            return songs(json.getObject("tracks").getArray("track"), null);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Популярные песни исполнителя — но только если имя совпало точно.
     *
     * <p>Last.fm охотно «исправляет» ввод и на песню вернёт её исполнителя, а на
     * опечатку — похожего артиста. Без сверки имени запрос «Bohemian Rhapsody» дал бы
     * топ песен Queen вместо самой песни.
     */
    public List<Song> topTracks(String artist, int limit) {
        var json = call("artist.gettoptracks", "artist", artist, "limit", String.valueOf(limit),
                "autocorrect", "1");
        if (json == null) {
            return List.of();
        }

        try {
            var container = json.getObject("toptracks");
            var real = container.getObject("@attr").getString("artist", "");

            if (!Song.normalize(real).equals(Song.normalize(artist))) {
                return List.of();
            }

            return songs(container.getArray("track"), real);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Ищет песню по названию и возвращает лучшее совпадение.
     */
    public Song searchTrack(String query) {
        var json = call("track.search", "track", query, "limit", "1");
        if (json == null) {
            return null;
        }

        try {
            var matches = json.getObject("results").getObject("trackmatches").getArray("track");
            if (matches.isEmpty()) {
                return null;
            }

            var track = matches.getObject(0);
            // В этом ответе исполнитель приходит строкой, а не объектом, как в остальных
            return new Song(track.getString("artist", ""), track.getString("name", ""));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Песни, похожие на указанную.
     */
    public List<Song> similar(String artist, String title, int limit) {
        var json = call("track.getsimilar", "artist", artist, "track", title,
                "limit", String.valueOf(limit), "autocorrect", "1");
        if (json == null) {
            return List.of();
        }

        try {
            return songs(json.getObject("similartracks").getArray("track"), null);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Разбирает массив треков.
     *
     * @param fallbackArtist имя исполнителя, если в ответе его нет (так в топе артиста)
     */
    private List<Song> songs(DataArray array, String fallbackArtist) {
        var result = new ArrayList<Song>();

        for (var i = 0; i < array.length(); i++) {
            try {
                var track = array.getObject(i);
                var name = track.getString("name", "");

                var artist = fallbackArtist;
                if (!track.isNull("artist")) {
                    artist = track.getObject("artist").getString("name", fallbackArtist);
                }

                if (!name.isEmpty()) {
                    result.add(new Song(artist == null ? "" : artist, name));
                }
            } catch (Exception e) {
                // Пропускаем кривую запись, остальной плейлист от этого не страдает
            }
        }

        return result;
    }

    private DataObject call(String method, String... params) {
        if (key.isEmpty()) {
            return null;
        }

        var url = new StringBuilder(API).append("?method=").append(method)
                .append("&api_key=").append(encode(key)).append("&format=json");

        for (var i = 0; i + 1 < params.length; i += 2) {
            url.append('&').append(params[i]).append('=').append(encode(params[i + 1]));
        }

        try {
            var response = http.send(
                    HttpRequest.newBuilder(URI.create(url.toString()))
                            .timeout(Duration.ofSeconds(10))
                            // Отдельные API отказывают клиентам без внятного
                            // User-Agent, поэтому представляемся по принятой форме
                            .header("User-Agent",
                                    "voice-bridge-bot/1.0 (+https://github.com/ViRuS-MomentX/user-status-discord)")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                // Last.fm объясняет отказ прямо в теле: «Invalid API key», превышение
                // лимита и прочее. Без этого текста остаётся только гадать.
                var body = response.body();
                log.error("Last.fm ответил {} на {}: {}", response.statusCode(), method,
                        body.length() > 300 ? body.substring(0, 300) : body);

                if (response.statusCode() == 403) {
                    log.error("Проверь music.lastfm.key. На странице Last.fm два поля — "
                            + "нужен API key, а не Shared secret.");
                }

                throw new CatalogUnavailableException("HTTP " + response.statusCode());
            }

            return DataObject.fromJson(response.body());
        } catch (CatalogUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.error("Запрос к Last.fm не удался ({}): {}", method, e.getMessage());
            throw new CatalogUnavailableException(e.getMessage());
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
