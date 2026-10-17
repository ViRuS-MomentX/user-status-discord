package ru.virus.manager;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
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

    /** Своё V.I.R.U.S. — при переезде бота остаётся на месте. */
    private static final Set<String> OWN = Set.of("virus.jar", "virus.bat", "processes.json",
            "processes.json.tmp", "logs", "projects", "reports", "tasks.json", "tasks.json.bak", "tasks.json.tmp");

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
            if (Files.exists(target.resolve(source.getFileName().toString()))) {
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
                Files.move(source, destination);
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

    /**
     * Копирует файлы и папки в папку проекта.
     *
     * @param replace заменять ли то, что уже есть с тем же именем
     * @return сколько скопировано верхнего уровня
     */
    static int copyInto(Path folder, List<Path> sources, boolean replace) throws IOException {
        var count = 0;

        for (var source : sources) {
            var destination = folder.resolve(source.getFileName().toString());

            // Папку саму в себя не копируем — это бесконечная матрёшка
            if (destination.toAbsolutePath().normalize().startsWith(source.toAbsolutePath().normalize())) {
                throw new IOException("нельзя положить папку саму в себя: " + source.getFileName());
            }

            if (Files.exists(destination) && !replace) {
                continue;
            }

            if (Files.isDirectory(source)) {
                copyTree(source, destination);
            } else {
                Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
            }

            count++;
        }

        return count;
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(destination.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, destination.resolve(source.relativize(file).toString()),
                        StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Удаляет файл или папку целиком — когда корзины нет. */
    static void deleteTree(Path path) throws IOException {
        if (!Files.isDirectory(path)) {
            Files.deleteIfExists(path);
            return;
        }

        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
