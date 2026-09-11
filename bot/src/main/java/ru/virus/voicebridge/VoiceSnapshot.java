package ru.virus.voicebridge;

/**
 * Неизменяемый снимок голосового состояния отслеживаемого пользователя.
 *
 * <p>Объект целиком описывает то, что уходит в CustomRP: где человек сидит и с какого
 * момента. Неизменяемость здесь не украшение — снимок пишет поток JDA, а читает поток
 * HTTP-сервера, и подменять ссылку целиком дешевле и безопаснее, чем городить блокировки
 * вокруг изменяемых полей.
 */
public final class VoiceSnapshot {

    /** Снимок для состояния «в голосовом канале не сидит». */
    public static final VoiceSnapshot IDLE = new VoiceSnapshot(false, 0L, null, null, null, 0, 0L);

    private final boolean inVoice;
    private final long channelId;
    private final String guildName;
    private final String channelName;
    private final String iconUrl;
    private final int members;
    private final long since;

    private VoiceSnapshot(boolean inVoice, long channelId, String guildName, String channelName, String iconUrl,
                          int members, long since) {
        this.inVoice = inVoice;
        this.channelId = channelId;
        this.guildName = guildName;
        this.channelName = channelName;
        this.iconUrl = iconUrl;
        this.members = members;
        this.since = since;
    }

    public static VoiceSnapshot inChannel(long channelId, String guildName, String channelName, String iconUrl,
                                          int members, long since) {
        return new VoiceSnapshot(true, channelId, guildName, channelName, iconUrl, members, since);
    }

    public boolean isInVoice() {
        return inVoice;
    }

    public long getChannelId() {
        return channelId;
    }

    public String getGuildName() {
        return guildName;
    }

    public String getChannelName() {
        return channelName;
    }

    /** Ссылка на иконку сервера, или пустая строка, если иконки у сервера нет. */
    public String getIconUrl() {
        return iconUrl;
    }

    public int getMembers() {
        return members;
    }

    /** Момент входа в текущий канал, epoch millis. Из него CustomRP считает время нахождения. */
    public long getSince() {
        return since;
    }

    /**
     * Сериализует снимок в JSON вручную.
     *
     * <p>Полей пять, и тащить ради них Jackson в fat-jar смысла нет.
     *
     * @param connected жив ли гейтвей прямо сейчас; CustomRP по этому полю отличает
     *                  «точно не в войсе» от «бот потерял связь и не знает».
     */
    public String toJson(boolean connected) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"connected\":").append(connected);
        sb.append(",\"inVoice\":").append(inVoice);
        if (inVoice) {
            sb.append(",\"guild\":").append(quote(guildName));
            sb.append(",\"channel\":").append(quote(channelName));
            sb.append(",\"icon\":").append(quote(iconUrl == null ? "" : iconUrl));
            sb.append(",\"members\":").append(members);
            sb.append(",\"since\":").append(since);
        }
        return sb.append('}').toString();
    }

    /** Экранирует строку по правилам JSON. Названия каналов бывают какими угодно. */
    private static String quote(String raw) {
        if (raw == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 16).append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    // Управляющие символы обязаны уходить в escape-последовательность,
                    // остальное — как есть, включая эмодзи в названиях каналов: отдаём UTF-8.
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }
}
