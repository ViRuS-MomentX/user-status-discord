package ru.virus.voicebridge;

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
 * Каталог iTunes: поиск песен и популярное у исполнителя.
 *
 * <p>Ключей не требует и доступен там, где Last.fm отказывает. Расплата за это —
 * похожих песен у iTunes нет как понятия, поэтому продолжением введённой песни
 * служат другие популярные вещи того же исполнителя.
 */
public final class ITunes implements MusicCatalog {

    private static final Logger log = LoggerFactory.getLogger(ITunes.class);

    private static final String API = "https://itunes.apple.com/search";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String country;

    public ITunes(String country) {
        this.country = country.isEmpty() ? "US" : country;
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public String name() {
        return "iTunes";
    }

    @Override
    public List<Song> playlistFor(String input, int limit) {
        var found = search(input, limit, false);

        if (found.isEmpty()) {
            return List.of();
        }

        var first = found.get(0);

        // Если введённое совпало с именем исполнителя, это запрос на его творчество
        // целиком. Выдача iTunes отсортирована по популярности, так что первые
        // результаты и есть самые известные вещи.
        if (Song.normalize(first.artist).equals(Song.normalize(input))) {
            var top = search(input, limit, true);
            return top.isEmpty() ? found : top;
        }

        // Иначе это песня: ставим её первой, а дальше — остальное у того же исполнителя.
        // Настоящих похожих iTunes не отдаёт, ближе этого подобраться нечем.
        var playlist = new ArrayList<Song>();
        playlist.add(first);

        for (var song : search(first.artist, limit + 1, true)) {
            if (playlist.size() >= limit) {
                break;
            }
            if (!song.sameAs(first)) {
                playlist.add(song);
            }
        }

        return playlist;
    }

    /**
     * Ищет песни.
     *
     * @param byArtist искать только по полю исполнителя, а не по названию тоже
     */
    private List<Song> search(String term, int limit, boolean byArtist) {
        var url = API + "?term=" + encode(term)
                + "&entity=song&limit=" + Math.min(limit, 200)
                + "&country=" + encode(country)
                + (byArtist ? "&attribute=artistTerm" : "");

        try {
            var response = http.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(10))
                            .header("User-Agent",
                                    "voice-bridge-bot/1.0 (+https://github.com/ViRuS-MomentX/user-status-discord)")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                var body = response.body();
                log.error("iTunes ответил {}: {}", response.statusCode(),
                        body.length() > 300 ? body.substring(0, 300) : body);
                return List.of();
            }

            return parse(response.body());
        } catch (Exception e) {
            log.error("Запрос к iTunes не удался: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Song> parse(String body) {
        var songs = new ArrayList<Song>();

        try {
            var results = DataObject.fromJson(body).getArray("results");

            for (var i = 0; i < results.length(); i++) {
                try {
                    var track = results.getObject(i);
                    var title = track.getString("trackName", "");

                    if (!title.isEmpty()) {
                        songs.add(new Song(track.getString("artistName", ""), title));
                    }
                } catch (Exception e) {
                    // Кривая запись не должна утащить за собой весь плейлист
                }
            }
        } catch (Exception e) {
            log.error("Не удалось разобрать ответ iTunes: {}", e.getMessage());
        }

        return songs;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
