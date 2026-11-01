package ru.virus.manager;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Обновление с GitHub: как только на GitHub собралась новая версия, V.I.R.U.S.
 * скачивает её и ставит сам.
 *
 * <p>Каждый пуш в репозиторий собирает GitHub Actions (release.yml) и
 * выкладывает релиз «build-N» с архивом V.I.R.U.S.zip. Номер сборки зашит в
 * virus.jar (build.txt) — по нему видно, новее ли релиз того, что стоит.
 */
final class AutoUpdate {

    static final String REPO = "ViRuS-MomentX/user-status-discord";
    static final String LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";
    static final String ASSET = "V.I.R.U.S.zip";

    /** Свежий релиз: номер сборки, откуда качать, что в нём нового. */
    record Release(int build, String url, String notes) {
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final String api;

    AutoUpdate() {
        this(LATEST);
    }

    AutoUpdate(String api) {
        this.api = api;
    }

    /** Какая сборка стоит; −1 — собрана не на GitHub (присланный архив) — её обновим до релиза. */
    static int current() {
        try (InputStream in = AutoUpdate.class.getResourceAsStream("build.txt")) {
            return in == null ? -1 : Integer.parseInt(new String(in.readAllBytes(), StandardCharsets.UTF_8).strip());
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }

    /** Последний релиз на GitHub; null — релизов нет или архива в нём нет. */
    Release latest() throws IOException {
        var request = HttpRequest.newBuilder(URI.create(api))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", Virus.NAME)
                .GET()
                .build();

        HttpResponse<String> response;

        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("прервано");
        }

        if (response.statusCode() == 404) {
            return null;
        }

        if (response.statusCode() != 200) {
            throw new IOException("GitHub ответил " + response.statusCode());
        }

        return parse(Json.object(response.body()));
    }

    static Release parse(Map<String, Object> json) {
        var tag = String.valueOf(json.getOrDefault("tag_name", ""));

        if (!tag.matches("build-\\d+") || Boolean.TRUE.equals(json.get("draft"))) {
            return null;
        }

        if (json.get("assets") instanceof List<?> assets) {
            for (var item : assets) {
                if (item instanceof Map<?, ?> asset && ASSET.equals(asset.get("name"))
                        && asset.get("browser_download_url") != null) {
                    return new Release(Integer.parseInt(tag.substring("build-".length())),
                            String.valueOf(asset.get("browser_download_url")),
                            String.valueOf(json.getOrDefault("body", "")).strip());
                }
            }
        }

        return null;
    }

    /** Новее ли релиз того, что стоит. */
    static boolean newer(Release release, int current) {
        return release != null && release.build() > current;
    }

    /** Скачать архив релиза рядом с V.I.R.U.S. */
    Path download(Release release, Path home) throws IOException {
        var target = home.resolve("update-download.zip");
        var request = HttpRequest.newBuilder(URI.create(release.url()))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", Virus.NAME)
                .GET()
                .build();

        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofFile(target));

            if (response.statusCode() != 200) {
                Files.deleteIfExists(target);
                throw new IOException("архив не скачался: GitHub ответил " + response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("прервано");
        }

        return target;
    }
}
