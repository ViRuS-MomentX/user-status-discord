package ru.virus.voicebridge;

import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Музыкальные команды.
 */
public final class MusicCommands extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(MusicCommands.class);

    private final long guildId;
    private final MusicRequests requests;
    private final MusicService music;

    public MusicCommands(long guildId, MusicRequests requests) {
        this.guildId = guildId;
        this.requests = requests;
        this.music = requests.getMusic();
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

    /**
     * Следит за тем, как сам бот входит и выходит из голосовых каналов.
     *
     * <p>Нужно, чтобы отличить «подключились и молчим» от «соединение рвётся и
     * пересоздаётся по кругу»: снаружи и то и другое выглядит одинаково.
     */
    @Override
    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        if (event.getGuild().getIdLong() != guildId
                || event.getMember().getIdLong() != event.getJDA().getSelfUser().getIdLong()) {
            return;
        }

        var playing = music.getQueue().current();
        var what = playing == null ? "ничего не играет" : "играет «" + playing.getInfo().title + "»";

        if (event.getChannelJoined() != null) {
            log.info("Бот вошёл в «{}» ({}).", event.getChannelJoined().getName(), what);
        } else if (event.getChannelLeft() != null) {
            log.warn("Бот вышел из «{}» ({}). Если это повторяется — рвётся голосовое соединение.",
                    event.getChannelLeft().getName(), what);
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

        if (!requests.getCatalog().isConfigured()) {
            event.getChannel().sendMessage("Каталог " + requests.getCatalog().name()
                    + " не настроен — подбирать плейлист нечем.").queue();
            return;
        }

        music.connect(event.getGuild(), state.getChannel());

        var reply = event.getChannel();
        reply.sendMessage("Ищу «" + query + "»...").queue();
        requests.submit(query, text -> reply.sendMessage(text).queue());
    }

    /**
     * Собирает плейлист и наполняет очередь.
     *
     * <p>Первый трек ставится отдельно и сразу: ждать, пока найдутся все полтора десятка,
     * значит слушать тишину несколько секунд.
     */
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
