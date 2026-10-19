package ru.virus.manager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Какие USB-устройства с подсветкой подключены и кто ими может управлять.
 *
 * <p>Windows 11 сама красит устройства с поддержкой «Динамического освещения»
 * (HID LampArray) — без программ производителя. Остальным нужен либо OpenRGB,
 * либо родная программа; VID:PID из этого списка — то, по чему их узнают.
 */
final class LightScan {

    /**
     * @param lamps умеет ли «Динамическое освещение» Windows
     */
    record Device(String vid, String pid, String name, boolean lamps) {

        String id() {
            return vid + ":" + pid;
        }

        /** Похоже ли на устройство с подсветкой — их показываем первыми. */
        boolean likelyLit() {
            return lamps || LIT.matcher(name).find();
        }
    }

    /** Названия, по которым видно клавиатуру, мышь, кулер или контроллер подсветки. */
    private static final Pattern LIT = Pattern.compile(
            "(?iu)jonsbo|shark|keyboard|клавиатур|mouse|мыш|rgb|argb|led|light|lcd|cooler|fan|"
                    + "aura|mystic|polychrome|corsair|razer|logitech|steelseries|hyperx|nzxt|lian");

    private static final Pattern ID = Pattern.compile("(?i)VID_([0-9A-F]{4}).{0,3}PID_([0-9A-F]{4})");

    /** Корневые концентраторы, Bluetooth и прочее, где подсветки не бывает. */
    private static final Pattern BORING = Pattern.compile(
            "(?iu)root hub|корневой|usb hub|концентратор|host controller|bluetooth|composite device|"
                    + "составное|webcam|камера|audio|звук|mass storage|запоминающее");

    private LightScan() {
    }

    static String script() {
        return "[Console]::OutputEncoding=[Text.Encoding]::UTF8;"
                + "Get-PnpDevice -PresentOnly -ErrorAction SilentlyContinue|"
                + "Where-Object{$_.InstanceId -match 'VID_'}|"
                + "ForEach-Object{[Console]::Out.WriteLine('DEV:'+$_.InstanceId+'|'+$_.FriendlyName)};"
                // «Динамическое освещение»: устройства, которыми Windows умеет красить сама
                + "try{Add-Type -AssemblyName System.Runtime.WindowsRuntime;"
                + "$null=[Windows.Devices.Enumeration.DeviceInformation,Windows.Devices.Enumeration,ContentType=WindowsRuntime];"
                + "$null=[Windows.Devices.Lights.LampArray,Windows.Devices.Lights,ContentType=WindowsRuntime];"
                + "$as=([System.WindowsRuntimeSystemExtensions].GetMethods()|Where-Object{$_.Name -eq 'AsTask' -and "
                + "$_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'})[0];"
                + "$op=[Windows.Devices.Enumeration.DeviceInformation]::FindAllAsync("
                + "[Windows.Devices.Lights.LampArray]::GetDeviceSelector());"
                + "$task=$as.MakeGenericMethod([Windows.Devices.Enumeration.DeviceInformationCollection]).Invoke($null,@($op));"
                + "$null=$task.Wait(8000);"
                + "foreach($d in $task.Result){[Console]::Out.WriteLine('LAMP:'+$d.Id+'|'+$d.Name)}}catch{}";
    }

    /** Найти устройства. Медленно (PowerShell) — не из потока окна. */
    static List<Device> scan() throws Exception {
        var encoded = java.util.Base64.getEncoder().encodeToString(script().getBytes(StandardCharsets.UTF_16LE));
        var process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
                .redirectErrorStream(true)
                .start();
        process.getOutputStream().close();
        var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        if (!process.waitFor(40, TimeUnit.SECONDS)) {
            process.destroyForcibly();
        }

        return parse(out);
    }

    /**
     * Строки скрипта → устройства, по одному на VID:PID: у клавиатуры в
     * диспетчере устройств бывает с десяток записей, берём самое говорящее имя.
     */
    static List<Device> parse(String output) {
        var names = new LinkedHashMap<String, String>();
        var lamps = new java.util.HashSet<String>();

        for (var line : output.lines().map(String::strip).toList()) {
            var lamp = line.startsWith("LAMP:");

            if (!lamp && !line.startsWith("DEV:")) {
                continue;
            }

            var body = line.substring(line.indexOf(':') + 1);
            var bar = body.indexOf('|');
            var instance = bar < 0 ? body : body.substring(0, bar);
            var name = bar < 0 ? "" : body.substring(bar + 1).strip();
            var matcher = ID.matcher(instance);

            if (!matcher.find()) {
                continue;
            }

            var key = matcher.group(1).toUpperCase(Locale.ROOT) + ":" + matcher.group(2).toUpperCase(Locale.ROOT);

            if (lamp) {
                lamps.add(key);
            }

            if (name.isEmpty() || (BORING.matcher(name).find() && !LIT.matcher(name).find())) {
                names.putIfAbsent(key, "");
                continue;
            }

            names.merge(key, name, LightScan::better);
        }

        var result = new ArrayList<Device>();

        names.forEach((key, name) -> {
            if (name.isEmpty() && !lamps.contains(key)) {
                return;
            }

            var parts = key.split(":");
            result.add(new Device(parts[0], parts[1], name.isEmpty() ? "устройство " + key : name,
                    lamps.contains(key)));
        });

        result.sort(java.util.Comparator.comparing((Device device) -> !device.lamps())
                .thenComparing(device -> !device.likelyLit())
                .thenComparing(Device::name));
        return result;
    }

    /** «ATTACK SHARK X98» лучше, чем «HID-совместимое устройство». */
    private static String better(String old, String candidate) {
        if (old.isEmpty()) {
            return candidate;
        }

        var oldGeneric = generic(old);
        var newGeneric = generic(candidate);

        if (oldGeneric != newGeneric) {
            return oldGeneric ? candidate : old;
        }

        return old;
    }

    private static boolean generic(String name) {
        var lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("hid") || lower.contains("hid-") || lower.contains("совместим")
                || lower.contains("compliant") || lower.startsWith("usb input") || lower.startsWith("устройство ввода")
                || lower.contains("vendor-defined") || lower.contains("определено поставщиком");
    }
}
