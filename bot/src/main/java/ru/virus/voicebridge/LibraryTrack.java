package ru.virus.voicebridge;

import net.dv8tion.jda.api.utils.data.DataObject;

/**
 * Запись в своей фонотеке.
 *
 * @param file имя файла внутри папки фонотеки — не полный путь, чтобы папку можно было
 *             перенести целиком
 * @param source ссылка, откуда трек взят; по ней же ловим повторы
 * @param seconds длительность; ноль означает «не удалось узнать»
 * @param addedBy кто принёс
 */
public record LibraryTrack(String file, String title, String artist, int seconds,
                           String source, String addedBy) {

    public static LibraryTrack from(DataObject json) {
        return new LibraryTrack(
                json.getString("file", ""),
                json.getString("title", ""),
                json.getString("artist", ""),
                json.getInt("seconds", 0),
                json.getString("source", ""),
                json.getString("addedBy", ""));
    }

    public DataObject toJson() {
        return DataObject.empty()
                .put("file", file)
                .put("title", title)
                .put("artist", artist)
                .put("seconds", seconds)
                .put("source", source)
                .put("addedBy", addedBy);
    }

    /** Как трек называть человеку. */
    public String label() {
        return artist.isBlank() ? title : artist + " — " + title;
    }

    /** По чему искать: и по названию, и по исполнителю разом. */
    public String haystack() {
        return title + " " + artist;
    }
}
