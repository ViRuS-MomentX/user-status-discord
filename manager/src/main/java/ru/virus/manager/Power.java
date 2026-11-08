package ru.virus.manager;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Питание Windows для подставки сна: схема электропитания и мониторы. */
final class Power {

    /** «Экономия энергии» — встроенная схема Windows. */
    static final String SAVER = "a1841308-3541-4fab-bc81-f71556f20b4a";

    private static final Pattern GUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private Power() {
    }

    private static String run(String... command) {
        try {
            var process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getOutputStream().close();
            var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor(15, TimeUnit.SECONDS);
            return out;
        } catch (Exception e) {
            return "";
        }
    }

    /** GUID текущей схемы из «powercfg /getactivescheme»; пусто — не узнали. */
    static String activePlan() {
        return SystemInfo.isWindows() ? guid(run("powercfg", "/getactivescheme")) : "";
    }

    static String guid(String text) {
        var matcher = GUID.matcher(text);
        return matcher.find() ? matcher.group().toLowerCase() : "";
    }

    static void setPlan(String guid) {
        if (SystemInfo.isWindows() && GUID.matcher(guid).matches()) {
            run("powercfg", "/setactive", guid);
        }
    }

    /** Мониторы: погасить (как «сон дисплея») или разбудить лёгким движением мыши. */
    static void monitors(boolean on) {
        if (!SystemInfo.isWindows()) {
            return;
        }

        var script = "Add-Type -Name P -Namespace V -MemberDefinition '"
                + "[DllImport(\"user32.dll\")] public static extern bool PostMessageW(IntPtr h, uint m, IntPtr w, IntPtr l);"
                + "[DllImport(\"user32.dll\")] public static extern void mouse_event(int f, int x, int y, int d, int e);'; "
                + (on ? "[V.P]::mouse_event(1, 0, 1, 0, 0); Start-Sleep -Milliseconds 50; [V.P]::mouse_event(1, 0, -1, 0, 0)"
                : "[V.P]::PostMessageW([IntPtr]0xFFFF, 0x0112, [IntPtr]0xF170, [IntPtr]2) | Out-Null");
        run("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script);
    }
}
