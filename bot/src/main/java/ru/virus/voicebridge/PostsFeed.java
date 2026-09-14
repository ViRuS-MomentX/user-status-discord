package ru.virus.voicebridge;

import net.dv8tion.jda.api.utils.data.DataArray;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Лента постов сайта.
 *
 * <p>Сайт выкладывает записи файлом posts.json — его собирает та же сборка, что и
 * страницы, поэтому лента не может разойтись с тем, что видят люди.
 */
public final class PostsFeed {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final String url;

    public PostsFeed(String url) {
        this.url = url;
    }

    /** Адрес сайта без пути: по нему достраиваются ссылки на картинки. */
    public String site() {
        var at = url.indexOf('/', url.indexOf("//") + 2);
        return at < 0 ? url : url.substring(0, at);
    }

    /**
     * Читает ленту.
     *
     * @throws IOException если сайт не ответил или ответил не тем
     */
    public List<Post> load() throws IOException {
        var request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                // Прокси и CDN охотно отдают старую копию; нам нужна свежая
                .header("Cache-Control", "no-cache")
                .GET()
                .build();

        HttpResponse<String> response;

        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("чтение прервано");
        }

        if (response.statusCode() != 200) {
            throw new IOException("сайт ответил " + response.statusCode());
        }

        DataArray array;

        try {
            array = DataArray.fromJson(response.body());
        } catch (Exception e) {
            throw new IOException("это не лента постов: " + e.getMessage());
        }

        var posts = new ArrayList<Post>(array.length());

        for (var i = 0; i < array.length(); i++) {
            posts.add(Post.from(array.getObject(i)));
        }

        return posts;
    }
}
