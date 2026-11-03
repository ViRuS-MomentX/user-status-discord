package ru.virus.manager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Все процессы под присмотром и их описания в processes.json.
 */
final class Supervisor {

    private final Path home;
    private final String java;
    private final Path file;
    private final Map<String, ManagedProcess> processes = new LinkedHashMap<>();

    Supervisor(Path home, String java) {
        this.home = home;
        this.java = java;
        this.file = home.resolve("processes.json");
    }

    /** Читает описания; при первом запуске — боты из этого репозитория. */
    synchronized void load() throws IOException {
        List<ProcessSpec> specs;

        if (Files.isRegularFile(file)) {
            specs = new ArrayList<>();
            var json = Json.parse(Files.readString(file, StandardCharsets.UTF_8));

            if (json instanceof List<?> list) {
                for (var item : list) {
                    if (item instanceof Map<?, ?> map) {
                        @SuppressWarnings("unchecked")
                        var spec = ProcessSpec.fromJson((Map<String, Object>) map);
                        specs.add(spec);
                    }
                }
            }
        } else {
            specs = ProcessSpec.defaults();
        }

        for (var spec : specs) {
            processes.put(spec.id(), create(spec));
        }

        seed();
        save();
    }

    /**
     * Процессы, которые V.I.R.U.S. заводит сам, — один раз. Удалил — не
     * вернётся: кто заведён, записано в virus-seeded.txt.
     */
    private void seed() throws IOException {
        var memo = home.resolve("virus-seeded.txt");
        var seeded = Files.isRegularFile(memo)
                ? new java.util.HashSet<>(Files.readAllLines(memo, StandardCharsets.UTF_8)) : new HashSet<String>();

        if (!seeded.contains(ServerTools.ID)) {
            if (!processes.containsKey(ServerTools.ID)) {
                var spec = ServerTools.defaultSpec();
                processes.put(spec.id(), create(spec));
                Files.createDirectories(home.resolve(spec.workdir()));
            }

            seeded.add(ServerTools.ID);
            Files.write(memo, seeded, StandardCharsets.UTF_8);
        }

        // zapret (обход блокировок Discord/YouTube) — тоже один раз; сам он ставится из своих настроек
        if (!seeded.contains(Zapret.ID)) {
            if (!processes.containsKey(Zapret.ID)) {
                var spec = Zapret.defaultSpec();
                processes.put(spec.id(), create(spec));
                Files.createDirectories(home.resolve(spec.workdir()));
            }

            seeded.add(Zapret.ID);
            Files.write(memo, seeded, StandardCharsets.UTF_8);
        }

        // Telegram-прокси Flowseal (TG WS Proxy): Telegram при zapret ходит через него
        if (!seeded.contains(TgProxy.ID)) {
            if (!processes.containsKey(TgProxy.ID)) {
                var spec = TgProxy.defaultSpec();
                processes.put(spec.id(), create(spec));
                Files.createDirectories(home.resolve(spec.workdir()));
            }

            seeded.add(TgProxy.ID);
            Files.write(memo, seeded, StandardCharsets.UTF_8);
        }
    }

    private ManagedProcess create(ProcessSpec spec) {
        return new ManagedProcess(spec, home, java, home.resolve("logs").resolve(spec.id() + ".log"));
    }

    Path home() {
        return home;
    }

    /**
     * Папка бота из этого репозитория — где лежат его настройки. Там, где
     * запускается процесс с voice-bridge-bot.jar; нет такого — папка V.I.R.U.S.
     */
    synchronized Path botFolder() {
        for (var process : processes.values()) {
            if (process.spec().command().stream().anyMatch(part -> part.endsWith(Projects.BOT_JAR))) {
                return process.workdir();
            }
        }

        return home;
    }

    synchronized List<ManagedProcess> all() {
        return List.copyOf(processes.values());
    }

    synchronized ManagedProcess get(String id) {
        return processes.get(id);
    }

    /** Новый процесс или правка старого. Работающий продолжает, новая команда — со следующего запуска. */
    synchronized void put(ProcessSpec spec) throws IOException {
        var existing = processes.get(spec.id());

        if (existing == null) {
            processes.put(spec.id(), create(spec));
        } else {
            existing.update(spec);
        }

        save();
    }

    synchronized void remove(String id) throws IOException {
        var process = processes.remove(id);

        if (process != null) {
            process.stop();
        }

        save();
    }

    /** Файл со списком работавших процессов — чтобы после обновления запустить их снова. */
    static final String RESUME = "resume.txt";

    void startAutostart() {
        var resume = home.resolve(RESUME);
        var again = new HashSet<String>();

        try {
            if (Files.isRegularFile(resume)) {
                again.addAll(Files.readAllLines(resume, StandardCharsets.UTF_8));
                Files.delete(resume);
            }
        } catch (IOException ignored) {
            // нет списка — запустим только автозапуск
        }

        // И то, что работало, когда V.I.R.U.S. закрылся в прошлый раз (выключение компьютера тоже)
        again.addAll(lastRunning());

        for (var process : all()) {
            if (process.spec().autostart() || again.contains(process.spec().id())) {
                process.start();
            }
        }

        tracking = true;
    }

    /** Что было запущено в прошлый раз — запускается при следующем старте V.I.R.U.S. */
    static final String LAST = "last-running.txt";
    private volatile boolean tracking;
    private java.util.Set<String> last;

    private java.util.Set<String> lastRunning() {
        try {
            var file = home.resolve(LAST);
            return Files.isRegularFile(file) ? new HashSet<>(Files.readAllLines(file, StandardCharsets.UTF_8)) : new HashSet<>();
        } catch (IOException e) {
            return new HashSet<>();
        }
    }

    /**
     * Запомнить, что работает. Убираем из списка только то, что остановили
     * руками (STOPPED): упавшее при выключении компьютера остаётся — после
     * включения поднимется снова.
     */
    synchronized void track() {
        if (!tracking) {
            return;
        }

        if (last == null) {
            last = lastRunning();
        }

        var next = new HashSet<>(last);

        for (var process : processes.values()) {
            switch (process.state()) {
                case RUNNING, WAITING -> next.add(process.spec().id());
                case STOPPED -> next.remove(process.spec().id());
                default -> {
                    // упал — не трогаем
                }
            }
        }

        next.retainAll(processes.keySet());

        if (!next.equals(last)) {
            try {
                Files.write(home.resolve(LAST), next.stream().sorted().toList(), StandardCharsets.UTF_8);
                last = next;
            } catch (IOException ignored) {
                // попробуем на следующем шаге
            }
        }
    }

    /** Запомнить, что сейчас работает: после обновления V.I.R.U.S. запустит это сам. */
    void rememberRunning() throws IOException {
        var running = all().stream()
                .filter(process -> process.state() == ManagedProcess.State.RUNNING
                        || process.state() == ManagedProcess.State.WAITING)
                .map(process -> process.spec().id())
                .toList();
        Files.write(home.resolve(RESUME), running, StandardCharsets.UTF_8);
    }

    /** Останавливает всех и ждёт — при выходе из V.I.R.U.S. */
    void stopAll() {
        var all = all();
        all.forEach(ManagedProcess::stop);
        all.forEach(process -> process.awaitStop(20_000));
    }

    private void save() throws IOException {
        var list = new ArrayList<Object>();

        for (var process : processes.values()) {
            list.add(process.spec().toJson());
        }

        var temporary = file.resolveSibling("processes.json.tmp");
        Files.writeString(temporary, Json.write(list), StandardCharsets.UTF_8);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Java для дочерних процессов — ровно та, на которой работает V.I.R.U.S.
     *
     * <p>Если он запущен через javaw (двойной клик, автозапуск), ботам тоже
     * javaw: консольная java из оконной программы может открыть каждому своё
     * чёрное окно. Вывод всё равно идёт в панель через канал, окно не нужно.
     */
    static String findJava() {
        return ProcessHandle.current().info().command().orElse("java");
    }

    /** javaw для автозапуска: та, что без окна. */
    static String findJavaw() {
        var self = Path.of(ProcessHandle.current().info().command().orElse("javaw"));
        var windowless = self.resolveSibling("javaw.exe");
        return Files.isRegularFile(windowless) ? windowless.toString() : self.toString();
    }
}
