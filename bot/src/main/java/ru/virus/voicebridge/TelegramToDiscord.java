package ru.virus.voicebridge;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

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

    private final JDA jda;
    private final BridgeSettings settings;
    private final Telegram telegram;

    private volatile boolean running = true;
    private Thread worker;

    public TelegramToDiscord(JDA jda, BridgeSettings settings, Telegram telegram) {
        this.jda = jda;
        this.settings = settings;
        this.telegram = telegram;
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
        var channel = jda.getChannelById(GuildMessageChannel.class, settings.channel());

        if (channel == null) {
            log.error("Канал {} не найден — сообщению из Telegram некуда деться.", settings.channel());
            return;
        }

        var text = "**" + message.author() + "**"
                + (message.text().isBlank() ? "" : "\n" + message.text());

        var action = channel.sendMessage(text);

        if (settings.files() && !message.fileId().isBlank()) {
            try {
                var data = telegram.download(message.fileId(),
                        settings.maxMegabytes() * 1024 * 1024);
                action = action.setFiles(FileUpload.fromData(data, message.fileName()));
            } catch (Exception e) {
                log.error("Вложение из Telegram не забралось: {}", e.getMessage());
                action = channel.sendMessage(text + "\n*(вложение не перенеслось: "
                        + e.getMessage() + ")*");
            }
        }

        // Упоминания обезвреживаем: иначе написавший в Telegram сможет дёрнуть @everyone
        // на сервере, куда его даже не приглашали
        action.setAllowedMentions(List.of()).queue(ok -> { },
                error -> log.error("Не удалось написать в Discord: {}", error.getMessage()));
    }
}
