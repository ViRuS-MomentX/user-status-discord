package ru.virus.voicebridge;

/**
 * Настройки моста между каналом Discord и группой Telegram.
 *
 * @param channel канал Discord, чьи сообщения уходят в Telegram
 * @param token токен телеграм-бота от @BotFather
 * @param chat куда писать: @имя публичной группы или её числовой ID
 * @param files переносить ли картинки и файлы, а не только текст
 * @param maxMegabytes предел на один файл; у Telegram свой в 50 МБ
 * @param proxy «хост:порт» HTTP-прокси, если до Telegram не достучаться напрямую
 */
public record BridgeSettings(boolean enabled, long channel, String token, String chat,
                             boolean files, int maxMegabytes, String proxy) {

    public boolean isUsable() {
        return enabled && channel != 0 && !token.isBlank() && !chat.isBlank();
    }
}
