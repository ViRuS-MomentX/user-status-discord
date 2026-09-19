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

    /** Сколько текста берём из эмбедов бота: длиннее Telegram всё равно обрежет. */
    private static final int EMBED_LIMIT = 3000;

    private final long guildId;
    private final BridgeSettings settings;
    private final Telegram telegram;

    /** Под этим номером в канале появляется всё, что мы сами принесли из Telegram. */
    private final long ownWebhook;

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
        this.ownWebhook = DiscordWebhook.idOf(settings.webhook());
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getGuild().getIdLong() != guildId
                || event.getChannel().getIdLong() != settings.channel()) {
            return;
        }

        // Своё же сообщение, принесённое с той стороны, обратно не отправляем: иначе две
        // половины моста будут пересылать друг другу одно и то же без конца. Отсекаем
        // ровно два источника — свой вебхук и самого себя, — а не всех ботов подряд:
        // чужие боты в канале говорят по делу, и их слова должны доходить до Telegram
        var author = event.getAuthor();

        if (author.getIdLong() == event.getJDA().getSelfUser().getIdLong()
                || (ownWebhook != 0 && author.getIdLong() == ownWebhook)) {
            return;
        }

        var message = event.getMessage();
        var name = event.getMember() == null
                ? author.getEffectiveName()
                : event.getMember().getEffectiveName();

        // Ботов помечаем: в Telegram иначе не отличить живого человека от Ириса
        var shown = author.isBot() || event.isWebhookMessage() ? "🤖 " + name : name;

        worker.submit(() -> forward(message, shown));
    }

    private void forward(Message message, String author) {
        try {
            var text = textOf(message);
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

    /**
     * Что пересказать в Telegram.
     *
     * <p>Боты вроде Ириса обычно пишут не текстом, а карточкой: сама строка
     * сообщения пуста, а всё содержимое лежит в эмбеде. Без разбора эмбедов
     * такие сообщения доходили бы пустыми, то есть не доходили вовсе.
     */
    private static String textOf(Message message) {
        var said = message.getContentDisplay();

        if (!said.isBlank() || message.getEmbeds().isEmpty()) {
            return said;
        }

        var retold = new StringBuilder();

        for (var embed : message.getEmbeds()) {
            add(retold, embed.getTitle());
            add(retold, embed.getDescription());

            for (var field : embed.getFields()) {
                var name = visible(field.getName());
                var value = visible(field.getValue());

                if (!name.isEmpty() && !value.isEmpty()) {
                    add(retold, name + ": " + value);
                } else {
                    add(retold, name + value);
                }
            }

            if (retold.length() > EMBED_LIMIT) {
                break;
            }
        }

        return retold.toString().trim();
    }

    /**
     * Убирает пустоту, которая только притворяется текстом.
     *
     * <p>Поле эмбеда без заголовка Discord не принимает, поэтому боты ставят
     * туда невидимый символ. Без очистки он дошёл бы до Telegram двоеточием,
     * перед которым ничего нет.
     */
    private static String visible(String text) {
        return text == null ? "" : text.replaceAll("[\u200B-\u200F\uFEFF]", "").trim();
    }

    private static void add(StringBuilder text, String line) {
        if (line == null || line.isBlank()) {
            return;
        }

        if (!text.isEmpty()) {
            text.append('\n');
        }

        text.append(line.trim());
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
