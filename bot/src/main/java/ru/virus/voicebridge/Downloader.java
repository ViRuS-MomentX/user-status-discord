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
import java.util.Locale;
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

        var builder = new ProcessBuilder(command).redirectErrorStream(true);

        // yt-dlp написан на Python, а тот пишет в трубу кодировкой системы — на русской
        // Windows это cp1251, и ошибка доходит до чата кракозябрами. Просим UTF-8 у
        // самого Python: перекодировать на своей стороне было бы гаданием
        builder.environment().put("PYTHONIOENCODING", "utf-8");

        try {
            process = builder.start();
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
        // обычная страница у того, кто залогинен.
        //
        // Файл с куками идёт первым: читать их прямо из браузера получается не всегда —
        // тот держит базу открытой, а свежий Chrome на Windows ещё и шифрует её так,
        // что снаружи не разобрать.
        if (!settings.cookieFile().isBlank()) {
            command.add("--cookies");
            command.add(settings.cookieFile());
        } else if (!settings.cookies().isBlank()) {
            command.add("--cookies-from-browser");
            command.add(settings.cookies());
        }

        // YouTube отвечает по-разному в зависимости от того, каким приложением к нему
        // пришли: то, что закрыто для браузера, открыто для телевизора или телефона.
        // Отдельная настройка, а не общие ключи: подбирать клиент приходится руками,
        // и делать это должно быть просто
        if (!settings.client().isBlank()) {
            command.add("--extractor-args");
            command.add("youtube:player_client=" + settings.client());
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
     * Приметы, по которым видно, чем лечится отказ.
     *
     * <p>Приметы нарочно длинные. Короткая подстрока ловит лишнее: «age» находится
     * внутри «page», и ошибка «Unable to download API page» получала совет про вход
     * в аккаунт, к которому не имела отношения.
     */
    private static final List<Advice> ADVICE = List.of(
            new Advice(
                    List.of("cookie"),
                    "До кук браузера не добраться: он держит их базу, а свежий Chrome "
                            + "на Windows ещё и шифрует её. Надёжнее выгрузить куки в файл "
                            + "и указать его в music.library.cookiefile — либо взять Firefox."),
            new Advice(
                    List.of("failed to establish a new connection", "connection refused",
                            "winerror 10061", "socks", "proxy", "connection reset",
                            "timed out", "unreachable"),
                    "Связи нет. Если в music.library.args прописан --proxy — убери его или "
                            + "запусти сам прокси; если включён VPN — проверь, что он работает."),
            new Advice(
                    List.of("video unavailable", "available in your country",
                            "blocked it in your country", "geo-restricted", "geo restriction",
                            "not available from your location"),
                    "Похоже на блокировку по стране. Включи VPN и брось ссылку заново."),
            new Advice(
                    List.of("sign in to confirm", "not a bot", "confirm your age",
                            "age-restricted", "login required", "private video",
                            "members-only", "join this channel"),
                    "YouTube требует вход. Дай боту куки: music.library.cookiefile=cookies.txt "
                            + "или music.library.cookies=firefox."),
            new Advice(
                    List.of("page needs to be reloaded", "please try again later",
                            "failed to extract any player response", "throttled"),
                    "YouTube не пустил того клиента, которым представился yt-dlp. Впиши в "
                            + "настройки music.library.client=tv (или android, ios, mweb) и "
                            + "перезапусти бота. Если не поможет — обнови качалку: yt-dlp.exe -U"),
            new Advice(
                    List.of("unable to extract", "nsig", "player response",
                            "requested format is not available"),
                    "Похоже, yt-dlp устарел. Обнови его: yt-dlp.exe -U"));

    /** Примета и что по ней советовать. */
    private record Advice(List<String> marks, String text) {

        boolean matches(String error) {
            for (var mark : marks) {
                if (error.contains(mark)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Добавляет к отказу совет, если по нему понятно, чем лечится.
     *
     * <p>Текст от yt-dlp написан для того, кто сидит в консоли. В чате его читает
     * человек, которому нужно знать не что случилось, а что теперь делать.
     */
    private static String hint(String error) {
        var lower = error.toLowerCase(Locale.ROOT);

        for (var advice : ADVICE) {
            if (advice.matches(lower)) {
                return "\n" + advice.text();
            }
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
