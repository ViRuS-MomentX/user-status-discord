package ru.virus.voicebridge;

import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Кого как называет участник: «Санёк» вместо «Александр Петров».
 *
 * <p>Учится сам — по обращениям в начале его ответов: ответил Пете со «Санёк,
 * го» — значит, «Санёк» кандидат в прозвища Пети. После двух таких раз
 * прозвище считается настоящим. Можно и задать вручную командой. Хранится в
 * папке parody-memory, чтобы переживать перезапуски.
 */
final class ParodyNames {

    private static final Logger log = LoggerFactory.getLogger(ParodyNames.class);

    /** Сколько раз обращение должно встретиться, чтобы считаться прозвищем. */
    static final int SURE = 2;

    /** Обращение в начале реплики: «Санёк, …», «Петруха! …», «Димон ты где». */
    private static final Pattern ADDRESS = Pattern.compile("^\\s*([\\p{L}][\\p{L}-]{1,19})\\s*[,!]");

    /** Слова, которые стоят в начале с запятой, но обращением не являются. */
    static final Set<String> NOT_NAMES = Set.of(
            "ну", "да", "нет", "не", "ок", "окей", "ладно", "короче", "слушай", "слушайте", "смотри",
            "блин", "бля", "блять", "ага", "угу", "ну-ка", "эй", "так", "вот", "кстати", "кароче",
            "ахах", "хаха", "лол", "ого", "ух", "ой", "эх", "мда", "хм", "ясно", "понятно", "спасибо",
            "привет", "здарова", "пока", "братан", "бро", "чел", "пацаны", "ребят", "ребята", "народ",
            "все", "всем", "чё", "че", "что", "как", "кто", "где", "когда", "зачем", "почему", "ну-ну",
            "конечно", "типа", "вообще", "кароч", "алло", "аллё", "э", "a", "ok", "yes", "no", "bro");

    /** Что известно о человеке. */
    static final class Person {
        String name = "";
        final Map<String, Integer> heard = new LinkedHashMap<>();
        final Set<String> manual = new LinkedHashSet<>();

        /** Прозвища, в которых уверены: заданные вручную и услышанные не раз. */
        List<String> nicknames() {
            var result = new ArrayList<>(manual);

            heard.entrySet().stream()
                    .filter(entry -> entry.getValue() >= SURE)
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .map(Map.Entry::getKey)
                    .filter(nick -> !result.contains(nick))
                    .forEach(result::add);

            return result;
        }
    }

    private final Path file;
    private final Map<Long, Person> people = new LinkedHashMap<>();

    ParodyNames(Path file) {
        this.file = file;
    }

    synchronized void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }

        try {
            var root = DataObject.fromJson(Files.readString(file, StandardCharsets.UTF_8));

            for (var key : root.keys()) {
                var json = root.getObject(key);
                var person = new Person();
                person.name = json.getString("name", "");

                if (json.hasKey("heard")) {
                    var heard = json.getObject("heard");
                    heard.keys().forEach(nick -> person.heard.put(nick, heard.getInt(nick, 0)));
                }

                if (json.hasKey("manual")) {
                    var manual = json.getArray("manual");
                    for (var i = 0; i < manual.length(); i++) {
                        person.manual.add(manual.getString(i));
                    }
                }

                people.put(Long.parseLong(key), person);
            }
        } catch (IOException | RuntimeException e) {
            var aside = file.resolveSibling(file.getFileName() + ".broken");

            try {
                Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailed) {
                aside = file;
            }

            log.error("Не прочиталась память имён {}: {}. Отложена как {}, начинаю с нуля.",
                    file.getFileName(), e.getMessage(), aside.getFileName());
        }
    }

    /** Запоминает, как человека зовут в Telegram, — чтобы было с чем сопоставить прозвище. */
    synchronized void seen(long id, String name) {
        if (id == 0 || name.isBlank()) {
            return;
        }

        var person = people.computeIfAbsent(id, key -> new Person());

        if (!name.equals(person.name)) {
            person.name = name;
            save();
        }
    }

    /**
     * Учится по ответу участника: если он начал с обращения, это, похоже,
     * прозвище того, кому он ответил.
     *
     * @return услышанное прозвище или {@code null}
     */
    synchronized String learn(long addresseeId, String text) {
        if (addresseeId == 0) {
            return null;
        }

        var match = ADDRESS.matcher(text);

        if (!match.find()) {
            return null;
        }

        var word = match.group(1);

        if (NOT_NAMES.contains(word.toLowerCase(Locale.ROOT))) {
            return null;
        }

        var person = people.computeIfAbsent(addresseeId, key -> new Person());
        var count = person.heard.merge(word, 1, Integer::sum);

        if (count == SURE) {
            log.info("Запомнил прозвище: «{}» — так зовут {}.", word,
                    person.name.isBlank() ? "собеседника " + addresseeId : person.name);
        }

        save();
        return word;
    }

    /** Прозвище вручную — командой. */
    synchronized void teach(long id, String nickname) {
        var person = people.computeIfAbsent(id, key -> new Person());
        person.manual.add(nickname.strip());
        save();
    }

    /** Забыть прозвище — и заданное, и услышанное. */
    synchronized boolean forget(long id, String nickname) {
        var person = people.get(id);

        if (person == null) {
            return false;
        }

        var removed = person.manual.remove(nickname) | person.heard.remove(nickname) != null;

        if (removed) {
            save();
        }

        return removed;
    }

    synchronized List<String> nicknames(long id) {
        var person = people.get(id);
        return person == null ? List.of() : person.nicknames();
    }

    synchronized String name(long id) {
        var person = people.get(id);
        return person == null ? "" : person.name;
    }

    /**
     * Строки для подсказки ИИ: «Петя Иванов — Петька, Петруха». Только люди,
     * о которых что-то известно, и сначала те, кто сейчас в разговоре.
     */
    synchronized String describe(Collection<Long> active, int limit) {
        var lines = new ArrayList<String>();
        // Только те, кто сейчас в разговоре: имена остальных сбивали ИИ
        var order = new LinkedHashSet<>(active);

        for (var id : order) {
            var person = people.get(id);

            if (person == null || person.name.isBlank()) {
                continue;
            }

            var nicks = person.nicknames();

            if (!nicks.isEmpty()) {
                lines.add(person.name + " — " + String.join(", ", nicks));
            }

            if (lines.size() >= limit) {
                break;
            }
        }

        return String.join("\n", lines);
    }

    /**
     * Все имена каждого: как его видно в Telegram, его прозвища и как его
     * велел звать хозяин. Нужно, чтобы узнать обращение к человеку.
     */
    synchronized java.util.Map<Long, List<String>> allNames(List<PeopleBook.Entry> book) {
        var result = new java.util.LinkedHashMap<Long, List<String>>();

        people.forEach((id, person) -> {
            var list = new ArrayList<String>();
            list.add(person.name);
            list.addAll(person.nicknames());
            result.put(id, list);
        });

        for (var entry : book) {
            result.computeIfAbsent(entry.id(), id -> new ArrayList<>()).add(entry.name());
        }

        return result;
    }

    /** Для /parody_names: все, о ком что-то известно. */
    synchronized String report() {
        var lines = new ArrayList<String>();

        for (var person : people.values()) {
            var nicks = person.nicknames();

            if (!nicks.isEmpty() && !person.name.isBlank()) {
                lines.add(person.name + " — " + String.join(", ", nicks));
            }
        }

        return lines.isEmpty() ? "" : String.join("\n", lines);
    }

    private void save() {
        var root = DataObject.empty();

        people.forEach((id, person) -> {
            var heard = DataObject.empty();
            person.heard.forEach(heard::put);
            var manual = net.dv8tion.jda.api.utils.data.DataArray.empty();
            person.manual.forEach(manual::add);
            root.put(String.valueOf(id), DataObject.empty()
                    .put("name", person.name)
                    .put("heard", heard)
                    .put("manual", manual));
        });

        try {
            Files.createDirectories(file.getParent());
            var temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, root.toPrettyString(), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("Не сохранилась память имён: {}", e.getMessage());
        }
    }
}
