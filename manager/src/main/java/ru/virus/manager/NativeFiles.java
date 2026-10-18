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

    private static void prepare(FileDialog dialog, String key, String... extensions) {
        var last = PREFS.get(key, "");
        var start = last.isEmpty() ? Path.of(System.getProperty("user.home"), "Downloads") : Path.of(last);

        if (!java.nio.file.Files.isDirectory(start)) {
            start = Path.of(System.getProperty("user.home"));
        }

        dialog.setDirectory(start.toString());

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
