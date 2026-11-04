package ru.virus.manager;

import java.io.IOException;
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
 * «Слушает Spotify» в Discord — через Music Presence (github.com/ungive/
 * discord-music-presence): берёт трек у Spotify из Windows и показывает его в
 * профиле Discord с обложкой. V.I.R.U.S. сам скачивает его портативную
 * версию и держит запущенным — тихо, без настроек и кнопок в окне.
 * Обновляется Music Presence сам (его встроенное обновление с подписью).
 */
final class MusicPresence {

    static final String REPO = "ungive/discord-music-presence";

    private MusicPresence() {
    }

    static Path folder(Path home) {
        return home.resolve("musicpresence");
    }

    /** Zip для Windows x64 из релиза (не установщик). */
    static String zipUrl(String json) throws IOException {
        if (Json.parse(json) instanceof Map<?, ?> release && release.get("assets") instanceof List<?> assets) {
            for (var asset : assets) {
                if (asset instanceof Map<?, ?> file && String.valueOf(file.get("name")).toLowerCase().endsWith("windows-x64.zip")) {
                    return String.valueOf(file.get("browser_download_url"));
                }
            }
        }

        throw new IOException("в релизе Music Presence нет zip для Windows");
    }

    /** Сама программа: exe со словом presence, не деинсталлятор и не служебный. */
    static Path exe(Path folder) {
        if (!Files.isDirectory(folder)) {
            return null;
        }

        try (var files = Files.walk(folder, 4)) {
            return files.filter(path -> {
                var name = path.getFileName().toString().toLowerCase();
                return name.endsWith(".exe") && name.contains("presence") && !name.contains("uninst")
                        && !name.contains("update") && !name.contains("crash");
            }).findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static void download(Path folder) throws IOException, InterruptedException {
        var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();
        var release = http.send(HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + REPO + "/releases/latest"))
                .header("Accept", "application/vnd.github+json").header("User-Agent", "VIRUS-manager")
                .timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (release.statusCode() != 200) {
            throw new IOException("GitHub ответил " + release.statusCode());
        }

        Files.createDirectories(folder);
        var zip = folder.resolve("download.zip");
        var response = http.send(HttpRequest.newBuilder(URI.create(zipUrl(release.body()))).header("User-Agent", "VIRUS-manager")
                .timeout(Duration.ofMinutes(5)).build(), HttpResponse.BodyHandlers.ofFile(zip));

        if (response.statusCode() != 200) {
            throw new IOException("Music Presence не скачался: " + response.statusCode());
        }

        try (var in = Files.newInputStream(zip)) {
            Zapret.unzip(in, folder.resolve("app"));
        }

        Files.deleteIfExists(zip);
    }

    /**
     * Скачать, если нет, и запустить, если не работает. Если Music Presence
     * уже стоит у пользователя и запущен — свой не трогаем (две копии
     * спорили бы за статус).
     */
    static void keep(Path home) {
        if (!SystemInfo.isWindows()) {
            return;
        }

        try {
            var folder = folder(home);
            var exe = exe(folder);

            if (Admin.running().stream().anyMatch(name -> name.toLowerCase().contains("presence"))) {
                return;
            }

            if (exe == null) {
                download(folder);
                exe = exe(folder);
            }

            if (exe != null) {
                new ProcessBuilder(exe.toString()).directory(exe.getParent().toFile())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            }
        } catch (Exception ignored) {
            // нет сети или антивирус — попробуем в следующий раз, молча
        }
    }
}
