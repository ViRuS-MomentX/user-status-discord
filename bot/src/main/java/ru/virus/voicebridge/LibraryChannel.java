package ru.virus.voicebridge;

import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Канал, куда кидают ссылки на песни.
 *
 * <p>Бросил ссылку — бот скачал и положил в фонотеку. Дальше трек играет всегда и
 * мгновенно: ни поиска, ни таймаутов, ни зависимости от того, доступна ли площадка
 * сегодня.
 */
public final class LibraryChannel extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(LibraryChannel.class);

    private static final Pattern LINK = Pattern.compile("https?://\\S+");

    private static final Emoji WORKING = Emoji.fromUnicode("⏳");
    private static final Emoji DONE = Emoji.fromUnicode("✅");
    private static final Emoji FAILED = Emoji.fromUnicode("❌");

    private final long guildId;
    private final LibrarySettings settings;
    private final MusicLibrary library;
    private final Downloader downloader;

    /**
     * Качаем по одному.
     *
     * <p>Параллельные закачки делят один канал связи и только мешают друг другу, а при
     * включённом VPN ещё и упираются в него.
     */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "library-downloader");
        thread.setDaemon(true);
        return thread;
    });

    public LibraryChannel(long guildId, LibrarySettings settings, MusicLibrary library,
                          Downloader downloader) {
        this.guildId = guildId;
        this.settings = settings;
        this.library = library;
        this.downloader = downloader;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || !event.isFromGuild()
                || event.getGuild().getIdLong() != guildId
                || event.getChannel().getIdLong() != settings.channel()) {
            return;
        }

        var found = LINK.matcher(event.getMessage().getContentRaw());

        if (!found.find()) {
            return;
        }

        var url = found.group();
        var message = event.getMessage();

        String host;
        try {
            host = URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            host = null;
        }

        if (host == null || !settings.allows(host)) {
            message.addReaction(FAILED).queue();
            message.reply("Эту площадку не качаю. Разрешённые: "
                    + String.join(", ", settings.hosts())).queue();
            return;
        }

        if (library.has(url)) {
            message.addReaction(DONE).queue();
            message.reply("Уже в фонотеке: **" + library.get(url).label() + "**").queue();
            return;
        }

        message.addReaction(WORKING).queue();
        worker.submit(() -> download(message, url));
    }

    private void download(net.dv8tion.jda.api.entities.Message message, String url) {
        try {
            var track = downloader.fetch(url, message.getAuthor().getName());
            library.add(track);

            message.removeReaction(WORKING).queue(ok -> { }, error -> { });
            message.addReaction(DONE).queue();
            message.reply("Добавлено: **" + track.label() + "**. В фонотеке треков: "
                    + library.size() + ".").queue();

            log.info("В фонотеку добавлен «{}» ({}).", track.label(), url);
        } catch (Downloader.DownloadException e) {
            message.removeReaction(WORKING).queue(ok -> { }, error -> { });
            message.addReaction(FAILED).queue();
            message.reply("Не скачалось: " + e.getMessage()).queue();

            log.error("Не скачался {}: {}", url, e.getMessage());
        } catch (Exception e) {
            // Поток качалки один на всех: исключение отсюда не должно его хоронить
            message.addReaction(FAILED).queue();
            log.error("Сбой при скачивании {}: {}", url, e.toString());
        }
    }

    public void shutdown() {
        worker.shutdownNow();
    }
}
