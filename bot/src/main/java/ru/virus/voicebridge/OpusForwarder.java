package ru.virus.voicebridge;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame;
import net.dv8tion.jda.api.audio.AudioSendHandler;

import java.nio.ByteBuffer;

/**
 * Переливает звук из плеера lavaplayer в голосовое соединение JDA.
 *
 * <p>Плеер настроен отдавать сразу Opus — тот же формат, в котором Discord принимает
 * голос. Поэтому перекодировать ничего не нужно, кадр уходит как есть.
 */
public final class OpusForwarder implements AudioSendHandler {

    private final AudioPlayer player;

    // Один буфер на всё время работы: JDA спрашивает кадр пятьдесят раз в секунду,
    // и выделять под каждый новый массив — зря мусорить.
    private final ByteBuffer buffer = ByteBuffer.allocate(1024);
    private final MutableAudioFrame frame = new MutableAudioFrame();

    public OpusForwarder(AudioPlayer player) {
        this.player = player;
        this.frame.setBuffer(buffer);
    }

    @Override
    public boolean canProvide() {
        return player.provide(frame);
    }

    @Override
    public ByteBuffer provide20MsAudio() {
        return buffer.flip();
    }

    @Override
    public boolean isOpus() {
        return true;
    }
}
