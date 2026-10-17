package ru.virus.manager;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Консоль одного процесса: последние строки в памяти для панели и полный
 * журнал в файле — чтобы было что посмотреть и после перезапуска.
 */
final class LogBuffer {

    /** Сколько строк держать в памяти. Дальше — только в файле. */
    static final int KEEP = 3000;

    /** Больше — файл откладывается в .1, и журнал начинается заново. */
    private static final long FILE_LIMIT = 5L * 1024 * 1024;

    /** Строка консоли: номер по порядку, время и текст. */
    record Line(long seq, long time, String text) {
    }

    private final ArrayDeque<Line> lines = new ArrayDeque<>();
    private final List<Consumer<Line>> listeners = new CopyOnWriteArrayList<>();
    private final Path file;

    private long seq;
    private Writer writer;

    /** @param file журнал на диске; {@code null} — только в памяти */
    LogBuffer(Path file) {
        this.file = file;
    }

    void add(String text) {
        Line line;

        synchronized (this) {
            line = new Line(++seq, System.currentTimeMillis(), text);
            lines.addLast(line);

            while (lines.size() > KEEP) {
                lines.removeFirst();
            }

            toFile(text);
        }

        for (var listener : listeners) {
            try {
                listener.accept(line);
            } catch (RuntimeException e) {
                // Отвалившийся слушатель — закрытая вкладка браузера; его снимут
                listeners.remove(listener);
            }
        }
    }

    /** Строки после этого номера; 0 — все, что есть в памяти. */
    synchronized List<Line> since(long after) {
        var result = new ArrayList<Line>();

        for (var line : lines) {
            if (line.seq() > after) {
                result.add(line);
            }
        }

        return result;
    }

    synchronized void clear() {
        lines.clear();
    }

    void listen(Consumer<Line> listener) {
        listeners.add(listener);
    }

    void unlisten(Consumer<Line> listener) {
        listeners.remove(listener);
    }

    private void toFile(String text) {
        if (file == null) {
            return;
        }

        try {
            if (writer == null) {
                Files.createDirectories(file.getParent());
                rotate();
                writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }

            writer.write(text);
            writer.write(System.lineSeparator());
            writer.flush();

            if (Files.size(file) > FILE_LIMIT) {
                writer.close();
                writer = null;
            }
        } catch (IOException e) {
            // Без файла консоль в панели всё равно работает — не мешаем процессу
            writer = null;
        }
    }

    private void rotate() throws IOException {
        if (Files.isRegularFile(file) && Files.size(file) > FILE_LIMIT) {
            Files.move(file, file.resolveSibling(file.getFileName() + ".1"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    synchronized void close() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
                // закрываемся — и так и так
            }
            writer = null;
        }
    }
}
