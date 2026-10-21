package ru.virus.voicebridge;

/**
 * Настройки пародийного бота в Telegram.
 *
 * @param token токен отдельного телеграм-бота от @BotFather — не того, что у моста
 * @param chat группа, где бот читает и пишет: @имя или числовой ID
 * @param target номер участника в Telegram, чью манеру бот перенимает
 * @param name как участника называть в подсказке для ИИ
 * @param llmProvider чья модель: ollama, gigachat или anthropic
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
 * @param mark с чего начинается каждая реплика, с пробелом на конце
 * @param character описание своего персонажа; пусто — режим пародии на участника.
 *                  Персонаж никого не изображает: у него свои имя и характер, а
 *                  {@code target} — лишь чью манеру письма (не мнения) он берёт
 */
public record ParodySettings(boolean enabled, String token, String chat, long target, String name,
                             String llmProvider, String llmKey, String llmModel,
                             String llmScope, String llmCert, String llmUrl, String llmProxy,
                             int chance, int cooldownMinutes, int minSamples, String importFile,
                             String proxy, String api,
                             long owner, String quiet, int daily, String mark, String character,
                             java.util.List<Long> ban) {

    /** Пометка по умолчанию. */
    public static final String DEFAULT_MARK = "пародия: ";

    /** Длиннее — пометка уже не пометка, а отдельная реплика. */
    private static final int MARK_LIMIT = 40;

    /**
     * Своя пометка — если по ней всё ещё видно, что пишет не человек.
     *
     * <p>Бот носит ник и аватарку участника, и без ясной пометки в группе думали
     * бы, что это написал он сам. Поэтому слова менять можно, а убрать смысл —
     * нет: в пометке должно быть «пародия», «ИИ», «AI», «бот» или 🤖.
     *
     * @return пометка с пробелом на конце; {@code null}, если такая не годится
     */
    public static String acceptMark(String raw) {
        var trimmed = raw.strip();

        if (trimmed.isEmpty()) {
            return DEFAULT_MARK;
        }

        if (trimmed.length() > MARK_LIMIT) {
            return null;
        }

        var lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        var clear = lower.contains("пароди") || lower.contains("🤖")
                || lower.matches("(?s).*(^|[^\\p{L}])(ии|ai|бот|bot)([^\\p{L}]|$).*")
                || lower.contains("бот-") || lower.contains("-бот");

        return clear ? trimmed + " " : null;
    }

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

    /** Модель на этом же компьютере — ей ключ не нужен. */
    public boolean isOllama() {
        return "ollama".equals(llmProvider);
    }

    /** Свой персонаж, а не пародия на участника. */
    public boolean isCharacter() {
        return !character.isBlank();
    }

    public boolean isGigaChat() {
        return "gigachat".equals(llmProvider);
    }

    public boolean isUsable() {
        return enabled && !token.isBlank() && !chat.isBlank() && (target != 0 || isCharacter())
                && (isOllama() || !llmKey.isBlank()) && !llmModel.isBlank();
    }
}
