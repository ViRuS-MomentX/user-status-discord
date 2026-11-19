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

    static final String REPO = "ViRuS-MomentX/virus-app";
    /** Файл рядом с virus.jar с токеном только для чтения: репозиторий приватный, без него GitHub не отдаёт релизы. */
    static final String TOKEN_FILE = "update-token.txt";
    static final String LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";
    static final String ASSET = "V.I.R.U.S.zip";

    /** Свежий релиз: номер сборки, откуда качать, что в нём нового. */
    record Release(int build, String url, String notes) {
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    /** Скачивание: переходы по ссылкам вручную — токен нельзя отдавать хранилищу файлов, куда GitHub перенаправляет. */
    private final HttpClient plain = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final String api;
    private final java.util.function.Supplier<String> token;

    AutoUpdate() {
        this(LATEST);
    }

    AutoUpdate(String api) {
        this(api, () -> "");
    }

    AutoUpdate(String api, java.util.function.Supplier<String> token) {
        this.api = api;
        this.token = token;
    }

    /** Токен из файла update-token.txt в папке V.I.R.U.S. (или из переменной VIRUS_UPDATE_TOKEN); пусто — нет. */
    static String readToken(Path home) {
        try {
            var file = home.resolve(TOKEN_FILE);

            if (Files.isRegularFile(file)) {
                return Files.readString(file, StandardCharsets.UTF_8).strip();
            }
        } catch (IOException ignored) {
            // нет файла — смотрим переменную
        }

        var env = System.getenv("VIRUS_UPDATE_TOKEN");
        return env == null ? "" : env.strip();
    }

    /** Нужен токен, а его нет или он не подошёл: человеку надо что-то сделать. */
    static final class TokenException extends IOException {
        TokenException(String message) {
            super(message);
        }
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
        var key = token.get();
        var builder = HttpRequest.newBuilder(URI.create(api))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", Virus.NAME);

        if (!key.isBlank()) {
            builder.header("Authorization", "Bearer " + key);
        }

        var request = builder.GET().build();

        HttpResponse<String> response;

        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("прервано");
        }

        // Приватный репозиторий без токена отвечает «не найдено», с неверным — «не авторизован»
        if (response.statusCode() == 401 || response.statusCode() == 403 || response.statusCode() == 404 && key.isBlank()
                && REPO.equals(repoOf(api))) {
            throw new TokenException(key.isBlank()
                    ? "репозиторий обновлений приватный — нужен токен: положи его в файл " + TOKEN_FILE + " рядом с virus.jar"
                    : "GitHub не принял токен из " + TOKEN_FILE + " — он просрочен или без доступа «Contents: Read-only» к " + REPO);
        }

        if (response.statusCode() == 404) {
            return null;
        }

        if (response.statusCode() != 200) {
            throw new IOException("GitHub ответил " + response.statusCode());
        }

        return parse(Json.object(response.body()));
    }

    private static String repoOf(String url) {
        var marker = "/repos/";
        var from = url.indexOf(marker);
        var to = url.indexOf("/releases");
        return from < 0 || to < from ? "" : url.substring(from + marker.length(), to);
    }

    static Release parse(Map<String, Object> json) {
        var tag = String.valueOf(json.getOrDefault("tag_name", ""));

        if (!tag.matches("build-\\d+") || Boolean.TRUE.equals(json.get("draft"))) {
            return null;
        }

        if (json.get("assets") instanceof List<?> assets) {
            for (var item : assets) {
                // Ссылка API (а не browser_download_url): только она отдаёт файл из приватного репозитория по токену
                if (item instanceof Map<?, ?> asset && ASSET.equals(asset.get("name"))
                        && (asset.get("url") != null || asset.get("browser_download_url") != null)) {
                    return new Release(Integer.parseInt(tag.substring("build-".length())),
                            String.valueOf(asset.get("url") != null ? asset.get("url") : asset.get("browser_download_url")),
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
        var url = release.url();
        var key = token.get();
        var first = true;

        try {
            for (var hop = 0; hop < 5; hop++) {
                var request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMinutes(5))
                        .header("User-Agent", Virus.NAME)
                        .header("Accept", "application/octet-stream");

                // Токен — только GitHub, не хранилищу, куда он перенаправит
                if (first && !key.isBlank()) {
                    request.header("Authorization", "Bearer " + key);
                }

                var response = plain.send(request.GET().build(), HttpResponse.BodyHandlers.ofFile(target));
                var code = response.statusCode();

                if (code == 200) {
                    return target;
                }

                if (code >= 300 && code < 400 && response.headers().firstValue("Location").isPresent()) {
                    url = URI.create(url).resolve(response.headers().firstValue("Location").get()).toString();
                    first = false;
                    continue;
                }

                Files.deleteIfExists(target);
                throw new IOException("архив не скачался: GitHub ответил " + code);
            }

            Files.deleteIfExists(target);
            throw new IOException("архив не скачался: слишком много перенаправлений");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("прервано");
        }
    }
}
