package ru.virus.voicebridge;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.utils.data.DataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Настоящие сообщения участника — образцы, по которым ИИ перенимает манеру.
 *
 * <p>Лежат в файле рядом с настройками, чтобы после перезапуска не начинать с нуля.
 */
public final class ParodySamples {

    private static final Logger log = LoggerFactory.getLogger(ParodySamples.class);

    /** Больше держать незачем: в подсказку всё равно идёт малая часть. */
    private static final int LIMIT = 1000;

    /** Длиннее — уже не реплика, а простыня, и манеры по ней не понять. */
    private static final int LONGEST = 600;

    private final Path file;
    private final List<String> samples = new ArrayList<>();

    public ParodySamples(Path file) {
        this.file = file;
    }

    public synchronized void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }

        try {
            var array = DataArray.fromJson(Files.readString(file, StandardCharsets.UTF_8));

            for (var i = 0; i < array.length(); i++) {
                samples.add(array.getString(i, ""));
            }

            samples.removeIf(String::isBlank);
        } catch (IOException | RuntimeException e) {
            samples.clear();

            // Откладываем в сторону, а не затираем первым же новым сообщением:
            // вдруг там месяцы переписки, которые ещё можно спасти руками
            var aside = file.resolveSibling(file.getFileName() + ".broken");

            try {
                Files.move(file, aside, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailed) {
                aside = file;
            }

            log.error("Не прочитались образцы из {}: {}. Файл отложен как {}, начинаю с нуля.",
                    file.getFileName(), e.getMessage(), aside.getFileName());
        }
    }

    /**
     * Забирает сообщения участника из экспорта Telegram Desktop.
     *
     * <p>Экспорт делается так: открыть группу → ⋮ → Экспорт истории чата →
     * формат JSON. Получится папка с файлом result.json.
     *
     * @return сколько сообщений нашлось
     */
    public synchronized int importExport(Path export, long target) throws IOException {
        var root = DataObject.fromJson(Files.readString(export, StandardCharsets.UTF_8));
        var messages = root.getArray("messages");
        var wanted = "user" + target;
        var found = new ArrayList<String>();

        for (var i = 0; i < messages.length(); i++) {
            var message = messages.getObject(i);

            if (!"message".equals(message.getString("type", ""))
                    || !wanted.equals(message.getString("from_id", ""))) {
                continue;
            }

            var text = flatten(message);

            if (fits(text)) {
                found.add(text.trim());
            }
        }

        // Экспорт идёт от старого к новому, а свежие держим в конце
        samples.addAll(0, found);
        trim();
        save();
        return found.size();
    }

    /**
     * Текст сообщения из экспорта.
     *
     * <p>Простой текст лежит строкой, а с разметкой — массивом кусков, где
     * обычные куски строки, а ссылки и жирное — объекты с полем text.
     */
    private static String flatten(DataObject message) {
        if (message.isType("text", DataType.STRING)) {
            return message.getString("text", "");
        }

        if (!message.isType("text", DataType.ARRAY)) {
            return "";
        }

        var parts = message.getArray("text");
        var text = new StringBuilder();

        for (var i = 0; i < parts.length(); i++) {
            if (parts.isType(i, DataType.STRING)) {
                text.append(parts.getString(i));
            } else if (parts.isType(i, DataType.OBJECT)) {
                text.append(parts.getObject(i).getString("text", ""));
            }
        }

        return text.toString();
    }

    private static boolean fits(String text) {
        var trimmed = text.trim();
        // Команды ботам — не речь человека
        return !trimmed.isEmpty() && trimmed.length() <= LONGEST && !trimmed.startsWith("/");
    }

    public synchronized void add(String text) {
        if (!fits(text)) {
            return;
        }

        samples.add(text.trim());
        trim();
        save();
    }

    /**
     * Стирает всё собранное — и в памяти, и на диске.
     *
     * @return сколько было
     */
    public synchronized int clear() {
        var count = samples.size();
        samples.clear();
        save();
        return count;
    }

    public synchronized int size() {
        return samples.size();
    }

    /** Копия, чтобы перебирать её без замка. */
    public synchronized List<String> all() {
        return List.copyOf(samples);
    }

    private void trim() {
        while (samples.size() > LIMIT) {
            samples.removeFirst();
        }
    }

    private void save() {
        var array = DataArray.empty();
        samples.forEach(array::add);

        try {
            var temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, array.toString(), StandardCharsets.UTF_8);
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("Не сохранились образцы в {}: {}", file.getFileName(), e.getMessage());
        }
    }
}
