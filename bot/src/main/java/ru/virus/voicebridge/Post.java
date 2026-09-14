package ru.virus.voicebridge;

import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * Запись из ленты сайта.
 *
 * @param image путь к картинке относительно корня сайта; пустая строка — без картинки
 */
public record Post(String title, String date, String time, String text, String image, String category) {

    public static Post from(DataObject json) {
        return new Post(
                json.getString("title", ""),
                json.getString("date", ""),
                json.getString("time", ""),
                json.getString("text", ""),
                json.getString("image", ""),
                json.getString("category", ""));
    }

    /**
     * Чем запись отличается от остальных.
     *
     * <p>Своего номера у постов нет, поэтому опознаём их по заголовку и времени: в одну
     * минуту две записи с одним названием не появляются.
     */
    public String key() {
        return date + " " + time + " " + title;
    }

    /**
     * Когда запись опубликована.
     *
     * <p>На сайте дата и время записаны без часового пояса и означают местное время
     * автора — бот работает на его же машине, поэтому берём пояс системы.
     *
     * @return <code>null</code>, если даты нет или она записана непонятно
     */
    public OffsetDateTime published() {
        if (date.isEmpty()) {
            return null;
        }

        try {
            var moment = LocalDateTime.parse(date + "T" + (time.isEmpty() ? "00:00" : time));
            return moment.atZone(ZoneId.systemDefault()).toOffsetDateTime();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
