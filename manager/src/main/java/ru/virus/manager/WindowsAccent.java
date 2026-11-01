package ru.virus.manager;

import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Цвет оформления Windows — для «Цвет как в Windows», как «Использовать цвет
 * акцента Windows» в Динамическом освещении Windows 11. Лежит в реестре:
 * HKCU\Software\Microsoft\Windows\DWM, AccentColor — число 0xAABBGGRR.
 */
final class WindowsAccent {

    private static final Pattern DWORD = Pattern.compile("AccentColor\\s+REG_DWORD\\s+0x([0-9a-fA-F]+)");

    private WindowsAccent() {
    }

    /** Цвет акцента; null — не Windows или не прочитался. */
    static Color read() {
        if (!SystemInfo.isWindows()) {
            return null;
        }

        try {
            var process = new ProcessBuilder("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\DWM", "/v",
                    "AccentColor").redirectErrorStream(true).start();
            var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor(5, TimeUnit.SECONDS);
            return parse(out);
        } catch (Exception e) {
            return null;
        }
    }

    /** Ответ reg query → цвет. Порядок в числе — ABGR: красный в младшем байте. */
    static Color parse(String output) {
        var matcher = DWORD.matcher(output);

        if (!matcher.find()) {
            return null;
        }

        var value = Long.parseLong(matcher.group(1), 16);
        return new Color((int) (value & 0xFF), (int) ((value >> 8) & 0xFF), (int) ((value >> 16) & 0xFF));
    }
}
