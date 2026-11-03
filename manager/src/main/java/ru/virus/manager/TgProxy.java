package ru.virus.manager;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Telegram при zapret: zapret обходит DPI для сайтов, а Telegram Desktop
 * ходит своим протоколом прямо на IP — их режут целиком. Flowseal (автор
 * zapret-discord-youtube) сделал для этого TG WS Proxy: локальный
 * MTProto-прокси, который несёт трафик Telegram через WebSocket на его же
 * домены. V.I.R.U.S. скачивает его с GitHub, держит фоновым процессом и
 * один раз подключает к нему Telegram ссылкой tg://proxy.
 */
final class TgProxy {

    static final String ID = "tg-proxy";
    static final String REPO = "Flowseal/tg-ws-proxy";
    static final String EXE = "TgWsProxy.exe";
    static final int PORT = 1443;

    private final Path home;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15)).build();

    TgProxy(Path home) {
        this.home = home;
    }

    Path folder() {
        return home.resolve("tgproxy");
    }

    Path exe() {
        return folder().resolve(EXE);
    }

    /** Настройки прокси — рядом с ним («портативный» режим TG WS Proxy). */
    Path data() {
        return folder().resolve("TgWsProxy_data");
    }

    String version() {
        try {
            return Files.readString(folder().resolve("version.txt"), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            return "";
        }
    }

    static ProcessSpec defaultSpec() {
        return new ProcessSpec(ID, "Telegram-прокси", List.of("{home}\\tgproxy\\" + EXE, "--portable"), "tgproxy",
                false, true, true);
    }

    record Release(String tag, String url) {
    }

    /** Нужный exe из релиза: x64 или ARM64 для Windows 10+. */
    static Release parseRelease(String json, boolean arm) throws IOException {
        if (!(Json.parse(json) instanceof Map<?, ?> release)) {
            throw new IOException("непонятный ответ GitHub");
        }

        var tag = String.valueOf(release.get("tag_name"));
        var wanted = arm ? "tgwsproxy_windows_arm64.exe" : "tgwsproxy_windows.exe";

        if (release.get("assets") instanceof List<?> assets) {
            for (var asset : assets) {
                if (asset instanceof Map<?, ?> file && String.valueOf(file.get("name")).toLowerCase().equals(wanted)) {
                    return new Release(tag, String.valueOf(file.get("browser_download_url")));
                }
            }
        }

        throw new IOException("в релизе " + tag + " нет " + wanted);
    }

    Release latest() throws IOException, InterruptedException {
        var response = http.send(HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + REPO + "/releases/latest"))
                .timeout(Duration.ofSeconds(20)).header("Accept", "application/vnd.github+json")
                .header("User-Agent", "VIRUS-manager").build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != 200) {
            throw new IOException("GitHub ответил " + response.statusCode());
        }

        return parseRelease(response.body(), System.getProperty("os.arch", "").toLowerCase().contains("aarch64"));
    }

    /** Скачать новую версию (если она новее) и подготовить настройки. @return true — exe поменялся */
    boolean ensure(Runnable stopFirst) throws IOException, InterruptedException {
        Files.createDirectories(folder());
        configure();
        var release = latest();

        if (Files.isRegularFile(exe()) && !Zapret.newer(release.tag(), version())) {
            return false;
        }

        var part = folder().resolve(EXE + ".part");
        var response = http.send(HttpRequest.newBuilder(URI.create(release.url())).timeout(Duration.ofMinutes(5))
                .header("User-Agent", "VIRUS-manager").build(), HttpResponse.BodyHandlers.ofFile(part));

        if (response.statusCode() != 200 || Files.size(part) < 1_000_000) {
            Files.deleteIfExists(part);
            throw new IOException("TG WS Proxy не скачался (" + response.statusCode() + ")");
        }

        // Работающий exe Windows заменить не даст — сначала остановить
        if (Files.isRegularFile(exe())) {
            stopFirst.run();
            Thread.sleep(1500);
        }

        Files.move(part, exe(), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(folder().resolve("version.txt"), release.tag(), StandardCharsets.UTF_8);
        return true;
    }

    /**
     * Настройки прокси: свой секрет, порт 1443, без окна первого запуска и без
     * его собственных проверок обновлений (обновляет V.I.R.U.S.).
     */
    void configure() throws IOException {
        Files.createDirectories(data());
        var file = data().resolve("config.json");
        var config = new LinkedHashMap<String, Object>();

        if (Files.isRegularFile(file) && Json.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof Map<?, ?> old) {
            old.forEach((key, value) -> config.put(String.valueOf(key), value));
        }

        if (!(config.get("secret") instanceof String secret) || !secret.matches("[0-9a-f]{32}")) {
            var bytes = new byte[16];
            new SecureRandom().nextBytes(bytes);
            config.put("secret", HexFormat.of().formatHex(bytes));
        }

        config.putIfAbsent("host", "127.0.0.1");
        config.putIfAbsent("port", PORT);
        config.put("check_updates", false);
        Files.writeString(file, Json.write(config), StandardCharsets.UTF_8);
        var marker = data().resolve(".first_run_done_mtproto");

        if (!Files.exists(marker)) {
            Files.writeString(marker, "");
        }
    }

    /** Ссылка, которой Telegram Desktop сам добавляет прокси (как «Открыть в Telegram» у Flowseal). */
    String link() throws IOException {
        configure();
        var config = (Map<?, ?>) Json.parse(Files.readString(data().resolve("config.json"), StandardCharsets.UTF_8));
        return link(String.valueOf(config.get("host")), config.get("port") instanceof Number port ? port.intValue() : PORT,
                String.valueOf(config.get("secret")));
    }

    static String link(String host, int port, String secret) {
        var server = host.equals("0.0.0.0") || host.isBlank() ? "127.0.0.1" : host;
        return "tg://proxy?server=" + server + "&port=" + port + "&secret=dd" + secret;
    }
}
