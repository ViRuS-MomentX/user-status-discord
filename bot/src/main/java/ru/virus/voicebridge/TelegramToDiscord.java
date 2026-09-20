package ru.virus.voicebridge;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Вторая половина моста: из группы Telegram в канал Discord.
 *
 * <p>Telegram сам ничего не присылает: за новым ходят сами, держа запрос открытым,
 * пока не появится сообщение. Поэтому здесь свой поток, а не слушатель событий.
 */
public final class TelegramToDiscord {

    private static final Logger log = LoggerFactory.getLogger(TelegramToDiscord.class);

    /** Сколько ждать после сбоя, прежде чем пробовать снова. */
    private static final long RETRY_PAUSE_MS = 15_000;

    /** Паузы между попытками доставить одно сообщение, в миллисекундах. */
    private static final long[] SEND_RETRY_MS = { 5_000, 15_000 };

    private final JDA jda;
    private final BridgeSettings settings;
    private final Telegram telegram;
    private final DiscordWebhook webhook;
    private final BridgeLinks links;

    /**
     * Найденные аватарки.
     *
     * <p>Спрашивать их у Telegram на каждое сообщение — два лишних запроса подряд,
     * а меняют аватарку раз в полгода.
     */
    private final Map<Long, String> avatars = new ConcurrentHashMap<>();

    private volatile boolean running = true;
    private Thread worker;

    public TelegramToDiscord(JDA jda, BridgeSettings settings, Telegram telegram) {
        this(jda, settings, telegram, new BridgeLinks());
    }

    public TelegramToDiscord(JDA jda, BridgeSettings settings, Telegram telegram,
                             BridgeLinks links) {
        this.jda = jda;
        this.settings = settings;
        this.telegram = telegram;
        this.links = links;
        this.webhook = settings.webhook().isBlank() ? null : new DiscordWebhook(settings.webhook());
    }

    public void start() {
        worker = new Thread(this::loop, "bridge-from-telegram");
        worker.setDaemon(true);
        worker.start();
    }

    public void stop() {
        running = false;

        if (worker != null) {
            worker.interrupt();
        }
    }

    private void loop() {
        long offset = 0;

        // Всё, что пришло, пока бот не работал, пропускаем: вываливать в канал вчерашнюю
        // переписку при каждом запуске никто не просил
        try {
            offset = telegram.lastUpdateId(0);
        } catch (Exception e) {
            log.warn("Не удалось узнать, на чём остановились: {}", e.getMessage());
        }

        while (running) {
            try {
                var messages = telegram.poll(offset);

                for (var message : messages) {
                    offset = Math.max(offset, message.updateId() + 1);

                    if (!message.fromBot()) {
                        deliver(message);
                    }
                }

                // Даже когда переносить нечего, отметку надо сдвинуть: служебные
                // обновления иначе будут приходить снова и снова
                if (messages.isEmpty()) {
                    offset = telegram.lastUpdateId(offset);
                }
            } catch (Exception e) {
                if (!running) {
                    return;
                }

                log.warn("Telegram не ответил: {}. Пробую через {} с.",
                        e.getMessage(), RETRY_PAUSE_MS / 1000);

                try {
                    Thread.sleep(RETRY_PAUSE_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void deliver(TelegramMessage message) {
        byte[] file = null;
        String failure = null;

        if (settings.files() && !message.fileId().isBlank()) {
            try {
                file = telegram.download(message.fileId(), settings.maxMegabytes() * 1024 * 1024);
            } catch (Exception e) {
                log.error("Вложение из Telegram не забралось: {}", e.getMessage());
                failure = e.getMessage();
            }
        }

        // Повторяем, пока не уйдёт. Обрыв до Discord длится обычно секунды, и без
        // повтора каждое такое мгновение выедало бы из переписки по сообщению —
        // о потере знал бы только лог. Ждём на этом же потоке, чтобы сообщения
        // дошли в том порядке, в каком их написали
        for (var attempt = 0; running; attempt++) {
            if (post(message, file, failure)) {
                return;
            }

            if (attempt >= SEND_RETRY_MS.length) {
                log.error("Сообщение от {} так и не ушло в Discord — потеряно.",
                        message.author());
                return;
            }

            var pause = SEND_RETRY_MS[attempt];
            log.warn("Повторю отправку через {} с.", pause / 1000);

            try {
                Thread.sleep(pause);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Одна попытка: сначала вебхуком, а если он молчит — от имени бота. */
    private boolean post(TelegramMessage message, byte[] file, String failure) {
        if (webhook != null && byWebhook(message, file, failure)) {
            return true;
        }

        return byBot(message, file, failure);
    }

    /**
     * Пишет от имени написавшего: его ник и его аватарка.
     *
     * <p>К имени добавлена пометка источника. Вебхук позволяет назваться кем угодно,
     * и без неё писавший в Telegram мог бы выдать себя за участника сервера.
     */
    private boolean byWebhook(TelegramMessage message, byte[] file, String failure) {
        var text = quoted(message) + message.text();

        if (failure != null) {
            text = (text.isBlank() ? "" : text + "\n") + "*(вложение не перенеслось: "
                    + failure + ")*";
        }

        try {
            links.remember(message.messageId(),
                    webhook.send(message.author() + " · Telegram", avatarOf(message), text,
                            message.fileName(), file));
            return true;
        } catch (Exception e) {
            log.error("Вебхук не принял сообщение: {}. Пишу от имени бота.", e.getMessage());
            return false;
        }
    }

    /**
     * Аватарка человека, спрошенная один раз за всё время работы.
     *
     * <p>Запоминаем только то, что действительно ответил Telegram, — в том числе
     * «фотографии нет», это честный ответ. А вот обрыв связи в кэш не кладём:
     * иначе одна неудачная минута оставляла бы человека без аватарки навсегда,
     * до перезапуска бота.
     */
    private String avatarOf(TelegramMessage message) {
        // Discord забирает картинку сам, поэтому ссылка должна быть видна из интернета.
        // Сервер на localhost этому условию не отвечает, а вот свой в интернете — да
        if (!settings.avatars() || settings.isLocalApi() || message.authorId() == 0) {
            return "";
        }

        var known = avatars.get(message.authorId());

        if (known != null) {
            return known;
        }

        String found;

        try {
            found = telegram.avatar(message.authorId());
        } catch (Exception e) {
            log.warn("Аватарка {} не нашлась: {}. Спрошу снова со следующим сообщением.",
                    message.author(), e.getMessage());
            return "";
        }

        avatars.put(message.authorId(), found);

        if (found.isEmpty()) {
            log.info("У {} нет аватарки в Telegram — сообщения пойдут без неё.", message.author());
        }

        return found;
    }

    /**
     * Строка-отсылка к тому, на что отвечают.
     *
     * <p>Вебхуки Discord отвечать не умеют — в их запросе попросту нет поля для
     * этого. Поэтому обозначаем ответ цитатой со ссылкой: она кликается и уводит
     * к исходному сообщению, что почти так же удобно, как настоящая стрелка.
     */
    private String quoted(TelegramMessage message) {
        var answered = links.discordFor(message.replyToId());

        if (answered == 0) {
            return "";
        }

        var channel = jda == null ? null : jda.getChannelById(GuildMessageChannel.class,
                settings.channel());

        if (channel == null) {
            return "";
        }

        return "> ↩ [в ответ на это](https://discord.com/channels/"
                + channel.getGuild().getId() + "/" + channel.getId() + "/" + answered + ")\n";
    }

    private boolean byBot(TelegramMessage message, byte[] file, String failure) {
        var channel = jda.getChannelById(GuildMessageChannel.class, settings.channel());

        if (channel == null) {
            log.error("Канал {} не найден — сообщению из Telegram некуда деться.", settings.channel());
            return false;
        }

        var text = "**" + message.author() + "**"
                + (message.text().isBlank() ? "" : "\n" + message.text())
                + (failure == null ? "" : "\n*(вложение не перенеслось: " + failure + ")*");

        var action = channel.sendMessage(text);

        if (file != null) {
            action = action.setFiles(FileUpload.fromData(file, message.fileName()));
        }

        // От своего имени бот отвечать умеет, в отличие от вебхука, — и здесь ответ
        // получается настоящим
        var answered = links.discordFor(message.replyToId());

        if (answered != 0) {
            action = action.setMessageReference(answered).failOnInvalidReply(false);
        }

        // Упоминания обезвреживаем: иначе написавший в Telegram сможет дёрнуть @everyone
        // на сервере, куда его даже не приглашали
        try {
            // Ждём ответа, а не отправляем вслепую: без этого о неудаче узнавал бы
            // только лог, и повторять было бы нечего
            var sent = action.setAllowedMentions(List.of()).submit().join();
            links.remember(message.messageId(), sent.getIdLong());
            return true;
        } catch (Exception e) {
            log.error("Не удалось написать в Discord: {}", reason(e));
            return false;
        }
    }

    /** Внятная строка из цепочки причин: у JDA снаружи лежит пустая обёртка. */
    private static String reason(Throwable trouble) {
        var depth = 0;
        var said = "";

        for (var step = trouble; step != null && depth < 20; step = step.getCause(), depth++) {
            var message = step.getMessage();

            if (message != null && !message.isBlank()) {
                said = message;
            }
        }

        return said.isEmpty() ? trouble.getClass().getSimpleName() : said;
    }
}
