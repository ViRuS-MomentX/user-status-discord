package ru.virus.voicebridge;

/**
 * Слежение за лентой постов сайта.
 *
 * @param url адрес файла с лентой
 * @param channel куда объявлять новые записи
 * @param minutes как часто заглядывать в ленту
 */
public record PostsSettings(boolean enabled, String url, long channel, int minutes) {

    /** Хватает ли настроек, чтобы слежение имело смысл. */
    public boolean isUsable() {
        return enabled && !url.isEmpty() && channel != 0;
    }
}
