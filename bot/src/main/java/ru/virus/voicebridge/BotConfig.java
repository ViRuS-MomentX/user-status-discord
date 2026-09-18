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

import java.util.List;
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
    private final PanelSettings panel;
    private final PostsSettings posts;
    private final LibrarySettings library;

    private BotConfig(String token, long guildId, long userId, String httpHost, int httpPort, String httpToken,
                      RankLadder ladder, String botsRole, boolean moderation,
                      boolean music, String lastFmKey, int playlistSize, MusicCatalog catalog,
                      OnlineStatus status, PanelSettings panel, PostsSettings posts,
                      LibrarySettings library) {
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
        this.panel = panel;
        this.posts = posts;
        this.library = library;
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

    /** Картинка-подсказка и слова для сезонных подборок панели. */
    public PanelSettings getPanel() {
        return panel;
    }

    /** Слежение за лентой постов сайта. */
    public PostsSettings getPosts() {
        return posts;
    }

    /** Своя фонотека: где лежит и чем наполняется. */
    public LibrarySettings getLibrary() {
        return library;
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
                parseStatus(props.getProperty("bot.status", "").trim()),
                panelSettings(props),
                postsSettings(props),
                librarySettings(props));
    }

    /** Площадки, ссылки на которые принимаются по умолчанию. */
    private static final String DEFAULT_HOSTS = "youtube.com,youtu.be,soundcloud.com,"
            + "bandcamp.com,music.yandex.ru,vk.com,vkvideo.ru,archive.org,jamendo.com,"
            + "freemusicarchive.org,ccmixter.org";

    /**
     * Настройки своей фонотеки.
     */
    private static LibrarySettings librarySettings(Properties props) {
        var hosts = new java.util.ArrayList<String>();

        for (var host : props.getProperty("music.library.hosts", DEFAULT_HOSTS).split(",")) {
            var trimmed = host.trim().toLowerCase();
            if (!trimmed.isEmpty()) {
                hosts.add(trimmed);
            }
        }

        return new LibrarySettings(
                Boolean.parseBoolean(props.getProperty("music.library.enabled", "false").trim()),
                season(props, "music.library.folder", "library"),
                number(props, "music.library.channel", 0),
                season(props, "music.library.ytdlp", "yt-dlp"),
                props.getProperty("music.library.ffmpeg", "").trim(),
                bounded(props, "music.library.minutes", 15, 1, 180),
                bounded(props, "music.library.megabytes", 30, 1, 500),
                bounded(props, "music.library.waitminutes", 5, 1, 60),
                List.copyOf(hosts),
                props.getProperty("music.library.cookies", "").trim(),
                props.getProperty("music.library.cookiefile", "").trim(),
                props.getProperty("music.library.client", "").trim(),
                words(props.getProperty("music.library.args", "")));
    }

    /** Разбивает строку настройки на отдельные слова-аргументы. */
    private static List<String> words(String raw) {
        var trimmed = raw.trim();
        return trimmed.isEmpty() ? List.of() : List.of(trimmed.split("\\s+"));
    }

    /** Числовой ID из настроек. Ноль означает «не задано». */
    private static long number(Properties props, String key, long fallback) {
        var raw = props.getProperty(key, "").trim();

        if (raw.isEmpty()) {
            return fallback;
        }

        try {
            return Long.parseUnsignedLong(raw);
        } catch (NumberFormatException e) {
            log.error("{} должен быть числовым ID, а не «{}».", key, raw);
            return fallback;
        }
    }

    /** Число в разумных пределах: за ними настройка делает только хуже. */
    private static int bounded(Properties props, String key, int fallback, int min, int max) {
        var raw = props.getProperty(key, "").trim();

        if (raw.isEmpty()) {
            return fallback;
        }

        try {
            return Math.max(min, Math.min(max, Integer.parseInt(raw)));
        } catch (NumberFormatException e) {
            log.error("{} должен быть числом, а не «{}». Беру {}.", key, raw, fallback);
            return fallback;
        }
    }

    /**
     * Настройки слежения за лентой сайта.
     *
     * <p>Проверять чаще раза в минуту незачем: посты пишет человек, а сайт выкладывает
     * их сборкой, которая и сама идёт минуты.
     */
    private static PostsSettings postsSettings(Properties props) {
        var minutes = 10;
        var raw = props.getProperty("posts.minutes", "").trim();

        if (!raw.isEmpty()) {
            try {
                minutes = Math.max(1, Math.min(1440, Integer.parseInt(raw)));
            } catch (NumberFormatException e) {
                log.error("posts.minutes должен быть числом, а не «{}». Беру 10.", raw);
            }
        }

        var channel = 0L;
        var rawChannel = props.getProperty("posts.channel", "").trim();

        if (!rawChannel.isEmpty()) {
            try {
                channel = Long.parseUnsignedLong(rawChannel);
            } catch (NumberFormatException e) {
                log.error("posts.channel должен быть числовым ID канала, а не «{}».", rawChannel);
            }
        }

        return new PostsSettings(
                Boolean.parseBoolean(props.getProperty("posts.enabled", "false").trim()),
                props.getProperty("posts.url", "").trim(),
                channel,
                minutes);
    }

    /**
     * Настройки панели.
     *
     * <p>Сезонные кнопки — это обычный поиск по каталогу, и запрос вынесен в файл:
     * подборка «новогоднее» на английском и на русском находит разное, а какая нужна,
     * знает только владелец сервера.
     */
    private static PanelSettings panelSettings(Properties props) {
        return new PanelSettings(
                props.getProperty("music.panel.image", "").trim(),
                season(props, "music.panel.legend", "panel-legend.png"),
                season(props, "music.panel.sheet", "emoji-sheet.png"),
                season(props, "music.season.winter", "christmas songs"),
                season(props, "music.season.spring", "spring hits"),
                season(props, "music.season.autumn", "autumn chill"),
                season(props, "music.season.summer", "summer hits"));
    }

    /** Пустую строку в настройках считаем «не трогали» и берём значение по умолчанию. */
    private static String season(Properties props, String key, String fallback) {
        var value = props.getProperty(key, "").trim();
        return value.isEmpty() ? fallback : value;
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
