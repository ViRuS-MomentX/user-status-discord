package ru.virus.voicebridge;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Очередь воспроизведения: ставит следующий трек, когда закончился предыдущий.
 */
public final class TrackQueue extends AudioEventAdapter {

    private static final Logger log = LoggerFactory.getLogger(TrackQueue.class);

    private final AudioPlayer player;
    // Двусторонняя: новую просьбу во время проигрывания кладём в голову,
    // а не в хвост к полутора десяткам треков плейлиста
    private final BlockingDeque<AudioTrack> queue = new LinkedBlockingDeque<>();

    public TrackQueue(AudioPlayer player) {
        this.player = player;
    }

    /**
     * Ставит трек в очередь или включает сразу, если ничего не играет.
     */
    public void add(AudioTrack track) {
        // startTrack с noInterrupt возвращает false, если что-то уже играет,
        // и тогда трек просто ждёт своей очереди
        if (!player.startTrack(track, true)) {
            queue.offerLast(track);
        }
    }

    /**
     * Ставит трек следующим: он заиграет сразу после текущего, не дожидаясь остального.
     */
    public void addNext(AudioTrack track) {
        if (!player.startTrack(track, true)) {
            queue.offerFirst(track);
        }
    }

    /**
     * Включает следующий трек. Если очередь пуста, воспроизведение останавливается.
     */
    public void next() {
        player.startTrack(queue.poll(), false);
    }

    /** Треки, ожидающие очереди. Играющий сюда не входит. */
    public List<AudioTrack> waiting() {
        return List.copyOf(queue);
    }

    public AudioTrack current() {
        return player.getPlayingTrack();
    }

    /**
     * Останавливает воспроизведение и забывает очередь.
     */
    public void clear() {
        queue.clear();
        player.stopTrack();
    }

    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
        // mayStartNext false означает, что трек прервали вручную: следующий там
        // запускает тот, кто прервал, иначе он проскочил бы дважды
        if (endReason.mayStartNext) {
            next();
        }
    }

    @Override
    public void onTrackException(AudioPlayer player, AudioTrack track, FriendlyException exception) {
        // Битую ссылку или удалённый трек молча пропускаем — очередь важнее одного трека
        log.error("Трек «{}» не проигрался: {}", track.getInfo().title, exception.getMessage());
    }
}
