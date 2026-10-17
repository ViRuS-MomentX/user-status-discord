package ru.virus.manager;

import org.junit.jupiter.api.Test;

import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordPresenceTest {

    @Test
    void handshakesAndSetsActivityWithPicture() throws Exception {
        // Короткий путь: у сокетов предел длины имени
        var dir = Files.createTempDirectory("dp");
        var socket = dir.resolve("discord-ipc-0");
        var received = new CopyOnWriteArrayList<String>();

        try (var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(socket));

            var fake = new Thread(() -> {
                try (var client = server.accept()) {
                    var pipe = new DiscordPresence.Pipe(Channels.newInputStream(client),
                            Channels.newOutputStream(client), client);
                    received.add(DiscordPresence.read(pipe));
                    DiscordPresence.send(pipe, 1, "{\"cmd\":\"DISPATCH\",\"evt\":\"READY\",\"data\":{}}");
                    received.add(DiscordPresence.read(pipe));
                    DiscordPresence.send(pipe, 1, "{\"cmd\":\"SET_ACTIVITY\",\"data\":{}}");
                    // Ждём закрытия, чтобы V.I.R.U.S. не увидел обрыв раньше времени
                    DiscordPresence.read(pipe);
                } catch (Exception ignored) {
                    // клиент ушёл — тест закончен
                }
            });
            fake.start();

            var status = new AtomicReference<>("");
            var presence = new DiscordPresence(() -> "123456789012345678",
                    () -> new DiscordPresence.Activity("Работает процессов: 2 из 2", "", "virus", "V.I.R.U.S.", 1_700_000_000_000L),
                    () -> List.of(socket), status::set);
            presence.start();

            var deadline = System.currentTimeMillis() + 5000;

            while (!status.get().equals("показывается в Discord") && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }

            presence.close();
            fake.join(2000);

            assertEquals("показывается в Discord", status.get());
            assertTrue(received.get(0).contains("\"client_id\":\"123456789012345678\""), received.get(0));
            var activity = received.get(1);
            assertTrue(activity.contains("\"cmd\":\"SET_ACTIVITY\""), activity);
            assertTrue(activity.contains("\"large_image\":\"virus\""), activity);
            assertTrue(activity.contains("\"details\":\"Работает процессов: 2 из 2\""), activity);
            assertTrue(activity.contains("\"start\":1700000000"), activity);
        } finally {
            Files.deleteIfExists(socket);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void withoutIdItStaysOff() throws Exception {
        var status = new AtomicReference<>("");
        var presence = new DiscordPresence(() -> "", () -> null, List::of, status::set);
        presence.start();
        var deadline = System.currentTimeMillis() + 3000;

        while (status.get().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        presence.close();
        assertEquals("выключено", status.get());
    }

    @Test
    void longLinesAreCut() {
        var json = DiscordPresence.setActivity(new DiscordPresence.Activity("x".repeat(300), "", "", "", 0));
        assertTrue(json.contains("\"details\":\"" + "x".repeat(127) + "…\""), json);
        assertTrue(!json.contains("assets"), json);
    }
}
