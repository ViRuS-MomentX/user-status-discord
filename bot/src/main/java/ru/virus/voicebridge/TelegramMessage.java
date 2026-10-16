package ru.virus.voicebridge;

import net.dv8tion.jda.api.utils.data.DataObject;

/**
 * Сообщение из Telegram, приведённое к тому, что нужно мосту.
 *
 * @param updateId номер обновления; по нему Telegram понимает, что мы его забрали
 * @param messageId номер самого сообщения; по нему ответы находят его на той стороне
 * @param replyToId на какое сообщение отвечают; 0 — это не ответ
 * @param chat откуда пришло
 * @param authorId номер автора в Telegram; по нему берётся аватарка
 * @param author кого показать в Discord
 * @param text написанное; пустая строка, если прислали один файл
 * @param fileId чем забрать вложение; пустая строка, если его нет
 * @param fromBot прислал ли это бот — такое пересылать нельзя, иначе мост зациклится
 * @param replyToAuthor кому отвечают; пустая строка — это не ответ
 * @param replyToText что было в сообщении, на которое отвечают
 */
public record TelegramMessage(long updateId, long messageId, long replyToId, String chat,
                              long authorId, String author, String text, String fileId,
                              String fileName, boolean fromBot,
                              String replyToAuthor, String replyToText) {

    /**
     * Разбирает одно обновление.
     *
     * @return <code>null</code>, если в обновлении нет обычного сообщения
     */
    public static TelegramMessage from(DataObject update) {
        var updateId = update.getLong("update_id", 0);

        if (!update.hasKey("message")) {
            return null;
        }

        var message = update.getObject("message");
        var chat = message.hasKey("chat") ? message.getObject("chat").getLong("id", 0) : 0;
        var messageId = message.getLong("message_id", 0);

        // На что отвечают. Свои же пересланные сообщения бот узнаёт по этому номеру
        var replyToId = 0L;
        var replyToAuthor = "";
        var replyToText = "";

        if (message.hasKey("reply_to_message")) {
            var original = message.getObject("reply_to_message");
            replyToId = original.getLong("message_id", 0);
            replyToText = original.getString("text", original.getString("caption", ""));
            replyToAuthor = original.hasKey("from") ? name(original.getObject("from")) : "Кто-то";
        }

        var author = "Кто-то";
        var authorId = 0L;
        var fromBot = false;

        if (message.hasKey("from")) {
            var from = message.getObject("from");
            authorId = from.getLong("id", 0);
            author = name(from);
            fromBot = from.getBoolean("is_bot", false);
        }

        var text = message.getString("text", message.getString("caption", ""));

        var fileId = "";
        var fileName = "";

        if (message.hasKey("photo")) {
            // Одно фото приходит набором размеров: последний — самый крупный
            var sizes = message.getArray("photo");

            if (!sizes.isEmpty()) {
                fileId = sizes.getObject(sizes.length() - 1).getString("file_id", "");
                fileName = "photo.jpg";
            }
        } else if (message.hasKey("document")) {
            var document = message.getObject("document");
            fileId = document.getString("file_id", "");
            fileName = document.getString("file_name", "file");
        } else if (message.hasKey("voice")) {
            fileId = message.getObject("voice").getString("file_id", "");
            fileName = "voice.ogg";
        } else if (message.hasKey("video")) {
            fileId = message.getObject("video").getString("file_id", "");
            fileName = "video.mp4";
        } else if (message.hasKey("audio")) {
            var audio = message.getObject("audio");
            fileId = audio.getString("file_id", "");
            fileName = audio.getString("file_name", "audio.mp3");
        }

        if (text.isBlank() && fileId.isBlank()) {
            // Вход в группу, закрепление, смена названия — переносить нечего
            return null;
        }

        return new TelegramMessage(updateId, messageId, replyToId, String.valueOf(chat),
                authorId, author, text, fileId, fileName, fromBot, replyToAuthor, replyToText);
    }

    /** Как показывать человека: имя с фамилией, а без них — юзернейм. */
    private static String name(DataObject from) {
        var name = from.getString("first_name", "");
        var last = from.getString("last_name", "");

        if (!last.isBlank()) {
            name = name + " " + last;
        }

        return name.isBlank() ? from.getString("username", "Кто-то") : name;
    }
}
