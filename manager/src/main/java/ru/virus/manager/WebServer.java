package ru.virus.manager;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Панель в браузере и её API.
 *
 * <p>Слушает только 127.0.0.1. Но этого мало: браузер ходит на 127.0.0.1 и по
 * просьбе любого открытого сайта. Поэтому каждый запрос к API несёт ключ,
 * который выдаётся вместе со страницей панели, — чужой сайт прочитать её не
 * может, а значит, и ключа не знает. А проверка Host закрывает подмену адреса
 * (DNS rebinding), когда чужой сайт притворяется самим 127.0.0.1.
 */
final class WebServer {

    private final HttpServer server;
    private final Supervisor supervisor;
    private final Path config;
    private final Autostart autostart;
    private final Runnable quit;
    private final String token;

    WebServer(int port, Supervisor supervisor, Path config, Autostart autostart, Runnable quit)
            throws IOException {
        this.supervisor = supervisor;
        this.config = config;
        this.autostart = autostart;
        this.quit = quit;

        var random = new byte[24];
        new SecureRandom().nextBytes(random);
        this.token = HexFormat.of().formatHex(random);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(4, runnable -> {
            var thread = new Thread(runnable, "virus-web");
            thread.setDaemon(true);
            return thread;
        }));
    }

    void start() {
        server.start();
    }

    void stop() {
        server.stop(0);
    }

    int port() {
        return server.getAddress().getPort();
    }

    String url() {
        return "http://127.0.0.1:" + port() + "/";
    }

    /** Для тестов: ключ, который получила бы страница. */
    String token() {
        return token;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!isLocalHost(exchange.getRequestHeaders().getFirst("Host"))) {
                send(exchange, 403, "text/plain", "Чужой адрес.");
                return;
            }

            var path = exchange.getRequestURI().getPath();

            if (!path.startsWith("/api/")) {
                page(exchange, path);
                return;
            }

            if (!sameToken(exchange.getRequestHeaders().getFirst("X-Virus-Token"))) {
                json(exchange, 403, Map.of("error", "нет ключа панели — обнови страницу"));
                return;
            }

            try {
                api(exchange, path, exchange.getRequestMethod().toUpperCase());
            } catch (IllegalArgumentException e) {
                json(exchange, 400, Map.of("error", e.getMessage()));
            } catch (IOException e) {
                json(exchange, 500, Map.of("error", e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }
    }

    private void api(HttpExchange exchange, String path, String method) throws IOException {
        var parts = path.substring("/api/".length()).split("/");

        switch (parts[0]) {
            case "state" -> json(exchange, 200, state());
            case "processes" -> processes(exchange, parts, method);
            case "config" -> config(exchange, parts, method);
            case "autostart" -> {
                requirePost(method);
                if (Boolean.TRUE.equals(body(exchange).get("enabled"))) {
                    autostart.enable();
                } else {
                    autostart.disable();
                }
                json(exchange, 200, state());
            }
            case "quit" -> {
                requirePost(method);
                json(exchange, 200, Map.of("ok", true));
                // Ответить успеваем, а выход — уже отдельным потоком
                Thread.ofPlatform().daemon(false).start(quit);
            }
            default -> json(exchange, 404, Map.of("error", "нет такого: " + path));
        }
    }

    private Map<String, Object> state() {
        var list = new ArrayList<Object>();

        for (var process : supervisor.all()) {
            list.add(process.toJson());
        }

        var state = new LinkedHashMap<String, Object>();
        state.put("processes", list);
        state.put("autostart", Map.of("supported", autostart.isSupported(), "enabled", autostart.isEnabled()));
        state.put("oldTask", Autostart.oldTaskExists());
        state.put("config", config.toString());
        return state;
    }

    private void processes(HttpExchange exchange, String[] parts, String method) throws IOException {
        if (parts.length == 1) {
            requirePost(method);
            var spec = ProcessSpec.fromJson(body(exchange));
            supervisor.put(spec);
            json(exchange, 200, state());
            return;
        }

        var process = supervisor.get(parts[1]);

        if (process == null) {
            json(exchange, 404, Map.of("error", "нет процесса " + parts[1]));
            return;
        }

        var action = parts.length > 2 ? parts[2] : "";

        if (action.equals("log")) {
            var after = query(exchange).getOrDefault("after", "0");
            var lines = new ArrayList<Object>();

            for (var line : process.log().since(Long.parseLong(after))) {
                lines.add(Map.of("seq", line.seq(), "time", line.time(), "text", line.text()));
            }

            json(exchange, 200, Map.of("lines", lines));
            return;
        }

        requirePost(method);

        switch (action) {
            case "start" -> process.start();
            case "stop" -> process.stop();
            case "restart" -> process.restart();
            case "clear" -> process.log().clear();
            case "delete" -> supervisor.remove(process.spec().id());
            default -> throw new IllegalArgumentException("не умею «" + action + "»");
        }

        json(exchange, 200, state());
    }

    private void config(HttpExchange exchange, String[] parts, String method) throws IOException {
        var action = parts.length > 1 ? parts[1] : "";

        switch (action) {
            case "" -> {
                if (method.equals("POST")) {
                    var values = new LinkedHashMap<String, String>();

                    if (body(exchange).get("values") instanceof Map<?, ?> map) {
                        map.forEach((key, value) -> values.put(String.valueOf(key),
                                value == null ? "" : String.valueOf(value)));
                    }

                    var file = ConfigFile.read(config);
                    var changed = file.update(values);

                    if (!changed.isEmpty()) {
                        file.write(config);
                    }

                    json(exchange, 200, Map.of("changed", changed));
                    return;
                }

                json(exchange, 200, configState());
            }
            case "raw" -> {
                if (method.equals("POST")) {
                    var text = String.valueOf(body(exchange).getOrDefault("text", ""));
                    ConfigFile.parse(text).write(config);
                    json(exchange, 200, Map.of("ok", true));
                    return;
                }

                json(exchange, 200, Map.of("text", ConfigFile.read(config).text()));
            }
            case "create" -> {
                requirePost(method);

                if (Files.exists(config)) {
                    throw new IllegalArgumentException("config.properties уже есть");
                }

                example().write(config);
                json(exchange, 200, configState());
            }
            case "add-missing" -> {
                requirePost(method);
                var file = ConfigFile.read(config);
                var added = file.addMissing(example());

                if (added > 0) {
                    file.write(config);
                }

                json(exchange, 200, Map.of("added", added));
            }
            default -> json(exchange, 404, Map.of("error", "нет такого: config/" + action));
        }
    }

    private Map<String, Object> configState() throws IOException {
        var state = new LinkedHashMap<String, Object>();
        state.put("path", config.toString());
        state.put("exists", Files.isRegularFile(config));

        var file = ConfigFile.read(config);
        var fields = new ArrayList<Object>();

        for (var field : file.fields()) {
            fields.add(field.toJson());
        }

        state.put("fields", fields);

        var example = example();
        var probe = ConfigFile.parse(file.text());
        state.put("missing", Files.isRegularFile(config) ? probe.addMissing(example) : 0);
        return state;
    }

    /** Образец настроек, приехавший внутри virus.jar. */
    private static ConfigFile example() throws IOException {
        try (InputStream in = WebServer.class.getResourceAsStream("/config.properties.example")) {
            if (in == null) {
                return ConfigFile.parse("");
            }

            return ConfigFile.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private void page(HttpExchange exchange, String path) throws IOException {
        var name = path.equals("/") ? "index.html" : path.substring(1);

        // Только плоские имена из папки web: никаких ../ наружу
        if (!name.matches("[a-z0-9-]+\\.(html|css|js|svg)")) {
            send(exchange, 404, "text/plain", "Нет такого.");
            return;
        }

        try (InputStream in = WebServer.class.getResourceAsStream("/web/" + name)) {
            if (in == null) {
                send(exchange, 404, "text/plain", "Нет такого.");
                return;
            }

            var text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            var type = switch (name.substring(name.lastIndexOf('.') + 1)) {
                case "html" -> "text/html";
                case "css" -> "text/css";
                case "js" -> "text/javascript";
                default -> "image/svg+xml";
            };

            if (name.equals("index.html")) {
                text = text.replace("__VIRUS_TOKEN__", token);
            }

            send(exchange, 200, type, text);
        }
    }

    private static void requirePost(String method) {
        if (!method.equals("POST")) {
            throw new IllegalArgumentException("нужен POST");
        }
    }

    private static Map<String, Object> body(HttpExchange exchange) throws IOException {
        var text = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return text.isBlank() ? Map.of() : Json.object(text);
    }

    private static Map<String, String> query(HttpExchange exchange) {
        var result = new LinkedHashMap<String, String>();
        var raw = exchange.getRequestURI().getRawQuery();

        if (raw == null) {
            return result;
        }

        for (var pair : raw.split("&")) {
            var eq = pair.indexOf('=');

            if (eq > 0) {
                result.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }

        return result;
    }

    private static void json(HttpExchange exchange, int status, Object body) throws IOException {
        send(exchange, status, "application/json", Json.write(body));
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", type + "; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        // Панель нельзя вставить в чужую страницу и подсунуть нажатие на кнопку
        headers.set("X-Frame-Options", "DENY");
        headers.set("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; "
                + "img-src 'self' data:; frame-ancestors 'none'");
        headers.set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private boolean sameToken(String given) {
        return given != null && MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.ISO_8859_1));
    }

    static boolean isLocalHost(String header) {
        if (header == null || header.isBlank()) {
            return false;
        }

        var host = header.trim().toLowerCase();
        var colon = host.lastIndexOf(':');

        if (colon > 0 && !host.endsWith("]")) {
            host = host.substring(0, colon);
        }

        return host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]");
    }
}
