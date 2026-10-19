package ru.virus.manager;

import com.sun.net.httpserver.HttpServer;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SignalRGB без его окна.
 *
 * <p>Два пути. Первый — его локальный API (порт 16038): список эффектов,
 * включить эффект, яркость, вкл/выкл. Второй — свой эффект «VIRUS» в папке
 * эффектов SignalRGB: он раз в четверть секунды спрашивает у V.I.R.U.S.
 * (порт 16039), какой режим, цвета и скорость, — так режимы меняются мгновенно,
 * без перезапуска эффекта.
 */
final class SignalRgb {

    static final int PORT = 16038;
    static final int OUR_PORT = 16039;
    static final String EFFECT = "VIRUS";

    record Effect(String id, String name, String apply) {
    }

    record State(boolean enabled, int brightness, String effectId, String effectName) {
    }

    /** Режимы эффекта «VIRUS»: номер — как в signalrgb-virus.html. */
    record Mode(int id, String name, boolean usesColor, boolean usesSecond) {
    }

    static final List<Mode> MODES = List.of(
            new Mode(0, "Статичный", true, false),
            new Mode(1, "Дыхание", true, false),
            new Mode(2, "Радуга", false, false),
            new Mode(3, "Радужная волна", false, false),
            new Mode(4, "Переливы", true, true),
            new Mode(5, "Матрица", true, false),
            new Mode(6, "Огонь", false, false),
            new Mode(7, "Полиция", false, false),
            new Mode(8, "Северное сияние", false, false),
            new Mode(9, "Пульс", true, false),
            new Mode(10, "Нагрузка ЦП", false, false),
            new Mode(11, "Звёзды", true, true));

    private final HttpClient http = HttpClient.newBuilder()
            .proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final String base;

    SignalRgb() {
        this("http://127.0.0.1:" + PORT);
    }

    SignalRgb(String base) {
        this.base = base;
    }

    List<Effect> effects() throws IOException {
        var data = data(call("GET", "/api/v1/lighting/effects", null));
        var result = new ArrayList<Effect>();

        if (data instanceof Map<?, ?> map && map.get("items") instanceof List<?> items) {
            for (var item : items) {
                if (item instanceof Map<?, ?> effect) {
                    var id = String.valueOf(effect.get("id"));
                    var name = effect.get("attributes") instanceof Map<?, ?> attributes && attributes.get("name") != null
                            ? String.valueOf(attributes.get("name")) : id;
                    var apply = effect.get("links") instanceof Map<?, ?> links && links.get("apply") != null
                            ? String.valueOf(links.get("apply")) : "/api/v1/lighting/effects/" + id + "/apply";
                    result.add(new Effect(id, name, apply));
                }
            }
        }

        result.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return result;
    }

    State state() throws IOException {
        var data = data(call("GET", "/api/v1/lighting", null));

        if (!(data instanceof Map<?, ?> map)) {
            throw new IOException("SignalRGB ответил непонятно");
        }

        var attributes = map.get("attributes") instanceof Map<?, ?> a ? a : Map.of();
        return new State(!Boolean.FALSE.equals(attributes.get("enabled")),
                attributes.get("global_brightness") instanceof Number n ? n.intValue() : 100,
                map.get("id") == null ? "" : String.valueOf(map.get("id")),
                attributes.get("name") == null ? "" : String.valueOf(attributes.get("name")));
    }

    void apply(Effect effect) throws IOException {
        call("POST", effect.apply(), null);
    }

    void brightness(int value) throws IOException {
        call("PATCH", "/api/v1/lighting/global_brightness", "{\"global_brightness\":" + Math.max(0, Math.min(100, value)) + "}");
    }

    void enabled(boolean on) throws IOException {
        call("PATCH", "/api/v1/lighting/enabled", "{\"enabled\":" + on + "}");
    }

    void next() throws IOException {
        call("POST", "/api/v1/lighting/next", null);
    }

    void previous() throws IOException {
        call("POST", "/api/v1/lighting/previous", null);
    }

    private String call(String method, String path, String json) throws IOException {
        var request = HttpRequest.newBuilder(URI.create(path.startsWith("http") ? path : base + path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json))
                .build();

        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new Locked();
            }

            if (response.statusCode() >= 300) {
                throw new IOException("SignalRGB ответил " + response.statusCode());
            }

            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("прервано", e);
        } catch (java.net.ConnectException e) {
            throw new IOException("SignalRGB не запущен", e);
        }
    }

    /** Ответы SignalRGB: { status: "ok", data: …, errors: [...] }. */
    private static Object data(String body) throws IOException {
        var json = Json.object(body);

        if (json.get("errors") instanceof List<?> errors && !errors.isEmpty()
                && errors.getFirst() instanceof Map<?, ?> error) {
            throw new IOException(String.valueOf(error.get("detail") != null ? error.get("detail") : error.get("title")));
        }

        return json.get("data");
    }

    /**
     * SignalRGB запущен, но управлять собой через API не даёт (403) — в
     * бесплатной версии API закрыт. Тогда эффекты включаем его же ссылками.
     */
    static final class Locked extends IOException {
        Locked() {
            super("SignalRGB закрыл API (403) — так бывает без Pro");
        }
    }

    /** Ссылка SignalRGB «включи эффект», в фоне — без всплывающего окна. */
    static String applyUrl(String effect) {
        return "signalrgb://effect/apply/" + URLEncoder.encode(effect, StandardCharsets.UTF_8).replace("+", "%20")
                + "?-silentlaunch-";
    }

    /**
     * Отдать ссылку SignalRGB. Через его лаунчер с --url — так советует сама
     * SignalRGB, и так не нужен разбор ссылки Проводником (explorer.exe с
     * «?» и «&» в ссылке открывал вместо этого папку «Документы»).
     */
    static void open(String url) throws IOException {
        var launcher = launcher();

        if (launcher != null) {
            new ProcessBuilder(launcher.toString(), "--url=" + url.substring("signalrgb://".length())).start();
            return;
        }

        // Нет лаунчера — пусть Windows откроет ссылку, как по клику в браузере
        try {
            java.awt.Desktop.getDesktop().browse(URI.create(url));
        } catch (RuntimeException e) {
            new ProcessBuilder("rundll32.exe", "url.dll,FileProtocolHandler", url).start();
        }
    }

    /** SignalRgbLauncher.exe: обычно %LOCALAPPDATA%\VortxEngine. */
    static Path launcher() {
        var local = System.getenv("LOCALAPPDATA");

        if (local == null) {
            return null;
        }

        var direct = Path.of(local, "VortxEngine", "SignalRgbLauncher.exe");

        if (Files.isRegularFile(direct)) {
            return direct;
        }

        try (var files = Files.find(Path.of(local, "VortxEngine"), 3,
                (path, attributes) -> path.getFileName().toString().equalsIgnoreCase("SignalRgbLauncher.exe"))) {
            return files.findFirst().orElse(null);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Эффекты SignalRGB, которые лежат на диске: без API их список больше
     * взять неоткуда. Имя эффекта — его &lt;title&gt;, по нему и включаем.
     */
    static List<String> localEffects(List<Path> roots) {
        var names = new java.util.TreeSet<String>(String.CASE_INSENSITIVE_ORDER);

        for (var root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }

            try (var files = Files.find(root, 7, (path, attributes) -> attributes.isRegularFile()
                    && path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".html")
                    && attributes.size() < 2_000_000)) {
                files.limit(2000).forEach(path -> {
                    var title = title(path);

                    if (title != null && !title.equals(EFFECT)) {
                        names.add(title);
                    }
                });
            } catch (IOException | RuntimeException ignored) {
                // нет доступа к папке — берём что нашли
            }
        }

        return List.copyOf(names);
    }

    /** &lt;title&gt; эффекта; null — не эффект (нет холста или заголовка). */
    static String title(Path file) {
        try (var in = Files.newInputStream(file)) {
            var head = new String(in.readNBytes(16_384), StandardCharsets.UTF_8);
            var start = head.indexOf("<title>");
            var end = head.indexOf("</title>");

            if (start < 0 || end <= start || !head.contains("<canvas")) {
                return null;
            }

            var title = head.substring(start + 7, end).strip();
            return title.isEmpty() || title.length() > 60 ? null : title;
        } catch (IOException e) {
            return null;
        }
    }

    /** Где SignalRGB держит эффекты: свои, скачанные и встроенные. */
    static List<Path> effectRoots() {
        var local = System.getenv("LOCALAPPDATA");
        var roots = new ArrayList<Path>();
        roots.add(effectsFolder());

        if (local != null) {
            roots.add(Path.of(local, "VortxEngine"));
            roots.add(Path.of(local, "WhirlwindFX"));
        }

        roots.add(effectsFolder().getParent());
        return roots;
    }

    // ── эффект «VIRUS» ────────────────────────────────────────────────

    static String effectHtml() throws IOException {
        try (InputStream in = SignalRgb.class.getResourceAsStream("signalrgb-virus.html")) {
            if (in == null) {
                throw new IOException("нет signalrgb-virus.html в сборке");
            }

            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Папка эффектов SignalRGB: Документы\WhirlwindFX\Effects (Документы бывают в OneDrive). */
    static Path effectsFolder() {
        var home = Path.of(System.getProperty("user.home"));

        for (var documents : List.of(home.resolve("Documents"), home.resolve("OneDrive").resolve("Documents"),
                home.resolve("OneDrive").resolve("Документы"))) {
            if (Files.isDirectory(documents.resolve("WhirlwindFX"))) {
                return documents.resolve("WhirlwindFX").resolve("Effects");
            }
        }

        return home.resolve("Documents").resolve("WhirlwindFX").resolve("Effects");
    }

    /**
     * Положить эффект в папку SignalRGB, если его там нет или он старый.
     *
     * @return true — файл записан заново (SignalRGB увидит его после перезапуска)
     */
    static boolean install(Path folder) throws IOException {
        var file = folder.resolve(EFFECT + ".html");
        var html = effectHtml();

        if (Files.isRegularFile(file) && Files.readString(file, StandardCharsets.UTF_8).equals(html)) {
            return false;
        }

        Files.createDirectories(folder);
        Files.writeString(file, html, StandardCharsets.UTF_8);
        return true;
    }

    /**
     * Запасной путь, если эффект не может спросить V.I.R.U.S. (SignalRGB
     * закрыл ему сеть): включить эффект ссылкой SignalRGB сразу с настройками.
     */
    static String applyUrl(Live live) {
        return "signalrgb://effect/apply/" + EFFECT + "?mode=" + live.mode + "&speed=" + live.speed
                + "&color=" + URLEncoder.encode(hex(live.color), StandardCharsets.UTF_8)
                + "&color2=" + URLEncoder.encode(hex(live.color2), StandardCharsets.UTF_8)
                + "&-silentlaunch-";
    }

    static String hex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    /**
     * Что сейчас показывать эффекту «VIRUS». Эффект спрашивает раз в
     * четверть секунды — по времени последнего вопроса видно, жив ли он.
     */
    static final class Live implements AutoCloseable {
        volatile int mode = 3;
        volatile int speed = 50;
        volatile Color color = Theme.ACCENT;
        volatile Color color2 = new Color(0x8A2BE2);
        /** Яркость и вкл/выкл самого эффекта — работают и без API SignalRGB. */
        volatile int brightness = 100;
        volatile boolean on = true;
        volatile long lastAsked;
        private final java.util.function.DoubleSupplier cpu;
        private HttpServer server;

        Live(java.util.function.DoubleSupplier cpu) {
            this.cpu = cpu;
        }

        void start(int port) throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            server.createContext("/state", exchange -> {
                lastAsked = System.currentTimeMillis();
                var body = json().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                // Эффект — это страница, открытая из файла: без этого заголовка браузер ответ не отдаст
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                exchange.sendResponseHeaders(200, body.length);

                try (var out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
        }

        double cpu() {
            return Math.max(0, cpu.getAsDouble());
        }

        String json() {
            var load = cpu.getAsDouble();
            return "{\"mode\":" + mode + ",\"speed\":" + speed + ",\"color\":\"" + hex(color) + "\",\"color2\":\""
                    + hex(color2) + "\",\"brightness\":" + brightness + ",\"on\":" + on + ",\"cpu\":" + String.format(java.util.Locale.ROOT, "%.3f", Math.max(0, load)) + "}";
        }

        boolean heard() {
            return System.currentTimeMillis() - lastAsked < 3000;
        }

        @Override
        public void close() {
            if (server != null) {
                server.stop(0);
            }
        }
    }

    static Mode mode(int id) {
        return MODES.stream().filter(mode -> mode.id() == id).findFirst().orElse(MODES.getFirst());
    }
}
