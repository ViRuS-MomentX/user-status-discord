package ru.virus.manager;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Задачи и идеи: tasks.json в папке V.I.R.U.S.
 *
 * <p>Кнопки «Сохранить» нет: каждая правка записывается сама через полсекунды
 * тишины. Запись идёт во временный файл, сбрасывается на диск и только потом
 * подменяет tasks.json — выключение посреди записи оставит прошлую версию, а не
 * обрывок. Предыдущая версия лежит рядом как tasks.json.bak.
 */
final class TaskStore {

    enum Status {
        IDEA("Идея"), DOING("В работе"), DONE("Готово");

        final String title;

        Status(String title) {
            this.title = title;
        }

        static Status of(Object value) {
            for (var status : values()) {
                if (status.name().equalsIgnoreCase(String.valueOf(value))) {
                    return status;
                }
            }
            return IDEA;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    enum Priority {
        LOW("Низкий"), NORMAL("Обычный"), HIGH("Высокий");

        final String title;

        Priority(String title) {
            this.title = title;
        }

        static Priority of(Object value) {
            for (var priority : values()) {
                if (priority.name().equalsIgnoreCase(String.valueOf(value))) {
                    return priority;
                }
            }
            return NORMAL;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    /** Одна задача. Номер выдаётся раз и навсегда: удалённый номер не достаётся новой. */
    static final class Task {
        final int number;
        String title = "";
        String notes = "";
        String project = "";
        Status status = Status.IDEA;
        Priority priority = Priority.NORMAL;
        final long created;
        long updated;
        /** Прикреплённые фото — имена файлов в task-files/<номер>/. */
        final List<String> images = new ArrayList<>();

        Task(int number, long created) {
            this.number = number;
            this.created = created;
            this.updated = created;
        }

        /** Пункты-галочки в заметках: сколько отмечено и сколько всего. */
        int[] checklist() {
            var done = 0;
            var all = 0;

            for (var line : notes.split("\n")) {
                var trimmed = line.strip();

                if (trimmed.startsWith("[ ]")) {
                    all++;
                } else if (trimmed.startsWith("[x]") || trimmed.startsWith("[X]")) {
                    all++;
                    done++;
                }
            }

            return new int[] { done, all };
        }

        boolean matches(String query) {
            if (query.isBlank()) {
                return true;
            }

            var q = query.strip().toLowerCase();

            if (q.matches("#?\\d+")) {
                return String.valueOf(number).equals(q.replace("#", ""));
            }

            return title.toLowerCase().contains(q) || notes.toLowerCase().contains(q)
                    || project.toLowerCase().contains(q);
        }
    }

    static final long QUIET_MS = 500;

    private final Path file;
    private final List<Task> tasks = new ArrayList<>();
    private int next = 1;

    /** Файл не прочитался: перед первой записью отложить его в сторону, а не затереть. */
    private boolean keepBroken;

    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        var thread = new Thread(runnable, "virus-tasks-save");
        thread.setDaemon(true);
        return thread;
    });
    private ScheduledFuture<?> pending;
    private volatile Consumer<Long> onSaved = time -> { };
    private volatile Consumer<String> onError = error -> { };

    TaskStore(Path file) {
        this.file = file;
    }

    Path file() {
        return file;
    }

    void onSaved(Consumer<Long> listener) {
        onSaved = listener;
    }

    void onError(Consumer<String> listener) {
        onError = listener;
    }

    /**
     * Читает задачи. Испорченный tasks.json (такого быть не должно, но диск
     * есть диск) не теряет всё: берётся tasks.json.bak.
     *
     * @return откуда прочитано: null — файла ещё нет
     */
    synchronized Path load() throws IOException {
        tasks.clear();
        next = 1;
        IOException failure = null;

        for (var candidate : List.of(file, backup())) {
            if (!Files.isRegularFile(candidate)) {
                continue;
            }

            try {
                read(Files.readString(candidate, StandardCharsets.UTF_8));
                return candidate;
            } catch (RuntimeException | IOException e) {
                tasks.clear();
                next = 1;

                if (failure == null) {
                    failure = new IOException(candidate.getFileName() + " не читается: " + e.getMessage());
                }
            }
        }

        if (failure != null) {
            keepBroken = true;
            throw failure;
        }

        return null;
    }

    private void read(String text) {
        var json = Json.object(text);

        for (var item : (List<?>) json.getOrDefault("tasks", List.of())) {
            @SuppressWarnings("unchecked")
            var map = (Map<String, Object>) item;
            var task = new Task(number(map.get("number")), millis(map.get("created")));
            task.title = string(map.get("title"));
            task.notes = string(map.get("notes"));

            if (map.get("images") instanceof List<?> images) {
                images.forEach(image -> task.images.add(String.valueOf(image)));
            }

            task.project = string(map.get("project"));
            task.status = Status.of(map.get("status"));
            task.priority = Priority.of(map.get("priority"));
            task.updated = millis(map.get("updated"));
            tasks.add(task);
            next = Math.max(next, task.number + 1);
        }

        next = Math.max(next, number(json.get("next")));
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long millis(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static int number(Object value) {
        return value instanceof Number number ? (int) Math.min(number.longValue(), Integer.MAX_VALUE) : 0;
    }

    synchronized List<Task> all() {
        return List.copyOf(tasks);
    }

    /** Новая задача — сверху списка. */
    synchronized Task add() {
        var task = new Task(next++, System.currentTimeMillis());
        tasks.addFirst(task);
        changed();
        return task;
    }

    synchronized void remove(Task task) {
        tasks.remove(task);
        changed();

        // Фото удалённой задачи больше никому не нужны
        try {
            deleteFolder(imagesOf(task));
        } catch (IOException e) {
            onError.accept("Фото задачи #" + task.number + " не удалились: " + e.getMessage());
        }
    }

    /** Папка с фото задачи. */
    Path imagesOf(Task task) {
        return file.resolveSibling("task-files").resolve(String.valueOf(task.number));
    }

    Path image(Task task, String name) {
        return imagesOf(task).resolve(name);
    }

    /** Копирует картинку к задаче. @return имя файла у задачи */
    String attach(Task task, Path source) throws IOException {
        var name = source.getFileName().toString();
        var dot = name.lastIndexOf('.');
        var target = unique(task, dot > 0 ? name.substring(dot).toLowerCase() : ".png");
        Files.createDirectories(target.getParent());
        Files.copy(source, target);
        edit(task, t -> t.images.add(target.getFileName().toString()));
        return target.getFileName().toString();
    }

    /** Картинка из буфера обмена — в png. */
    String attach(Task task, java.awt.image.BufferedImage picture) throws IOException {
        var target = unique(task, ".png");
        Files.createDirectories(target.getParent());
        javax.imageio.ImageIO.write(picture, "png", target.toFile());
        edit(task, t -> t.images.add(target.getFileName().toString()));
        return target.getFileName().toString();
    }

    void detach(Task task, String name) throws IOException {
        edit(task, t -> t.images.remove(name));
        Files.deleteIfExists(image(task, name));
    }

    private Path unique(Task task, String extension) {
        var stamp = new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new java.util.Date());
        var target = imagesOf(task).resolve(stamp + extension);

        for (var i = 2; Files.exists(target); i++) {
            target = imagesOf(task).resolve(stamp + "-" + i + extension);
        }

        return target;
    }

    private static void deleteFolder(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return;
        }

        try (var walk = Files.walk(folder)) {
            for (var path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    /** Правка задачи: всё меняется под замком, чтобы запись не поймала половину. */
    synchronized void edit(Task task, Consumer<Task> change) {
        change.accept(task);
        task.updated = System.currentTimeMillis();
        changed();
    }

    /** Сдвигает задачу вверх (−1) или вниз (+1) по списку. */
    synchronized boolean move(Task task, int step) {
        var index = tasks.indexOf(task);
        var target = index + step;

        if (index < 0 || target < 0 || target >= tasks.size()) {
            return false;
        }

        tasks.remove(index);
        tasks.add(target, task);
        changed();
        return true;
    }

    /** Отметить, что есть что записать: запишется, когда правки стихнут. */
    synchronized void changed() {
        if (pending != null) {
            pending.cancel(false);
        }

        pending = timer.schedule(this::saveQuietly, QUIET_MS, TimeUnit.MILLISECONDS);
    }

    private void saveQuietly() {
        try {
            saveNow();
        } catch (IOException e) {
            onError.accept("Задачи не сохранились: " + e.getMessage());
        }
    }

    /** Записать прямо сейчас — при выходе из программы. */
    void saveNow() throws IOException {
        String text;

        synchronized (this) {
            if (pending != null) {
                pending.cancel(false);
                pending = null;
            }

            text = json();
        }

        write(text);
        onSaved.accept(System.currentTimeMillis());
    }

    synchronized String json() {
        var list = new ArrayList<Object>();

        for (var task : tasks) {
            var map = new LinkedHashMap<String, Object>();
            map.put("number", task.number);
            map.put("title", task.title);
            map.put("notes", task.notes);
            map.put("images", List.copyOf(task.images));
            map.put("project", task.project);
            map.put("status", task.status.name().toLowerCase());
            map.put("priority", task.priority.name().toLowerCase());
            map.put("created", task.created);
            map.put("updated", task.updated);
            list.add(map);
        }

        var root = new LinkedHashMap<String, Object>();
        root.put("next", next);
        root.put("tasks", list);
        return Json.write(root);
    }

    /** Одна запись за раз: две параллельные могли бы подменить файл в обратном порядке. */
    private synchronized void write(String text) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());

        if (keepBroken) {
            for (var old : List.of(file, backup())) {
                if (Files.isRegularFile(old)) {
                    Files.copy(old, old.resolveSibling(old.getFileName() + ".broken"),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }

            keepBroken = false;
        }
        var temp = file.resolveSibling(file.getFileName() + ".tmp");

        try (var channel = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            var bytes = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));

            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }

            // До диска, а не в кэш системы: иначе при отключении питания могло бы не дойти
            channel.force(true);
        }

        if (Files.isRegularFile(file)) {
            Files.copy(file, backup(), StandardCopyOption.REPLACE_EXISTING);
        }

        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path backup() {
        return file.resolveSibling(file.getFileName() + ".bak");
    }

    /** Список текстом — вставить в чат или заметки. */
    static String asText(List<Task> list) {
        var out = new StringBuilder();

        for (var task : list) {
            out.append('#').append(task.number).append(" [").append(task.status.title).append(']');

            if (task.priority == Priority.HIGH) {
                out.append(" [!]");
            }

            if (!task.project.isBlank()) {
                out.append(" (").append(task.project).append(')');
            }

            out.append(' ').append(task.title.isBlank() ? "без названия" : task.title).append('\n');

            for (var line : task.notes.split("\n")) {
                if (!line.isBlank()) {
                    out.append("    ").append(line).append('\n');
                }
            }
        }

        return out.toString();
    }
}
