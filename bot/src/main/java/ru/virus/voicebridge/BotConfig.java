package ru.virus.voicebridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.dv8tion.jda.api.OnlineStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * Настройки бота: файл config.properties рядом с jar, любое значение можно перебить
 * переменной окружения.
 *
 * <p>Переменные окружения удобны там, где файл с токеном класть не хочется — например
 * при запуске из планировщика задач.
 */
public final class BotConfig {

    private static final Logger log = LoggerFactory.getLogger(BotConfig.class);

    private final String token;
    private final long guildId;
    private final long userId;
    private final String httpHost;
    private final int httpPort;
    private final String httpToken;
    private final RankLadder ladder;
    private final String botsRole;
    private final boolean moderation;
    private final boolean music;
    private final String lastFmKey;
    private final int playlistSize;
    private final MusicCatalog catalog;
    private final OnlineStatus status;

    private BotConfig(String token, long guildId, long userId, String httpHost, int httpPort, String httpToken,
                      RankLadder ladder, String botsRole, boolean moderation,
                      boolean music, String lastFmKey, int playlistSize, MusicCatalog catalog,
                      OnlineStatus status) {
        this.token = token;
        this.guildId = guildId;
        this.userId = userId;
        this.httpHost = httpHost;
        this.httpPort = httpPort;
        this.httpToken = httpToken;
        this.ladder = ladder;
        this.botsRole = botsRole;
        this.moderation = moderation;
        this.music = music;
        this.lastFmKey = lastFmKey;
        this.playlistSize = playlistSize;
        this.catalog = catalog;
        this.status = status;
    }

    public String getToken() {
        return token;
    }

    public long getGuildId() {
        return guildId;
    }

    public long getUserId() {
        return userId;
    }

    public String getHttpHost() {
        return httpHost;
    }

    public int getHttpPort() {
        return httpPort;
    }

    /** Общий секрет для запросов к эндпоинту. Пустая строка — проверка выключена. */
    public String getHttpToken() {
        return httpToken;
    }

    /** Настройки ранговой системы. */
    public RankLadder getLadder() {
        return ladder;
    }

    /** Роль, которая выдаётся всем ботам сервера. Пустая строка — выдача выключена. */
    public String getBotsRole() {
        return botsRole;
    }

    /** Включена ли команда очистки канала. */
    public boolean isModerationEnabled() {
        return moderation;
    }

    /** Включены ли музыкальные команды. */
    public boolean isMusicEnabled() {
        return music;
    }

    /** Ключ Last.fm для подбора плейлиста. */
    public String getLastFmKey() {
        return lastFmKey;
    }

    /**
     * Каталог подсказок: откуда брать, что играть после введённого.
     */
    public MusicCatalog getCatalog() {
        return catalog;
    }

    /** Каким бот показывается в списке участников. */
    public OnlineStatus getStatus() {
        return status;
    }

    /** Сколько треков класть в очередь за одну команду start. */
    public int getPlaylistSize() {
        return playlistSize;
    }

    /**
     * Читает настройки и сразу проверяет их.
     *
     * @throws ConfigException если чего-то не хватает или значение не разобрать; текст
     *                         исключения рассчитан на то, что его увидит человек в консоли
     */
    public static BotConfig load(Path file) throws ConfigException {
        Properties props = new Properties();

        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file);
                 Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                props.load(reader);
            } catch (IOException e) {
                throw new ConfigException("Не удалось прочитать " + file.toAbsolutePath() + ": " + e.getMessage());
            }
        }

        String token = pick(props, "bot.token", "VOICEBRIDGE_BOT_TOKEN");
        if (token.isEmpty()) {
            throw new ConfigException("Не задан bot.token. Создай бота на "
                    + "https://discord.com/developers/applications и впиши его токен в " + file.getFileName() + ".");
        }

        long guildId = parseId(pick(props, "guild.id", "VOICEBRIDGE_GUILD_ID"), "guild.id");
        long userId = parseId(pick(props, "user.id", "VOICEBRIDGE_USER_ID"), "user.id");

        String host = pick(props, "http.host", "VOICEBRIDGE_HTTP_HOST");
        if (host.isEmpty()) {
            host = "127.0.0.1";
        }

        int port = 7373;
        String rawPort = pick(props, "http.port", "VOICEBRIDGE_HTTP_PORT");
        if (!rawPort.isEmpty()) {
            try {
                port = Integer.parseInt(rawPort);
            } catch (NumberFormatException e) {
                throw new ConfigException("http.port должен быть числом, а не «" + rawPort + "».");
            }
            if (port < 1 || port > 65535) {
                throw new ConfigException("http.port вне диапазона 1–65535: " + port + ".");
            }
        }

        return new BotConfig(token, guildId, userId, host, port, pick(props, "http.token", "VOICEBRIDGE_HTTP_TOKEN"),
                RankLadder.from(props),
                props.getProperty("bots.role", "").trim(),
                Boolean.parseBoolean(props.getProperty("moderation.enabled", "false").trim()),
                Boolean.parseBoolean(props.getProperty("music.enabled", "false").trim()),
                pick(props, "music.lastfm.key", "VOICEBRIDGE_LASTFM_KEY"),
                parsePlaylistSize(props.getProperty("music.playlist", "").trim()),
                pickCatalog(props),
                parseStatus(props.getProperty("bot.status", "").trim()));
    }

    /**
     * Разбирает статус бота. Неизвестное значение не повод падать — берём «не беспокоить».
     */
    private static OnlineStatus parseStatus(String raw) {
        if (raw.isEmpty()) {
            return OnlineStatus.DO_NOT_DISTURB;
        }

        var status = OnlineStatus.fromKey(raw.toLowerCase());

        if (status == OnlineStatus.UNKNOWN) {
            log.error("Неизвестный bot.status «{}». Беру dnd. "
                    + "Допустимые: online, idle, dnd, invisible.", raw);
            return OnlineStatus.DO_NOT_DISTURB;
        }

        return status;
    }

    /**
     * Выбирает каталог подсказок. По умолчанию iTunes: он отвечает без ключей и
     * оттуда, откуда Last.fm отказывает.
     */
    private static MusicCatalog pickCatalog(Properties props) {
        var choice = props.getProperty("music.catalog", "itunes").trim().toLowerCase();

        if (choice.equals("lastfm")) {
            return new LastFm(pick(props, "music.lastfm.key", "VOICEBRIDGE_LASTFM_KEY"));
        }

        return new ITunes(props.getProperty("music.itunes.country", "US").trim(),
                    props.getProperty("music.itunes.useragent", "").trim());
    }

    /**
     * Размер плейлиста. За пределами разумного его ограничиваем: каждый трек — это
     * отдельный поиск на SoundCloud, и сотня записей собиралась бы минутами.
     */
    private static int parsePlaylistSize(String raw) {
        if (raw.isEmpty()) {
            return 15;
        }

        try {
            return Math.max(1, Math.min(50, Integer.parseInt(raw)));
        } catch (NumberFormatException e) {
            return 15;
        }
    }

    /** Переменная окружения приоритетнее файла: так удобнее подменять токен на месте. */
    private static String pick(Properties props, String key, String envKey) {
        String fromEnv = System.getenv(envKey);
        if (fromEnv != null && !fromEnv.trim().isEmpty()) {
            return fromEnv.trim();
        }
        return props.getProperty(key, "").trim();
    }

    private static long parseId(String raw, String key) throws ConfigException {
        if (raw.isEmpty()) {
            throw new ConfigException("Не задан " + key + ". Включи в Discord режим разработчика "
                    + "(Настройки → Расширенные) и скопируй ID правым кликом.");
        }
        try {
            return Long.parseUnsignedLong(raw);
        } catch (NumberFormatException e) {
            throw new ConfigException(key + " должен быть числовым ID, а не «" + raw + "».");
        }
    }

    /** Ошибка настройки: показываем текст пользователю и выходим, стектрейс тут не нужен. */
    public static final class ConfigException extends Exception {
        private static final long serialVersionUID = 1L;

        public ConfigException(String message) {
            super(message);
        }
    }
}
