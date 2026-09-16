package ru.virus.voicebridge;

import java.util.List;
import java.util.Locale;

/**
 * Настройки своей фонотеки.
 *
 * @param folder папка со скачанным, рядом с настройками
 * @param channel канал, куда кидают ссылки; ноль — приём ссылок выключен
 * @param ytdlp чем качать: имя программы или путь к ней
 * @param ffmpeg где лежит ffmpeg; пусто — искать в PATH
 * @param hosts площадки, ссылки на которые принимаем
 * @param cookies браузер, откуда брать куки: YouTube многое отдаёт только вошедшим
 * @param extraArgs что ещё передать качалке — прокси, формат, обходные ключи
 */
public record LibrarySettings(boolean enabled, String folder, long channel, String ytdlp,
                              String ffmpeg, int maxMinutes, int maxMegabytes, int waitMinutes,
                              List<String> hosts, String cookies, List<String> extraArgs) {

    /** Можно ли играть из фонотеки: для этого канал не нужен, хватает папки. */
    public boolean isUsable() {
        return enabled && !folder.isBlank();
    }

    /** Принимаем ли ссылки из канала. */
    public boolean acceptsLinks() {
        return isUsable() && channel != 0;
    }

    /**
     * Наша ли это площадка.
     *
     * <p>Список нужен не для придирок: без него в канал можно бросить ссылку на что
     * угодно, и бот честно потащит с незнакомого сервера файл любого размера.
     */
    public boolean allows(String host) {
        var lower = host.toLowerCase(Locale.ROOT);

        for (var allowed : hosts) {
            if (lower.equals(allowed) || lower.endsWith("." + allowed)) {
                return true;
            }
        }

        return false;
    }
}
