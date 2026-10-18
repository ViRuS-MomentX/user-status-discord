package ru.virus.manager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Лишние копии ботов — запущенные мимо V.I.R.U.S.: старой задачей автозапуска,
 * двойным щелчком по run.bat, оставшиеся после сбоя. Две копии с одним токеном
 * мешают друг другу: Telegram отдаёт сообщения то одной, то другой, Discord
 * видит бота дважды.
 */
final class Duplicates {

    record Copy(long pid, String what, String commandLine) {
    }

    private Duplicates() {
    }

    /** Чужие копии: процессы с тем же jar, что у наших, но не наши. */
    static List<Copy> find(Supervisor supervisor) {
        var jars = new LinkedHashSet<String>();
        var ours = new HashSet<Long>();
        ours.add(ProcessHandle.current().pid());

        for (var process : supervisor.all()) {
            for (var part : process.spec().command()) {
                if (part.toLowerCase().endsWith(".jar")) {
                    jars.add(part.replace('\\', '/').replaceAll(".*/", "").toLowerCase());
                }
            }

            if (process.toJson().get("pid") instanceof Number pid) {
                ours.add(pid.longValue());
                ProcessHandle.of(pid.longValue()).ifPresent(handle ->
                        handle.descendants().forEach(child -> ours.add(child.pid())));
            }
        }

        return pick(commandLines(), jars, ours);
    }

    /** Отбор — отдельно, чтобы проверять без настоящих процессов. */
    static List<Copy> pick(Map<Long, String> commandLines, Set<String> jars, Set<Long> ours) {
        var copies = new ArrayList<Copy>();

        commandLines.forEach((pid, line) -> {
            if (ours.contains(pid) || line == null) {
                return;
            }

            var lower = line.toLowerCase().replace('\\', '/');

            for (var jar : jars) {
                if (lower.contains("/" + jar) || lower.contains(" " + jar) || lower.contains("\"" + jar)) {
                    copies.add(new Copy(pid, describe(lower, jar), line));
                    return;
                }
            }
        });

        return copies;
    }

    static String describe(String commandLine, String jar) {
        if (!jar.equals(Projects.BOT_JAR)) {
            return jar;
        }

        return commandLine.contains("--parody-only") ? "Пародия"
                : commandLine.contains("--no-parody") ? "Discord-бот и мост"
                : "бот целиком (Discord и пародия)";
    }

    /** Останавливает копии вместе с их дочерними процессами. @return сколько остановлено */
    static int kill(List<Copy> copies) {
        var killed = 0;

        for (var copy : copies) {
            var handle = ProcessHandle.of(copy.pid());

            if (handle.isPresent()) {
                handle.get().descendants().forEach(ProcessHandle::destroyForcibly);

                if (handle.get().destroyForcibly()) {
                    killed++;
                }
            }
        }

        return killed;
    }

    /**
     * Командные строки Java-процессов. Windows не отдаёт Java чужие аргументы,
     * поэтому там спрашиваем WMI.
     */
    private static Map<Long, String> commandLines() {
        var result = new java.util.LinkedHashMap<Long, String>();

        if (!SystemInfo.isWindows()) {
            ProcessHandle.allProcesses().forEach(handle ->
                    handle.info().commandLine().ifPresent(line -> result.put(handle.pid(), line)));
            return result;
        }

        var script = "[Console]::OutputEncoding=[Text.Encoding]::UTF8;"
                + "@(Get-CimInstance Win32_Process -Filter \"Name='java.exe' or Name='javaw.exe'\""
                + "|Select-Object ProcessId,CommandLine)|ConvertTo-Json -Compress";

        try {
            var process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
                    .redirectErrorStream(true).start();
            process.getOutputStream().close();
            var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            process.waitFor(20, TimeUnit.SECONDS);
            var start = out.indexOf('[');

            if (start < 0) {
                start = out.indexOf('{');
            }

            if (start < 0) {
                return result;
            }

            var json = Json.parse(out.substring(start));
            var items = json instanceof List<?> list ? list : List.of(json);

            for (var item : items) {
                if (item instanceof Map<?, ?> map && map.get("ProcessId") instanceof Number pid) {
                    var line = map.get("CommandLine");
                    result.put(pid.longValue(), line == null ? null : String.valueOf(line));
                }
            }
        } catch (Exception ignored) {
            // не спросилось — значит, дублей не покажем
        }

        return result;
    }
}
