package ru.virus.manager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Один процесс под присмотром: запуск, остановка, перезапуск при падении и
 * его консоль.
 */
final class ManagedProcess {

    enum State {
        /** Не запущен и не должен. */
        STOPPED,
        /** Работает. */
        RUNNING,
        /** Просили остановиться — ждём, пока закончит. */
        STOPPING,
        /** Упал и скоро будет поднят снова. */
        WAITING,
        /** Упал, и поднимать его не будем: перезапуск выключен или беда в настройках. */
        FAILED
    }

    /** Код выхода бота при ошибке в настройках: перезапуск тут не поможет. */
    static final int CONFIG_ERROR = 2;

    /** Паузы перед перезапуском после падений подряд. */
    private static final long[] RESTART_DELAYS_MS = { 5_000, 10_000, 30_000, 60_000 };

    /** Проработал дольше — значит, падения «подряд» кончились, счёт сначала. */
    private static final long STABLE_MS = 120_000;

    /** Сколько ждать мягкой остановки, прежде чем обрывать. */
    private static final long GRACE_MS = 15_000;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final LogBuffer log;
    private final Path home;
    private final String java;
    private final ScheduledExecutorService timer;

    private volatile ProcessSpec spec;
    private volatile State state = State.STOPPED;
    private volatile Process process;
    private volatile long startedAt;
    private volatile Integer exitCode;
    private volatile int crashesInRow;
    private volatile int restarts;
    private volatile String note = "";
    private volatile boolean stopRequested;
    private ScheduledFuture<?> pending;

    ManagedProcess(ProcessSpec spec, Path home, String java, Path logFile) {
        this.spec = spec;
        this.home = home;
        this.java = java;
        this.log = new LogBuffer(logFile);
        this.timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "virus-" + spec.id() + "-timer");
            thread.setDaemon(true);
            return thread;
        });
    }

    ProcessSpec spec() {
        return spec;
    }

    void update(ProcessSpec spec) {
        this.spec = spec;
    }

    LogBuffer log() {
        return log;
    }

    State state() {
        return state;
    }

    /** Команда с подставленными {java}, {home} и {jar} — jar сервера в папке процесса. */
    List<String> resolvedCommand() {
        var result = new ArrayList<String>();
        String jar = null;

        for (var part : spec.command()) {
            if (part.contains("{jar}") && jar == null) {
                var found = ServerTools.serverJar(workdir());
                jar = found == null ? "server.jar" : found.getFileName().toString();
            }

            result.add(part.replace("{java}", java).replace("{home}", home.toString())
                    .replace("{jar}", jar == null ? "" : jar));
        }

        return withUtf8(spec.command(), result);
    }

    /**
     * Java на Windows пишет вывод в кодировке консоли, а мы читаем UTF-8 — и
     * русский текст превращается в квадраты. Если команда запускает {java} и
     * кодировку сама не задаёт, просим у Java UTF-8 для вывода и ввода.
     */
    static List<String> withUtf8(List<String> raw, List<String> resolved) {
        if (raw.isEmpty() || !raw.getFirst().equals("{java}")
                || raw.stream().anyMatch(part -> part.contains("stdout.encoding"))) {
            return resolved;
        }

        var result = new ArrayList<>(resolved);
        result.addAll(1, List.of("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dstdin.encoding=UTF-8"));
        return result;
    }

    /** Строка в консоль процесса — как будто её набрали в его окне. */
    boolean send(String line) {
        var running = process;

        if (running == null || !running.isAlive()) {
            return false;
        }

        try {
            var in = running.getOutputStream();
            in.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            in.flush();
            system("> " + line);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Ждёт, пока процесс завершится, не закрывая журнал. @return завершился ли */
    boolean waitExit(long millis) {
        var running = process;

        if (running == null) {
            return true;
        }

        try {
            return running.waitFor(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Серверу нужно время сохранить миры — ему ждём дольше, чем боту. */
    private long grace() {
        return ServerTools.isServer(spec) ? 120_000 : GRACE_MS;
    }

    Path workdir() {
        var dir = spec.workdir().replace("{home}", home.toString());
        var folder = dir.isBlank() ? home : home.resolve(dir);
        // Сервер скопировали в папку вместе с его собственной папкой — найдём его внутри
        return ServerTools.isServer(spec) ? ServerTools.serverHome(folder) : folder;
    }

    synchronized void start() {
        cancelPending();

        if (state == State.RUNNING || state == State.STOPPING) {
            return;
        }

        stopRequested = false;
        launch();
    }

    private synchronized void launch() {
        // Без jar сервера запускать нечего: Java показала бы окно с ошибкой, а
        // перезапуск — ещё и ещё одно
        if (spec.command().stream().anyMatch(part -> part.contains("{jar}"))
                && ServerTools.serverJar(workdir()) == null) {
            state = State.FAILED;
            note = "в папке нет jar сервера — открой «Файлы» и положи туда сервер";
            system("Не запускаю: в " + workdir() + " нет jar сервера. Нажми «Файлы» и положи туда "
                    + "содержимое папки сервера (jar, plugins, world…).");
            return;
        }

        var command = resolvedCommand();
        system("Запуск: " + ProcessSpec.join(command));

        Process started;

        try {
            // Папка проекта ещё не заведена — заводим: пустой процесс тоже где-то живёт
            Files.createDirectories(workdir());
            started = new ProcessBuilder(command)
                    .directory(workdir().toFile())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            state = State.FAILED;
            note = "не запустился: " + e.getMessage();
            system("Не запустился: " + e.getMessage());
            return;
        }

        process = started;
        startedAt = System.currentTimeMillis();
        exitCode = null;
        note = "";
        state = State.RUNNING;

        var reader = new Thread(() -> pump(started), "virus-" + spec.id() + "-console");
        reader.setDaemon(true);
        reader.start();
    }

    /** Переносит вывод процесса в консоль и решает, что делать, когда он кончится. */
    private void pump(Process running) {
        try (var in = new BufferedReader(new InputStreamReader(running.getInputStream(), StandardCharsets.UTF_8))) {
            String line;

            while ((line = in.readLine()) != null) {
                log.add(line);
            }
        } catch (IOException e) {
            // Поток закрылся вместе с процессом — дальше узнаем код выхода
        }

        int code;

        try {
            code = running.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        finished(running, code);
    }

    private synchronized void finished(Process running, int code) {
        if (running != process) {
            return;
        }

        process = null;
        exitCode = code;
        var lived = System.currentTimeMillis() - startedAt;

        if (stopRequested) {
            state = State.STOPPED;
            note = "";
            system("Остановлен.");
            return;
        }

        if (code == CONFIG_ERROR) {
            state = State.FAILED;
            note = "ошибка настройки — смотри консоль и поправь конфиг";
            system("Завершился с ошибкой настройки (код 2). Перезапускать не буду: поправь настройки и запусти.");
            return;
        }

        if (!spec.restart()) {
            state = State.FAILED;
            note = "завершился с кодом " + code;
            system("Завершился с кодом " + code + ".");
            return;
        }

        crashesInRow = lived > STABLE_MS ? 1 : crashesInRow + 1;
        var delay = RESTART_DELAYS_MS[Math.min(crashesInRow - 1, RESTART_DELAYS_MS.length - 1)];

        state = State.WAITING;
        note = "упал с кодом " + code + ", перезапуск через " + delay / 1000 + " с";
        system("Завершился с кодом " + code + ". Перезапущу через " + delay / 1000 + " с.");

        pending = timer.schedule(() -> {
            synchronized (this) {
                if (state == State.WAITING && !stopRequested) {
                    restarts++;
                    launch();
                }
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    /**
     * Просит остановиться. Свои боты понимают «stop» и успевают сохранить всё
     * нужное; кто не понял за {@link #GRACE_MS} — обрывается.
     */
    synchronized void stop() {
        cancelPending();
        stopRequested = true;
        var running = process;

        if (running == null) {
            if (state != State.STOPPED) {
                state = State.STOPPED;
                note = "";
                system("Остановлен.");
            }
            return;
        }

        state = State.STOPPING;
        system("Останавливаю…");

        if (spec.supervised()) {
            try {
                OutputStream in = running.getOutputStream();
                in.write("stop\n".getBytes(StandardCharsets.UTF_8));
                in.flush();
            } catch (IOException e) {
                running.destroy();
            }
        } else {
            running.destroy();
        }

        timer.schedule(() -> {
            if (running.isAlive()) {
                system("Не остановился сам — обрываю.");
                running.descendants().forEach(ProcessHandle::destroyForcibly);
                running.destroyForcibly();
            }
        }, grace(), TimeUnit.MILLISECONDS);
    }

    /** Остановить и запустить снова, когда остановится. */
    void restart() {
        var running = process;
        stop();

        if (running == null) {
            start();
            return;
        }

        running.onExit().thenRun(() -> timer.schedule(this::start, 300, TimeUnit.MILLISECONDS));
    }

    /** Ждёт остановки — при выходе из V.I.R.U.S. */
    void awaitStop(long millis) {
        var running = process;

        if (running != null) {
            try {
                running.waitFor(millis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (running.isAlive()) {
                running.destroyForcibly();
            }
        }

        log.close();
    }

    private void cancelPending() {
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
    }

    /** Строка от самого V.I.R.U.S. — отличается от вывода процесса значком. */
    private void system(String text) {
        log.add(LocalTime.now().format(CLOCK) + " [V.I.R.U.S.] ▸ " + text);
    }

    Map<String, Object> toJson() {
        var json = new LinkedHashMap<String, Object>(spec.toJson());
        var running = process;
        json.put("commandLine", ProcessSpec.join(spec.command()));
        json.put("state", state.name());
        json.put("pid", running == null ? null : running.pid());
        json.put("startedAt", state == State.RUNNING ? startedAt : null);
        json.put("exitCode", exitCode);
        json.put("restarts", restarts);
        json.put("note", note);
        return json;
    }
}
