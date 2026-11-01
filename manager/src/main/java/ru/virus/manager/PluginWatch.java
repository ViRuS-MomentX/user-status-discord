package ru.virus.manager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * Автообновление плагина на тестовом сервере: собрал плагин — через пару
 * секунд он уже на сервере, а сервер перезапущен.
 *
 * <p>Следит за папкой сборки (обычно build/libs): как только там появляется
 * новый jar плагина и дописан до конца, сервер мягко останавливается, jar
 * встаёт вместо старого, сервер запускается. Потом журнал сервера читается
 * ещё полторы минуты — включился ли плагин или упал, и где.
 */
final class PluginWatch {

    /** Что следим: путь к jar, выбранный хозяином (папка и имя берутся из него). */
    static final String SETTINGS = ".virus-plugin-watch";
    /** Какая сборка уже стоит — чтобы после перезапуска V.I.R.U.S. не ставить её второй раз. */
    static final String DEPLOYED = ".virus-plugin-deployed";

    private static final long POLL_MS = 2000;
    /** Сколько читать журнал после запуска, ожидая «плагин включился». */
    private static final long VERDICT_MS = 90_000;

    /** «VirusSwords-1.0.3.jar» → «VirusSwords»: под этим именем ищутся новые сборки. */
    private static final Pattern VERSION = Pattern.compile("[-_]v?\\d.*$");

    /** Сборки, которые не плагин: исходники, документация, «тонкие» jar без зависимостей. */
    private static final Pattern NOT_PLUGIN = Pattern.compile("(?i).*-(sources|javadoc|plain|dev)\\.jar$");

    private final ManagedProcess server;
    private final BiConsumer<String, Boolean> notify;
    private volatile Thread thread;
    private volatile String seen = "";
    private volatile boolean deploying;

    /**
     * @param notify что сказать хозяину: текст и хорошо ли это (для уведомления в трее)
     */
    PluginWatch(ManagedProcess server, BiConsumer<String, Boolean> notify) {
        this.server = server;
        this.notify = notify;
    }

    /** Какой jar выбран; null — ничего не выбрано. */
    Path chosen() {
        try {
            var file = server.workdir().resolve(SETTINGS);
            return Files.isRegularFile(file) ? Path.of(Files.readString(file, StandardCharsets.UTF_8).strip()) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    boolean enabled() {
        return chosen() != null;
    }

    /** Начать следить за сборками рядом с этим jar. */
    void watch(Path jar) throws IOException {
        Files.writeString(server.workdir().resolve(SETTINGS), jar.toAbsolutePath().toString(), StandardCharsets.UTF_8);
        // Выбранную сборку не ставим сразу — только следующие
        Files.writeString(server.workdir().resolve(DEPLOYED), signature(jar), StandardCharsets.UTF_8);
        start();
    }

    void off() throws IOException {
        Files.deleteIfExists(server.workdir().resolve(SETTINGS));
        stop();
    }

    synchronized void start() {
        if (thread != null && thread.isAlive() || !enabled()) {
            return;
        }

        thread = new Thread(this::loop, "virus-plugin-watch");
        thread.setDaemon(true);
        thread.start();
    }

    synchronized void stop() {
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    boolean deploying() {
        return deploying;
    }

    /** «VirusSwords» — имя плагина без версии. */
    static String base(Path jar) {
        var name = jar.getFileName().toString();
        var stem = name.toLowerCase(Locale.ROOT).endsWith(".jar") ? name.substring(0, name.length() - 4) : name;
        var base = VERSION.matcher(stem).replaceFirst("");
        return base.isEmpty() ? stem : base;
    }

    /** Самая свежая сборка этого плагина в папке; null — нет. */
    static Path newest(Path folder, String base) {
        if (!Files.isDirectory(folder)) {
            return null;
        }

        try (var files = Files.list(folder)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> {
                        var name = path.getFileName().toString();
                        return name.toLowerCase(Locale.ROOT).endsWith(".jar") && !NOT_PLUGIN.matcher(name).matches()
                                && base(path).equalsIgnoreCase(base);
                    })
                    .max(Comparator.comparingLong(PluginWatch::modified))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    /** Имя, время и размер — меняется, когда сборка новая. */
    static String signature(Path jar) {
        try {
            return jar.getFileName() + "|" + Files.getLastModifiedTime(jar).toMillis() + "|" + Files.size(jar);
        } catch (IOException e) {
            return "";
        }
    }

    private void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                check();
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                say("Автообновление плагина: " + e.getMessage());
            }
        }
    }

    /** Один обход: есть ли новая сборка и дописана ли она. */
    /** Свой замок: следящий поток и ручная проверка не должны вдвоём поставить одну сборку. */
    private final Object checking = new Object();

    void check() throws IOException, InterruptedException {
        // Не общий с start/stop: замена плагина идёт минутами, окно не должно ждать
        synchronized (checking) {
            checkLocked();
        }
    }

    private void checkLocked() throws IOException, InterruptedException {
        var jar = chosen();

        if (jar == null) {
            return;
        }

        var fresh = newest(jar.getParent(), base(jar));

        if (fresh == null) {
            return;
        }

        var signature = signature(fresh);
        var memo = server.workdir().resolve(DEPLOYED);
        var deployed = Files.isRegularFile(memo) ? Files.readString(memo, StandardCharsets.UTF_8).strip() : "";

        if (signature.isEmpty() || signature.equals(deployed)) {
            return;
        }

        // Gradle дописывает jar не мгновенно: ставим, когда он два обхода подряд не менялся
        if (!signature.equals(seen)) {
            seen = signature;
            return;
        }

        deploy(fresh, signature);
    }

    private void deploy(Path jar, String signature) throws IOException, InterruptedException {
        deploying = true;

        try {
            var started = System.currentTimeMillis();
            var wasRunning = server.state() == ManagedProcess.State.RUNNING
                    || server.state() == ManagedProcess.State.WAITING;
            say("Новая сборка " + jar.getFileName() + (wasRunning ? " — перезапускаю сервер." : " — ставлю."));

            if (wasRunning) {
                server.stop();

                if (!server.waitExit(150_000)) {
                    notify.accept("Сервер не остановился — " + jar.getFileName() + " не поставлен.", false);
                    return;
                }
            }

            // Плагин с ошибкой может испортить мир — раз в полчаса снимаем его перед заменой
            if (!WorldSnapshots.recent(server.workdir(), 30 * 60_000L)) {
                var snapshot = WorldSnapshots.take(server.workdir(), "plugin");

                if (snapshot != null) {
                    say("Снимок мира перед заменой: " + snapshot.getFileName() + ".");
                }
            }

            var removed = ServerTools.replacePlugin(server.workdir(), jar);
            Files.writeString(server.workdir().resolve(DEPLOYED), signature, StandardCharsets.UTF_8);
            say("Плагин заменён на " + jar.getFileName()
                    + (removed.isEmpty() ? "" : " (убран " + String.join(", ", removed) + ")") + ".");

            if (wasRunning) {
                var since = server.log().last();
                server.start();
                verdict(base(jar), since, started);
            } else {
                notify.accept(jar.getFileName() + " поставлен — сервер был выключен.", true);
            }
        } finally {
            deploying = false;
        }
    }

    /** Читает журнал после запуска: включился плагин или упал. */
    private void verdict(String plugin, long since, long started) throws InterruptedException {
        var deadline = System.currentTimeMillis() + VERDICT_MS;
        var seq = since;

        while (System.currentTimeMillis() < deadline) {
            for (var line : server.log().since(seq)) {
                seq = line.seq();
                var outcome = outcome(line.text(), plugin);

                if (outcome == Boolean.TRUE) {
                    var seconds = (System.currentTimeMillis() - started) / 1000;
                    notify.accept(plugin + " обновлён и включился за " + seconds + " с.", true);
                    return;
                }

                if (outcome == Boolean.FALSE) {
                    notify.accept(plugin + " упал при включении: " + shorten(line.text()), false);
                    return;
                }
            }

            if (server.state() == ManagedProcess.State.FAILED) {
                notify.accept("Сервер упал после замены " + plugin + " — смотри консоль.", false);
                return;
            }

            Thread.sleep(500);
        }

        notify.accept(plugin + " поставлен, но сервер не сказал, включился ли он — смотри консоль.", false);
    }

    /**
     * Строка журнала Paper про плагин: TRUE — включился, FALSE — ошибка,
     * null — не про это.
     */
    static Boolean outcome(String line, String plugin) {
        var lower = line.toLowerCase(Locale.ROOT);
        var name = plugin.toLowerCase(Locale.ROOT);

        if (!lower.contains(name)) {
            // «Done (12.3s)!» без строки об ошибке — значит, всё поднялось
            return lower.contains("done (") && lower.contains("for help") ? Boolean.TRUE : null;
        }

        if (lower.contains("error occurred while enabling") || lower.contains("could not load")
                || lower.contains("exception") || lower.contains("disabling " + name)) {
            return Boolean.FALSE;
        }

        return null;
    }

    private static String shorten(String text) {
        var clean = text.strip();
        return clean.length() > 160 ? clean.substring(0, 160) + "…" : clean;
    }

    private void say(String text) {
        server.log().add(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " [V.I.R.U.S.] ▸ " + text);
    }
}
