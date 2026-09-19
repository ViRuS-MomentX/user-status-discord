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
 * @param webhook ссылка вебхука Discord: с ней сообщения из Telegram выходят под
 *                именем и аватаркой написавшего, а не от имени бота
 * @param avatars подставлять ли аватарки из Telegram
 * @param api свой сервер Bot API вместо официального; пусто —официальный api.telegram.org
 */
public record BridgeSettings(boolean enabled, long channel, String token, String chat,
                             boolean files, int maxMegabytes, String proxy,
                             String webhook, boolean avatars, String api) {

    /** Свой ли сервер Bot API. */
    public boolean hasOwnApi() {
        return !api.isBlank();
    }

    public boolean isUsable() {
        return enabled && channel != 0 && !token.isBlank() && !chat.isBlank();
    }
}
