package ru.virus.manager;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Одна копия V.I.R.U.S. на компьютер.
 *
 * <p>Две копии запустили бы ботов дважды с одними токенами. Первая держит порт
 * на 127.0.0.1; вторая, не сумев его занять, просит первую показать окно и
 * уходит — так повторный клик по ярлыку просто разворачивает V.I.R.U.S.
 */
final class SingleInstance {

    private static final String SHOW = "show";

    private final ServerSocket socket;

    private SingleInstance(ServerSocket socket) {
        this.socket = socket;
    }

    /**
     * @return замок, если мы первые; {@code null} — уже работает другая копия
     *         (ей отправлена просьба показать окно)
     */
    static SingleInstance acquire(int port) {
        try {
            var server = new ServerSocket();
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            return new SingleInstance(server);
        } catch (IOException taken) {
            try (var client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                client.getOutputStream().write((SHOW + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // порт занят чем-то чужим — значит, и показывать нечего
            }
            return null;
        }
    }

    /** Слушает просьбы следующих копий. */
    void onShow(Runnable show) {
        var listener = new Thread(() -> {
            while (!socket.isClosed()) {
                try (var client = socket.accept()) {
                    client.setSoTimeout(2000);
                    var line = new String(client.getInputStream().readNBytes(16), StandardCharsets.UTF_8).trim();

                    if (line.equals(SHOW)) {
                        show.run();
                    }
                } catch (IOException e) {
                    // соединение оборвалось — ждём следующего
                }
            }
        }, "virus-single-instance");
        listener.setDaemon(true);
        listener.start();
    }

    void release() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // и так закрываемся
        }
    }
}
