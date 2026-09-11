package ru.virus.voicebridge;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.exceptions.InvalidTokenException;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.BindException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Точка входа.
 *
 * <p>Бот подключается к Discord, следит за голосовыми каналами одного сервера и отдаёт
 * текущее состояние по локальному HTTP. Ставит активность не он, а CustomRP: приложение
 * само ходит за этими данными, потому что присутствие пользователя может выставить только
 * программа, запущенная рядом с клиентом Discord.
 */
public final class VoiceBridgeBot {

    private static final Logger log = LoggerFactory.getLogger(VoiceBridgeBot.class);

    public static void main(String[] args) {
        Path configPath = Paths.get(args.length > 0 ? args[0] : "config.properties");

        BotConfig config;
        try {
            config = BotConfig.load(configPath);
        } catch (BotConfig.ConfigException e) {
            // Ошибки настройки — это не сбой программы, а недоделанная инструкция.
            // Стектрейс тут только мешает, показываем одну понятную строку.
            log.error("Ошибка настройки: {}", e.getMessage());
            System.exit(2);
            return;
        }

        VoiceTracker tracker = new VoiceTracker(config.getGuildId(), config.getUserId());

        HttpBridge bridge;
        try {
            bridge = new HttpBridge(tracker, config.getHttpHost(), config.getHttpPort(), config.getHttpToken());
            bridge.start();
        } catch (BindException e) {
            log.error("Порт {} уже занят. Укажи в config.properties другой http.port "
                    + "и тот же адрес в настройках CustomRP.", config.getHttpPort());
            System.exit(3);
            return;
        } catch (IOException e) {
            log.error("Не удалось поднять HTTP-эндпоинт: {}", e.getMessage());
            System.exit(3);
            return;
        }

        JDA jda;
        try {
            jda = JDABuilder.createLight(config.getToken(), GatewayIntent.GUILD_VOICE_STATES)
                    // Кэш голосовых состояний — единственное, что нам нужно от JDA.
                    .enableCache(CacheFlag.VOICE_STATE)
                    .setMemberCachePolicy(MemberCachePolicy.VOICE)
                    .setStatus(OnlineStatus.INVISIBLE)
                    .addEventListeners(tracker)
                    .build();
        } catch (InvalidTokenException e) {
            log.error("Discord не принял токен. Проверь bot.token — возможно, он был сброшен "
                    + "в настройках приложения.");
            bridge.stop();
            System.exit(4);
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Останавливаюсь.");
            bridge.stop();
            jda.shutdown();
        }, "voice-bridge-shutdown"));
    }

    private VoiceBridgeBot() {
    }
}
