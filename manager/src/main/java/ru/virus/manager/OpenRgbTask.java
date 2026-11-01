package ru.virus.manager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * OpenRGB с правами администратора без вопроса каждый раз: задача в
 * Планировщике Windows «при входе, с наивысшими правами». Её создание один
 * раз спрашивает разрешение; дальше OpenRGB поднимается сам при входе в
 * Windows, а V.I.R.U.S. может запустить его через эту задачу, если OpenRGB
 * закрыли, — уже без окна UAC. Так iCUE и SignalRGB оживают сразу после
 * перезагрузки, а не ждут, пока их запустят руками.
 */
final class OpenRgbTask {

    static final String NAME = "V.I.R.U.S. OpenRGB";

    private OpenRgbTask() {
    }

    /** Команда для Планировщика: OpenRGB с сервером SDK, свёрнутым. */
    static String command(String openRgb) {
        return "\"" + openRgb + "\" --server --startminimized";
    }

    /** Есть ли уже задача. */
    static boolean exists() {
        if (!SystemInfo.isWindows()) {
            return false;
        }

        try {
            var process = new ProcessBuilder("schtasks", "/Query", "/TN", NAME).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** Создать задачу — Windows один раз спросит разрешение администратора. */
    static void install(String openRgb) throws IOException {
        run(elevated(List.of("/Create", "/TN", NAME, "/TR", command(openRgb), "/SC", "ONLOGON", "/RL", "HIGHEST",
                "/F")));
    }

    /** Убрать задачу — тоже с разрешением администратора: создана она с наивысшими правами. */
    static void remove() throws IOException {
        run(elevated(List.of("/Delete", "/TN", NAME, "/F")));
    }

    /** Запустить OpenRGB сейчас — через задачу, без окна UAC. */
    static boolean start() {
        try {
            var process = new ProcessBuilder("schtasks", "/Run", "/TN", NAME).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** schtasks от администратора: PowerShell Start-Process -Verb RunAs. */
    static String elevated(List<String> arguments) {
        var quoted = new StringBuilder();

        for (var argument : arguments) {
            quoted.append(quoted.isEmpty() ? "" : ",").append('\'')
                    .append(('"' + argument.replace("\"", "\\\"") + '"').replace("'", "''")).append('\'');
        }

        return "Start-Process -FilePath 'schtasks.exe' -ArgumentList " + quoted + " -Verb RunAs -WindowStyle Hidden -Wait";
    }

    private static void run(String script) throws IOException {
        var encoded = java.util.Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        var process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
                .redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();

        try {
            process.waitFor(120, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
