package ru.virus.voicebridge;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Память о том, какое сообщение в Telegram какому в Discord соответствует.
 *
 * <p>Нужна ответам: человек отвечает на пересланное сообщение, и мост должен
 * понять, чему оно было копией на той стороне, — иначе ответ придёт отдельной
 * репликой без связи с тем, на что отвечали.
 *
 * <p>Память нарочно ограничена и живёт только до перезапуска. Хранить её вечно
 * незачем: отвечают почти всегда на свежее, а список без предела рос бы всё
 * время работы бота.
 */
public final class BridgeLinks {

    /** Сколько пар помним. Тысячи сообщений хватает на несколько дней болтовни. */
    private static final int REMEMBERED = 5000;

    private final Map<Long, Long> discordByTelegram = bounded();
    private final Map<Long, Long> telegramByDiscord = bounded();

    /** Запоминает, что это одно и то же сообщение по обе стороны моста. */
    public synchronized void remember(long telegramId, long discordId) {
        if (telegramId == 0 || discordId == 0) {
            return;
        }

        discordByTelegram.put(telegramId, discordId);
        telegramByDiscord.put(discordId, telegramId);
    }

    /** Чему в Discord соответствует сообщение Telegram; 0 — не знаем. */
    public synchronized long discordFor(long telegramId) {
        return discordByTelegram.getOrDefault(telegramId, 0L);
    }

    /** Чему в Telegram соответствует сообщение Discord; 0 — не знаем. */
    public synchronized long telegramFor(long discordId) {
        return telegramByDiscord.getOrDefault(discordId, 0L);
    }

    /** Список, который сам забывает самое старое, когда набирается лишнее. */
    private static Map<Long, Long> bounded() {
        return new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Long> eldest) {
                return size() > REMEMBERED;
            }
        };
    }
}
