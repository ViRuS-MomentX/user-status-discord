package ru.virus.voicebridge;

import net.dv8tion.jda.api.utils.data.DataObject;

/**
 * Сообщение из Telegram, приведённое к тому, что нужно мосту.
 *
 * @param updateId номер обновления; по нему Telegram понимает, что мы его забрали
 * @param chat откуда пришло
 * @param authorId номер автора в Telegram; по нему берётся аватарка
 * @param author кого показать в Discord
 * @param text написанное; пустая строка, если прислали один файл
 * @param fileId чем забрать вложение; пустая строка, если его нет
 * @param fromBot прислал ли это бот — такое пересылать нельзя, иначе мост зациклится
 */
public record TelegramMessage(long updateId, String chat, long authorId, String author,
                              String text, String fileId, String fileName, boolean fromBot) {

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

        var author = "Кто-то";
        var authorId = 0L;
        var fromBot = false;

        if (message.hasKey("from")) {
            var from = message.getObject("from");
            authorId = from.getLong("id", 0);
            var name = from.getString("first_name", "");
            var last = from.getString("last_name", "");

            if (!last.isBlank()) {
                name = name + " " + last;
            }

            if (name.isBlank()) {
                name = from.getString("username", "Кто-то");
            }

            author = name;
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

        return new TelegramMessage(updateId, String.valueOf(chat), authorId, author, text,
                fileId, fileName, fromBot);
    }
}
