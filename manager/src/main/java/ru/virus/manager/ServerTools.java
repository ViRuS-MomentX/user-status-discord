package ru.virus.manager;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Сервер Minecraft в папке проекта: найти его jar, пересоздать миры,
 * заменить плагин.
 */
final class ServerTools {

    /** Тестовый сервер — заводится в V.I.R.U.S. сам. */
    static final String ID = "test-server";

    /** Плагин, который разрабатывается: его прошлые сборки убираем при замене. */
    static final String PLUGIN = "virusswords";

    /** По этим словам в имени узнаём jar самого сервера, а не библиотеки рядом. */
    private static final List<String> SERVER_JARS = List.of(
            "paper", "purpur", "folia", "pufferfish", "spigot", "craftbukkit", "fabric", "forge",
            "neoforge", "quilt", "server", "minecraft");

    /** Где V.I.R.U.S. помнит, какой плагин ставил последним. */
    private static final String LAST_PLUGIN = ".virus-plugin";

    private ServerTools() {
    }

    static ProcessSpec defaultSpec() {
        return new ProcessSpec(ID, "Тестовый сервер",
                List.of("{java}", "-Xms1G", "-Xmx4G", "-jar", "{jar}", "nogui"),
                "projects/" + ID, false, true, true);
    }

    /** Сервер ли это: по «nogui» в команде или по {jar}. */
    static boolean isServer(ProcessSpec spec) {
        return spec.command().stream().anyMatch(part -> part.equalsIgnoreCase("nogui") || part.contains("{jar}"));
    }

    /** jar сервера в папке: единственный — он; несколько — по названию. */
    static Path serverJar(Path folder) {
        List<Path> jars;

        try (var stream = Files.list(folder)) {
            jars = stream.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return null;
        }

        if (jars.size() == 1) {
            return jars.getFirst();
        }

        for (var word : SERVER_JARS) {
            for (var jar : jars) {
                if (jar.getFileName().toString().toLowerCase(Locale.ROOT).contains(word)) {
                    return jar;
                }
            }
        }

        return jars.isEmpty() ? null : jars.getFirst();
    }

    /** Имя мира из server.properties; нет файла — «world». */
    static String levelName(Path folder) {
        var file = folder.resolve("server.properties");

        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                var props = new Properties();
                props.load(reader);
                var name = props.getProperty("level-name", "world").strip();
                return name.isEmpty() ? "world" : name;
            } catch (IOException | IllegalArgumentException ignored) {
                // кривой файл — берём обычное имя
            }
        }

        return "world";
    }

    /**
     * Папки всех трёх измерений. У Paper и Spigot они рядом: world,
     * world_nether, world_the_end; у ванильного сервера ад и край лежат внутри
     * world — их убирает удаление самой world.
     */
    static List<Path> worlds(Path folder) {
        var level = levelName(folder);
        var result = new ArrayList<Path>();

        for (var name : List.of(level, level + "_nether", level + "_the_end")) {
            var path = folder.resolve(name).normalize();

            // Имя мира с «..» не уведёт за пределы папки сервера
            if (path.startsWith(folder.normalize()) && Files.isDirectory(path)) {
                result.add(path);
            }
        }

        return result;
    }

    /** Удаляет миры — при следующем запуске сервер создаст новые. @return сколько удалено */
    static int deleteWorlds(Path folder) throws IOException {
        var worlds = worlds(folder);

        for (var world : worlds) {
            deleteTree(world);
        }

        return worlds.size();
    }

    /**
     * Ставит выбранный jar вместо прошлой сборки плагина: убирает все
     * VirusSwords-*.jar и тот jar, что V.I.R.U.S. ставил в прошлый раз.
     * Папку с настройками плагина не трогает.
     *
     * @return какие файлы убраны
     */
    static List<String> replacePlugin(Path folder, Path chosen) throws IOException {
        var plugins = folder.resolve("plugins");
        Files.createDirectories(plugins);

        var memo = folder.resolve(LAST_PLUGIN);
        var last = Files.isRegularFile(memo) ? Files.readString(memo, StandardCharsets.UTF_8).strip() : "";
        var removed = new ArrayList<String>();

        try (var stream = Files.list(plugins)) {
            for (var jar : stream.filter(Files::isRegularFile).toList()) {
                var name = jar.getFileName().toString();
                var lower = name.toLowerCase(Locale.ROOT);

                if (lower.endsWith(".jar") && (lower.startsWith(PLUGIN) || name.equals(last))
                        && !jar.equals(plugins.resolve(chosen.getFileName().toString()))) {
                    Files.delete(jar);
                    removed.add(name);
                }
            }
        }

        Files.copy(chosen, plugins.resolve(chosen.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(memo, chosen.getFileName().toString(), StandardCharsets.UTF_8);
        return removed;
    }

    private static void deleteTree(Path path) throws IOException {
        try (var walk = Files.walk(path)) {
            for (var item : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(item);
            }
        }
    }
}
