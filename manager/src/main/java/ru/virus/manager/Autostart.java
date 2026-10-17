package ru.virus.manager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Автозапуск V.I.R.U.S. при входе в Windows.
 *
 * <p>Через папку «Автозагрузка», а не планировщик: туда может писать сам
 * пользователь, без прав администратора, и автозапуск включается одной
 * галочкой в панели. Кладём туда маленький скрипт, который запускает
 * V.I.R.U.S. без окна консоли.
 */
final class Autostart {

    private static final String FILE = "V.I.R.U.S.vbs";

    /** Задача старого скрипта tools/install-autostart.ps1. */
    static final String OLD_TASK = "VoiceBridgeBot";

    private final Path startupFolder;
    private final String javaw;
    private final Path jar;

    Autostart(Path startupFolder, String javaw, Path jar) {
        this.startupFolder = startupFolder;
        this.javaw = javaw;
        this.jar = jar;
    }

    /** Папка автозагрузки текущего пользователя; {@code null} — не Windows. */
    static Path windowsStartupFolder() {
        var appData = System.getenv("APPDATA");

        if (appData == null || !System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return null;
        }

        return Path.of(appData, "Microsoft", "Windows", "Start Menu", "Programs", "Startup");
    }

    boolean isSupported() {
        return startupFolder != null && jar != null;
    }

    boolean isEnabled() {
        return isSupported() && Files.isRegularFile(startupFolder.resolve(FILE));
    }

    void enable() throws IOException {
        if (!isSupported()) {
            throw new IOException("автозапуск умею ставить только на Windows и только из virus.jar");
        }

        Files.createDirectories(startupFolder);
        Files.writeString(startupFolder.resolve(FILE), script(), StandardCharsets.UTF_16LE);
    }

    void disable() throws IOException {
        if (startupFolder != null) {
            Files.deleteIfExists(startupFolder.resolve(FILE));
        }
    }

    /**
     * Скрипт для Windows Script Host. Ноль во втором аргументе Run — без окна,
     * False — не ждать. Кавычки удвоены: так VBScript пишет кавычку внутри строки,
     * а без них путь с пробелами развалился бы на части.
     *
     * <p>Пишется в UTF-16 с меткой BOM: только так Windows Script Host читает
     * русские буквы в пути к папке.
     */
    String script() {
        // --background: при входе в Windows панель не открывается сама, только значок в трее
        var command = "\"\"" + javaw + "\"\" -jar \"\"" + jar + "\"\" --background";
        return "﻿' Запускает V.I.R.U.S. при входе в Windows. Удалить файл — убрать автозапуск.\r\n"
                + "Set shell = CreateObject(\"WScript.Shell\")\r\n"
                + "shell.CurrentDirectory = \"" + jar.getParent() + "\"\r\n"
                + "shell.Run \"" + command + "\", 0, False\r\n";
    }

    /**
     * Стоит ли ещё задача старого скрипта автозапуска. Она запускает бота мимо
     * V.I.R.U.S., и тогда работали бы две копии с одним токеном.
     */
    static boolean oldTaskExists() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return false;
        }

        try {
            var query = new ProcessBuilder("schtasks", "/Query", "/TN", OLD_TASK)
                    .redirectErrorStream(true)
                    .start();
            query.getInputStream().readAllBytes();

            return query.waitFor(10, TimeUnit.SECONDS) && query.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
