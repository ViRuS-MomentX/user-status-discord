package ru.virus.manager;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Какая игра запущена — так же, как это видит Discord: его открытый список
 * «узнаваемых» игр (applications/detectable: название, exe и иконка) против
 * запущенных программ. Список берётся раз в сутки и лежит в кэше.
 */
final class GameNow {

    /** Игра: номер приложения Discord (для иконки), название, с какого времени запущена. */
    record Game(String id, String name, String icon, long since) {
    }

    static final String LIST = "https://discord.com/api/v10/applications/detectable";

    private final Path cache;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    /** exe (строчными, без пути) → игра. */
    private volatile Map<String, Game> known = Map.of();
    private final Map<String, BufferedImage> icons = new ConcurrentHashMap<>();
    private volatile Game current;
    private volatile long loadedAt;

    GameNow(Path home) {
        cache = home.resolve("cache").resolve("discord-games.json");
    }

    Game now() {
        return current;
    }

    /** Иконка игры с CDN Discord (подгружается один раз). */
    BufferedImage icon(Game game) {
        if (game == null || game.icon().isBlank()) {
            return null;
        }

        return icons.computeIfAbsent(game.id(), id -> {
            try {
                var response = http.send(HttpRequest.newBuilder(URI.create("https://cdn.discordapp.com/app-icons/" + id + "/"
                        + game.icon() + ".png?size=256")).timeout(Duration.ofSeconds(15)).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                return response.statusCode() == 200 ? ImageIO.read(new ByteArrayInputStream(response.body())) : null;
            } catch (Exception e) {
                return null;
            }
        });
    }

    /** Разобрать список Discord: только Windows-exe, без лаунчеров. */
    static Map<String, Game> parse(String json) {
        var result = new HashMap<String, Game>();

        if (!(Json.parse(json) instanceof List<?> apps)) {
            return result;
        }

        for (var item : apps) {
            if (!(item instanceof Map<?, ?> app) || !(app.get("executables") instanceof List<?> exes)) {
                continue;
            }

            var game = new Game(String.valueOf(app.get("id")), String.valueOf(app.get("name")),
                    app.get("icon_hash") instanceof String hash ? hash : app.get("icon") instanceof String icon ? icon : "", 0);

            for (var entry : exes) {
                if (entry instanceof Map<?, ?> exe && "win32".equals(exe.get("os")) && !Boolean.TRUE.equals(exe.get("is_launcher"))
                        && exe.get("name") instanceof String name) {
                    var file = name.replace('\\', '/');
                    file = file.substring(file.lastIndexOf('/') + 1).toLowerCase();

                    // Слишком общие имена дают ложные совпадения
                    if (file.endsWith(".exe") && !List.of("launcher.exe", "game.exe", "client.exe", "start.exe", "app.exe",
                            "main.exe", "setup.exe", "javaw.exe", "java.exe", "python.exe").contains(file)) {
                        result.putIfAbsent(file, game);
                    }
                }
            }
        }

        return result;
    }

    /** Обновить список игр (раз в сутки) и посмотреть, что запущено. */
    void poll() {
        if (!SystemInfo.isWindows()) {
            return;
        }

        var age = System.currentTimeMillis() - loadedAt;

        if (age > 24 * 3600_000L || known.isEmpty() && age > 10 * 60_000L) {
            load();
        }

        Game found = null;

        for (var name : Admin.running()) {
            var game = known.get(name.toLowerCase());

            if (game != null) {
                found = game;
                break;
            }
        }

        var before = current;

        if (found == null) {
            current = null;
        } else if (before == null || !before.id().equals(found.id())) {
            current = new Game(found.id(), found.name(), found.icon(), System.currentTimeMillis());
        }
    }

    private void load() {
        try {
            var fresh = Files.isRegularFile(cache)
                    && System.currentTimeMillis() - Files.getLastModifiedTime(cache).toMillis() < 24 * 3600_000L;

            if (!fresh) {
                var response = http.send(HttpRequest.newBuilder(URI.create(LIST)).timeout(Duration.ofSeconds(60))
                        .header("User-Agent", "VIRUS-manager").build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                if (response.statusCode() == 200 && response.body().startsWith("[")) {
                    Files.createDirectories(cache.getParent());
                    Files.writeString(cache, response.body(), StandardCharsets.UTF_8);
                }
            }

            if (Files.isRegularFile(cache)) {
                known = parse(Files.readString(cache, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // нет сети — попробуем позже, старый список остаётся
        }

        loadedAt = System.currentTimeMillis();
    }
}
