package ru.virus.manager;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

/**
 * Табло экрана в корпусе в стиле V.I.R.U.S.: только иконки фоновых
 * процессов. Цвет иконки — состояние: серый — выключен, жёлтый —
 * запускается, зелёный — работает, красный — ошибка в консоли или упал.
 */
final class ScreenBoard {

    static final Color BG = Theme.BG;
    static final Color TILE = Theme.CARD_SOLID;
    static final Color LINE = Theme.LINE;
    static final Color OFF = new Color(0x4A, 0x55, 0x50);
    static final Color RUN = Theme.ACCENT;
    static final Color START = Theme.WARN;
    static final Color ERROR = Theme.ERROR;

    /** Сколько после запуска процесс считается «запускается». */
    static final long STARTING_MS = 15_000;

    /** Процессы на табло: id процесса → какая иконка. */
    enum Icon { DISCORD, TELEGRAM, CREEPER, FOLDER }

    static final Map<String, Icon> ICONS = Map.of("discord", Icon.DISCORD, "parody", Icon.TELEGRAM,
            "test-server", Icon.CREEPER, Zapret.ID, Icon.FOLDER);
    static final List<String> ORDER = List.of("discord", "parody", "test-server", Zapret.ID);

    private ScreenBoard() {
    }

    /** Состояние для табло: «off», «start», «run», «error». */
    static String status(ManagedProcess.State state, long uptimeMs, boolean consoleError) {
        return switch (state) {
            case STOPPED -> "off";
            case FAILED -> "error";
            case WAITING, STOPPING -> "start";
            case RUNNING -> consoleError ? "error" : uptimeMs < STARTING_MS ? "start" : "run";
        };
    }

    static Color color(String status) {
        return switch (status) {
            case "run" -> RUN;
            case "start" -> START;
            case "error" -> ERROR;
            default -> OFF;
        };
    }

    static BufferedImage render(int width, int height, Map<String, String> states) {
        return render(width, height, states, System.currentTimeMillis());
    }

    /** Табло «лёжа»: 2×2 плитки (или в ряд, если экран длинный и низкий). */
    static BufferedImage render(int width, int height, Map<String, String> states, long now) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        background(g, width, height, now);

        var wide = width >= height * 2.4;
        var columns = wide ? 4 : 2;
        var rows = wide ? 1 : 2;
        var gap = Math.max(8, Math.min(width, height) / 28);
        var tileW = (width - gap * (columns + 1)) / columns;
        var tileH = (height - gap * (rows + 1)) / rows;

        for (var i = 0; i < ORDER.size(); i++) {
            var id = ORDER.get(i);
            var x = gap + (i % columns) * (tileW + gap);
            var y = gap + (i / columns) * (tileH + gap);
            tile(g, x, y, tileW, tileH, ICONS.get(id), color(states.getOrDefault(id, "off")));
        }

        if (!wide) {
            emblem(g, width / 2f, height / 2f, Math.min(width, height) / 7.5f);
        }

        scanlines(g, width, height);
        g.dispose();
        return image;
    }

    /**
     * Фон как в окне V.I.R.U.S. (MatrixBackground): тёмная основа, зелёное
     * свечение в углу и сетка цифр, по которой бегут яркие «капли» со
     * шлейфом. Капли зависят от времени — от кадра к кадру фон движется.
     */
    static void background(Graphics2D g, int width, int height, long now) {
        g.setColor(BG);
        g.fillRect(0, 0, width, height);
        var radius = Math.max(width, height) * 0.7f;
        g.setPaint(new RadialGradientPaint(width * 0.88f, -height * 0.1f, radius, new float[] {0f, 1f},
                new Color[] {new Color(57, 255, 138, 22), new Color(57, 255, 138, 0)}));
        g.fillRect(0, 0, width, height);

        var scale = Math.max(0.6, Math.min(width, height) / 320.0);
        var cellW = (int) Math.round(16 * scale);
        var cellH = (int) Math.round(20 * scale);
        var columns = width / cellW + 1;
        var rows = height / cellH + 1;
        g.setFont(Theme.mono.deriveFont(Font.BOLD, (float) (13 * scale)));
        var metrics = g.getFontMetrics();
        var baseline = (cellH + metrics.getAscent() - metrics.getDescent()) / 2;
        var seconds = now / 1000.0;
        var shades = MatrixBackground.SHADES;

        for (var column = 0; column < columns; column++) {
            // Капля в этом столбце: своя скорость и сдвиг; шлейф ~7 клеток
            var speed = 2.5 + hash(column * 31 + 7) * 5;
            var span = rows + 10;
            var head = (seconds * speed + hash(column * 17 + 3) * span) % span;
            var lit = hash(column * 13 + 1) < 0.55;

            for (var row = 0; row < rows; row++) {
                var level = 0.07f;
                var behind = head - row;

                if (lit && behind >= 0 && behind < 7) {
                    level = (float) Math.max(level, 1 - behind / 7);
                }

                // Цифры меняются медленно, как в окне
                var digit = hash(column * 977 + row * 131 + (int) (seconds / 3)) < 0.5 ? '0' : '1';
                g.setColor(shades[(int) (level * (shades.length - 1))]);
                g.drawString(String.valueOf(digit), column * cellW + (int) (4 * scale), row * cellH + baseline);
            }
        }
    }

    private static double hash(int n) {
        var x = Math.sin(n * 12.9898 + 78.233) * 43758.5453;
        return x - Math.floor(x);
    }

    /** Лёгкие строки развёртки — для вида старого монитора. */
    private static void scanlines(Graphics2D g, int width, int height) {
        g.setColor(new Color(0, 0, 0, 34));

        for (var y = 0; y < height; y += 3) {
            g.fillRect(0, y, width, 1);
        }
    }

    /** Эмблема V.I.R.U.S. в центре между плитками — только для красоты. */
    private static void emblem(Graphics2D g, float cx, float cy, float r) {
        g.setColor(BG);
        g.fill(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
        g.setPaint(new RadialGradientPaint(cx, cy, r * 1.6f, new float[] {0f, 1f},
                new Color[] {new Color(57, 255, 138, 70), new Color(57, 255, 138, 0)}));
        g.fill(new Ellipse2D.Float(cx - r * 1.6f, cy - r * 1.6f, r * 3.2f, r * 3.2f));
        g.setStroke(new BasicStroke(Math.max(2f, r * 0.12f)));
        g.setColor(RUN);
        g.draw(new Ellipse2D.Float(cx - r * 0.82f, cy - r * 0.82f, r * 1.64f, r * 1.64f));
        var v = new Path2D.Float();
        v.moveTo(cx - r * 0.42f, cy - r * 0.32f);
        v.lineTo(cx, cy + r * 0.42f);
        v.lineTo(cx + r * 0.42f, cy - r * 0.32f);
        g.setStroke(new BasicStroke(Math.max(2f, r * 0.16f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(v);
    }

    /** Уголки-скобки по краям плитки, как прицел HUD. */
    private static void brackets(Graphics2D g, int x, int y, int w, int h, Color color) {
        var len = Math.min(w, h) * 0.12f;
        var inset = Math.min(w, h) * 0.07f;
        g.setStroke(new BasicStroke(Math.max(1.5f, Math.min(w, h) / 90f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), color == OFF ? 70 : 150));
        float l = x + inset, t = y + inset, r = x + w - inset, b = y + h - inset;
        var p = new Path2D.Float();
        p.moveTo(l, t + len);
        p.lineTo(l, t);
        p.lineTo(l + len, t);
        p.moveTo(r - len, t);
        p.lineTo(r, t);
        p.lineTo(r, t + len);
        p.moveTo(r, b - len);
        p.lineTo(r, b);
        p.lineTo(r - len, b);
        p.moveTo(l + len, b);
        p.lineTo(l, b);
        p.lineTo(l, b - len);
        g.draw(p);
    }

    private static void tile(Graphics2D g, int x, int y, int w, int h, Icon icon, Color color) {
        var radius = Math.min(w, h) / 6f;
        // Плитка полупрозрачная — сквозь неё видно бегущие цифры, как сквозь карточки в окне
        g.setColor(new Color(TILE.getRed(), TILE.getGreen(), TILE.getBlue(), 200));
        g.fill(new RoundRectangle2D.Float(x, y, w, h, radius, radius));
        // Свечение цветом состояния снизу — как у карточек в окне
        g.setPaint(new GradientPaint(x, y + h * 0.55f, new Color(color.getRed(), color.getGreen(), color.getBlue(), 0),
                x, y + h, new Color(color.getRed(), color.getGreen(), color.getBlue(), color == OFF ? 0 : 38)));
        g.fill(new RoundRectangle2D.Float(x, y, w, h, radius, radius));
        g.setStroke(new BasicStroke(1.5f));
        g.setColor(color == OFF ? LINE : new Color(color.getRed(), color.getGreen(), color.getBlue(), 120));
        g.draw(new RoundRectangle2D.Float(x + 0.75f, y + 0.75f, w - 1.5f, h - 1.5f, radius, radius));

        brackets(g, x, y, w, h, color);
        var size = Math.min(w, h) * 0.56f;
        var cx = x + w / 2f;
        var cy = y + h / 2f;

        if (color != OFF) {
            g.setPaint(new RadialGradientPaint(cx, cy, size * 0.95f, new float[] {0f, 1f},
                    new Color[] {new Color(color.getRed(), color.getGreen(), color.getBlue(), 60),
                            new Color(color.getRed(), color.getGreen(), color.getBlue(), 0)}));
            g.fill(new Ellipse2D.Float(cx - size, cy - size, size * 2, size * 2));
        }

        g.setColor(color);
        var shape = switch (icon) {
            case DISCORD -> discord(cx, cy, size);
            case TELEGRAM -> telegram(cx, cy, size);
            case CREEPER -> creeper(cx, cy, size);
            case FOLDER -> folder(cx, cy, size);
        };
        g.fill(shape);
    }

    /** Значок Discord: «джойстик-лицо» с двумя глазами. */
    static Area discord(float cx, float cy, float size) {
        var w = size;
        var h = size * 0.76f;
        var x = cx - w / 2;
        var y = cy - h / 2;
        var body = new Path2D.Float();
        body.moveTo(x + w * 0.18f, y + h * 0.08f);
        body.curveTo(x + w * 0.34f, y, x + w * 0.38f, y + h * 0.05f, x + w * 0.4f, y + h * 0.1f);
        body.lineTo(x + w * 0.6f, y + h * 0.1f);
        body.curveTo(x + w * 0.62f, y + h * 0.05f, x + w * 0.66f, y, x + w * 0.82f, y + h * 0.08f);
        body.curveTo(x + w * 0.98f, y + h * 0.35f, x + w, y + h * 0.65f, x + w * 0.96f, y + h * 0.86f);
        body.curveTo(x + w * 0.86f, y + h * 0.95f, x + w * 0.75f, y + h, x + w * 0.68f, y + h);
        body.lineTo(x + w * 0.62f, y + h * 0.88f);
        body.curveTo(x + w * 0.54f, y + h * 0.9f, x + w * 0.46f, y + h * 0.9f, x + w * 0.38f, y + h * 0.88f);
        body.lineTo(x + w * 0.32f, y + h);
        body.curveTo(x + w * 0.25f, y + h, x + w * 0.14f, y + h * 0.95f, x + w * 0.04f, y + h * 0.86f);
        body.curveTo(x, y + h * 0.65f, x + w * 0.02f, y + h * 0.35f, x + w * 0.18f, y + h * 0.08f);
        body.closePath();
        var area = new Area(body);
        area.subtract(new Area(new Ellipse2D.Float(x + w * 0.27f, y + h * 0.42f, w * 0.15f, h * 0.24f)));
        area.subtract(new Area(new Ellipse2D.Float(x + w * 0.58f, y + h * 0.42f, w * 0.15f, h * 0.24f)));
        return area;
    }

    /** Значок Telegram: бумажный самолётик. */
    static Area telegram(float cx, float cy, float size) {
        var s = size;
        var x = cx - s / 2;
        var y = cy - s / 2;
        var plane = new Path2D.Float();
        plane.moveTo(x, y + s * 0.47f);
        plane.lineTo(x + s, y + s * 0.08f);
        plane.lineTo(x + s * 0.83f, y + s * 0.92f);
        plane.lineTo(x + s * 0.55f, y + s * 0.7f);
        plane.lineTo(x + s * 0.4f, y + s * 0.86f);
        plane.lineTo(x + s * 0.37f, y + s * 0.62f);
        plane.lineTo(x + s * 0.85f, y + s * 0.2f);
        plane.lineTo(x + s * 0.3f, y + s * 0.58f);
        plane.closePath();
        return new Area(plane);
    }

    /** Лицо крипера: глаза и рот из клеток, без головы. */
    static Area creeper(float cx, float cy, float size) {
        // Клетки лица на сетке 6×6: глаза сверху, рот с «ножками» снизу
        int[][] face = {
                {0, 0}, {1, 0}, {0, 1}, {1, 1}, {4, 0}, {5, 0}, {4, 1}, {5, 1},  // глаза
                {2, 2}, {3, 2},                                                  // переносица рта
                {1, 3}, {2, 3}, {3, 3}, {4, 3}, {1, 4}, {2, 4}, {3, 4}, {4, 4},  // рот
                {1, 5}, {4, 5}};                                                 // «ножки» рта
        var cell = size / 6f;
        var x = cx - size / 2;
        var y = cy - size / 2;
        var area = new Area();

        for (var p : face) {
            // Чуть внахлёст — без щелей между клетками
            area.add(new Area(new java.awt.geom.Rectangle2D.Float(x + p[0] * cell, y + p[1] * cell, cell + 0.6f, cell + 0.6f)));
        }

        return area;
    }

    /** Папка, как значок файлового менеджера. */
    static Area folder(float cx, float cy, float size) {
        var w = size;
        var h = size * 0.78f;
        var x = cx - w / 2;
        var y = cy - h / 2;
        var r = w * 0.08f;
        var shape = new Area(new RoundRectangle2D.Float(x, y + h * 0.16f, w, h * 0.84f, r, r));
        var tab = new Path2D.Float();
        tab.moveTo(x, y + h * 0.2f);
        tab.lineTo(x, y + r);
        tab.quadTo(x, y, x + r, y);
        tab.lineTo(x + w * 0.36f, y);
        tab.lineTo(x + w * 0.46f, y + h * 0.16f);
        tab.lineTo(x, y + h * 0.2f);
        tab.closePath();
        shape.add(new Area(tab));
        // Линия «крышки» — чтобы читалось как папка
        shape.subtract(new Area(new java.awt.geom.Rectangle2D.Float(x, y + h * 0.3f, w, h * 0.035f)));
        return shape;
    }
}
