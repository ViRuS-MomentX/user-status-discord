package ru.virus.voicebridge;

import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Музыкальные команды.
 */
public final class MusicCommands extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(MusicCommands.class);

    private final long guildId;
    private final MusicService music;
    private final MusicCatalog catalog;
    private final int playlistSize;

    /**
     * Сборка плейлиста ходит в сеть и может занять секунды. Делать это в потоке событий
     * JDA нельзя: он один на всего бота, и пока он ждёт Last.fm, остальные команды
     * и голосовой статус стоят.
     */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "music-loader");
        thread.setDaemon(true);
        return thread;
    });

    public MusicCommands(long guildId, MusicService music, MusicCatalog catalog, int playlistSize) {
        this.guildId = guildId;
        this.music = music;
        this.catalog = catalog;
        this.playlistSize = playlistSize;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || !event.isFromGuild() || event.getGuild().getIdLong() != guildId) {
            return;
        }

        var raw = event.getMessage().getContentRaw().trim();
        var lower = raw.toLowerCase();

        if (lower.startsWith("start ")) {
            start(event, raw.substring("start ".length()).trim());
        } else if (lower.equals("скип") || lower.equals("skip")) {
            skip(event);
        } else if (lower.equals("стоп") || lower.equals("stop")) {
            stop(event);
        } else if (lower.equals("очередь") || lower.equals("queue")) {
            showQueue(event);
        }
    }

    private void start(MessageReceivedEvent event, String query) {
        if (query.isEmpty()) {
            event.getChannel().sendMessage("Напиши, что включить: `start Кино` или `start Группа крови`").queue();
            return;
        }

        var member = event.getMember();
        var state = member == null ? null : member.getVoiceState();

        if (state == null || state.getChannel() == null) {
            event.getChannel().sendMessage("Сначала зайди в голосовой канал.").queue();
            return;
        }

        if (!catalog.isConfigured()) {
            event.getChannel().sendMessage("Каталог " + catalog.name()
                    + " не настроен — подбирать плейлист нечем.").queue();
            return;
        }

        var channel = state.getChannel();
        music.connect(event.getGuild(), channel);

        event.getChannel().sendMessage("Ищу «" + query + "»...").queue();
        worker.submit(() -> buildPlaylist(event.getChannel(), query));
    }

    /**
     * Собирает плейлист и наполняет очередь.
     *
     * <p>Первый трек ставится отдельно и сразу: ждать, пока найдутся все полтора десятка,
     * значит слушать тишину несколько секунд.
     */
    private void buildPlaylist(MessageChannel reply, String query) {
        try {
            var songs = catalog.playlistFor(query, playlistSize);

            if (songs.isEmpty()) {
                reply.sendMessage(catalog.name() + " ничего не знает про «" + query + "».").queue();
                return;
            }

            var first = music.search(songs.get(0).query()).join();

            if (first == null) {
                reply.sendMessage("На SoundCloud не нашлось: " + songs.get(0)).queue();
                return;
            }

            music.getQueue().add(first);
            reply.sendMessage("Играет: **" + first.getInfo().title + "**\n"
                    + "Догружаю ещё " + (songs.size() - 1) + " треков...").queue();

            var added = 0;
            for (var song : songs.subList(1, songs.size())) {
                var track = music.search(song.query()).join();
                if (track != null) {
                    music.getQueue().add(track);
                    added++;
                }
            }

            reply.sendMessage("В очереди треков: " + added + ".").queue();
        } catch (Exception e) {
            log.error("Не удалось собрать плейлист по «{}»: {}", query, e.toString());
            reply.sendMessage("Что-то пошло не так при сборке плейлиста.").queue();
        }
    }

    private void skip(MessageReceivedEvent event) {
        var current = music.getQueue().current();

        if (current == null) {
            event.getChannel().sendMessage("Сейчас ничего не играет.").queue();
            return;
        }

        music.getQueue().next();

        var next = music.getQueue().current();
        event.getChannel().sendMessage(next == null
                ? "Пропущено. Очередь пуста."
                : "Пропущено. Играет: **" + next.getInfo().title + "**").queue();
    }

    private void stop(MessageReceivedEvent event) {
        music.disconnect(event.getGuild());
        event.getChannel().sendMessage("Остановлено, очередь очищена.").queue();
    }

    private void showQueue(MessageReceivedEvent event) {
        var current = music.getQueue().current();
        var waiting = music.getQueue().waiting();

        if (current == null) {
            event.getChannel().sendMessage("Сейчас ничего не играет.").queue();
            return;
        }

        var text = new StringBuilder("Играет: **").append(current.getInfo().title).append("**");

        if (waiting.isEmpty()) {
            text.append("\nДальше ничего нет.");
        } else {
            text.append("\n\nДалее:");
            // Список целиком не влезет в лимит сообщения, да и читать его никто не станет
            var shown = Math.min(waiting.size(), 10);
            for (var i = 0; i < shown; i++) {
                text.append("\n").append(i + 1).append(". ").append(waiting.get(i).getInfo().title);
            }
            if (waiting.size() > shown) {
                text.append("\n... и ещё ").append(waiting.size() - shown);
            }
        }

        event.getChannel().sendMessage(text.toString()).queue();
    }
}
