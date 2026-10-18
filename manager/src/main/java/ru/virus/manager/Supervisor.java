package ru.virus.manager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
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
                ? new java.util.HashSet<>(Files.readAllLines(memo, StandardCharsets.UTF_8)) : new java.util.HashSet<String>();

        if (!seeded.contains(ServerTools.ID)) {
            if (!processes.containsKey(ServerTools.ID)) {
                var spec = ServerTools.defaultSpec();
                processes.put(spec.id(), create(spec));
                Files.createDirectories(home.resolve(spec.workdir()));
            }

            seeded.add(ServerTools.ID);
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

    void startAutostart() {
        for (var process : all()) {
            if (process.spec().autostart()) {
                process.start();
            }
        }
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
