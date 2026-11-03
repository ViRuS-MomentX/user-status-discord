package ru.virus.manager;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/**
 * Картинки для полосок 640×180: в простое — живой цифровой фон в стиле
 * V.I.R.U.S., когда играет музыка — обложка, трек и полоса времени, когда
 * запущена игра — её иконка, название и сколько уже играешь.
 */
final class ActivityBoard {

    private ActivityBoard() {
    }

    private static Graphics2D start(BufferedImage image, long now) {
        var g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        ScreenBoard.background(g, image.getWidth(), image.getHeight(), now);
        return g;
    }

    private static void finish(Graphics2D g, int width, int height) {
        ScreenBoard.scanlines(g, width, height);
        g.dispose();
    }

    /**
     * Простой: бегущие «капли» фона, сканирующая полоса, пульсирующая
     * эмблема и строка «системы» — только для красоты.
     */
    static BufferedImage idle(int width, int height, long now) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = start(image, now);
        var t = now / 1000.0;

        // Сканирующая полоса ходит слева направо
        var x = (float) ((t * 120) % (width + 160)) - 80;
        g.setPaint(new GradientPaint(x - 80, 0, new Color(57, 255, 138, 0), x, 0, new Color(57, 255, 138, 60), true));
        g.fillRect((int) x - 80, 0, 160, height);

        // Эмблема дышит
        var pulse = (float) (0.9 + 0.1 * Math.sin(t * 2));
        ScreenBoard.emblem(g, width / 2f, height / 2f - 6, height / 4.2f * pulse);

        // Подпись и «телеметрия» по краям
        g.setFont(Theme.mono.deriveFont(Font.BOLD, 15f));
        g.setColor(new Color(57, 255, 138, 200));
        var title = "V.I.R.U.S.";
        var metrics = g.getFontMetrics();
        g.drawString(title, (width - metrics.stringWidth(title)) / 2f, height - 16);
        g.setFont(Theme.mono.deriveFont(11f));
        g.setColor(new Color(57, 255, 138, 120));
        var clock = java.time.LocalTime.now().withNano(0).toString();
        g.drawString(clock, 24, 28);
        var load = String.format("SYS %02d%%", (int) Math.round(ScreenBoard.hash((int) (t / 2)) * 30 + 5));
        g.drawString(load, width - 24 - g.getFontMetrics().stringWidth(load), 28);
        ScreenBoard.brackets(g, 6, 6, width - 12, height - 12, Theme.ACCENT);
        finish(g, width, height);
        return image;
    }

    static String time(long ms) {
        var seconds = Math.max(0, ms / 1000);
        return seconds >= 3600 ? String.format("%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    /** Строка, обрезанная по ширине с многоточием. */
    static String fit(Graphics2D g, String text, int width) {
        var metrics = g.getFontMetrics();

        if (metrics.stringWidth(text) <= width) {
            return text;
        }

        var cut = text;

        while (!cut.isEmpty() && metrics.stringWidth(cut + "…") > width) {
            cut = cut.substring(0, cut.length() - 1);
        }

        return cut.strip() + "…";
    }

    /** Квадрат с картинкой в зелёной рамке с уголками; без картинки — заглушка-символ. */
    private static void framed(Graphics2D g, BufferedImage picture, int x, int y, int size, String placeholder) {
        g.setColor(Theme.CARD_SOLID);
        g.fill(new RoundRectangle2D.Float(x, y, size, size, 10, 10));

        if (picture != null) {
            var clip = g.getClip();
            g.clip(new RoundRectangle2D.Float(x + 4, y + 4, size - 8, size - 8, 8, 8));
            g.drawImage(picture, x + 4, y + 4, size - 8, size - 8, null);
            g.setClip(clip);
        } else if (placeholder == null) {
            ScreenBoard.emblem(g, x + size / 2f, y + size / 2f, size / 3.2f);
        } else {
            g.setFont(Theme.ui.deriveFont(Font.BOLD, size * 0.42f));
            g.setColor(new Color(57, 255, 138, 150));
            var metrics = g.getFontMetrics();
            g.drawString(placeholder, x + (size - metrics.stringWidth(placeholder)) / 2f,
                    y + (size + metrics.getAscent() - metrics.getDescent()) / 2f);
        }

        g.setStroke(new BasicStroke(2.5f));
        g.setColor(Theme.ACCENT);
        g.draw(new RoundRectangle2D.Float(x, y, size, size, 10, 10));
        ScreenBoard.brackets(g, x - 6, y - 6, size + 12, size + 12, Theme.ACCENT);
    }

    /** Музыка: слева обложка, по центру название и полоса времени, снизу исполнитель. */
    static BufferedImage music(int width, int height, MediaNow.Track track, long now) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = start(image, now);
        var size = height - 32;
        framed(g, track.cover(), 16, 16, size, null);

        var left = 16 + size + 22;
        var right = width - 18;
        var span = right - left;

        // Значок источника и состояние
        g.setFont(Theme.mono.deriveFont(Font.BOLD, 11f));
        g.setColor(new Color(57, 255, 138, 170));
        g.drawString((track.playing() ? "ИГРАЕТ" : "ПАУЗА") + "  ·  SPOTIFY", left, 30);

        // Эквалайзер справа сверху — пляшет, пока играет
        for (var i = 0; i < 7; i++) {
            var level = track.playing() ? 0.25 + 0.75 * Math.abs(Math.sin(now / 160.0 + i * 1.7) * Math.sin(now / 410.0 + i)) : 0.15;
            var barH = (int) Math.round(18 * level);
            g.setColor(Theme.ACCENT);
            g.fillRect(right - 7 * 7 + i * 7, 32 - barH, 4, barH);
        }

        // Название
        g.setFont(Theme.ui.deriveFont(Font.BOLD, 30f));
        g.setColor(Theme.TEXT);
        g.drawString(fit(g, track.title(), span), left, 70);

        // Полоса времени
        var position = track.position(now);
        var duration = Math.max(1, track.durationMs());
        var barY = 92;
        g.setColor(Theme.LINE_2);
        g.fill(new RoundRectangle2D.Float(left, barY, span, 8, 8, 8));
        var done = (float) (span * Math.min(1, position / (double) duration));
        g.setPaint(new GradientPaint(left, 0, Theme.ACCENT_DIM, left + done, 0, Theme.ACCENT));
        g.fill(new RoundRectangle2D.Float(left, barY, Math.max(8, done), 8, 8, 8));
        g.setColor(Color.WHITE);
        g.fill(new java.awt.geom.Ellipse2D.Float(left + done - 7, barY - 3, 14, 14));
        g.setFont(Theme.mono.deriveFont(12f));
        g.setColor(Theme.MUTED);
        g.drawString(time(position), left, barY + 26);
        var total = track.durationMs() > 0 ? time(track.durationMs()) : "--:--";
        g.drawString(total, right - g.getFontMetrics().stringWidth(total), barY + 26);

        // Исполнитель и (если влезает) альбом справа
        var albumWidth = 0;

        if (!track.album().isBlank() && !track.album().equals(track.title())) {
            g.setFont(Theme.ui.deriveFont(12f));
            g.setColor(Theme.MUTED);
            var album = fit(g, track.album(), span / 3);
            albumWidth = g.getFontMetrics().stringWidth(album) + 14;
            g.drawString(album, right - albumWidth + 14, height - 24);
        }

        g.setFont(Theme.ui.deriveFont(Font.BOLD, 19f));
        g.setColor(Theme.ACCENT);
        var artist = track.artist().isBlank() ? "неизвестный исполнитель" : track.artist();
        g.drawString(fit(g, artist, span - albumWidth), left, height - 22);

        finish(g, width, height);
        return image;
    }

    /** Игра: иконка в рамке, название, «в игре столько-то» и пульс. */
    static BufferedImage game(int width, int height, GameNow.Game game, BufferedImage icon, long now) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = start(image, now);
        var size = height - 32;
        framed(g, icon, 16, 16, size, game.name().isBlank() ? "?" : game.name().substring(0, 1).toUpperCase());

        var left = 16 + size + 22;
        var right = width - 18;
        var span = right - left;
        g.setFont(Theme.mono.deriveFont(Font.BOLD, 11f));
        g.setColor(new Color(57, 255, 138, 170));
        g.drawString("В ИГРЕ  ·  DISCORD", left, 30);

        g.setFont(Theme.ui.deriveFont(Font.BOLD, 32f));
        g.setColor(Theme.TEXT);
        g.drawString(fit(g, game.name(), span), left, 74);

        // Время в игре — крупно
        g.setFont(Theme.mono.deriveFont(Font.BOLD, 26f));
        g.setColor(Theme.ACCENT);
        var played = time(now - game.since());
        g.drawString(played, left, 114);
        var after = left + g.getFontMetrics().stringWidth(played) + 12;
        g.setFont(Theme.ui.deriveFont(13f));
        g.setColor(Theme.MUTED);
        g.drawString("уже в игре", after, 112);

        // «Кардиограмма» снизу
        var line = new Path2D.Float();
        var base = height - 24;

        for (var x = left; x <= right; x += 3) {
            var phase = ((x - left) + now / 12.0) % 120;
            var y = phase > 50 && phase < 56 ? base - 18 : phase > 56 && phase < 60 ? base + 10 : base;
            if (x == left) {
                line.moveTo(x, y);
            } else {
                line.lineTo(x, y);
            }
        }

        g.setStroke(new BasicStroke(2f));
        g.setColor(new Color(57, 255, 138, 170));
        g.draw(line);
        finish(g, width, height);
        return image;
    }
}
