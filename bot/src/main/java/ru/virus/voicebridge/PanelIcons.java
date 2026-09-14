package ru.virus.voicebridge;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Icon;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.guild.GuildReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Иконки кнопок панели: режет один лист 4×4 на шестнадцать эмодзи и заводит их на сервере.
 *
 * <p>Своя картинка на кнопке возможна только через эмодзи сервера — Discord не принимает
 * файл прямо в кнопку. Поэтому бот загружает иконки один раз сам: просить человека
 * нарезать лист и вручную создать шестнадцать эмодзи, а потом переписывать их ID в код,
 * значит превратить смену картинок в отдельную работу.
 *
 * <p>Пока эмодзи нет, кнопки показывают стандартные символы Unicode — панель работает
 * и без листа.
 */
public final class PanelIcons extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(PanelIcons.class);

    /**
     * Порядок чтения листа: слева направо, сверху вниз.
     *
     * <p>Это же порядок кнопок на панели, так что лист можно читать как саму панель,
     * только пауза и продолжение разнесены — на кнопке они меняются местами.
     */
    private static final List<String> KEYS = List.of(
            "quieter", "previous", "pause", "play",
            "next", "louder", "winter", "spring",
            "charts", "autumn", "summer", "own",
            "artist", "stop", "code", "clear");

    /** Сколько иконок в ряду листа. */
    private static final int COLUMNS = 4;

    /** Имена эмодзи начинаются с этого, чтобы не спутать их с чужими. */
    private static final String PREFIX = "vb_";

    /** Discord всё равно ужимает эмодзи до 128 точек — отдаём сразу столько. */
    private static final int SIZE = 128;

    /** Предел Discord на файл эмодзи. */
    private static final int MAX_BYTES = 256 * 1024;

    /** Ниже этого считаем точку прозрачной, когда ищем края иконки. */
    private static final int ALPHA_FLOOR = 8;

    /**
     * Насколько цвет может отличаться от фонового, чтобы всё ещё считаться фоном.
     *
     * <p>Держим маленьким нарочно: светло-серая иконка по тону недалеко от шахматки,
     * и щедрый допуск выел бы её вместе с фоном.
     */
    private static final int BACKGROUND_TOLERANCE = 10;

    /**
     * Сколько картинки должно уцелеть после снятия фона.
     *
     * <p>Если залило почти всё, значит цвет иконок совпал с фоном и заливка прошла
     * сквозь них. Такой лист лучше оставить как есть: плитки на кнопках заметны,
     * но это лучше, чем пустые кнопки.
     */
    private static final double MIN_LEFT = 0.02;

    /**
     * Сколько разных цветов допускаем в фоне.
     *
     * <p>Двух хватает на шахматку, которой рисуют прозрачность; если по краю листа
     * цветов больше, это уже не фон, а часть картинки, и трогать её нельзя.
     */
    private static final int BACKGROUND_SHADES = 3;

    /**
     * Как бот обрабатывает лист сейчас.
     *
     * <p>Входит в отметку рядом с эмодзи: когда обработка меняется, уже загруженные
     * иконки становятся устаревшими, даже если сам лист остался прежним.
     */
    private static final String PROCESSING = "2";

    private final long guildId;
    private final Path sheet;

    /** Чем помечены загруженные эмодзи: по этой отметке видно, что лист поменяли. */
    private final Path marker;

    private final Map<String, Emoji> icons = new ConcurrentHashMap<>();

    public PanelIcons(long guildId, Path sheet) {
        this.guildId = guildId;
        this.sheet = sheet;
        this.marker = sheet.resolveSibling("panel-icons.state");
    }

    /**
     * Иконка кнопки.
     *
     * @param fallback символ Unicode на случай, если своей иконки ещё нет
     */
    public Emoji get(String key, String fallback) {
        var own = icons.get(key);
        return own != null ? own : Emoji.fromUnicode(fallback);
    }

    @Override
    public void onGuildReady(GuildReadyEvent event) {
        if (event.getGuild().getIdLong() != guildId) {
            return;
        }

        var guild = event.getGuild();
        remember(guild);

        if (!Files.isRegularFile(sheet)) {
            if (icons.isEmpty()) {
                log.info("Иконок панели нет, беру стандартные символы. Чтобы поставить свои, "
                        + "положи лист 4×4 в {} и перезапусти бота.", sheet.toAbsolutePath());
            } else {
                log.info("Иконки панели на месте: {} шт.", icons.size());
            }
            return;
        }

        String stamp;
        try {
            stamp = stamp();
        } catch (IOException e) {
            log.error("Не удалось прочитать лист иконок {}: {}", sheet.toAbsolutePath(), e.getMessage());
            return;
        }

        // Отметка не совпала — значит лист поменяли или бот стал обрабатывать его иначе.
        // Старые эмодзи в этом случае надо заменить, а не оставлять вперемешку с новыми.
        var stale = !stamp.equals(readMarker());

        if (!stale && icons.size() == KEYS.size()) {
            log.info("Иконки панели на месте: {} шт.", icons.size());
            return;
        }

        // Заливка идёт в своём потоке: каждое создание — отдельный запрос к Discord,
        // а на потоке событий JDA ждать их нельзя
        var worker = new Thread(() -> rebuild(guild, stale, stamp), "panel-icons");
        worker.setDaemon(true);
        worker.start();
    }

    /** Подбирает эмодзи, созданные прошлым запуском: заново их заводить незачем. */
    private void remember(Guild guild) {
        for (var key : KEYS) {
            var found = guild.getEmojisByName(PREFIX + key, false);
            if (!found.isEmpty()) {
                icons.put(key, found.get(0));
            }
        }
    }

    /**
     * Приводит эмодзи сервера в соответствие с листом.
     *
     * @param stale выбросить ли то, что загружено сейчас
     */
    private void rebuild(Guild guild, boolean stale, String stamp) {
        var self = guild.getSelfMember();

        if (!self.hasPermission(Permission.CREATE_GUILD_EXPRESSIONS)
                && !self.hasPermission(Permission.MANAGE_GUILD_EXPRESSIONS)) {
            log.error("Нет права «Управление выражениями» — не могу создать эмодзи панели. "
                    + "Выдай его роли бота в настройках сервера и перезапусти.");
            return;
        }

        Map<String, byte[]> tiles;
        try {
            tiles = cut();
        } catch (IOException e) {
            log.error("Не удалось прочитать лист иконок {}: {}", sheet.toAbsolutePath(), e.getMessage());
            return;
        }

        // Удаляем только после того, как новый лист прочитан: иначе неудачная замена
        // оставила бы панель вообще без иконок
        if (stale) {
            drop(guild);
        }

        var free = guild.getMaxEmojis() - guild.getEmojis().size();
        var missing = missing();

        if (free < missing.size()) {
            log.error("На сервере свободно {} мест под эмодзи, а нужно {}. "
                    + "Освободи место или подними уровень буста.", free, missing.size());
            return;
        }

        var made = 0;

        for (var key : missing) {
            var png = tiles.get(key);

            if (png == null) {
                continue;
            }

            if (png.length > MAX_BYTES) {
                log.error("Иконка «{}» весит {} КБ — Discord принимает до 256 КБ.", key, png.length / 1024);
                continue;
            }

            try {
                // complete вместо queue: создание эмодзи жёстко ограничено по частоте,
                // и ждать своей очереди здесь правильнее, чем ловить отказ
                var emoji = guild.createEmoji(PREFIX + key, Icon.from(png, Icon.IconType.PNG)).complete();
                icons.put(key, emoji);
                made++;
            } catch (Exception e) {
                log.error("Не удалось создать эмодзи «{}»: {}", PREFIX + key, e.getMessage());
            }
        }

        log.info("Иконки панели: создано {}, всего {} из {}.", made, icons.size(), KEYS.size());

        if (icons.size() == KEYS.size()) {
            writeMarker(stamp);
        }
    }

    /**
     * Убирает эмодзи, загруженные прошлым листом.
     *
     * <p>Трогаем только свои шестнадцать имён: всё остальное на сервере не наше.
     */
    private void drop(Guild guild) {
        var dropped = 0;

        for (var key : KEYS) {
            for (var emoji : guild.getEmojisByName(PREFIX + key, false)) {
                try {
                    emoji.delete().complete();
                    dropped++;
                } catch (Exception e) {
                    log.error("Не удалось удалить эмодзи «{}»: {}", emoji.getName(), e.getMessage());
                }
            }
            icons.remove(key);
        }

        if (dropped > 0) {
            log.info("Лист иконок поменялся: убрано старых эмодзи — {}.", dropped);
        }
    }

    private List<String> missing() {
        var missing = new ArrayList<String>();
        for (var key : KEYS) {
            if (!icons.containsKey(key)) {
                missing.add(key);
            }
        }
        return missing;
    }

    /** Отпечаток листа вместе с версией обработки. */
    private String stamp() throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return PROCESSING + ":" + HexFormat.of().formatHex(digest.digest(Files.readAllBytes(sheet)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("в этой Java нет SHA-256");
        }
    }

    private String readMarker() {
        try {
            return Files.isRegularFile(marker)
                    ? Files.readString(marker, StandardCharsets.UTF_8).trim()
                    : "";
        } catch (IOException e) {
            return "";
        }
    }

    private void writeMarker(String stamp) {
        try {
            Files.writeString(marker, stamp, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Без отметки бот просто перезальёт иконки на следующем запуске — не беда
            log.warn("Не удалось записать {}: {}", marker.toAbsolutePath(), e.getMessage());
        }
    }

    /**
     * Режет лист на шестнадцать картинок 128×128.
     */
    private Map<String, byte[]> cut() throws IOException {
        var image = ImageIO.read(sheet.toFile());

        if (image == null) {
            throw new IOException("это не картинка или формат не поддерживается");
        }

        var rows = (KEYS.size() + COLUMNS - 1) / COLUMNS;
        var width = image.getWidth() / COLUMNS;
        var height = image.getHeight() / rows;

        if (width == 0 || height == 0) {
            throw new IOException("картинка меньше, чем " + COLUMNS + "×" + rows + " клеток");
        }

        // Шахматку, которой редакторы рисуют прозрачность, часто сохраняют прямо в файл.
        // Человек видит её как «прозрачный фон», а на кнопке она станет серой плиткой.
        if (!hasTransparency(image)) {
            var cleaned = dropBackground(image);
            var left = opaqueShare(cleaned);

            if (left < MIN_LEFT) {
                log.warn("У листа иконок нет прозрачного фона, и снять его не вышло — "
                        + "цвет иконок слишком близок к фону. На кнопках будут плитки; "
                        + "пересохрани лист в PNG с альфа-каналом.");
            } else if (hasTransparency(cleaned)) {
                log.info("У листа иконок фон был залит — убрал его сам.");
                image = cleaned;
            } else {
                log.warn("У листа иконок нет прозрачного фона: на кнопках будут плитки. "
                        + "Пересохрани лист в PNG с альфа-каналом.");
            }
        }

        var tiles = new LinkedHashMap<String, byte[]>();

        for (var i = 0; i < KEYS.size(); i++) {
            var tile = image.getSubimage((i % COLUMNS) * width, (i / COLUMNS) * height, width, height);
            tiles.put(KEYS.get(i), encode(square(trim(tile))));
        }

        return tiles;
    }

    /**
     * Делает прозрачным залитый фон.
     *
     * <p>Цвета фона берутся по рамке листа, а заливка идёт от краёв внутрь: так уходит и
     * ровная подложка, и шахматка, но не трогается такой же цвет внутри самой иконки —
     * до него заливке не дойти.
     */
    private static BufferedImage dropBackground(BufferedImage image) {
        var width = image.getWidth();
        var height = image.getHeight();

        var shades = borderShades(image);

        // Пёстрый край — это не фон, а картинка во всю клетку. Лучше оставить как есть,
        // чем выесть из иконок половину
        if (shades.isEmpty()) {
            return image;
        }

        var out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var g = out.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();

        var seen = new boolean[width * height];
        var wave = new ArrayDeque<Integer>();

        for (var x = 0; x < width; x++) {
            offer(out, seen, wave, shades, x, 0, width);
            offer(out, seen, wave, shades, x, height - 1, width);
        }
        for (var y = 0; y < height; y++) {
            offer(out, seen, wave, shades, 0, y, width);
            offer(out, seen, wave, shades, width - 1, y, width);
        }

        while (!wave.isEmpty()) {
            var at = wave.poll();
            var x = at % width;
            var y = at / width;

            out.setRGB(x, y, 0);

            if (x > 0) {
                offer(out, seen, wave, shades, x - 1, y, width);
            }
            if (x < width - 1) {
                offer(out, seen, wave, shades, x + 1, y, width);
            }
            if (y > 0) {
                offer(out, seen, wave, shades, x, y - 1, width);
            }
            if (y < height - 1) {
                offer(out, seen, wave, shades, x, y + 1, width);
            }
        }

        return out;
    }

    /** Ставит точку в очередь заливки, если она похожа на фон и ещё не разобрана. */
    private static void offer(BufferedImage image, boolean[] seen, ArrayDeque<Integer> wave,
                              List<Integer> shades, int x, int y, int width) {
        var at = y * width + x;

        if (seen[at]) {
            return;
        }

        seen[at] = true;

        if (matches(image.getRGB(x, y), shades)) {
            wave.add(at);
        }
    }

    /**
     * Собирает цвета рамки. Пустой список означает «на фон не похоже».
     */
    private static List<Integer> borderShades(BufferedImage image) {
        var shades = new ArrayList<Integer>();

        for (var x = 0; x < image.getWidth(); x++) {
            for (var y : new int[] { 0, image.getHeight() - 1 }) {
                if (!collect(shades, image.getRGB(x, y))) {
                    return List.of();
                }
            }
        }

        for (var y = 0; y < image.getHeight(); y++) {
            for (var x : new int[] { 0, image.getWidth() - 1 }) {
                if (!collect(shades, image.getRGB(x, y))) {
                    return List.of();
                }
            }
        }

        return shades;
    }

    /** @return <code>false</code>, если цветов на краю оказалось больше, чем бывает у фона */
    private static boolean collect(List<Integer> shades, int color) {
        if (matches(color, shades)) {
            return true;
        }

        if (shades.size() == BACKGROUND_SHADES) {
            return false;
        }

        shades.add(color);
        return true;
    }

    private static boolean matches(int color, List<Integer> shades) {
        for (var shade : shades) {
            if (close(color, shade)) {
                return true;
            }
        }
        return false;
    }

    private static boolean close(int left, int right) {
        for (var shift : new int[] { 16, 8, 0 }) {
            if (Math.abs(((left >> shift) & 0xFF) - ((right >> shift) & 0xFF)) > BACKGROUND_TOLERANCE) {
                return false;
            }
        }
        return true;
    }

    /**
     * Обрезает прозрачные поля.
     *
     * <p>Без этого иконки на кнопках оказались бы разного размера: на листе одна занимает
     * почти всю клетку, другая треть, и Discord ужал бы их вместе с пустотой вокруг.
     */
    private static BufferedImage trim(BufferedImage tile) {
        var left = tile.getWidth();
        var top = tile.getHeight();
        var right = -1;
        var bottom = -1;

        for (var y = 0; y < tile.getHeight(); y++) {
            for (var x = 0; x < tile.getWidth(); x++) {
                if ((tile.getRGB(x, y) >>> 24) <= ALPHA_FLOOR) {
                    continue;
                }
                left = Math.min(left, x);
                right = Math.max(right, x);
                top = Math.min(top, y);
                bottom = Math.max(bottom, y);
            }
        }

        // Пустая клетка: обрезать нечего, отдаём как есть
        if (right < 0) {
            return tile;
        }

        return tile.getSubimage(left, top, right - left + 1, bottom - top + 1);
    }

    /**
     * Вписывает иконку в прозрачный квадрат 128×128, не растягивая её.
     */
    private static BufferedImage square(BufferedImage tile) {
        var scale = (double) SIZE / Math.max(tile.getWidth(), tile.getHeight());
        var width = Math.max(1, (int) Math.round(tile.getWidth() * scale));
        var height = Math.max(1, (int) Math.round(tile.getHeight() * scale));

        var out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        var g = out.createGraphics();

        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(tile, (SIZE - width) / 2, (SIZE - height) / 2, width, height, null);
        g.dispose();

        return out;
    }

    private static byte[] encode(BufferedImage image) throws IOException {
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** Какая доля картинки осталась непрозрачной. */
    private static double opaqueShare(BufferedImage image) {
        var opaque = 0;

        for (var y = 0; y < image.getHeight(); y++) {
            for (var x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) > ALPHA_FLOOR) {
                    opaque++;
                }
            }
        }

        return (double) opaque / (image.getWidth() * image.getHeight());
    }

    private static boolean hasTransparency(BufferedImage image) {
        for (var y = 0; y < image.getHeight(); y++) {
            for (var x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) < 250) {
                    return true;
                }
            }
        }
        return false;
    }
}
