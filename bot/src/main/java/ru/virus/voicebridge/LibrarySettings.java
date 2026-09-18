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
 * @param cookieFile файл с куками; надёжнее браузера и старше его по приоритету
 * @param client каким клиентом YouTube представляться качалке: tv, android, ios, mweb
 * @param extraArgs что ещё передать качалке — прокси, формат, обходные ключи
 */
public record LibrarySettings(boolean enabled, String folder, long channel, String ytdlp,
                              String ffmpeg, int maxMinutes, int maxMegabytes, int waitMinutes,
                              List<String> hosts, String cookies, String cookieFile,
                              String client, List<String> extraArgs) {

    /**
     * Достраивает пути до файлов, положенных рядом с настройками.
     *
     * <p>Всё остальное в боте ищется рядом с config.properties, а имя программы Windows
     * разбирает по своим правилам — по системным путям, а не по папке бота. Человек же,
     * написав «yt-dlp.exe», имеет в виду тот файл, который сам туда положил. Поэтому
     * сначала смотрим рядом с настройками, и только если там пусто — отдаём имя системе.
     *
     * @param base папка с файлом настроек
     */
    public LibrarySettings resolvedAgainst(java.nio.file.Path base) {
        return new LibrarySettings(enabled, folder, channel,
                nearby(base, ytdlp), nearby(base, ffmpeg),
                maxMinutes, maxMegabytes, waitMinutes, hosts,
                cookies, nearby(base, cookieFile), client, extraArgs);
    }

    private static String nearby(java.nio.file.Path base, String name) {
        if (name.isBlank()) {
            return name;
        }

        var candidate = base.resolve(name);
        return java.nio.file.Files.isRegularFile(candidate) ? candidate.toString() : name;
    }

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
