package ru.virus.voicebridge;

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.entities.Guild;
import org.apache.http.client.config.RequestConfig;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * Плеер бота: поиск треков на SoundCloud, очередь и голосовое соединение.
 *
 * <p>Бот живёт на одном сервере, поэтому плеер здесь ровно один — заводить карту
 * «сервер → плеер» незачем.
 */
public final class MusicService {

    private static final Logger log = LoggerFactory.getLogger(MusicService.class);

    private final AudioPlayerManager manager = new DefaultAudioPlayerManager();
    private final AudioPlayer player;
    private final TrackQueue queue;

    /** Сколько ждать соединения с SoundCloud. Штатные три секунды до него не дотягиваются. */
    private static final int CONNECT_TIMEOUT_MS = 15_000;

    /** Сколько ждать данных после соединения. */
    private static final int SOCKET_TIMEOUT_MS = 30_000;

    public MusicService() {
        // Просим сразу Opus: именно его ждёт Discord, и лишнего перекодирования не будет
        manager.getConfiguration().setOutputFormat(StandardAudioDataFormats.DISCORD_OPUS);

        // По умолчанию lavaplayer отводит на соединение три секунды. До серверов
        // SoundCloud маршрут бывает длиннее, и поиск отваливается по таймауту там,
        // где достаточно было подождать.
        manager.setHttpRequestConfigurator(config -> RequestConfig.copy(config)
                .setConnectTimeout(CONNECT_TIMEOUT_MS)
                .setConnectionRequestTimeout(CONNECT_TIMEOUT_MS)
                .setSocketTimeout(SOCKET_TIMEOUT_MS)
                .build());

        manager.registerSourceManager(SoundCloudAudioSourceManager.createDefault());

        player = manager.createPlayer();
        queue = new TrackQueue(player);
        player.addListener(queue);
    }

    public TrackQueue getQueue() {
        return queue;
    }

    /** Текущая громкость в процентах. */
    public int getVolume() {
        return player.getVolume();
    }

    /**
     * Меняет громкость, удерживая её в разумных пределах.
     *
     * <p>Выше полутора сотен lavaplayer начинает заметно хрипеть, поэтому туда не пускаем.
     *
     * @return установленное значение
     */
    public int setVolume(int percent) {
        var value = Math.max(0, Math.min(150, percent));
        player.setVolume(value);
        return value;
    }

    /** Подключён ли бот к голосовому каналу. */
    public boolean isConnected(Guild guild) {
        return guild.getAudioManager().getConnectedChannel() != null;
    }

    /**
     * Подключается к голосовому каналу, если ещё не подключён.
     */
    public void connect(Guild guild, AudioChannel channel) {
        var audio = guild.getAudioManager();
        audio.setSendingHandler(new OpusForwarder(player));

        if (audio.getConnectedChannel() == null || audio.getConnectedChannel().getIdLong() != channel.getIdLong()) {
            audio.openAudioConnection(channel);
        }
    }

    /**
     * Отключается и очищает очередь.
     */
    public void disconnect(Guild guild) {
        queue.clear();
        guild.getAudioManager().closeAudioConnection();
    }

    /**
     * Ищет трек на SoundCloud по названию.
     *
     * <p>Берётся первый результат поиска: у SoundCloud он обычно и есть самый
     * подходящий, а перебирать варианты без разметки «официальный релиз» всё равно
     * не по чему.
     *
     * @return найденный трек или <code>null</code>, если ничего не нашлось
     */
    public CompletableFuture<AudioTrack> search(String query) {
        var result = new CompletableFuture<AudioTrack>();

        manager.loadItem("scsearch:" + query, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                result.complete(track);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                // Поиск всегда приходит сюда: результаты — это плейлист
                result.complete(playlist.getTracks().isEmpty() ? null : playlist.getTracks().get(0));
            }

            @Override
            public void noMatches() {
                result.complete(null);
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                log.error("Поиск «{}» не удался: {}", query, exception.getMessage());
                result.complete(null);
            }
        });

        return result;
    }

    public void shutdown() {
        queue.clear();
        manager.shutdown();
    }
}
