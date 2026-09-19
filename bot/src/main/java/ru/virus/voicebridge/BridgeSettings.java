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

    /**
     * Живёт ли свой сервер на этой же машине или в домашней сети.
     *
     * <p>Разница видна не боту, а Discord: за аватаркой он ходит сам, и до локального
     * адреса ему не дотянуться. А до чужого сервера в интернете — вполне, поэтому
     * запрет должен касаться только своих адресов, а не любого своего сервера.
     */
    public boolean isLocalApi() {
        if (api.isBlank()) {
            return false;
        }

        String host;

        try {
            host = java.net.URI.create(api).getHost();
        } catch (IllegalArgumentException e) {
            return true;
        }

        if (host == null) {
            return true;
        }

        // Адрес IPv6 приходит в квадратных скобках — они часть записи, а не имени
        var lower = host.toLowerCase(java.util.Locale.ROOT)
                .replace("[", "").replace("]", "");

        return lower.equals("localhost") || lower.equals("0:0:0:0:0:0:0:1") || lower.equals("::1") || lower.endsWith(".local")
                || lower.startsWith("127.") || lower.startsWith("10.")
                || lower.startsWith("192.168.")
                || lower.matches("^172\\.(1[6-9]|2[0-9]|3[01])\\..*");
    }

    public boolean isUsable() {
        return enabled && channel != 0 && !token.isBlank() && !chat.isBlank();
    }
}
