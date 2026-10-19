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

    /**
     * @param gpus     видеокарты, каждая отдельно и с объёмом памяти
     * @param ram      планки: «2 × 16 ГБ DDR5 6000 МГц»; пусто — неизвестно
     * @param drives   накопители: «Samsung 990 PRO 2 ТБ · NVMe SSD»
     * @param displays мониторы: «2560 × 1440 · 165 Гц»
     */
    record Hardware(String computer, String model, String os, String cpu, int cores,
                    List<String> gpus, String board, long memory, String ram, List<String> drives,
                    String bios, List<String> displays) {
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

            var gpus = gpus(wmi.get("gpus"));

            return new Hardware(computer,
                    join(wmi.get("maker"), wmi.get("model")),
                    or(wmi.get("os"), os),
                    or(wmi.get("cpu"), "неизвестен") ,
                    cores,
                    gpus.isEmpty() ? List.of("неизвестна") : gpus,
                    or(join(wmi.get("boardMaker"), wmi.get("board")), "неизвестна"),
                    memory,
                    ram(wmi.get("ram")),
                    drives(wmi.get("drives")),
                    or(wmi.get("bios"), ""),
                    displays());
        }

        return new Hardware(computer, linuxModel(), os, linuxCpu(), cores, List.of("—"), "—", memory, "",
                List.of(), "", displays());
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
                // Объём видеопамяти WMI врёт для карт больше 4 ГБ — честное число лежит в реестре драйвера
                + "$vr=@(Get-ItemProperty 'HKLM:\\SYSTEM\\ControlSet001\\Control\\Class\\"
                + "{4d36e968-e325-11ce-bfc1-08002be10318}\\0*' -ErrorAction SilentlyContinue|Where-Object{$_.DriverDesc});"
                + "$g=@(Get-CimInstance Win32_VideoController|ForEach-Object{$n=$_.Name;"
                + "$r=$vr|Where-Object{$_.DriverDesc -eq $n}|Select-Object -First 1;"
                + "$q=0;if($r){$q=[int64]$r.'HardwareInformation.qwMemorySize'};"
                + "if(-not $q){$q=[int64]$_.AdapterRAM};[pscustomobject]@{name=$n;memory=$q}});"
                + "$m=@(Get-CimInstance Win32_PhysicalMemory|ForEach-Object{[pscustomobject]@{size=[int64]$_.Capacity;"
                + "speed=$_.ConfiguredClockSpeed;type=$_.SMBIOSMemoryType}});"
                + "$d=@();try{$d=@(Get-PhysicalDisk|ForEach-Object{[pscustomobject]@{name=$_.FriendlyName;"
                + "size=[int64]$_.Size;media=[string]$_.MediaType;bus=[string]$_.BusType}})}catch{};"
                + "$bi=Get-CimInstance Win32_BIOS;"
                + "$b=Get-CimInstance Win32_BaseBoard;"
                + "$o=Get-CimInstance Win32_OperatingSystem;"
                + "[pscustomobject]@{maker=$c.Manufacturer;model=$c.Model;cpu=$p.Name.Trim();gpus=$g;ram=$m;drives=$d;"
                + "bios=($bi.Manufacturer+' '+$bi.SMBIOSBIOSVersion);"
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

    /** PowerShell отдаёт одиночный элемент то списком, то объектом — приводим к списку. */
    private static List<Map<?, ?>> items(Object value) {
        var list = new ArrayList<Map<?, ?>>();

        if (value instanceof Map<?, ?> map) {
            list.add(map);
        } else if (value instanceof List<?> many) {
            for (var item : many) {
                if (item instanceof Map<?, ?> map) {
                    list.add(map);
                }
            }
        }

        return list;
    }

    private static long number(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }

        try {
            return value == null ? 0 : Long.parseLong(String.valueOf(value).strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static List<String> gpus(Object value) {
        var result = new ArrayList<String>();

        for (var gpu : items(value)) {
            var name = or(gpu.get("name"), "");
            var memory = number(gpu.get("memory"));

            if (!name.isEmpty()) {
                // Встроенная графика берёт память у системы — её объём ни о чём не говорит
                result.add(memory >= 1L << 30 && !name.contains("(TM) Graphics")
                        ? name + " · " + size(memory) : name);
            }
        }

        return result;
    }

    /** «2 × 16 ГБ DDR5 6000 МГц»; разные планки — через запятую. */
    static String ram(Object value) {
        var groups = new java.util.LinkedHashMap<String, Integer>();

        for (var stick : items(value)) {
            var size = number(stick.get("size"));

            if (size <= 0) {
                continue;
            }

            var type = switch ((int) number(stick.get("type"))) {
                case 20 -> " DDR";
                case 21 -> " DDR2";
                case 24 -> " DDR3";
                case 26 -> " DDR4";
                case 34 -> " DDR5";
                case 35 -> " LPDDR5";
                default -> "";
            };
            var speed = number(stick.get("speed"));
            groups.merge(size(size) + type + (speed > 0 ? " " + speed + " МГц" : ""), 1, Integer::sum);
        }

        var parts = new ArrayList<String>();
        groups.forEach((what, count) -> parts.add(count > 1 ? count + " × " + what : what));
        return String.join(", ", parts);
    }

    static List<String> drives(Object value) {
        var result = new ArrayList<String>();

        for (var drive : items(value)) {
            var name = or(drive.get("name"), "");

            if (name.isEmpty()) {
                continue;
            }

            var media = or(drive.get("media"), "");
            var bus = or(drive.get("bus"), "");
            var kind = bus.equalsIgnoreCase("NVMe") ? "NVMe SSD"
                    : media.equalsIgnoreCase("SSD") ? "SSD"
                    : media.equalsIgnoreCase("HDD") ? "HDD"
                    : bus.equalsIgnoreCase("USB") ? "USB" : "";
            var size = number(drive.get("size"));
            result.add(name + (size > 0 ? " · " + size(size) : "") + (kind.isEmpty() ? "" : " · " + kind));
        }

        return result;
    }

    /** Мониторы — их Java знает сама: разрешение и частота. */
    static List<String> displays() {
        var result = new ArrayList<String>();

        try {
            for (var screen : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                var mode = screen.getDisplayMode();
                result.add(mode.getWidth() + " × " + mode.getHeight()
                        + (mode.getRefreshRate() > 0 ? " · " + mode.getRefreshRate() + " Гц" : ""));
            }
        } catch (java.awt.HeadlessException | Error ignored) {
            // без экрана — без мониторов
        }

        return result;
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
