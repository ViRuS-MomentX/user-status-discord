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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    private final long guildId;
    private final Path sheet;

    private final Map<String, Emoji> icons = new ConcurrentHashMap<>();

    public PanelIcons(long guildId, Path sheet) {
        this.guildId = guildId;
        this.sheet = sheet;
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

        var missing = missing();

        if (missing.isEmpty()) {
            log.info("Иконки панели на месте: {} шт.", icons.size());
            return;
        }

        if (!Files.isRegularFile(sheet)) {
            log.info("Иконок панели нет, беру стандартные символы. Чтобы поставить свои, "
                    + "положи лист 4×4 в {} и перезапусти бота.", sheet.toAbsolutePath());
            return;
        }

        // Заливка идёт в своём потоке: каждое создание — отдельный запрос к Discord,
        // а на потоке событий JDA ждать их нельзя
        var worker = new Thread(() -> upload(guild, missing), "panel-icons");
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

    private List<String> missing() {
        var missing = new ArrayList<String>();
        for (var key : KEYS) {
            if (!icons.containsKey(key)) {
                missing.add(key);
            }
        }
        return missing;
    }

    /**
     * Режет лист и заводит недостающие эмодзи.
     */
    private void upload(Guild guild, List<String> missing) {
        var self = guild.getSelfMember();

        if (!self.hasPermission(Permission.CREATE_GUILD_EXPRESSIONS)
                && !self.hasPermission(Permission.MANAGE_GUILD_EXPRESSIONS)) {
            log.error("Нет права «Управление выражениями» — не могу создать эмодзи панели. "
                    + "Выдай его роли бота в настройках сервера и перезапусти.");
            return;
        }

        var free = guild.getMaxEmojis() - guild.getEmojis().size();

        if (free < missing.size()) {
            log.error("На сервере свободно {} мест под эмодзи, а нужно {}. "
                    + "Освободи место или подними уровень буста.", free, missing.size());
            return;
        }

        Map<String, byte[]> tiles;
        try {
            tiles = cut();
        } catch (IOException e) {
            log.error("Не удалось прочитать лист иконок {}: {}", sheet.toAbsolutePath(), e.getMessage());
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

        if (!hasTransparency(image)) {
            log.warn("У листа иконок нет прозрачного фона — на кнопках будут квадратные плитки. "
                    + "Пересохрани его в PNG с альфа-каналом.");
        }

        var tiles = new LinkedHashMap<String, byte[]>();

        for (var i = 0; i < KEYS.size(); i++) {
            var tile = image.getSubimage((i % COLUMNS) * width, (i / COLUMNS) * height, width, height);
            tiles.put(KEYS.get(i), encode(square(trim(tile))));
        }

        return tiles;
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
