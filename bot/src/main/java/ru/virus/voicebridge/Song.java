package ru.virus.voicebridge;

/**
 * Одна песня в плейлисте: исполнитель и название.
 *
 * <p>Каталоги отдают только это — сам звук потом ищется на SoundCloud.
 */
public final class Song {

    public final String artist;
    public final String title;

    public Song(String artist, String title) {
        this.artist = artist == null ? "" : artist;
        this.title = title == null ? "" : title;
    }

    /** Строка для поиска на SoundCloud. */
    public String query() {
        return artist.isEmpty() ? title : artist + " " + title;
    }

    /** Сравнение названий без оглядки на регистр и лишние пробелы. */
    public boolean sameAs(Song other) {
        return other != null
                && normalize(artist).equals(normalize(other.artist))
                && normalize(title).equals(normalize(other.title));
    }

    public static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    @Override
    public String toString() {
        return artist.isEmpty() ? title : artist + " — " + title;
    }
}
