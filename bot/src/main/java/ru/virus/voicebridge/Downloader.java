package ru.virus.voicebridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Скачивает звук по ссылке через yt-dlp.
 *
 * <p>Своей качалки здесь нет и быть не может: площадки меняют защиту чаще, чем выходят
 * сборки бота. yt-dlp этим и занимается, его обновляют отдельно от нас.
 */
public final class Downloader {

    private static final Logger log = LoggerFactory.getLogger(Downloader.class);

    /**
     * Метка строки с данными о скачанном.
     *
     * <p>yt-dlp пишет в вывод много всего; по метке находим нужную строку, не разбирая
     * остальное.
     */
    private static final String MARK = "VBMETA";

    private final LibrarySettings settings;
    private final Path folder;

    public Downloader(LibrarySettings settings, Path folder) {
        this.settings = settings;
        this.folder = folder;
    }

    /** Установлен ли yt-dlp: спрашиваем у него же версию. */
    public boolean isReady() {
        try {
            var probe = new ProcessBuilder(settings.ytdlp(), "--version")
                    .redirectErrorStream(true)
                    .start();

            var finished = probe.waitFor(20, TimeUnit.SECONDS);

            if (!finished) {
                probe.destroyForcibly();
                return false;
            }

            return probe.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Качает трек и кладёт его в папку фонотеки.
     *
     * @throws DownloadException с текстом, который не стыдно показать человеку
     */
    public LibraryTrack fetch(String url, String addedBy) throws DownloadException {
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            throw new DownloadException("не создать папку фонотеки: " + e.getMessage());
        }

        var command = command(url);
        log.info("Качаю {}", url);

        Process process;

        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new DownloadException("не запустить " + settings.ytdlp() + ": " + e.getMessage());
        }

        var output = new ArrayList<String>();
        String meta = null;

        try (var reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.startsWith(MARK + "\t")) {
                    meta = line;
                } else if (!line.isBlank()) {
                    output.add(line);
                }
            }
        } catch (IOException e) {
            process.destroyForcibly();
            throw new DownloadException("оборвалось чтение вывода: " + e.getMessage());
        }

        try {
            if (!process.waitFor(settings.waitMinutes(), TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new DownloadException("не уложился в " + settings.waitMinutes() + " мин.");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new DownloadException("скачивание прервано");
        }

        if (process.exitValue() != 0 || meta == null) {
            throw new DownloadException(reason(output));
        }

        return parse(meta, url, addedBy);
    }

    /**
     * Собирает команду.
     *
     * <p>Аргументы передаём списком, а не строкой: ссылку приносит человек из чата, и
     * через оболочку она превратилась бы в дыру. Двойное тире перед ссылкой не даёт
     * прочитать её как ключ, если та начинается с минуса.
     */
    private List<String> command(String url) {
        var command = new ArrayList<String>(List.of(
                settings.ytdlp(),
                "--no-playlist",
                "--extract-audio",
                "--audio-format", "opus",
                "--audio-quality", "0",
                // Лучше отказать заранее, чем скачать восьмичасовой стрим дождя
                "--match-filter", "duration<?" + (settings.maxMinutes() * 60),
                "--max-filesize", settings.maxMegabytes() + "M",
                "--paths", folder.toAbsolutePath().toString(),
                "--output", "%(extractor)s-%(id)s.%(ext)s",
                "--print", "after_move:" + MARK
                        + "\t%(filepath)s\t%(title)s\t%(uploader)s\t%(duration)s",
                "--no-progress",
                "--no-warnings",
                "--no-colors"));

        if (!settings.ffmpeg().isBlank()) {
            command.add("--ffmpeg-location");
            command.add(settings.ffmpeg());
        }

        // Часть роликов YouTube отдаёт только вошедшим: «Video unavailable» снаружи и
        // обычная страница у того, кто залогинен. Куки браузера снимают это различие
        if (!settings.cookies().isBlank()) {
            command.add("--cookies-from-browser");
            command.add(settings.cookies());
        }

        // Площадки ломаются чаще, чем выходят сборки бота. Свои ключи — способ
        // починиться на месте, не дожидаясь новой версии
        command.addAll(settings.extraArgs());

        command.add("--");
        command.add(url);

        return command;
    }

    private LibraryTrack parse(String meta, String url, String addedBy) throws DownloadException {
        var parts = meta.split("\t", -1);

        if (parts.length < 5) {
            throw new DownloadException("yt-dlp ответил не тем, чего ждали");
        }

        var file = Path.of(parts[1]).getFileName().toString();
        var title = parts[2].isBlank() ? file : parts[2];
        var artist = parts[3].equals("NA") ? "" : parts[3];

        var seconds = 0;
        try {
            seconds = (int) Double.parseDouble(parts[4]);
        } catch (NumberFormatException e) {
            // Длительность знать приятно, но жить без неё можно
            seconds = 0;
        }

        if (!Files.isRegularFile(folder.resolve(file))) {
            throw new DownloadException("файл не появился в папке");
        }

        return new LibraryTrack(file, title, artist, seconds, url, addedBy);
    }

    /**
     * Выуживает из вывода строку, по которой человеку будет ясно, что не так.
     */
    private static String reason(List<String> output) {
        for (var i = output.size() - 1; i >= 0; i--) {
            var line = output.get(i);

            if (line.startsWith("ERROR:")) {
                return shorten(line.substring("ERROR:".length()).trim()) + hint(line);
            }
        }

        if (output.isEmpty()) {
            return "yt-dlp промолчал";
        }

        var last = output.get(output.size() - 1);
        return shorten(last) + hint(last);
    }

    private static String shorten(String text) {
        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }

    /**
     * Добавляет к отказу совет, если по нему понятно, чем лечится.
     *
     * <p>Текст от yt-dlp написан для того, кто сидит в консоли. В чате его читает
     * человек, которому нужно знать не что случилось, а что теперь делать.
     */
    private static String hint(String error) {
        var lower = error.toLowerCase(java.util.Locale.ROOT);

        if (lower.contains("video unavailable") || lower.contains("not available in your country")
                || lower.contains("blocked it in your country") || lower.contains("geo")) {
            return "\nПохоже на блокировку по стране. Включи VPN и брось ссылку заново.";
        }

        if (lower.contains("sign in") || lower.contains("not a bot") || lower.contains("login")
                || lower.contains("age")) {
            return "\nYouTube требует вход. Впиши в настройки music.library.cookies=chrome "
                    + "(или firefox, edge) и перезапусти бота.";
        }

        if (lower.contains("nsig") || lower.contains("player") || lower.contains("format")
                || lower.contains("unable to extract")) {
            return "\nПохоже, yt-dlp устарел. Обнови его: yt-dlp.exe -U";
        }

        return "";
    }

    /** Скачать не вышло. Текст рассчитан на то, что его увидят в чате. */
    public static final class DownloadException extends Exception {
        private static final long serialVersionUID = 1L;

        public DownloadException(String message) {
            super(message);
        }
    }
}
