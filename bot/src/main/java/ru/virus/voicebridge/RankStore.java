package ru.virus.voicebridge;

import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Балансы и ранги участников.
 *
 * <p>Хранится всё в одном JSON-файле рядом с конфигом. Базы данных тут взялись бы ради
 * пары чисел на человека, а DataObject и так приезжает вместе с JDA, так что своя
 * зависимость ради хранения не нужна.
 *
 * <p>Все методы синхронизированы: пишет поток начисления монет, читает поток событий
 * Discord, и порядок между ними не гарантирован.
 */
public final class RankStore {

    private static final Logger log = LoggerFactory.getLogger(RankStore.class);

    /** Данные одного участника. */
    public static final class Entry {
        /** Накопленные монеты. */
        public int coins;
        /** Минуты в голосовых каналах, ещё не превратившиеся в монету. */
        public int minutes;
        /** Сколько повышений сделано: 0 — стартовая роль, 1 — первая ступень, и так далее. */
        public int rank;

        Entry copy() {
            var copy = new Entry();
            copy.coins = coins;
            copy.minutes = minutes;
            copy.rank = rank;
            return copy;
        }
    }

    private final Path file;
    private final Map<String, Entry> entries = new HashMap<>();
    private boolean dirty = false;

    public RankStore(Path file) {
        this.file = file;
        load();
    }

    /**
     * Копия данных участника. Именно копия: вызывающий код читает её без блокировки,
     * пока начисление монет может менять оригинал.
     */
    public synchronized Entry get(String userId) {
        return entries.computeIfAbsent(userId, id -> new Entry()).copy();
    }

    /**
     * Засчитывает участнику минуту в голосовом канале и превращает накопленное в монеты.
     *
     * @param perCoin сколько минут стоит одна монета
     * @return сколько монет начислено этой минутой, обычно ноль
     */
    public synchronized int addMinute(String userId, int perCoin) {
        var entry = entries.computeIfAbsent(userId, id -> new Entry());
        entry.minutes++;
        dirty = true;

        if (entry.minutes < perCoin) {
            return 0;
        }

        var earned = entry.minutes / perCoin;
        entry.minutes %= perCoin;
        entry.coins += earned;

        return earned;
    }

    /**
     * Списывает цену повышения и поднимает ранг, если монет хватает.
     *
     * <p>Проверка и списание идут одной операцией: иначе два быстрых нажатия кнопки
     * успели бы пройти проверку оба и увести баланс в минус.
     *
     * @return <code>true</code>, если списание прошло
     */
    public synchronized boolean promote(String userId, int price) {
        var entry = entries.computeIfAbsent(userId, id -> new Entry());

        if (entry.coins < price) {
            return false;
        }

        entry.coins -= price;
        entry.rank++;
        dirty = true;

        return true;
    }

    /**
     * Откатывает повышение, если выдать роль так и не удалось.
     */
    public synchronized void refund(String userId, int price) {
        var entry = entries.computeIfAbsent(userId, id -> new Entry());
        entry.coins += price;
        entry.rank--;
        dirty = true;
    }

    /**
     * Записывает файл, если с прошлого раза что-то изменилось.
     */
    public synchronized void save() {
        if (!dirty) {
            return;
        }

        var root = DataObject.empty();
        entries.forEach((id, entry) -> root.put(id, DataObject.empty()
                .put("coins", entry.coins)
                .put("minutes", entry.minutes)
                .put("rank", entry.rank)));

        try {
            // Пишем через временный файл: выключение питания на середине записи
            // иначе оставило бы обрезанный JSON вместо всех балансов.
            var temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temp, root.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (IOException e) {
            log.error("Не удалось сохранить балансы в {}: {}", file, e.getMessage());
        }
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }

        try {
            var root = DataObject.fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));

            for (var id : root.keys()) {
                var raw = root.getObject(id);
                var entry = new Entry();
                entry.coins = raw.getInt("coins", 0);
                entry.minutes = raw.getInt("minutes", 0);
                entry.rank = raw.getInt("rank", 0);
                entries.put(id, entry);
            }

            log.info("Загружены балансы: {} участников.", entries.size());
        } catch (Exception e) {
            // Лучше начать с пустого файла, чем не запуститься совсем; старый файл
            // при этом не трогаем, чтобы данные можно было вытащить руками.
            log.error("Не удалось прочитать {}: {}. Начинаю с пустых балансов.", file, e.getMessage());
        }
    }
}
