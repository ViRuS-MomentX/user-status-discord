package ru.virus.manager;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;

/**
 * Клод — пиксельный маскот Claude: прямоугольное оранжевое тельце, ручки по
 * бокам, четыре ножки и глазки-щёлочки. Рисуется по клеточкам, чтобы был
 * резким на любом экране. Всё время танцует и каждые 10 секунд машет ручкой.
 * При запуске V.I.R.U.S. появляется на каждом экране, машет и спрыгивает вниз.
 */
final class ClaudeMascot {

    static final Color BODY = new Color(217, 119, 87);
    static final Color EYE = new Color(20, 20, 20);
    static final long WAVE_EVERY_MS = 10_000;
    static final long WAVE_FOR_MS = 2_600;
    /** Приветствие при запуске: прилетает, машет, подпрыгивает и спрыгивает вниз. */
    static final long INTRO_MS = 4_600;

    private ClaudeMascot() {
    }

    /** Машет ли сейчас (первые 2,6 с каждых 10 с). */
    static boolean greeting(long now) {
        return now % WAVE_EVERY_MS < WAVE_FOR_MS;
    }

    private static void cell(Graphics2D g, double x0, double y0, double u, double x, double y, double w, double h) {
        g.fillRect((int) Math.round(x0 + x * u), (int) Math.round(y0 + y * u), (int) Math.ceil(w * u), (int) Math.ceil(h * u));
    }

    /**
     * Сам Клод от левого верхнего угла тельца (18×12 клеток, с руками и
     * ногами — 24×15).
     *
     * @param dance ножки и левая ручка двигаются в такт
     * @param wave  правая ручка поднята и машет
     */
    static void body(Graphics2D g, double x0, double y0, double u, long now, boolean dance, boolean wave) {
        var t = now / 1000.0;
        var aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setColor(BODY);
        cell(g, x0, y0, u, 0, 0, 18, 12);

        // Ножки: в танце пары по очереди поджимаются
        var step = Math.floorMod((long) Math.floor(t * 4), 2L);

        for (var i = 0; i < 4; i++) {
            var x = new double[] {1, 4, 12, 15}[i];
            cell(g, x0, y0, u, x, 12, 2, dance && i % 2 == step ? 2 : 3);
        }

        var arm = dance && Math.sin(t * Math.PI * 2) > 0 ? -1 : 0;
        cell(g, x0, y0, u, -3, 6 + arm, 3, 3);

        if (wave) {
            // Поднятая ручка качается туда-сюда
            var tilt = (int) Math.floorMod(now / 220, 2L);
            cell(g, x0, y0, u, 18, 4, 2, 3);
            cell(g, x0, y0, u, 19 + tilt, 0, 2, 4);
            cell(g, x0, y0, u, 19 + tilt * 2, -3, 2, 3);
        } else {
            cell(g, x0, y0, u, 18, 6 - arm, 3, 3);
        }

        // Глазки, иногда моргает
        var blink = now % 4300 < 140;
        g.setColor(EYE);
        cell(g, x0, y0, u, 3, blink ? 4.4 : 3, 1, blink ? 0.6 : 3);
        cell(g, x0, y0, u, 14, blink ? 4.4 : 3, 1, blink ? 0.6 : 3);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
    }

    private static void shadow(Graphics2D g, double cx, double floor, double u, double lift) {
        var k = Math.max(0.3, 1 - lift / 6);
        g.setColor(new Color(0, 0, 0, (int) (100 * k)));
        g.fill(new Ellipse2D.Double(cx - 11 * u * k, floor, 22 * u * k, 2.2 * u));
    }

    /** Танец без остановки; каждые 10 секунд ещё и машет. (cx, cy) — центр тельца. */
    static void draw(Graphics2D g, double cx, double cy, double u, long now) {
        var t = now / 1000.0;
        var bounce = Math.abs(Math.sin(t * Math.PI * 2)) * 1.6;
        var sway = Math.sin(t * Math.PI) * 1.2;
        shadow(g, cx, cy + 9.6 * u, u, bounce);
        body(g, cx - 9 * u + sway * u, cy - 6 * u - bounce * u, u, now, true, greeting(now));
    }

    /**
     * Приветствие при запуске поверх кадра экрана: Клод падает сверху,
     * машет, подпрыгивает и спрыгивает вниз за край.
     *
     * @param elapsed сколько прошло с начала приветствия, мс
     * @return false — приветствие закончилось, рисовать нечего
     */
    static boolean intro(Graphics2D g, int width, int height, long elapsed, long now) {
        if (elapsed < 0 || elapsed >= INTRO_MS) {
            return false;
        }

        var u = Math.min(width / 52.0, height / 24.0);
        var cx = width / 2.0;
        var rest = height / 2.0 + u;
        double cy;

        if (elapsed < 700) {
            // Падает сверху с лёгким отскоком
            var k = elapsed / 700.0;
            var ease = 1 - Math.pow(1 - k, 3);
            cy = -10 * u + (rest + 10 * u) * ease;
        } else if (elapsed < 3000) {
            cy = rest - Math.abs(Math.sin((elapsed - 700) / 1000.0 * Math.PI * 2)) * 0.6 * u;
        } else if (elapsed < 3500) {
            // Подпрыгивает
            var k = (elapsed - 3000) / 500.0;
            cy = rest - Math.sin(k * Math.PI) * 5 * u;
        } else {
            // И уходит вниз за край, всё быстрее
            var k = (elapsed - 3500) / (double) (INTRO_MS - 3500);
            cy = rest + k * k * (height / 2.0 + 20 * u);
        }

        var waving = elapsed >= 700 && elapsed < 3000;

        if (cy < height) {
            shadow(g, cx, Math.min(height - 3 * u, rest + 9.6 * u), u, Math.max(0, (rest - cy) / u));
        }

        body(g, cx - 9 * u, cy - 6 * u, u, now, false, waving);
        return true;
    }

    /**
     * Своя сцена, не как в V.I.R.U.S.: тёмно-фиолетовый вечер, тёплый
     * прожектор сверху, огоньки-звёздочки и танцпол из мигающих плиток.
     */
    static void stage(Graphics2D g, int width, int height, long now) {
        var t = now / 1000.0;
        g.setPaint(new GradientPaint(0, 0, new Color(28, 14, 48), 0, height, new Color(58, 20, 52)));
        g.fillRect(0, 0, width, height);

        // Звёздочки мерцают
        for (var i = 0; i < 40; i++) {
            var x = ScreenBoard.hash(i * 7 + 1) * width;
            var y = ScreenBoard.hash(i * 13 + 5) * height * 0.62;
            var twinkle = 0.5 + 0.5 * Math.sin(t * (1 + ScreenBoard.hash(i + 3) * 2) + i);
            var size = 1.5 + ScreenBoard.hash(i + 9) * 2;
            g.setColor(new Color(255, 230, 200, (int) (60 + 150 * twinkle)));
            g.fill(new Ellipse2D.Double(x, y, size, size));
        }

        // Прожектор — конус тёплого света сверху, чуть качается
        var swing = Math.sin(t * 0.7) * width * 0.06;
        var beam = new Path2D.Double();
        beam.moveTo(width / 2.0 - width * 0.04, 0);
        beam.lineTo(width / 2.0 + width * 0.04, 0);
        beam.lineTo(width / 2.0 + width * 0.32 + swing, height * 0.82);
        beam.lineTo(width / 2.0 - width * 0.32 + swing, height * 0.82);
        beam.closePath();
        g.setPaint(new GradientPaint(0, 0, new Color(255, 200, 140, 70), 0, (float) (height * 0.82), new Color(255, 160, 110, 8)));
        g.fill(beam);
        g.setPaint(new RadialGradientPaint((float) (width / 2.0 + swing), (float) (height * 0.8), (float) (width * 0.35),
                new float[] {0f, 1f}, new Color[] {new Color(255, 180, 120, 60), new Color(255, 180, 120, 0)}));
        g.fillRect(0, 0, width, height);

        // Танцпол: плитки мигают по очереди
        var floor = height * 0.8;
        var tiles = 10;
        var tileW = width / (double) tiles;
        Color[] colors = {new Color(255, 94, 160), new Color(120, 90, 255), new Color(70, 210, 255), new Color(255, 196, 70)};

        for (var i = 0; i < tiles; i++) {
            for (var row = 0; row < 2; row++) {
                var tick = (long) Math.floor(t * 2);
                var beat = Math.floorMod(tick + i + row * 3, 4L);
                var c = colors[(int) Math.floorMod(tick + i + row, (long) colors.length)];
                var alpha = beat == 0 ? 150 : 45;
                g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha));
                var y = floor + row * (height - floor) / 2;
                g.fillRect((int) Math.round(i * tileW) + 1, (int) Math.round(y) + 1, (int) Math.ceil(tileW) - 2,
                        (int) Math.ceil((height - floor) / 2) - 2);
            }
        }
    }
}
