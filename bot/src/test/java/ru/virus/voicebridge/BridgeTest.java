package ru.virus.voicebridge;

import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Мост между Discord и Telegram: то, что проверяется без живых серверов. */
class BridgeTest {

    static TelegramMessage parse(String json) {
        return TelegramMessage.from(DataObject.fromJson(json));
    }

    @Test
    void readsPlainMessage() {
        var message = parse("""
                {"update_id": 5, "message": {"message_id": 7, "chat": {"id": -100},
                 "from": {"id": 42, "first_name": "Вася", "last_name": "Пупкин"},
                 "text": "привет"}}""");

        assertEquals(5, message.updateId());
        assertEquals(7, message.messageId());
        assertEquals("-100", message.chat());
        assertEquals(42, message.authorId());
        assertEquals("Вася Пупкин", message.author());
        assertEquals("привет", message.text());
        assertFalse(message.fromBot());
        assertFalse(message.forwarded());
        assertEquals(0, message.replyToId());
    }

    @Test
    void readsReplyAndUsernameFallback() {
        var message = parse("""
                {"update_id": 1, "message": {"message_id": 9, "chat": {"id": -1},
                 "from": {"id": 3, "first_name": "", "username": "nick"},
                 "reply_to_message": {"message_id": 8, "from": {"id": 4, "first_name": "Петя"},
                                      "text": "кто тут?"},
                 "text": "я"}}""");

        assertEquals("nick", message.author());
        assertEquals(8, message.replyToId());
        assertEquals("Петя", message.replyToAuthor());
        assertEquals("кто тут?", message.replyToText());
    }

    @Test
    void readsLargestPhotoAndCaption() {
        var message = parse("""
                {"update_id": 1, "message": {"message_id": 2, "chat": {"id": -1},
                 "from": {"id": 3, "first_name": "Маша"},
                 "photo": [{"file_id": "small"}, {"file_id": "big"}],
                 "caption": "смотри"}}""");

        assertEquals("big", message.fileId());
        assertEquals("смотри", message.text());
    }

    @Test
    void marksForwardedAndInlineBot() {
        assertTrue(parse("""
                {"update_id": 1, "message": {"message_id": 2, "chat": {"id": -1},
                 "from": {"id": 3, "first_name": "Маша"}, "forward_origin": {"type": "user"},
                 "text": "цитата"}}""").forwarded());
        assertTrue(parse("""
                {"update_id": 1, "message": {"message_id": 2, "chat": {"id": -1},
                 "from": {"id": 3, "first_name": "Маша"}, "via_bot": {"id": 9},
                 "text": "гифка"}}""").forwarded());
    }

    @Test
    void skipsServiceUpdates() {
        assertNull(parse("""
                {"update_id": 1, "message": {"message_id": 2, "chat": {"id": -1},
                 "from": {"id": 3, "first_name": "Маша"}, "new_chat_members": []}}"""));
        assertNull(parse("""
                {"update_id": 1, "edited_message": {"message_id": 2, "text": "x"}}"""));
    }

    @Test
    void cardEscapesAndKeepsWithinLimit() {
        var card = Telegram.card("<Вася>", "a < b & c");

        assertTrue(card.startsWith("<b>&lt;Вася&gt;</b>"), card);
        assertTrue(card.contains("a &lt; b &amp; c"), card);

        var caption = Telegram.card("Вася", "х".repeat(5000), 1024);
        var visible = caption.replaceAll("<[^>]+>", "");
        assertTrue(visible.length() <= 1024, String.valueOf(visible.length()));
    }

    @Test
    void cardWithoutTextIsJustHeader() {
        assertEquals("<b>Вася</b> <i>· Discord</i>", Telegram.card("Вася", " "));
    }

    @Test
    void localApiDetection() {
        assertTrue(BridgeSettings.isLocal("http://127.0.0.1:8081"));
        assertTrue(BridgeSettings.isLocal("http://localhost:8081"));
        assertTrue(BridgeSettings.isLocal("http://192.168.1.5:8081"));
        assertTrue(BridgeSettings.isLocal("http://172.20.0.2"));
        assertTrue(BridgeSettings.isLocal("http://[::1]:8081"));
        assertFalse(BridgeSettings.isLocal("https://name.user.workers.dev"));
        assertFalse(BridgeSettings.isLocal("http://172.32.0.1"));
        assertFalse(BridgeSettings.isLocal(""));
    }

    @Test
    void webhookUrls() {
        var url = "https://discord.com/api/webhooks/123456/secret";

        assertTrue(DiscordWebhook.looksRight(url));
        assertEquals(123456, DiscordWebhook.idOf(url));
        assertFalse(DiscordWebhook.looksRight("https://evil.example/api/webhooks/1/x"));
        assertEquals(0, DiscordWebhook.idOf("https://discord.com/api/webhooks/abc/secret"));
        assertEquals(0, DiscordWebhook.idOf(""));
    }

    @Test
    void webhookNamesDiscordAccepts() {
        assertEquals("disc0rd fan", DiscordWebhook.name("Discord fan"));
        assertEquals("clyd3", DiscordWebhook.name("CLYDE"));
        assertEquals("Аноним", DiscordWebhook.name("  "));
    }

    @Test
    void linksForgetOldestAndIgnoreZero() {
        var links = new BridgeLinks();
        links.remember(1, 100);
        links.remember(0, 200);

        assertEquals(100, links.discordFor(1));
        assertEquals(1, links.telegramFor(100));
        assertEquals(0, links.telegramFor(200));

        for (var i = 2; i <= 5001; i++) {
            links.remember(i, 1000 + i);
        }

        assertEquals(0, links.discordFor(1), "самое старое должно забыться");
        assertEquals(6001, links.discordFor(5001));
    }

    @Test
    void localFilePathsFromOwnApiServer() {
        assertTrue(Telegram.isOnDisk("/var/lib/telegram-bot-api/file.jpg"));
        assertTrue(Telegram.isOnDisk("C:\\bot\\file.jpg"));
        assertFalse(Telegram.isOnDisk("photos/file_1.jpg"));
    }
}
