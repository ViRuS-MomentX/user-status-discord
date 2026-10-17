package ru.virus.manager;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Что за компьютер: имя, модель, процессор, видеокарта, память, диски.
 *
 * <p>Java сама знает немного — систему, ядра, память. Модель процессора,
 * видеокарту и плату на Windows спрашиваем у WMI через PowerShell: один вызов
 * при открытии страницы, дальше только нагрузка, которую Java отдаёт сразу.
 */
final class SystemInfo {

    record Disk(String name, long total, long free) {
    }

    record Hardware(String computer, String model, String os, String cpu, int cores,
                    String gpu, String board, long memory) {
    }

    /** Нагрузка сейчас: процессор 0..1 (или −1, если неизвестно) и память. */
    record Load(double cpu, long memoryTotal, long memoryFree, List<Disk> disks) {
    }

    private SystemInfo() {
    }

    static Hardware hardware() {
        var os = System.getProperty("os.name", "") + " " + System.getProperty("os.version", "");
        var cores = Runtime.getRuntime().availableProcessors();
        var computer = computerName();
        var memory = load().memoryTotal();

        if (isWindows()) {
            var wmi = wmi();

            return new Hardware(computer,
                    join(wmi.get("maker"), wmi.get("model")),
                    or(wmi.get("os"), os),
                    or(wmi.get("cpu"), "неизвестен") ,
                    cores,
                    or(wmi.get("gpu"), "неизвестна"),
                    or(join(wmi.get("boardMaker"), wmi.get("board")), "неизвестна"),
                    memory);
        }

        return new Hardware(computer, linuxModel(), os, linuxCpu(), cores, "—", "—", memory);
    }

    static Load load() {
        double cpu = -1;
        long total = 0;
        long free = 0;

        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean bean) {
            cpu = bean.getCpuLoad();
            total = bean.getTotalMemorySize();
            free = bean.getFreeMemorySize();
        }

        var disks = new ArrayList<Disk>();

        for (var root : File.listRoots()) {
            if (root.getTotalSpace() > 0) {
                disks.add(new Disk(root.getPath(), root.getTotalSpace(), root.getUsableSpace()));
            }
        }

        return new Load(cpu, total, free, disks);
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String computerName() {
        var name = System.getenv("COMPUTERNAME");

        if (name == null || name.isBlank()) {
            try {
                name = java.net.InetAddress.getLocalHost().getHostName();
            } catch (java.io.IOException e) {
                name = "этот компьютер";
            }
        }

        return name;
    }

    /** Одним вызовом PowerShell — запуск его небыстрый, а спрашивать надо многое. */
    private static Map<String, Object> wmi() {
        var script = "[Console]::OutputEncoding=[Text.Encoding]::UTF8;"
                + "$c=Get-CimInstance Win32_ComputerSystem;"
                + "$p=Get-CimInstance Win32_Processor|Select-Object -First 1;"
                + "$g=(Get-CimInstance Win32_VideoController|ForEach-Object{$_.Name}) -join '; ';"
                + "$b=Get-CimInstance Win32_BaseBoard;"
                + "$o=Get-CimInstance Win32_OperatingSystem;"
                + "[pscustomobject]@{maker=$c.Manufacturer;model=$c.Model;cpu=$p.Name.Trim();gpu=$g;"
                + "boardMaker=$b.Manufacturer;board=$b.Product;os=($o.Caption+' '+$o.Version)}"
                + "|ConvertTo-Json -Compress";

        try {
            var process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
                    .redirectErrorStream(true)
                    .start();
            process.getOutputStream().close();
            var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();

            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Map.of();
            }

            var start = out.indexOf('{');
            return start < 0 ? Map.of() : Json.object(out.substring(start));
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String linuxCpu() {
        try {
            for (var line : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
                if (line.startsWith("model name")) {
                    return line.substring(line.indexOf(':') + 1).strip();
                }
            }
        } catch (Exception ignored) {
            // нет — так нет
        }

        return System.getProperty("os.arch", "неизвестен");
    }

    private static String linuxModel() {
        try {
            return Files.readString(Path.of("/sys/devices/virtual/dmi/id/product_name")).strip();
        } catch (Exception e) {
            return "—";
        }
    }

    private static String or(Object value, String fallback) {
        var text = value == null ? "" : String.valueOf(value).strip();
        return text.isEmpty() || text.equals("null") ? fallback : text;
    }

    private static String join(Object first, Object second) {
        var a = or(first, "");
        var b = or(second, "");

        // «ASUS» + «ASUS TUF…» — производитель уже в названии
        if (!a.isEmpty() && b.toLowerCase().startsWith(a.toLowerCase())) {
            return b;
        }

        return (a + " " + b).strip();
    }

    /** 17179869184 → «16 ГБ». */
    static String size(long bytes) {
        var gb = bytes / 1024.0 / 1024 / 1024;

        if (gb >= 1000) {
            return String.format("%.1f ТБ", gb / 1024);
        }

        return gb >= 10 ? Math.round(gb) + " ГБ" : String.format("%.1f ГБ", gb);
    }
}
