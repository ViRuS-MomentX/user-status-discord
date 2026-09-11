package ru.virus.voicebridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Настройки бота: файл config.properties рядом с jar, любое значение можно перебить
 * переменной окружения.
 *
 * <p>Переменные окружения удобны там, где файл с токеном класть не хочется — например
 * при запуске из планировщика задач.
 */
public final class BotConfig {

    private final String token;
    private final long guildId;
    private final long userId;
    private final String httpHost;
    private final int httpPort;
    private final String httpToken;

    private BotConfig(String token, long guildId, long userId, String httpHost, int httpPort, String httpToken) {
        this.token = token;
        this.guildId = guildId;
        this.userId = userId;
        this.httpHost = httpHost;
        this.httpPort = httpPort;
        this.httpToken = httpToken;
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

        return new BotConfig(token, guildId, userId, host, port, pick(props, "http.token", "VOICEBRIDGE_HTTP_TOKEN"));
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
