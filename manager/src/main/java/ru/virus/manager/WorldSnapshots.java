package ru.virus.manager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Снимки мира тестового сервера: перед пересозданием мира и заменой плагина
 * все три измерения складываются в zip, и откатиться можно одной кнопкой.
 *
 * <p>Снимаются только при остановленном сервере — иначе в архив попал бы
 * мир, записанный наполовину. Хранятся последние {@link #KEEP}.
 */
final class WorldSnapshots {

    static final int KEEP = 5;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private WorldSnapshots() {
    }

    static Path folder(Path server) {
        return server.resolve("backups").resolve("worlds");
    }

    /**
     * Сложить миры в архив.
     *
     * @param why зачем, латиницей (имя файла должно открываться везде): new-world, plugin, rollback
     * @return архив; null — миров нет, снимать нечего
     */
    static Path take(Path server, String why) throws IOException {
        var worlds = ServerTools.worlds(server);

        if (worlds.isEmpty()) {
            return null;
        }

        var dir = Files.createDirectories(folder(server));
        var zip = dir.resolve(LocalDateTime.now().format(STAMP) + "-" + why + ".zip");
        var temp = dir.resolve(zip.getFileName() + ".tmp");

        try (var out = new ZipOutputStream(Files.newOutputStream(temp))) {
            for (var world : worlds) {
                try (var walk = Files.walk(world)) {
                    for (var path : walk.filter(Files::isRegularFile).toList()) {
                        // Замок сессии сервер создаёт сам — в снимке он только мешал бы
                        if (path.getFileName().toString().equals("session.lock")) {
                            continue;
                        }

                        out.putNextEntry(new ZipEntry(server.relativize(path).toString().replace('\\', '/')));
                        Files.copy(path, out);
                        out.closeEntry();
                    }
                }
            }
        }

        Files.move(temp, zip, StandardCopyOption.REPLACE_EXISTING);
        prune(dir);
        return zip;
    }

    /** Снимки, свежие первыми. */
    static List<Path> list(Path server) {
        var dir = folder(server);

        if (!Files.isDirectory(dir)) {
            return List.of();
        }

        try (var files = Files.list(dir)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".zip"))
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Был ли снимок недавно — чтобы не снимать мир на каждую сборку плагина. */
    static boolean recent(Path server, long millis) {
        var all = list(server);

        try {
            return !all.isEmpty()
                    && System.currentTimeMillis() - Files.getLastModifiedTime(all.getFirst()).toMillis() < millis;
        } catch (IOException e) {
            return false;
        }
    }

    /** Вернуть мир из снимка: нынешние измерения удаляются, снимок разворачивается. */
    static void restore(Path server, Path zip) throws IOException {
        ServerTools.deleteWorlds(server);
        var root = server.toAbsolutePath().normalize();

        try (var in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;

            while ((entry = in.getNextEntry()) != null) {
                var target = root.resolve(entry.getName()).normalize();

                // Архив не может писать за пределы папки сервера
                if (!target.startsWith(root) || entry.isDirectory()) {
                    continue;
                }

                Files.createDirectories(target.getParent());
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void prune(Path dir) throws IOException {
        var all = new ArrayList<Path>();

        try (var files = Files.list(dir)) {
            files.filter(path -> path.getFileName().toString().endsWith(".zip")).forEach(all::add);
        }

        all.sort(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed());

        for (var old : all.subList(Math.min(KEEP, all.size()), all.size())) {
            Files.deleteIfExists(old);
        }
    }

    /** «2026-11-01_12-30-05-plugin.zip» → «01.11 12:30 · перед заменой плагина». */
    static String title(Path zip) {
        var name = zip.getFileName().toString().replaceFirst("\\.zip$", "");

        try {
            var when = LocalDateTime.parse(name.substring(0, 19), STAMP);
            var tag = name.length() > 20 ? name.substring(20) : "";
            var why = switch (tag) {
                case "plugin" -> "перед заменой плагина";
                case "new-world" -> "перед новым миром";
                case "rollback" -> "перед откатом";
                default -> tag.replace('-', ' ');
            };
            return when.format(DateTimeFormatter.ofPattern("dd.MM HH:mm")) + (why.isEmpty() ? "" : " · " + why);
        } catch (RuntimeException e) {
            return name;
        }
    }
}
