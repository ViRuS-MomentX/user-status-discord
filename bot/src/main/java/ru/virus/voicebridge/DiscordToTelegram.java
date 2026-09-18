package ru.virus.voicebridge;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Половина моста: из канала Discord в группу Telegram.
 */
public final class DiscordToTelegram extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(DiscordToTelegram.class);

    private final long guildId;
    private final BridgeSettings settings;
    private final Telegram telegram;

    /**
     * Отправка идёт отдельным потоком: она ходит в сеть, а на потоке событий JDA ждать
     * ответа нельзя — там же обрабатываются все остальные сообщения сервера.
     */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "bridge-to-telegram");
        thread.setDaemon(true);
        return thread;
    });

    public DiscordToTelegram(long guildId, BridgeSettings settings, Telegram telegram) {
        this.guildId = guildId;
        this.settings = settings;
        this.telegram = telegram;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        // Своё же сообщение, принесённое с той стороны, обратно не отправляем:
        // иначе две половины моста будут пересылать друг другу одно и то же без конца
        if (event.getAuthor().isBot() || !event.isFromGuild()
                || event.getGuild().getIdLong() != guildId
                || event.getChannel().getIdLong() != settings.channel()) {
            return;
        }

        var message = event.getMessage();
        var author = event.getMember() == null
                ? event.getAuthor().getEffectiveName()
                : event.getMember().getEffectiveName();

        worker.submit(() -> forward(message, author));
    }

    private void forward(Message message, String author) {
        try {
            var text = message.getContentDisplay();
            var attachments = message.getAttachments();

            if (!settings.files() || attachments.isEmpty()) {
                if (!text.isBlank()) {
                    telegram.sendMessage(settings.chat(), author, text);
                }
                return;
            }

            var first = true;

            for (var attachment : attachments) {
                var limit = settings.maxMegabytes() * 1024 * 1024;

                if (attachment.getSize() > limit) {
                    telegram.sendMessage(settings.chat(), author,
                            (first && !text.isBlank() ? text + "\n" : "")
                                    + "[файл «" + attachment.getFileName() + "» больше "
                                    + settings.maxMegabytes() + " МБ, не переношу]");
                    first = false;
                    continue;
                }

                var data = telegram.fetch(attachment.getUrl(), limit);

                // Подпись вешаем на первый файл: у остальных она была бы повтором
                telegram.sendFile(settings.chat(), author, first ? text : "",
                        attachment.getFileName(), data, isPicture(attachment.getFileName()));
                first = false;
            }
        } catch (Exception e) {
            log.error("Не удалось передать сообщение в Telegram: {}", e.getMessage());
        }
    }

    /** Картинку Telegram показывает прямо в ленте, остальное кладёт вложением. */
    private static boolean isPicture(String fileName) {
        var lower = fileName.toLowerCase(Locale.ROOT);

        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp");
    }

    public void shutdown() {
        worker.shutdownNow();
    }
}
