package ru.virus.manager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Папки проектов: V.I.R.U.S./projects/&lt;проект&gt;/ — у каждого свои файлы.
 *
 * <p>Процесс запускается в папке своего проекта, там же лежат его настройки и
 * данные. Сам V.I.R.U.S. держит в своей папке только своё: virus.jar, список
 * процессов, журналы и отчёты.
 */
final class Projects {

    /** Проект, в который переезжает бот из этого репозитория. */
    static final String BOT = "voice-bridge";

    /** Файл, по которому видно, что бот ещё лежит прямо в папке V.I.R.U.S. */
    static final String BOT_JAR = "voice-bridge-bot.jar";

    /**
     * Файлы новой версии бота: распаковал обновление поверх папки V.I.R.U.S. —
     * они заменят старые в папке проекта. Настройки и данные так не заменяются.
     */
    static final Set<String> UPDATES = Set.of(BOT_JAR, "run.bat", "config.properties.example", "worker.js");

    /** Своё V.I.R.U.S. — при переезде бота остаётся на месте. */
    private static final Set<String> OWN = Set.of("virus.jar", "virus.bat", "processes.json",
            "processes.json.tmp", "logs", "projects", "reports", "tasks.json", "tasks.json.bak", "tasks.json.tmp", "virus-settings.json", "device.json");

    private final Path home;

    Projects(Path home) {
        this.home = home;
    }

    Path root() {
        return home.resolve("projects");
    }

    List<Path> list() throws IOException {
        if (!Files.isDirectory(root())) {
            return List.of();
        }

        try (var stream = Files.list(root())) {
            return stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase()))
                    .toList();
        }
    }

    /** Новая папка проекта. Имя — такое, какое Windows примет как имя папки. */
    Path create(String name) throws IOException {
        var clean = name == null ? "" : name.strip();

        if (clean.isEmpty() || clean.length() > 60 || !clean.matches("[^\\\\/:*?\"<>|]+")
                || clean.equals(".") || clean.equals("..")) {
            throw new IOException("имя проекта — без знаков \\ / : * ? \" < > | и не длиннее 60");
        }

        var folder = root().resolve(clean);
        Files.createDirectories(folder);
        return folder;
    }

    /** Лежит ли бот ещё прямо в папке V.I.R.U.S., а не в папке проекта. */
    boolean botInHome() {
        return Files.isRegularFile(home.resolve(BOT_JAR));
    }

    /** Что переедет в папку проекта: всё, кроме своего у V.I.R.U.S. */
    List<Path> movable() throws IOException {
        try (var stream = Files.list(home)) {
            return stream.filter(path -> !OWN.contains(path.getFileName().toString()))
                    .sorted()
                    .toList();
        }
    }

    /**
     * Переносит бота в папку проекта и переводит туда процессы, которые
     * запускались прямо из папки V.I.R.U.S. Работающие процессы на время
     * переезда останавливаются: Windows не даёт двигать открытые файлы.
     *
     * @return сколько файлов и папок перенесено
     */
    int moveBot(Supervisor supervisor) throws IOException {
        var target = create(BOT);
        var sources = movable();

        // Сначала проверяем всё, потом трогаем: застрять на полпути хуже, чем не начать
        for (var source : sources) {
            if (!UPDATES.contains(source.getFileName().toString())
                    && Files.exists(target.resolve(source.getFileName().toString()))) {
                throw new IOException("в папке проекта уже есть " + source.getFileName()
                        + " — разберись с ним и попробуй снова");
            }
        }

        var affected = new ArrayList<ManagedProcess>();
        var wasRunning = new ArrayList<ManagedProcess>();

        for (var process : supervisor.all()) {
            if (process.spec().workdir().isBlank()) {
                affected.add(process);

                if (process.state() == ManagedProcess.State.RUNNING) {
                    wasRunning.add(process);
                }
            }
        }

        affected.forEach(ManagedProcess::stop);
        affected.forEach(process -> process.awaitStop(20_000));

        var moved = new ArrayList<Path[]>();

        try {
            for (var source : sources) {
                var destination = target.resolve(source.getFileName().toString());
                if (UPDATES.contains(source.getFileName().toString())) {
                    Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(source, destination);
                }

                moved.add(new Path[] { source, destination });
            }
        } catch (IOException e) {
            // Возвращаем перенесённое назад и поднимаем процессы как было
            for (var pair : moved.reversed()) {
                try {
                    Files.move(pair[1], pair[0]);
                } catch (IOException ignored) {
                    // что не вернулось — останется в папке проекта, это не потеря
                }
            }

            wasRunning.forEach(ManagedProcess::start);
            throw new IOException("не перенёсся " + e.getMessage()
                    + " — скорее всего, файл открыт другой программой. Всё возвращено как было.");
        }

        for (var process : affected) {
            var spec = process.spec();
            supervisor.put(new ProcessSpec(spec.id(), spec.name(), spec.command(), "projects/" + BOT,
                    spec.autostart(), spec.restart(), spec.supervised()));
        }

        wasRunning.forEach(ManagedProcess::start);
        return moved.size();
    }
}
