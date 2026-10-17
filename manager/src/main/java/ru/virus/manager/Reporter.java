package ru.virus.manager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Отчёт для разбора: всё, по чему можно понять, что пошло не так, — и ничего,
 * что опасно показать.
 *
 * <p>Внутри журналы всех процессов, настройки и состояние процессов. Токены и
 * ключи в настройках заменены звёздочками, пароль прокси скрыт, а в журналах
 * вычищено всё, что похоже на токен. Сообщения участника и память имён в отчёт
 * не идут вовсе: это чужая переписка, для разбора ошибок она не нужна.
 */
final class Reporter {

    /** Похоже на токен телеграм-бота: 1234567890:AAH… */
    private static final Pattern TELEGRAM_TOKEN = Pattern.compile("\\b\\d{6,12}:[A-Za-z0-9_-]{30,}");

    /** Похоже на токен Discord-бота: три части через точку. */
    private static final Pattern DISCORD_TOKEN = Pattern.compile("\\b[A-Za-z0-9_-]{23,28}\\.[A-Za-z0-9_-]{6,7}\\.[A-Za-z0-9_-]{27,}");

    /** Ссылка вебхука Discord — у кого она, тот пишет в канал. */
    private static final Pattern WEBHOOK = Pattern.compile("(?i)(discord(?:app)?\\.com/api/webhooks/\\d+/)[A-Za-z0-9_-]+");

    /** Логин и пароль в адресе прокси: «логин:пароль@хост:порт». */
    private static final Pattern PROXY_PASSWORD = Pattern.compile("^([^:@\\s]+):([^@\\s]+)@");

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private Reporter() {
    }

    /**
     * Собирает отчёт в папку reports.
     *
     * @param config файл настроек ботов; может не существовать
     * @return путь к готовому zip
     */
    static Path build(Path home, Supervisor supervisor, Path config) throws IOException {
        var folder = home.resolve("reports");
        Files.createDirectories(folder);
        var zip = folder.resolve("VIRUS-report-" + LocalDateTime.now().format(STAMP) + ".zip");

        try (var out = new ZipOutputStream(Files.newOutputStream(zip), StandardCharsets.UTF_8)) {
            put(out, "ПРОЧТИ.txt", """
                    Отчёт V.I.R.U.S. для разбора ошибок.

                    Токены и ключи в config.properties заменены звёздочками, из журналов
                    вычищено всё, что похоже на токен. Сообщения участника и память имён
                    сюда не попадают. Перед отправкой всё равно можно заглянуть внутрь.
                    """);
            put(out, "состояние.txt", state(supervisor));

            if (Files.isRegularFile(config)) {
                put(out, "config.properties", masked(ConfigFile.read(config)));
            } else {
                put(out, "config.properties", "# файла нет: " + config);
            }

            var processes = home.resolve("processes.json");

            if (Files.isRegularFile(processes)) {
                put(out, "processes.json", Files.readString(processes, StandardCharsets.UTF_8));
            }

            var logs = home.resolve("logs");

            if (Files.isDirectory(logs)) {
                try (var stream = Files.list(logs)) {
                    for (var log : stream.sorted().toList()) {
                        if (Files.isRegularFile(log)) {
                            put(out, "logs/" + log.getFileName(), scrub(Files.readString(log, StandardCharsets.UTF_8)));
                        }
                    }
                }
            }

            // Состояние пародии без переписки: выключена ли, сколько сказано
            var parodyState = config.resolveSibling("parody-state.json");

            if (Files.isRegularFile(parodyState)) {
                put(out, "parody-state.json", Files.readString(parodyState, StandardCharsets.UTF_8));
            }

            put(out, "файлы-бота.txt", listing(config.getParent()));
        }

        return zip;
    }

    private static void put(ZipOutputStream out, String name, String text) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    /** Настройки со звёздочками вместо секретов. Видно, задан ли секрет, но не он сам. */
    static String masked(ConfigFile config) {
        var copy = ConfigFile.parse(config.text());
        var changes = new LinkedHashMap<String, String>();

        for (var field : config.fields()) {
            var value = field.value();

            if (field.secret()) {
                changes.put(field.key(), value.isBlank() ? "" : "***задан, " + value.strip().length() + " знаков***");
            } else if (field.key().endsWith(".proxy") && PROXY_PASSWORD.matcher(value).find()) {
                changes.put(field.key(), PROXY_PASSWORD.matcher(value).replaceFirst("$1:***@"));
            }
        }

        copy.update(changes);
        return scrub(copy.text());
    }

    /** Вычищает из текста всё, что похоже на токен или ссылку вебхука. */
    static String scrub(String text) {
        text = TELEGRAM_TOKEN.matcher(text).replaceAll("***токен-telegram***");
        text = DISCORD_TOKEN.matcher(text).replaceAll("***токен-discord***");
        return WEBHOOK.matcher(text).replaceAll("$1***");
    }

    private static String state(Supervisor supervisor) {
        var lines = new ArrayList<String>();
        lines.add("Время: " + LocalDateTime.now());
        lines.add("Java: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        lines.add("Система: " + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " " + System.getProperty("os.arch"));
        lines.add("");

        for (var process : supervisor.all()) {
            var json = process.toJson();
            lines.add(process.spec().name() + " [" + process.spec().id() + "]: " + process.state()
                    + ", перезапусков " + json.get("restarts")
                    + (json.get("exitCode") != null ? ", код выхода " + json.get("exitCode") : "")
                    + (String.valueOf(json.get("note")).isBlank() ? "" : " — " + json.get("note")));
            lines.add("  команда: " + json.get("commandLine"));
            lines.add("  папка: " + process.workdir());
        }

        return String.join("\n", lines) + "\n";
    }

    /** Какие файлы лежат у бота — без содержимого: по ним видно, на месте ли сертификат и прочее. */
    private static String listing(Path folder) throws IOException {
        if (folder == null || !Files.isDirectory(folder)) {
            return "папки нет\n";
        }

        var lines = new ArrayList<String>();
        lines.add(folder.toString());

        try (var stream = Files.list(folder)) {
            for (var path : stream.sorted().toList()) {
                lines.add(Files.isDirectory(path) ? "  [папка] " + path.getFileName()
                        : "  " + path.getFileName() + "  (" + Files.size(path) + " байт)");
            }
        }

        return String.join("\n", lines) + "\n";
    }
}
