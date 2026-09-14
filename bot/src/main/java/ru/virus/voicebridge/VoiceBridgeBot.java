package ru.virus.voicebridge;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import club.minnced.discord.jdave.interop.JDaveSessionFactory;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
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
import java.util.ArrayList;
import java.util.List;

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
        RankLadder ladder = config.getLadder();

        // Ранговой системе нужно читать текст сообщений, а это привилегированный интент.
        // Без неё он не запрашивается вовсе, чтобы бот работал и с выключенной галочкой.
        List<GatewayIntent> intents = new ArrayList<>();
        intents.add(GatewayIntent.GUILD_VOICE_STATES);

        RankStore store = null;
        RankCommands commands = null;
        ModerationCommands moderation = null;
        MemberRoleKeeper memberRoles = null;

        // Текстовые команды читают содержимое сообщений — это привилегированный интент.
        // Запрашиваем его только если хоть что-то из команд включено.
        if (ladder.isEnabled() || config.isModerationEnabled() || config.isMusicEnabled()) {
            intents.add(GatewayIntent.GUILD_MESSAGES);
            intents.add(GatewayIntent.MESSAGE_CONTENT);
        }

        if (ladder.isEnabled()) {
            store = new RankStore(configPath.toAbsolutePath().resolveSibling("ranks.json"));
            commands = new RankCommands(config.getGuildId(), store, ladder);
        }

        if (config.isModerationEnabled()) {
            moderation = new ModerationCommands(config.getGuildId());
        }

        MusicService music = null;
        MusicRequests musicRequests = null;
        MusicCommands musicCommands = null;
        MusicPanel musicPanel = null;
        PanelIcons panelIcons = null;

        if (config.isMusicEnabled()) {
            music = new MusicService();
            musicRequests = new MusicRequests(music, config.getCatalog(), config.getPlaylistSize());
            // Лист иконок ищем рядом с настройками, а не в текущей папке: бота запускают
            // и ярлыком, и из планировщика, и «текущая папка» там каждый раз своя
            panelIcons = new PanelIcons(config.getGuildId(),
                    configPath.toAbsolutePath().resolveSibling(config.getPanel().sheet()));
            musicPanel = new MusicPanel(config.getGuildId(), musicRequests, config.getPanel(), panelIcons);
            musicCommands = new MusicCommands(config.getGuildId(), musicRequests, musicPanel);
        }

        // Роль ботам и ранг каждому участнику раздаёт один обход: список участников
        // нельзя запрашивать дважды, второй запрос отваливается по таймауту.
        var roleKeeper = new MemberRoleKeeper(config.getGuildId(), config.getBotsRole(),
                store, ladder.isEnabled() ? ladder : null);

        if (roleKeeper.hasWork()) {
            // Обход всех участников сервера — за привилегированным интентом
            intents.add(GatewayIntent.GUILD_MEMBERS);
            memberRoles = roleKeeper;
        }

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
            JDABuilder builder = JDABuilder.createLight(config.getToken(), intents)
                    // Кэш голосовых состояний — единственное, что нам нужно от JDA.
                    .enableCache(CacheFlag.VOICE_STATE)
                    .setMemberCachePolicy(MemberCachePolicy.VOICE)
                    .setStatus(config.getStatus())
                    // Discord требует сквозного шифрования голоса. Своей реализации у JDA
                    // нет — со встроенной заглушкой соединение закрывается через секунду
                    // после подключения, и бот бесконечно входит и выходит из канала.
                    .setAudioModuleConfig(new AudioModuleConfig()
                            .withDaveSessionFactory(new JDaveSessionFactory()))
                    .addEventListeners(tracker);

            for (var listener : new Object[] { commands, moderation, memberRoles,
                    panelIcons, musicCommands, musicPanel }) {
                if (listener != null) {
                    builder.addEventListeners(listener);
                }
            }

            jda = builder.build();
        } catch (InvalidTokenException e) {
            log.error("Discord не принял токен. Проверь bot.token — возможно, он был сброшен "
                    + "в настройках приложения.");
            bridge.stop();
            System.exit(4);
            return;
        }

        VoiceCoinTicker ticker = null;

        if (ladder.isEnabled()) {
            ticker = new VoiceCoinTicker(jda, config.getGuildId(), store, ladder.getMinutesPerCoin());

            try {
                // Ждём загрузки гильдий: до этого ролей ещё нет и проверять нечего.
                jda.awaitReady();

                var guild = jda.getGuildById(config.getGuildId());
                if (guild != null) {
                    ladder.verify(guild);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            ticker.start();
        }

        var runningTicker = ticker;
        var runningStore = store;
        var runningMusic = music;
        var runningRequests = musicRequests;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Останавливаюсь.");
            bridge.stop();

            // Сохранить балансы надо до разрыва связи: после shutdown начисление уже не идёт,
            // а недописанная минута иначе потерялась бы.
            if (runningTicker != null) {
                runningTicker.stop();
            }
            if (runningStore != null) {
                runningStore.save();
            }

            if (runningRequests != null) {
                runningRequests.shutdown();
            }
            if (runningMusic != null) {
                runningMusic.shutdown();
            }

            jda.shutdown();
        }, "voice-bridge-shutdown"));
    }

    private VoiceBridgeBot() {
    }
}
