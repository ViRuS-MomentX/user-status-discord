package ru.virus.voicebridge;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import club.minnced.discord.jdave.interop.JDaveSessionFactory;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
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
        if (ladder.isEnabled() || config.isModerationEnabled() || config.isMusicEnabled()
                || config.getBridge().isUsable()) {
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
        LibraryChannel libraryChannel = null;

        if (config.isMusicEnabled()) {
            music = new MusicService(config.isSoundCloudEnabled());

            MusicLibrary library = null;
            // Пути достраиваем от файла настроек: бота запускают и ярлыком, и из
            // планировщика, и «текущая папка» там каждый раз своя
            var librarySettings = config.getLibrary()
                    .resolvedAgainst(configPath.toAbsolutePath().getParent());

            if (librarySettings.isUsable()) {
                library = new MusicLibrary(
                        configPath.toAbsolutePath().resolveSibling(librarySettings.folder()));
                library.load();

                if (librarySettings.acceptsLinks()) {
                    var downloader = new Downloader(librarySettings, library.getFolder());

                    if (downloader.isReady()) {
                        libraryChannel = new LibraryChannel(config.getGuildId(), librarySettings,
                                library, downloader);
                        log.info("Приём ссылок на музыку включён: канал {}.", librarySettings.channel());
                    } else {
                        log.error("Не нашёлся {} — приём ссылок выключен. Скачай его с "
                                + "https://github.com/yt-dlp/yt-dlp/releases и положи рядом с ботом "
                                + "или укажи путь в music.library.ytdlp.", librarySettings.ytdlp());
                    }
                }
            }

            musicRequests = new MusicRequests(music, config.getCatalog(), config.getPlaylistSize(),
                    library);
            // Лист иконок ищем рядом с настройками, а не в текущей папке: бота запускают
            // и ярлыком, и из планировщика, и «текущая папка» там каждый раз своя
            panelIcons = new PanelIcons(config.getGuildId(),
                    configPath.toAbsolutePath().resolveSibling(config.getPanel().sheet()));
            musicPanel = new MusicPanel(config.getGuildId(), musicRequests, config.getPanel(), panelIcons,
                    configPath.toAbsolutePath().resolveSibling(config.getPanel().legend()));
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

        DiscordToTelegram toTelegram = null;
        Telegram telegram = null;
        BridgeLinks bridgeLinks = null;

        if (config.getBridge().isUsable()) {
            var settings = config.getBridge();

            // К своему серверу ходят по localhost, и прокси там только мешает: он
            // завернул бы в себя и обращения к собственной машине
            var proxy = settings.isLocalApi() ? "" : settings.proxy();

            if (settings.hasOwnApi()) {
                log.info("Telegram через свой сервер Bot API: {}", settings.api());

                if (settings.isLocalApi() && !settings.proxy().isBlank()) {
                    log.info("Прокси до своей же машины не нужен — не использую его.");
                }

                if (settings.isLocalApi() && settings.avatars() && !settings.webhook().isBlank()) {
                    log.warn("Аватарки из Telegram при сервере на этой же машине не "
                            + "подставить: Discord ходит за картинкой сам, а до твоего "
                            + "компьютера он не дотянется. Ники останутся, аватарки — нет.");
                }
            }

            telegram = new Telegram(settings.token(), proxy, settings.api());

            try {
                // Здороваемся сразу: про неверный токен лучше узнать при запуске,
                // а не при первом сообщении, которое тихо не дойдёт
                log.info("Мост с Telegram: бот @{}, группа {}.",
                        telegram.whoAmI(), config.getBridge().chat());
            } catch (Telegram.RejectedException e) {
                // Telegram ответил и развернул нас: тут ждать нечего, лечит человек
                log.error("Telegram отказал: {}. Проверь bridge.telegram.token. Мост выключен.",
                        e.getMessage());
                telegram = null;
            } catch (IOException e) {
                // А это связь. Мост оставляем: он сам пробует снова, и к тому времени,
                // как кто-то напишет, дорога может открыться
                // Совет должен указывать на ту дорогу, которой бот идёт на самом деле:
                // при своём сервере Bot API упоминать api.telegram.org и прокси
                // бессмысленно, они в этой цепочке не участвуют
                if (settings.hasOwnApi()) {
                    log.warn("Telegram сейчас не отвечает: {}. Мост поднят, буду пробовать "
                            + "дальше. Если так и останется — не открывается {}; "
                            + "проверь его, открыв {}/check.",
                            e.getMessage(), settings.api(), settings.api());
                } else {
                    log.warn("Telegram сейчас не отвечает: {}. Мост поднят, буду пробовать "
                            + "дальше. Если так и останется — до api.telegram.org не "
                            + "достучаться (провайдер, VPN), помогает bridge.telegram.proxy "
                            + "или свой сервер в bridge.telegram.api.", e.getMessage());
                }
            }

            var webhook = config.getBridge().webhook();

            if (!webhook.isBlank() && !DiscordWebhook.looksRight(webhook)) {
                log.error("bridge.discord.webhook не похож на ссылку вебхука Discord. "
                        + "Она начинается с https://discord.com/api/webhooks/ и берётся в "
                        + "настройках канала: Интеграции → Вебхуки → Копировать ссылку.");
            } else if (!webhook.isBlank()) {
                log.info("Сообщения из Telegram пойдут через вебхук: с ником и аватаркой.");
            }

            if (telegram != null) {
                // Общая на обе половины: ответы ищут пары именно в ней
                bridgeLinks = new BridgeLinks();
                toTelegram = new DiscordToTelegram(config.getGuildId(), config.getBridge(),
                        telegram, bridgeLinks);
            }
        } else if (config.getBridge().enabled()) {
            log.error("Мост включён, но не задан bridge.channel, bridge.telegram.token "
                    + "или bridge.telegram.chat.");
        }

        HttpBridge bridge;
        try {
            bridge = new HttpBridge(tracker, config.getHttpHost(), config.getHttpPort(), config.getHttpToken());
            bridge.start();
        } catch (BindException e) {
            // Порт занимает почти всегда прошлая копия бота, забытая в другом окне.
            // Про неё и говорим первой: смена порта тут лечит следствие, а не причину
            log.error("Порт {} уже занят — скорее всего, бот уже запущен в другом окне. "
                    + "Закрой ту копию и запусти заново. Если порт держит чужая программа, "
                    + "укажи в config.properties другой http.port и тот же адрес "
                    + "в настройках CustomRP.", config.getHttpPort());
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
                    panelIcons, musicCommands, musicPanel, libraryChannel, toTelegram }) {
                if (listener != null) {
                    builder.addEventListeners(listener);
                }
            }

            jda = connect(builder, bridge);
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

        TelegramToDiscord fromTelegram = null;

        if (telegram != null) {
            fromTelegram = new TelegramToDiscord(jda, config.getBridge(), telegram, bridgeLinks);
            fromTelegram.start();
        }

        PostsWatcher postsWatcher = null;

        if (config.getPosts().isUsable()) {
            postsWatcher = new PostsWatcher(jda, config.getPosts().channel(),
                    new PostsFeed(config.getPosts().url()),
                    configPath.toAbsolutePath().resolveSibling("posts-seen.txt"),
                    config.getPosts().minutes());
            postsWatcher.start();
        } else if (config.getPosts().enabled()) {
            log.error("Слежение за лентой включено, но не задан posts.url или posts.channel.");
        }

        var runningToTelegram = toTelegram;
        var runningFromTelegram = fromTelegram;
        var runningLibrary = libraryChannel;
        var runningWatcher = postsWatcher;
        var runningTicker = ticker;
        var runningStore = store;
        var runningMusic = music;
        var runningRequests = musicRequests;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Останавливаюсь.");
            bridge.stop();

            // Сохранить балансы надо до разрыва связи: после shutdown начисление уже не идёт,
            // а недописанная минута иначе потерялась бы.
            if (runningToTelegram != null) {
                runningToTelegram.shutdown();
            }

            if (runningFromTelegram != null) {
                runningFromTelegram.stop();
            }

            if (runningLibrary != null) {
                runningLibrary.shutdown();
            }

            if (runningWatcher != null) {
                runningWatcher.stop();
            }

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

    /** Сколько ждать между попытками войти в Discord, в секундах. */
    private static final int[] RETRY_SECONDS = { 15, 15, 30, 30, 60, 60, 60 };

    /**
     * Входит в Discord, переживая недоступность сети.
     *
     * <p>При запуске вместе с Windows бот почти всегда опережает то, что даёт ему
     * доступ наружу: служба обхода блокировок или VPN поднимаются позже. Выходить
     * с ошибкой в такой момент означает не запуститься вовсе, поэтому ждём —
     * суммарно около четырёх с половиной минут, этого хватает с запасом.
     */
    private static JDA connect(JDABuilder builder, HttpBridge bridge) {
        for (var attempt = 0; ; attempt++) {
            try {
                return builder.build();
            } catch (ErrorResponseException e) {
                // Сюда приходит и обрыв сети: JDA заворачивает её в свой тип с кодом -1.
                // Стек в сто строк тут ничего не объясняет, а пугает, поэтому говорим словами
                if (attempt >= RETRY_SECONDS.length) {
                    log.error("Discord так и не отозвался: {}. Обычно это блокировка или VPN "
                            + "с zapret'ом на пути — проверь командой "
                            + "curl.exe -s -o NUL -w \"%{http_code}\" "
                            + "https://discord.com/api/v10/gateway, должно быть 200.", reason(e));
                    bridge.stop();
                    System.exit(5);
                }

                var wait = RETRY_SECONDS[attempt];
                log.warn("Discord не отозвался: {}. Пробую снова через {} с.", reason(e), wait);

                try {
                    Thread.sleep(wait * 1000L);
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    bridge.stop();
                    System.exit(5);
                }
            }
        }
    }

    /**
     * Короткая причина вместо стека.
     *
     * <p>Берём самое глубокое внятное объяснение: у JDA в конце цепочки лежит
     * ContextException — метка места вызова без текста, и она бы вытеснила
     * настоящую причину вроде «Read timed out».
     */
    static String reason(Throwable trouble) {
        var depth = 0;
        var said = "";

        // Цепочку ограничиваем: причины могут ссылаться друг на друга по кругу,
        // и тогда обход без счётчика не кончится никогда
        for (var step = trouble; step != null && depth < 20; step = step.getCause(), depth++) {
            var message = step.getMessage();

            if (message != null && !message.isBlank()) {
                said = message;
            }
        }

        return said.isEmpty() ? trouble.getClass().getSimpleName() : said;
    }
}
