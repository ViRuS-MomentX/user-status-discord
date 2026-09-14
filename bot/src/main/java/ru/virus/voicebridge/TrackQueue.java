package ru.virus.voicebridge;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;

/**
 * Очередь воспроизведения: что играет сейчас, что дальше и что уже отыграло.
 *
 * <p>Очередь делится на две части. Впереди идут личные просьбы — то, что люди заказали
 * кнопкой или командой; за ними подобранный плейлист. Просьба всегда обгоняет плейлист,
 * но не обгоняет чужую просьбу: между собой они выстраиваются в порядке поступления.
 */
public final class TrackQueue extends AudioEventAdapter {

    private static final Logger log = LoggerFactory.getLogger(TrackQueue.class);

    /** Сколько отыгравших треков помнить ради кнопки «предыдущая». */
    private static final int HISTORY_LIMIT = 50;

    private final AudioPlayer player;

    private final LinkedList<AudioTrack> queue = new LinkedList<>();
    private final Deque<AudioTrack> history = new ArrayDeque<>();

    /**
     * Сколько треков в голове очереди — личные просьбы.
     *
     * <p>Новая просьба встаёт сразу за ними: так она обгонит плейлист, но не отберёт
     * очередь у того, кто попросил раньше.
     */
    private int requests = 0;

    /**
     * Кого дёрнуть, когда очередь изменилась.
     *
     * <p>Нужно панели: она рисует состояние очереди, а меняется оно не только от нажатий
     * на её кнопки — трек кончается сам, плейлист догружается фоном.
     */
    private volatile Runnable onChange;

    public TrackQueue(AudioPlayer player) {
        this.player = player;
    }

    public void setOnChange(Runnable onChange) {
        this.onChange = onChange;
    }

    private void changed() {
        var listener = onChange;

        if (listener != null) {
            listener.run();
        }
    }

    /**
     * Ставит трек в конец очереди или включает сразу, если ничего не играет.
     */
    public synchronized void add(AudioTrack track) {
        // startTrack с noInterrupt возвращает false, если что-то уже играет,
        // и тогда трек просто ждёт своей очереди
        if (!player.startTrack(track, true)) {
            queue.addLast(track);
        }

        changed();
    }

    /**
     * Ставит личную просьбу: она заиграет после текущего трека и после просьб,
     * поступивших раньше, но раньше подобранного плейлиста.
     */
    public synchronized void addRequest(AudioTrack track) {
        if (player.startTrack(track, true)) {
            return;
        }

        queue.add(Math.min(requests, queue.size()), track);
        requests++;
        changed();
    }

    /**
     * Включает следующий трек. Если очередь пуста, воспроизведение останавливается.
     */
    public synchronized void next() {
        remember(player.getPlayingTrack());

        var track = queue.pollFirst();

        if (requests > 0) {
            requests--;
        }

        player.startTrack(track, false);
        changed();
    }

    /**
     * Возвращается к предыдущему треку. Текущий при этом встаёт в голову очереди,
     * чтобы его можно было доиграть.
     */
    public synchronized boolean previous() {
        var earlier = history.pollLast();

        if (earlier == null) {
            return false;
        }

        var current = player.getPlayingTrack();

        if (current != null) {
            // Клон нужен потому, что отыгравший трек нельзя запустить заново:
            // у него уже израсходован внутренний исполнитель
            queue.addFirst(current.makeClone());
            requests++;
        }

        player.startTrack(earlier.makeClone(), false);
        changed();
        return true;
    }

    /** Стоит ли воспроизведение на паузе. */
    public boolean isPaused() {
        return player.isPaused();
    }

    /**
     * Переключает паузу.
     *
     * @return <code>true</code>, если после переключения стоит пауза
     */
    public boolean togglePause() {
        var paused = !player.isPaused();
        player.setPaused(paused);
        changed();
        return paused;
    }

    /** Треки, ожидающие очереди. Играющий сюда не входит. */
    public synchronized List<AudioTrack> waiting() {
        return List.copyOf(queue);
    }

    public AudioTrack current() {
        return player.getPlayingTrack();
    }

    /**
     * Убирает всё, что ждёт очереди, не трогая текущий трек.
     */
    public synchronized void clearQueue() {
        queue.clear();
        requests = 0;
        changed();
    }

    /**
     * Останавливает воспроизведение и забывает всё.
     */
    public synchronized void clear() {
        queue.clear();
        history.clear();
        requests = 0;
        player.setPaused(false);
        player.stopTrack();
        changed();
    }

    private void remember(AudioTrack track) {
        if (track == null) {
            return;
        }

        history.addLast(track);

        // История нужна только для шага назад, хранить её всю незачем
        if (history.size() > HISTORY_LIMIT) {
            history.pollFirst();
        }
    }

    @Override
    public void onTrackStart(AudioPlayer player, AudioTrack track) {
        // Трек мог начаться и сам, без чьего-либо нажатия: так узнаём об этом все,
        // кто показывает очередь
        changed();
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
