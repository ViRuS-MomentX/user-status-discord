package ru.virus.manager;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Что показывает большой экран корпуса (3,5″ на материнке): режим выбирается
 * в приложении. Все режимы — на фоне в стиле V.I.R.U.S.
 */
final class MainScreen {

    /** Режим: код в настройках и подпись в списке. */
    record Mode(String id, String title) {
    }

    static final List<Mode> MODES = List.of(
            new Mode("processes", "Фоновые процессы"),
            new Mode("auto", "Авто: музыка, когда играет, иначе процессы"),
            new Mode("claude", "Клод танцует"),
            new Mode("music", "Музыка из Spotify"),
            new Mode("clock", "Часы и система"));

    private MainScreen() {
    }

    static String title(String id) {
        return MODES.stream().filter(mode -> mode.id().equals(id)).map(Mode::title).findFirst().orElse(MODES.getFirst().title());
    }

    /** Как часто обновлять кадр: Клоду нужна плавность, часам — раз в секунду. */
    static long period(String mode, MediaNow.Track track) {
        return switch (mode) {
            case "claude" -> 80;
            case "music", "auto" -> track != null ? 400 : 1000;
            default -> 1000;
        };
    }

    static BufferedImage render(String mode, int width, int height, Map<String, String> processes, MediaNow.Track track,
                                double cpu, long now) {
        return switch (mode) {
            case "claude" -> claude(width, height, now);
            case "clock" -> clock(width, height, cpu, now);
            case "music" -> track != null ? music(width, height, track, now) : waiting(width, height, now);
            case "auto" -> track != null ? music(width, height, track, now) : ScreenBoard.render(width, height, processes, now);
            default -> ScreenBoard.render(width, height, processes, now);
        };
    }

    private static Graphics2D start(BufferedImage image, long now) {
        var g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        ScreenBoard.background(g, image.getWidth(), image.getHeight(), now);
        return g;
    }

    private static BufferedImage finish(BufferedImage image, Graphics2D g) {
        ScreenBoard.scanlines(g, image.getWidth(), image.getHeight());
        g.dispose();
        return image;
    }

    /** Клод на своей сцене: танцует не переставая и каждые 10 секунд машет ручкой. */
    static BufferedImage claude(int width, int height, long now) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        ClaudeMascot.stage(g, width, height, now);
        var u = Math.min(width / 56.0, height / 40.0);
        // Ножки стоят на танцполе
        ClaudeMascot.draw(g, width / 2.0, height * 0.8 - 8 * u, u, now);
        g.dispose();
        return image;
    }

    /** Приветствие при запуске поверх кадра (кроме режима «Клод» — там он и так на сцене). */
    static boolean intro(BufferedImage frame, String mode, long elapsed, long now) {
        if ("claude".equals(mode)) {
            return false;
        }

        var g = frame.createGraphics();

        try {
            return ClaudeMascot.intro(g, frame.getWidth(), frame.getHeight(), elapsed, now);
        } finally {
            g.dispose();
        }
    }

    /** Часы крупно, дата, загрузка процессора и температура видеокарты. */
    static BufferedImage clock(int width, int height, double cpu, long now) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = start(image, now);
        var time = LocalDateTime.now();
        var big = height / 3.2f;
        g.setFont(Theme.mono.deriveFont(Font.BOLD, big));
        var text = time.format(DateTimeFormatter.ofPattern("HH:mm"));
        var metrics = g.getFontMetrics();
        var x = (width - metrics.stringWidth(text)) / 2f;
        var y = height * 0.48f;
        g.setColor(new Color(57, 255, 138, 60));
        g.drawString(text, x + 3, y + 3);
        g.setColor(Theme.ACCENT);
        g.drawString(text, x, y);

        // Секунды — полосой под часами
        var bar = (float) (time.getSecond() + time.getNano() / 1e9) / 60f;
        var barW = metrics.stringWidth(text);
        g.setColor(Theme.LINE_2);
        g.fill(new RoundRectangle2D.Float(x, y + 14, barW, 6, 6, 6));
        g.setColor(Theme.ACCENT);
        g.fill(new RoundRectangle2D.Float(x, y + 14, Math.max(6, barW * bar), 6, 6, 6));

        g.setFont(Theme.ui.deriveFont(Font.BOLD, height / 17f));
        g.setColor(Theme.TEXT);
        var date = time.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.forLanguageTag("ru")));
        date = date.substring(0, 1).toUpperCase(Locale.forLanguageTag("ru")) + date.substring(1);
        var dateW = g.getFontMetrics().stringWidth(date);
        g.drawString(date, (width - dateW) / 2f, y + height / 8f);

        // Плашки снизу: процессор и видеокарта
        var gpu = GpuSensor.celsius();
        var tiles = List.of(new String[] {"ЦП", Math.round(cpu * 100) + "%"},
                new String[] {"ВИДЕОКАРТА", gpu >= 0 ? gpu + "°" : "—"});
        var tileW = width / 3;
        var tileH = height / 6;
        var gap = width / 18;
        var startX = (width - tileW * 2 - gap) / 2;
        var tileY = height - tileH - height / 14;

        for (var i = 0; i < tiles.size(); i++) {
            var tx = startX + i * (tileW + gap);
            g.setColor(Theme.CARD_SOLID);
            g.fill(new RoundRectangle2D.Float(tx, tileY, tileW, tileH, 14, 14));
            ScreenBoard.brackets(g, tx, tileY, tileW, tileH, Theme.ACCENT);
            g.setFont(Theme.mono.deriveFont(Font.BOLD, tileH / 4.5f));
            g.setColor(Theme.MUTED);
            g.drawString(tiles.get(i)[0], tx + tileW * 0.1f, tileY + tileH * 0.4f);
            g.setFont(Theme.mono.deriveFont(Font.BOLD, tileH / 2.4f));
            g.setColor(i == 1 && gpu >= 0 ? GpuSensor.color(gpu) : Theme.ACCENT);
            var value = tiles.get(i)[1];
            g.drawString(value, tx + tileW * 0.9f - g.getFontMetrics().stringWidth(value), tileY + tileH * 0.8f);
        }

        return finish(image, g);
    }

    /** «Музыка», а ничего не играет — тихая заставка. */
    static BufferedImage waiting(int width, int height, long now) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = start(image, now);
        ScreenBoard.emblem(g, width / 2f, height / 2f - height / 12f, height / 7f);
        g.setFont(Theme.ui.deriveFont(Font.BOLD, height / 18f));
        g.setColor(Theme.MUTED);
        var text = "Spotify молчит";
        g.drawString(text, (width - g.getFontMetrics().stringWidth(text)) / 2f, height * 0.78f);
        return finish(image, g);
    }

    /** Музыка на большом экране: обложка слева, справа трек, исполнитель и время. */
    static BufferedImage music(int width, int height, MediaNow.Track track, long now) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = start(image, now);
        var size = (int) (height * 0.56);
        var x = (int) (width * 0.06);
        var y = (int) (height * 0.1);

        // Обложка в зелёной рамке
        g.setColor(Theme.CARD_SOLID);
        g.fill(new RoundRectangle2D.Float(x, y, size, size, 16, 16));

        if (track.cover() != null) {
            var clip = g.getClip();
            g.clip(new RoundRectangle2D.Float(x + 6, y + 6, size - 12, size - 12, 12, 12));
            g.drawImage(track.cover(), x + 6, y + 6, size - 12, size - 12, null);
            g.setClip(clip);
        } else {
            ScreenBoard.emblem(g, x + size / 2f, y + size / 2f, size / 4f);
        }

        g.setStroke(new BasicStroke(3f));
        g.setColor(Theme.ACCENT);
        g.draw(new RoundRectangle2D.Float(x, y, size, size, 16, 16));
        ScreenBoard.brackets(g, x - 8, y - 8, size + 16, size + 16, Theme.ACCENT);

        // Справа от обложки: источник, эквалайзер, альбом
        var left = x + size + (int) (width * 0.05);
        var right = width - (int) (width * 0.06);
        g.setFont(Theme.mono.deriveFont(Font.BOLD, height / 30f));
        g.setColor(new Color(57, 255, 138, 170));
        g.drawString(track.playing() ? "ИГРАЕТ" : "ПАУЗА", left, y + height / 24f);
        g.drawString("SPOTIFY", left, y + height / 24f * 2.2f);

        var bars = 9;
        var barW = Math.max(4, (right - left) / (bars * 2));
        var base = y + size - height / 20f;

        for (var i = 0; i < bars; i++) {
            var level = track.playing() ? 0.2 + 0.8 * Math.abs(Math.sin(now / 170.0 + i * 1.3) * Math.sin(now / 430.0 + i * 0.7)) : 0.1;
            var h = (int) (size * 0.5 * level);
            g.setPaint(new GradientPaint(0, base - h, Theme.ACCENT, 0, base, Theme.ACCENT_DIM));
            g.fillRect(left + i * barW * 2, (int) base - h, barW, h);
        }

        if (!track.album().isBlank()) {
            g.setFont(Theme.ui.deriveFont(height / 30f));
            g.setColor(Theme.MUTED);
            g.drawString(ActivityBoard.fit(g, track.album(), right - left), left, y + size);
        }

        // Внизу на всю ширину: название, исполнитель и полоса времени
        var textLeft = x;
        var span = width - x * 2;
        g.setFont(Theme.ui.deriveFont(Font.BOLD, height / 13f));
        g.setColor(Theme.TEXT);
        var titleY = y + size + height / 9f;
        g.drawString(ActivityBoard.fit(g, track.title(), span), textLeft, titleY);
        g.setFont(Theme.ui.deriveFont(Font.BOLD, height / 20f));
        g.setColor(Theme.ACCENT);
        var artist = track.artist().isBlank() ? "неизвестный исполнитель" : track.artist();
        g.drawString(ActivityBoard.fit(g, artist, span), textLeft, titleY + height / 15f);

        var position = track.position(now);
        var duration = Math.max(1, track.durationMs());
        var barY = height - height / 11f;
        var done = (float) (span * Math.min(1, position / (double) duration));
        g.setColor(Theme.LINE_2);
        g.fill(new RoundRectangle2D.Float(textLeft, barY, span, 8, 8, 8));
        g.setPaint(new GradientPaint(textLeft, 0, Theme.ACCENT_DIM, textLeft + done, 0, Theme.ACCENT));
        g.fill(new RoundRectangle2D.Float(textLeft, barY, Math.max(8, done), 8, 8, 8));
        g.setFont(Theme.mono.deriveFont(height / 30f));
        g.setColor(Theme.MUTED);
        g.drawString(ActivityBoard.time(position), textLeft, barY + height / 22f);
        var total = track.durationMs() > 0 ? ActivityBoard.time(track.durationMs()) : "--:--";
        g.drawString(total, textLeft + span - g.getFontMetrics().stringWidth(total), barY + height / 22f);
        return finish(image, g);
    }
}
