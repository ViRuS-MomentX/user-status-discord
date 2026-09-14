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

    private static final String API_HOST = "https://itunes.apple.com";

    private static final String API = API_HOST + "/search";

    /** Сколько раз пробовать, прежде чем признать сервис недоступным. */
    private static final int ATTEMPTS = 3;

    private static final long RETRY_PAUSE_MS = 1000;

    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // Java по умолчанию ходит по HTTP/2, а браузеры к Apple — нет. Придирчивые
            // CDN на этом иногда и отсекают клиентов, поэтому идём как браузер.
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private final String country;
    private final String userAgent;

    public ITunes(String country, String userAgent) {
        this.country = country.isEmpty() ? "US" : country;
        // Apple отвечает 403 с пустым телом клиентам, которых не узнаёт: тот же запрос
        // из браузера проходит, а с именем бота в заголовке — нет. Поэтому по умолчанию
        // представляемся как обычный браузер, а строку можно переопределить в настройках.
        this.userAgent = userAgent.isEmpty() ? DEFAULT_USER_AGENT : userAgent;
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

    @Override
    public List<Song> byTerm(String term, int limit) {
        return search(term, limit, false);
    }

    @Override
    public List<Song> byArtist(String artist, int limit) {
        var top = search(artist, limit, true);
        // Если по полю исполнителя ничего не нашлось, пробуем обычным поиском:
        // имя могли написать иначе, чем оно записано в каталоге
        return top.isEmpty() ? search(artist, limit, false) : top;
    }

    /**
     * Песни из чарта популярного.
     *
     * <p>У Apple два разных адреса чарта и два разных формата ответа: старый лежит на
     * том же хосте, что и поиск, новый — на отдельном. Пробуем сначала старый, потому
     * что про его хост уже известно, что он отвечает.
     */
    @Override
    public List<Song> trending(int limit) {
        var safeLimit = Math.max(1, Math.min(limit, 100));

        var legacy = fetchTrending(API_HOST + "/" + country.toLowerCase()
                + "/rss/topsongs/limit=" + safeLimit + "/json");

        if (!legacy.isEmpty()) {
            return legacy;
        }

        return fetchTrending("https://rss.applemarketingtools.com/api/v2/"
                + country.toLowerCase() + "/music/most-played/" + safeLimit + "/songs.json");
    }

    private List<Song> fetchTrending(String url) {
        try {
            return parseTrending(get(url));
        } catch (Exception e) {
            log.warn("Чарт по адресу {} не получен: {}", url, e.getMessage());
            return List.of();
        }
    }

    /**
     * Разбирает чарт, понимая оба формата Apple.
     *
     * <p>Старый складывает название в «im:name.label», новый — просто в «name».
     * Формат заранее неизвестен, поэтому смотрим, что из этого есть в ответе.
     */
    private List<Song> parseTrending(String body) {
        var songs = new ArrayList<Song>();
        var root = DataObject.fromJson(body);
        var feed = root.getObject("feed");

        var entries = feed.hasKey("results") ? feed.getArray("results") : feed.getArray("entry");

        for (var i = 0; i < entries.length(); i++) {
            try {
                var entry = entries.getObject(i);
                String title;
                String artist;

                if (entry.hasKey("im:name")) {
                    title = entry.getObject("im:name").getString("label", "");
                    artist = entry.hasKey("im:artist")
                            ? entry.getObject("im:artist").getString("label", "") : "";
                } else {
                    title = entry.getString("name", "");
                    artist = entry.getString("artistName", "");
                }

                if (!title.isEmpty()) {
                    songs.add(new Song(artist, title));
                }
            } catch (Exception e) {
                // Кривая запись не должна утащить за собой весь чарт
            }
        }

        return songs;
    }

    /**
     * Ищет песни.
     *
     * <p>При неудаче повторяет запрос: у iTunes бывают разовые отказы и обрывы, и
     * сдаваться с первой попытки значит показывать «ничего не найдено» на песню,
     * которая минуту назад прекрасно игралась.
     *
     * @param byArtist искать только по полю исполнителя, а не по названию тоже
     * @throws CatalogUnavailableException если сервис так и не ответил
     */
    private List<Song> search(String term, int limit, boolean byArtist) {
        var url = API + "?term=" + encode(term)
                + "&entity=song&limit=" + Math.min(limit, 200)
                + "&country=" + encode(country)
                + (byArtist ? "&attribute=artistTerm" : "");

        String reason = null;

        for (var attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                return parse(get(url));
            } catch (CatalogUnavailableException e) {
                reason = e.getMessage();
            } catch (Exception e) {
                reason = e.getMessage() == null ? e.toString() : e.getMessage();
            }

            // Ссылку пишем целиком: её можно открыть в браузере и сравнить,
            // отвечает ли сервис на тот же самый запрос
            log.warn("iTunes не ответил (попытка {} из {}): {}\n  запрос: {}",
                    attempt, ATTEMPTS, reason, url);

            if (attempt < ATTEMPTS) {
                try {
                    Thread.sleep(RETRY_PAUSE_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        throw new CatalogUnavailableException(reason);
    }

    /**
     * Один запрос к Apple с браузерным набором заголовков.
     *
     * @throws CatalogUnavailableException если сервис ответил не двухсотым
     */
    private String get(String url) throws Exception {
        var response = http.send(
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .header("User-Agent", userAgent)
                        .header("Accept", "application/json")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            var body = response.body();
            throw new CatalogUnavailableException("HTTP " + response.statusCode() + ": "
                    + (body.length() > 200 ? body.substring(0, 200) : body));
        }

        return response.body();
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
