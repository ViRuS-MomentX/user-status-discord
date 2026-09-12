package ru.virus.voicebridge;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.channel.update.ChannelUpdateNameEvent;
import net.dv8tion.jda.api.events.guild.GuildReadyEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.SessionDisconnectEvent;
import net.dv8tion.jda.api.events.session.SessionRecreateEvent;
import net.dv8tion.jda.api.events.session.SessionResumeEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Держит актуальный снимок того, в каком голосовом канале сидит отслеживаемый пользователь.
 *
 * <p>Работает только по одной гильдии: всё, что происходит на других серверах, игнорируется
 * ещё на входе.
 */
public final class VoiceTracker extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(VoiceTracker.class);

    private final long guildId;
    private final long userId;
    private final Supplier<Long> clock;

    private volatile VoiceSnapshot current = VoiceSnapshot.IDLE;
    private volatile boolean connected = false;

    public VoiceTracker(long guildId, long userId) {
        this(guildId, userId, System::currentTimeMillis);
    }

    /** Отдельный конструктор с часами — чтобы тесты не зависели от реального времени. */
    VoiceTracker(long guildId, long userId, Supplier<Long> clock) {
        this.guildId = guildId;
        this.userId = userId;
        this.clock = clock;
    }

    public VoiceSnapshot getSnapshot() {
        return current;
    }

    public boolean isConnected() {
        return connected;
    }

    @Override
    public void onReady(ReadyEvent event) {
        connected = true;

        Guild guild = event.getJDA().getGuildById(guildId);
        if (guild == null) {
            log.error("Бот не состоит в сервере с ID {}. Пригласи его на сервер и перезапусти.", guildId);
            return;
        }

        log.info("Подключился. Слежу за пользователем {} на сервере «{}».", userId, guild.getName());
        recompute(guild);
    }

    @Override
    public void onSessionResume(SessionResumeEvent event) {
        connected = true;

        // После обрыва состояние могло измениться, пока мы были не в сети, — пересчитываем.
        Guild guild = event.getJDA().getGuildById(guildId);
        if (guild != null) {
            recompute(guild);
        }
    }

    @Override
    public void onSessionRecreate(SessionRecreateEvent event) {
        // Сессию не возобновили, а создали заново: кэш гильдий приедет следующим,
        // пересчёт сделает onGuildReady.
        connected = true;
        log.info("Связь с Discord восстановлена.");
    }

    @Override
    public void onGuildReady(GuildReadyEvent event) {
        if (event.getGuild().getIdLong() != guildId) {
            return;
        }

        // Единственный момент, когда голосовые состояния гильдии точно загружены.
        // После обрыва связи снимок восстанавливается именно отсюда.
        connected = true;
        recompute(event.getGuild());
    }

    @Override
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        // Снимок не сбрасываем: он ещё, скорее всего, верен. Но помечаем, что данные
        // уже не подтверждаются гейтвеем, и CustomRP сам решит, доверять им или нет.
        connected = false;
        log.warn("Потеряна связь с Discord, пробую переподключиться.");
    }

    @Override
    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        if (event.getGuild().getIdLong() != guildId) {
            return;
        }

        boolean aboutUs = event.getMember().getIdLong() == userId;
        // Чужие входы и выходы важны, только если они меняют число людей в нашем канале.
        boolean touchesOurChannel = current.isInVoice()
                && (matches(event.getChannelJoined()) || matches(event.getChannelLeft()));

        if (aboutUs || touchesOurChannel) {
            recompute(event.getGuild());
        }
    }

    @Override
    public void onChannelUpdateName(ChannelUpdateNameEvent event) {
        // Канал переименовали прямо во время сидения в нём — обновим подпись.
        if (current.isInVoice() && event.getChannel().getIdLong() == current.getChannelId()) {
            recompute(event.getGuild());
        }
    }

    /**
     * Собирает снимок заново из кэша JDA.
     *
     * <p>Момент входа сохраняется, пока пользователь не сменил канал: иначе таймер в статусе
     * сбрасывался бы каждый раз, когда к нему кто-то подсаживается.
     */
    private void recompute(Guild guild) {
        VoiceSnapshot previous = current;
        AudioChannel channel = findUserChannel(guild);

        if (channel == null) {
            if (previous.isInVoice()) {
                log.info("Вышел из «{}».", previous.getChannelName());
            }
            current = VoiceSnapshot.IDLE;
            return;
        }

        boolean sameChannel = previous.isInVoice() && previous.getChannelId() == channel.getIdLong();
        long since = sameChannel ? previous.getSince() : clock.get();

        int members = 0;
        for (Member member : channel.getMembers()) {
            if (!member.getUser().isBot()) {
                members++;
            }
        }

        // У сервера иконки может не быть — тогда CustomRP просто не станет менять картинку
        var iconUrl = guild.getIconUrl();

        current = VoiceSnapshot.inChannel(channel.getIdLong(), guild.getName(), channel.getName(),
                iconUrl == null ? "" : iconUrl, members, since);

        if (!sameChannel) {
            log.info("Зашёл в «{}» на сервере «{}».", channel.getName(), guild.getName());
        }
    }

    /**
     * Ищет пользователя среди голосовых и трибунных каналов гильдии.
     *
     * <p>Смотрим именно списки участников каналов, а не Member#getVoiceState: участники,
     * сидящие в войсе, кэшируются интентом GUILD_VOICE_STATES, и привилегированный
     * GUILD_MEMBERS для этого не нужен.
     */
    private AudioChannel findUserChannel(Guild guild) {
        List<AudioChannel> channels = new ArrayList<>();
        channels.addAll(guild.getVoiceChannelCache().asList());
        channels.addAll(guild.getStageChannelCache().asList());

        for (AudioChannel channel : channels) {
            for (Member member : channel.getMembers()) {
                if (member.getIdLong() == userId) {
                    return channel;
                }
            }
        }
        return null;
    }

    private boolean matches(AudioChannel channel) {
        return channel != null && channel.getIdLong() == current.getChannelId();
    }
}
