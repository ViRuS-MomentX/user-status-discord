package ru.virus.manager;

import java.awt.Component;
import java.awt.Dialog;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.Window;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.prefs.Preferences;

/**
 * Настоящий Проводник Windows для выбора файлов, а не его нарисованная
 * Swing-копия. Последняя папка у каждого вида выбора своя и запоминается.
 */
final class NativeFiles {

    private static final Preferences PREFS = Preferences.userNodeForPackage(NativeFiles.class);

    private NativeFiles() {
    }

    /**
     * Открыть файл (или несколько).
     *
     * @param key        под каким именем помнить последнюю папку
     * @param extensions какие расширения показывать, без точки; пусто — любые
     * @return выбранное; пусто — передумали
     */
    static List<Path> open(Component owner, String title, String key, boolean multiple, String... extensions) {
        if (SystemInfo.isWindows()) {
            var picked = explorer(false, title, start(key), multiple, null, extensions);

            if (picked != null) {
                var chosen = picked.stream().filter(path -> fits(path, extensions)).toList();

                if (!chosen.isEmpty()) {
                    PREFS.put(key, chosen.getFirst().getParent().toString());
                }

                return chosen;
            }
        }

        var dialog = dialog(owner, title, FileDialog.LOAD);
        dialog.setMultipleMode(multiple);
        prepare(dialog, key, extensions);
        dialog.setVisible(true);

        var files = dialog.getFiles();

        if (files == null || files.length == 0) {
            return List.of();
        }

        var chosen = Arrays.stream(files).map(file -> file.toPath()).filter(path -> fits(path, extensions)).toList();

        if (!chosen.isEmpty()) {
            PREFS.put(key, chosen.getFirst().getParent().toString());
        }

        return chosen;
    }

    /** Куда сохранить файл. @return null — передумали */
    static Path save(Component owner, String title, String key, String name) {
        if (SystemInfo.isWindows()) {
            var picked = explorer(true, title, start(key), false, name);

            if (picked != null) {
                if (picked.isEmpty()) {
                    return null;
                }

                PREFS.put(key, picked.getFirst().getParent().toString());
                return picked.getFirst();
            }
        }

        var dialog = dialog(owner, title, FileDialog.SAVE);
        prepare(dialog, key);
        dialog.setFile(name);
        dialog.setVisible(true);

        if (dialog.getFile() == null) {
            return null;
        }

        var path = Path.of(dialog.getDirectory(), dialog.getFile());
        PREFS.put(key, path.getParent().toString());
        return path;
    }

    private static FileDialog dialog(Component owner, String title, int mode) {
        var window = owner instanceof Window w ? w : owner == null ? null : javax.swing.SwingUtilities.getWindowAncestor(owner);

        if (window instanceof Dialog d) {
            return new FileDialog(d, title, mode);
        }

        return new FileDialog(window instanceof Frame f ? f : null, title, mode);
    }

    /** С какой папки начать: последняя для этого выбора, иначе «Загрузки». */
    private static Path start(String key) {
        var last = PREFS.get(key, "");
        var start = last.isEmpty() ? Path.of(System.getProperty("user.home"), "Downloads") : Path.of(last);
        return java.nio.file.Files.isDirectory(start) ? start : Path.of(System.getProperty("user.home"));
    }

    /**
     * Современный Проводник Windows — окно выбора файлов из .NET, которое на
     * Windows 10/11 показывается в новом виде. Пока оно открыто, окно V.I.R.U.S.
     * продолжает рисоваться: ждём во вложенном цикле событий.
     *
     * @return выбранное (пусто — отменили); null — не получилось, нужен запасной путь
     */
    private static List<Path> explorer(boolean save, String title, Path folder, boolean multiple, String name,
                                       String... extensions) {
        var script = script(save, title, folder, multiple, name, extensions);
        var encoded = java.util.Base64.getEncoder().encodeToString(script.getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
        var result = new java.util.concurrent.atomic.AtomicReference<List<Path>>();
        var loop = java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();

        var thread = new Thread(() -> {
            try {
                var process = new ProcessBuilder("powershell.exe", "-NoProfile", "-STA", "-NonInteractive",
                        "-EncodedCommand", encoded).redirectErrorStream(true).start();
                process.getOutputStream().close();
                var out = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

                if (process.waitFor() == 0) {
                    result.set(out.lines().map(String::strip).filter(line -> line.startsWith("FILE:"))
                            .map(line -> Path.of(line.substring(5))).toList());
                }
            } catch (Exception ignored) {
                // PowerShell не запустился — останется запасной диалог
            } finally {
                loop.exit();
            }
        }, "virus-explorer");
        thread.setDaemon(true);
        thread.start();

        if (java.awt.EventQueue.isDispatchThread()) {
            loop.enter();
        } else {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        return result.get();
    }

    static String script(boolean save, String title, Path folder, boolean multiple, String name, String... extensions) {
        var filter = extensions.length == 0 ? "Все файлы|*.*"
                : "Подходящие файлы (" + String.join(", ", Arrays.stream(extensions).map(ext -> "*." + ext).toList())
                        + ")|" + String.join(";", Arrays.stream(extensions).map(ext -> "*." + ext).toList())
                        + "|Все файлы|*.*";
        return "[Console]::OutputEncoding=[Text.Encoding]::UTF8;"
                + "Add-Type -AssemblyName System.Windows.Forms;"
                + "[System.Windows.Forms.Application]::EnableVisualStyles();"
                + "$owner=New-Object System.Windows.Forms.Form -Property @{TopMost=$true;ShowInTaskbar=$false;"
                + "Width=1;Height=1;StartPosition='CenterScreen';Opacity=0};$owner.Show();$owner.Activate();"
                + "$d=New-Object System.Windows.Forms." + (save ? "SaveFileDialog" : "OpenFileDialog") + ";"
                + "$d.Title=" + quote(title) + ";"
                + "$d.InitialDirectory=" + quote(folder.toString()) + ";"
                + "$d.Filter=" + quote(filter) + ";"
                + (save ? "$d.FileName=" + quote(name == null ? "" : name) + ";"
                        : "$d.Multiselect=$" + multiple + ";")
                + "if($d.ShowDialog($owner) -eq [System.Windows.Forms.DialogResult]::OK){"
                + "foreach($f in $d.FileNames){[Console]::Out.WriteLine('FILE:'+$f)}};"
                + "$owner.Close()";
    }

    private static String quote(String text) {
        return "'" + text.replace("'", "''") + "'";
    }

    private static void prepare(FileDialog dialog, String key, String... extensions) {
        dialog.setDirectory(start(key).toString());

        if (extensions.length > 0) {
            // Проводник Windows понимает фильтр «*.zip;*.jar» прямо в имени файла,
            // остальные системы — через FilenameFilter
            dialog.setFile(String.join(";", Arrays.stream(extensions).map(ext -> "*." + ext).toList()));
            dialog.setFilenameFilter((dir, name) -> fits(Path.of(name), extensions));
        }
    }

    static boolean fits(Path path, String... extensions) {
        if (extensions.length == 0) {
            return true;
        }

        var name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Arrays.stream(extensions).anyMatch(ext -> name.endsWith("." + ext.toLowerCase(Locale.ROOT)));
    }
}
