package ru.virus.voicebridge;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Начисляет монеты за время, проведённое в голосовых каналах.
 *
 * <p>Раз в минуту обходит каналы гильдии и засчитывает минуту каждому, кто сидит не один.
 * Опрос вместо подсчёта по событиям входа и выхода выбран нарочно: событие можно
 * пропустить при обрыве связи, и тогда человеку либо накрутится лишнее, либо пропадёт
 * честно высиженное. Опрос же всегда исходит из того, что видно прямо сейчас.
 */
public final class VoiceCoinTicker {

    private static final Logger log = LoggerFactory.getLogger(VoiceCoinTicker.class);

    /** Сколько людей должно быть в канале, чтобы шло начисление. */
    private static final int MIN_COMPANY = 2;

    private final JDA jda;
    private final long guildId;
    private final RankStore store;
    private final int minutesPerCoin;
    private final ScheduledExecutorService scheduler;

    public VoiceCoinTicker(JDA jda, long guildId, RankStore store, int minutesPerCoin) {
        this.jda = jda;
        this.guildId = guildId;
        this.store = store;
        this.minutesPerCoin = minutesPerCoin;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "voice-coin-ticker");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.MINUTES);
        log.info("Начисление монет включено: {} минут за монету, от {} человек в канале.",
                minutesPerCoin, MIN_COMPANY);
    }

    public void stop() {
        scheduler.shutdownNow();
        store.save();
    }

    private void tick() {
        try {
            var guild = jda.getGuildById(guildId);
            if (guild == null) {
                return;
            }

            var afkChannel = guild.getAfkChannel();

            List<AudioChannel> channels = new ArrayList<>();
            channels.addAll(guild.getVoiceChannelCache().asList());
            channels.addAll(guild.getStageChannelCache().asList());

            for (var channel : channels) {
                // В отсидочном канале люди висят часами, ничего не делая, — там не считаем
                if (afkChannel != null && channel.getIdLong() == afkChannel.getIdLong()) {
                    continue;
                }

                List<Member> humans = new ArrayList<>();
                for (var member : channel.getMembers()) {
                    if (!member.getUser().isBot()) {
                        humans.add(member);
                    }
                }

                if (humans.size() < MIN_COMPANY) {
                    continue;
                }

                for (var member : humans) {
                    var earned = store.addMinute(member.getId(), minutesPerCoin);
                    if (earned > 0) {
                        log.info("{} получает монет: {}", member.getUser().getName(), earned);
                    }
                }
            }

            store.save();
        } catch (Exception e) {
            // Планировщик молча снимает задачу с повтора, если из неё вылетело исключение,
            // поэтому ловим всё: одна ошибка не должна навсегда остановить начисление.
            log.error("Сбой при начислении монет: {}", e.toString());
        }
    }
}
