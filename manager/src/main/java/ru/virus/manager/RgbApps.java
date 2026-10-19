package ru.virus.manager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Программы для подсветки и экранов разных фирм: что уже стоит, как
 * поставить (winget) и как открыть.
 *
 * <p>Установленное ищем через Get-StartApps — так видны и обычные программы,
 * и программы из Microsoft Store (MSI Center ставится оттуда), а открываются
 * они одинаково: shell:AppsFolder\ID.
 */
final class RgbApps {

    /**
     * @param ids       идентификаторы winget — пробуем по очереди
     * @param storeName точное имя для поиска в winget, если ID не подошли
     * @param page      где скачать вручную, если winget не справился
     * @param match     как программа называется в меню «Пуск» (строчными)
     * @param launch    чем открыть, если в «Пуске» её нет (например, steam://…)
     */
    record App(String name, String purpose, List<String> ids, String storeName, String page,
               List<String> match, String launch) {

        boolean installable() {
            return !ids.isEmpty() || !storeName.isEmpty();
        }
    }

    static final List<App> ALL = List.of(
            new App("SignalRGB", "одна подсветка на всё: плата MSI, память XPG, видеокарта GIGABYTE, кулеры",
                    List.of("WhirlwindFX.SignalRgb"), "", "https://signalrgb.com/download/",
                    List.of("signalrgb"), ""),
            new App("OpenRGB", "то же бесплатно и без входа в аккаунт; V.I.R.U.S. красит через него",
                    List.of("OpenRGB.OpenRGB", "CalcProgrammer1.OpenRGB"), "", "https://openrgb.org/releases.html",
                    List.of("openrgb"), ""),
            new App("MSI Center", "родная для платы MSI: Mystic Light — плата, ARGB-разъёмы, память",
                    List.of(), "MSI Center", "https://www.msi.com/Landing/MSI-Center",
                    List.of("msi center"), ""),
            new App("GIGABYTE Control Center", "родная для видеокарты GIGABYTE AERO, если подсветку не взял SignalRGB",
                    List.of("GIGABYTE.GigabyteControlCenter"), "GIGABYTE Control Center",
                    "https://www.gigabyte.com/Support/Consumer/Download", List.of("gigabyte control center", "gcc"), ""),
            new App("AIDA64 Extreme", "датчики на экранах корпуса (SensorPanel); платная, 30 дней бесплатно",
                    List.of("FinalWire.AIDA64.Extreme"), "", "https://www.aida64.com/downloads",
                    List.of("aida64"), ""),
            new App("Wallpaper Engine", "живые обои на экраны, которые Windows видит как мониторы",
                    List.of(), "", "https://store.steampowered.com/app/431960/",
                    List.of("wallpaper engine"), "steam://rungameid/431960"));

    private RgbApps() {
    }

    /** Имя в «Пуске» → его ID для shell:AppsFolder. */
    static Map<String, String> startApps() {
        var script = "[Console]::OutputEncoding=[Text.Encoding]::UTF8;"
                + "Get-StartApps|ForEach-Object{[Console]::Out.WriteLine('APP:'+$_.AppID+'|'+$_.Name)}";
        var encoded = java.util.Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));

        try {
            var process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
                    .redirectErrorStream(true)
                    .start();
            process.getOutputStream().close();
            var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }

            return parse(out);
        } catch (Exception e) {
            return Map.of();
        }
    }

    static Map<String, String> parse(String output) {
        var apps = new LinkedHashMap<String, String>();

        for (var line : output.lines().map(String::strip).toList()) {
            if (line.startsWith("APP:") && line.indexOf('|') > 4) {
                apps.putIfAbsent(line.substring(line.indexOf('|') + 1).strip(), line.substring(4, line.indexOf('|')));
            }
        }

        return apps;
    }

    /**
     * ID в «Пуске» для этой программы; null — не установлена. Ярлыки
     * «Удалить …» и «Uninstall» не в счёт.
     */
    static String find(App app, Map<String, String> start) {
        for (var entry : start.entrySet()) {
            var name = entry.getKey().toLowerCase(Locale.ROOT);

            if (name.contains("uninstall") || name.contains("удал")) {
                continue;
            }

            for (var match : app.match()) {
                if (name.equals(match) || name.startsWith(match + " ") || name.contains(match)
                        && match.length() > 3) {
                    return entry.getValue();
                }
            }
        }

        return null;
    }

    /**
     * Скрипт установки для cmd: ID winget по очереди, потом поиск по имени
     * (в том числе в Microsoft Store), а если ничего не вышло — страница загрузки.
     */
    static String installScript(App app) {
        var flags = " --accept-source-agreements --accept-package-agreements";
        var lines = new StringBuilder("@echo off\r\nchcp 65001 >nul\r\n")
                .append("echo Устанавливаю ").append(app.name()).append(" через winget...\r\n")
                .append("where winget >nul 2>nul || goto page\r\n");

        for (var id : app.ids()) {
            lines.append("winget install --id ").append(id).append(" -e").append(flags)
                    .append(" && goto done\r\n");
        }

        if (!app.storeName().isEmpty()) {
            lines.append("winget install --name \"").append(app.storeName()).append("\" -e").append(flags)
                    .append(" && goto done\r\n");
        }

        return lines.append(":page\r\n")
                .append("echo Через winget не вышло - открываю страницу загрузки.\r\n")
                .append("start \"\" \"").append(app.page()).append("\"\r\n")
                .append("goto end\r\n")
                .append(":done\r\n")
                .append("echo Готово. Вернись в V.I.R.U.S. и нажми \"Проверить\".\r\n")
                .append(":end\r\n")
                .append("pause\r\n")
                .toString();
    }

    /** Окно установки — видимое: winget может спросить разрешение администратора. */
    static void install(App app) throws java.io.IOException {
        var script = Files.createTempFile("virus-install-", ".cmd");
        // cmd читает .cmd в кодировке консоли; chcp 65001 в начале — чтобы русский не поплыл
        Files.writeString(script, installScript(app), StandardCharsets.UTF_8);
        new ProcessBuilder("cmd.exe", "/c", "start", "\"Установка " + app.name() + "\"", "cmd.exe", "/c",
                script.toString()).start();
    }

    static void open(App app, String startId) throws java.io.IOException {
        if (startId != null) {
            new ProcessBuilder("explorer.exe", "shell:AppsFolder\\" + startId).start();
        } else if (!app.launch().isEmpty()) {
            new ProcessBuilder("explorer.exe", app.launch()).start();
        } else {
            throw new java.io.IOException(app.name() + " не установлена");
        }
    }
}
