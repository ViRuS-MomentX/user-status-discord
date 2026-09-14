package ru.virus.voicebridge;

import net.dv8tion.jda.api.utils.data.DataObject;

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

    /** Когда запись опубликована, одной строкой. */
    public String when() {
        return date.isEmpty() ? time : date + ", " + time;
    }
}
