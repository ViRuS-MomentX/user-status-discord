package ru.virus.voicebridge;

/**
 * Настройки пародийного бота в Telegram.
 *
 * @param token токен отдельного телеграм-бота от @BotFather — не того, что у моста
 * @param chat группа, где бот читает и пишет: @имя или числовой ID
 * @param target номер участника в Telegram, чью манеру бот перенимает
 * @param name как участника называть в подсказке для ИИ
 * @param llmProvider чья модель: anthropic или gigachat
 * @param llmKey ключ API Anthropic или ключ авторизации GigaChat
 * @param llmModel имя модели
 * @param llmScope к какому тарифу GigaChat относится ключ
 * @param llmCert файл сертификата НУЦ Минцифры для GigaChat, от папки с настройками
 * @param llmUrl адрес API; пусто — официальный
 * @param llmProxy «хост:порт» HTTP-прокси до API; пусто — напрямую
 * @param chance с какой вероятностью, в процентах, бот встревает после чужого сообщения
 * @param cooldownMinutes сколько минут молчать после своей реплики
 * @param minSamples сколько сообщений участника набрать, прежде чем начать пародировать
 * @param importFile экспорт истории группы из Telegram Desktop (result.json), чтобы
 *                   не ждать, пока сообщения наберутся сами; пусто — не нужен
 * @param proxy прокси до Telegram — тот же, что у моста
 * @param api свой сервер Bot API — тот же, что у моста
 * @param owner номер хозяина бота в Telegram: он, как и сам участник, может
 *              выключать пародию командами; 0 — команды только у участника
 * @param quiet тихие часы «с-до» по часам компьютера, например «23-8»; пусто — без них
 * @param daily сколько реплик в сутки самое большее; 0 — без предела
 */
public record ParodySettings(boolean enabled, String token, String chat, long target, String name,
                             String llmProvider, String llmKey, String llmModel,
                             String llmScope, String llmCert, String llmUrl, String llmProxy,
                             int chance, int cooldownMinutes, int minSamples, String importFile,
                             String proxy, String api,
                             long owner, String quiet, int daily) {

    /**
     * Тихий ли сейчас час.
     *
     * <p>Промежуток может переходить через полночь: «23-8» — с одиннадцати
     * вечера до восьми утра.
     */
    public boolean isQuiet(int hour) {
        var dash = quiet.indexOf('-');

        if (dash <= 0) {
            return false;
        }

        int from;
        int to;

        try {
            from = Integer.parseInt(quiet.substring(0, dash).trim());
            to = Integer.parseInt(quiet.substring(dash + 1).trim());
        } catch (NumberFormatException e) {
            return false;
        }

        if (from == to) {
            return false;
        }

        return from < to ? hour >= from && hour < to : hour >= from || hour < to;
    }

    /** Может ли этот человек управлять пародией. */
    public boolean mayCommand(long userId) {
        return userId != 0 && (userId == target || userId == owner);
    }

    public boolean isGigaChat() {
        return "gigachat".equals(llmProvider);
    }

    public boolean isUsable() {
        return enabled && !token.isBlank() && !chat.isBlank() && target != 0
                && !llmKey.isBlank() && !llmModel.isBlank();
    }
}
