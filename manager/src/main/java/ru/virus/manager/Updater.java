package ru.virus.manager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * Обновление из присланного архива: выбрал zip (или отдельные jar) — V.I.R.U.S.
 * сам кладёт новые файлы на место и перезапускается.
 *
 * <p>Работающий virus.jar Windows заменить не даст, поэтому файлы сначала
 * складываются в папку update, а переносит их маленький скрипт PowerShell —
 * когда V.I.R.U.S. уже закрылся. Файлы бота при следующем запуске сами
 * переезжают в папку его проекта.
 *
 * <p>Берутся только файлы с известными именами: настройки, миры и прочее из
 * архива случайно ничего не затрут.
 */
final class Updater {

    static final Set<String> FILES = Set.of("virus.jar", "virus.bat", Projects.BOT_JAR, "run.bat",
            "config.properties.example", "worker.js");

    private Updater() {
    }

    static Path staging(Path home) {
        return home.resolve("update");
    }

    /**
     * Складывает обновление в папку update.
     *
     * @return какие файлы будут обновлены
     */
    static List<String> stage(Path home, List<Path> chosen) throws IOException {
        var folder = staging(home);
        deleteTree(folder);
        Files.createDirectories(folder);
        var staged = new ArrayList<String>();

        for (var source : chosen) {
            var name = source.getFileName().toString();

            if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                try (var zip = new ZipFile(source.toFile(), StandardCharsets.UTF_8)) {
                    for (var entry : zip.stream().toList()) {
                        var inner = entry.getName().replace('\\', '/');
                        var file = inner.substring(inner.lastIndexOf('/') + 1);

                        if (!entry.isDirectory() && FILES.contains(file) && !staged.contains(file)) {
                            try (var in = zip.getInputStream(entry)) {
                                Files.copy(in, folder.resolve(file), StandardCopyOption.REPLACE_EXISTING);
                            }
                            staged.add(file);
                        }
                    }
                }
            } else if (FILES.contains(name)) {
                Files.copy(source, folder.resolve(name), StandardCopyOption.REPLACE_EXISTING);

                if (!staged.contains(name)) {
                    staged.add(name);
                }
            }
        }

        if (staged.isEmpty()) {
            deleteTree(folder);
            throw new IOException("в выбранном нет файлов V.I.R.U.S. — нужен присланный архив V.I.R.U.S.zip "
                    + "или файлы вроде virus.jar и voice-bridge-bot.jar");
        }

        return staged;
    }

    /**
     * Скрипт, который дождётся выхода V.I.R.U.S., перенесёт файлы и запустит
     * его снова. Пути — в одинарных кавычках PowerShell, апостроф удваивается.
     */
    static String script(long pid, Path home, String javaw) {
        var homeText = quote(home.toString());
        var update = quote(staging(home).toString());
        return "Wait-Process -Id " + pid + " -ErrorAction SilentlyContinue;"
                + "Start-Sleep -Seconds 1;"
                + "Copy-Item -Path (Join-Path " + update + " '*') -Destination " + homeText + " -Force;"
                + "Remove-Item -Path " + update + " -Recurse -Force -ErrorAction SilentlyContinue;"
                + "Set-Content -Path (Join-Path " + homeText + " 'update-done.txt') -Value 'ok';"
                + "Start-Process -FilePath " + quote(javaw) + " -ArgumentList '-jar','virus.jar' -WorkingDirectory "
                + homeText;
    }

    private static void deleteTree(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return;
        }

        try (var walk = Files.walk(folder)) {
            for (var path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static String quote(String text) {
        return "'" + text.replace("'", "''") + "'";
    }

    /** Запускает перенос и сразу возвращается: дальше V.I.R.U.S. должен закрыться сам. */
    static void launch(Path home) throws IOException {
        if (!SystemInfo.isWindows()) {
            throw new IOException("обновление в один клик есть только на Windows");
        }

        var command = ProcessHandle.current().info().command().orElse("javaw.exe");
        var javaw = command.toLowerCase(Locale.ROOT).endsWith("java.exe")
                ? command.substring(0, command.length() - "java.exe".length()) + "javaw.exe" : command;
        var encoded = java.util.Base64.getEncoder()
                .encodeToString(script(ProcessHandle.current().pid(), home, javaw).getBytes(StandardCharsets.UTF_16LE));

        new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                "-EncodedCommand", encoded)
                .directory(home.toFile())
                .start();
    }

    /** Только что обновились? Метку стираем, чтобы сказать об этом один раз. */
    static boolean justUpdated(Path home) {
        try {
            return Files.deleteIfExists(home.resolve("update-done.txt"));
        } catch (IOException e) {
            return false;
        }
    }
}
