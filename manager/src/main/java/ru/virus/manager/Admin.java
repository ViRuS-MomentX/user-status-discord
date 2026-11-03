package ru.virus.manager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Права администратора и «кто мешает». V.I.R.U.S. работает от
 * администратора: так он без вопросов запускает службу zapret, OpenRGB
 * с доступом к памяти и плате и закрывает программы, которые держат экраны
 * корпуса или подсветку.
 */
final class Admin {

    /** Программы JONSBO/TURZX/Turing, которые держат экраны корпуса. */
    static final Pattern SCREEN_HOGS = Pattern.compile("(?i)^(jonsbo|turzx|turing|uscreen|usbmonitor|smartmonitor)[^\\\\/]*\\.exe$");

    /**
     * Программы подсветки, которые спорят с OpenRGB за плату, память и
     * вентиляторы: SignalRGB, MSI Center/Mystic Light, Armoury Crate, iCUE,
     * GIGABYTE RGB Fusion/Control Center, NZXT CAM, L-Connect, Polychrome.
     */
    static final Pattern RGB_HOGS = Pattern.compile("(?i)^(signalrgb[^\\\\/]*|mysticlight[^\\\\/]*|ledkeeper[^\\\\/]*"
            + "|msi[ ._]?(central|center)[^\\\\/]*|lightingservice|armourycrate[^\\\\/]*|icue|rgbfusion[^\\\\/]*|gcc"
            + "|gigabytecontrolcenter|nzxt cam|l-connect[^\\\\/]*|polychrome[^\\\\/]*|aurasrv|asusmanager)\\.exe$");

    private static Boolean admin;

    private Admin() {
    }

    /** Запущены ли мы от администратора (Windows); вне Windows — true, спрашивать нечего. */
    static synchronized boolean is() {
        if (admin == null) {
            admin = !SystemInfo.isWindows() || exitCode("net", "session") == 0;
        }

        return admin;
    }

    private static int exitCode(String... command) {
        try {
            var process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getOutputStream().close();
            process.getInputStream().readAllBytes();
            return process.waitFor(10, TimeUnit.SECONDS) ? process.exitValue() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Перезапустить себя от администратора (Windows спросит один раз).
     *
     * @return true — новая копия запущена, этой надо выйти; false — отказали или не вышло
     */
    static boolean relaunch(String javaw, Path jar, List<String> args) {
        if (!SystemInfo.isWindows() || jar == null || javaw == null) {
            return false;
        }

        var arguments = new ArrayList<String>();
        arguments.add("'-jar'");
        arguments.add("'\"" + jar.toString().replace("'", "''") + "\"'");

        for (var arg : args) {
            arguments.add("'" + arg.replace("'", "''") + "'");
        }

        arguments.add("'--elevated'");
        var script = "try { Start-Process -FilePath '" + javaw.replace("'", "''") + "' -Verb RunAs -ArgumentList "
                + String.join(",", arguments) + " -ErrorAction Stop; exit 0 } catch { exit 1 }";
        return exitCode("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script) == 0;
    }

    /** Имена запущенных программ (tasklist). */
    static List<String> running() {
        if (!SystemInfo.isWindows()) {
            return List.of();
        }

        try {
            var process = new ProcessBuilder("tasklist", "/FO", "CSV", "/NH").redirectErrorStream(true).start();
            process.getOutputStream().close();
            var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor(10, TimeUnit.SECONDS);
            return names(out);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Имена из CSV tasklist: «"JONSBO.exe","1234",…». */
    static List<String> names(String csv) {
        return csv.lines().filter(line -> line.startsWith("\"")).map(line -> line.substring(1, Math.max(1, line.indexOf('"', 1))))
                .filter(name -> !name.isBlank()).distinct().toList();
    }

    /** Кто из запущенных подходит под шаблон. */
    static List<String> matching(Pattern pattern, List<String> running) {
        return running.stream().filter(name -> pattern.matcher(name).matches()).toList();
    }

    /** Службы подсветки чужих программ (Armoury, Mystic Light, SignalRGB) — остановить, пока работает V.I.R.U.S. */
    static void stopRgbServices() {
        if (SystemInfo.isWindows() && is()) {
            exitCode("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "Get-Service | Where-Object { $_.Status -eq 'Running' -and ($_.Name + ' ' + $_.DisplayName) -match "
                            + "'LightingService|Mystic ?Light|SignalRgb' } | Stop-Service -Force -ErrorAction SilentlyContinue");
        }
    }

    /**
     * Закрыть программы по шаблону (с детьми). Себя, OpenRGB и Java не трогаем.
     *
     * @return кого закрыли
     */
    static List<String> close(Pattern pattern) {
        var found = matching(pattern, running());

        for (var name : found) {
            exitCode("taskkill", "/F", "/T", "/IM", name);
        }

        return found;
    }
}
