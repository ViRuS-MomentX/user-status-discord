package ru.virus.voicebridge;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramRouteTest {

    @Test
    void closedRouteSwitchesToTheOther() throws Exception {
        int closed;

        try (var socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }

        try (var fake = new FakeTelegram()) {
            var dead = "http://127.0.0.1:" + closed;
            var telegram = new Telegram("T", List.of(dead, fake.url()));

            // Первый путь закрыт: запрос падает, но следующий пойдёт другим путём
            assertThrows(IOException.class, telegram::whoAmI);
            assertEquals(fake.url(), telegram.route());
            assertEquals("parody_bot", telegram.whoAmI());
        }
    }

    @Test
    void officialAloneHasNoSpare() {
        var telegram = new Telegram("T", "", "");
        assertTrue(telegram.route().startsWith("https://api.telegram.org"));
        assertEquals("example.workers.dev", Telegram.host("https://example.workers.dev/x"));
    }
}
