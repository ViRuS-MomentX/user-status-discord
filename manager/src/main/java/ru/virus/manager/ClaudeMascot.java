package ru.virus.manager;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Клод — пиксельный маскот Claude: прямоугольное оранжевое тельце, ручки по
 * бокам, четыре ножки и глазки-щёлочки. Рисуется по клеточкам, чтобы был
 * резким на любом экране. Танцует, моргает, а каждые 10 секунд машет рукой
 * и здоровается.
 */
final class ClaudeMascot {

    static final Color BODY = new Color(217, 119, 87);
    static final Color EYE = new Color(20, 20, 20);
    static final long GREET_EVERY_MS = 10_000;
    static final long GREET_FOR_MS = 2_600;

    private ClaudeMascot() {
    }

    /** Машет ли сейчас (первые 2,6 с каждых 10 с). */
    static boolean greeting(long now) {
        return now % GREET_EVERY_MS < GREET_FOR_MS;
    }

    private static void cell(Graphics2D g, double x0, double y0, double u, double x, double y, double w, double h) {
        g.fillRect((int) Math.round(x0 + x * u), (int) Math.round(y0 + y * u), (int) Math.ceil(w * u), (int) Math.ceil(h * u));
    }

    /**
     * Нарисовать Клода. Тельце — 18×12 клеток, вместе с руками и ногами
     * 24×15; (cx, cy) — центр тельца, u — размер клетки в точках.
     */
    static void draw(Graphics2D g, double cx, double cy, double u, long now) {
        draw(g, cx, cy, u, now, Integer.MAX_VALUE);
    }

    /** @param screenWidth ширина экрана — облачко «Привет!» не вылезает за край */
    static void draw(Graphics2D g, double cx, double cy, double u, long now, int screenWidth) {
        var t = now / 1000.0;
        var waving = greeting(now);
        // Танец: подпрыгивает в такт, на приветствии — стоит ровнее
        var bounce = waving ? Math.abs(Math.sin(t * 3)) * 0.4 : Math.abs(Math.sin(t * Math.PI * 2)) * 1.6;
        var sway = waving ? 0 : Math.sin(t * Math.PI) * 1.2;
        var x0 = cx - 9 * u + sway * u;
        var y0 = cy - 6 * u - bounce * u;

        // Тень на «полу» — сжимается, когда Клод в прыжке
        var shadow = 1 - bounce / 3;
        g.setColor(new Color(0, 0, 0, 90));
        g.fill(new Ellipse2D.Double(cx - 11 * u * shadow, cy + 9.6 * u, 22 * u * shadow, 2.2 * u));

        g.setColor(BODY);
        // Тельце
        cell(g, x0, y0, u, 0, 0, 18, 12);

        // Ножки: в танце пары по очереди поджимаются
        var step = (int) Math.floor(t * 4) % 2;

        for (var i = 0; i < 4; i++) {
            var x = new double[] {1, 4, 12, 15}[i];
            var lifted = !waving && (i % 2 == step);
            cell(g, x0, y0, u, x, 12, 2, lifted ? 2 : 3);
        }

        // Ручки: левая всегда сбоку (в танце — вверх-вниз), правая на приветствии машет
        var arm = waving ? 0 : (Math.sin(t * Math.PI * 2) > 0 ? -1 : 0);
        cell(g, x0, y0, u, -3, 6 + arm, 3, 3);

        if (waving) {
            // Поднятая рука: вверх от плеча, качается туда-сюда
            var tilt = (int) Math.floor(now / 220.0) % 2 == 0 ? 0 : 1;
            cell(g, x0, y0, u, 18, 4, 2, 3);
            cell(g, x0, y0, u, 19 + tilt, 0, 2, 4);
            cell(g, x0, y0, u, 19 + tilt * 2, -3, 2, 3);
        } else {
            cell(g, x0, y0, u, 18, 6 - arm, 3, 3);
        }

        // Глазки-щёлочки, иногда моргает
        var blink = now % 4300 < 140;
        g.setColor(EYE);
        cell(g, x0, y0, u, 3, blink ? 4.4 : 3, 1, blink ? 0.6 : 3);
        cell(g, x0, y0, u, 14, blink ? 4.4 : 3, 1, blink ? 0.6 : 3);

        if (waving) {
            bubble(g, x0 + 22 * u, y0 - 8 * u, u, "Привет!", screenWidth);
        }
    }

    /** Облачко с репликой справа сверху. */
    private static void bubble(Graphics2D g, double x, double y, double u, String text, int screenWidth) {
        g.setFont(Theme.ui.deriveFont(Font.BOLD, (float) (u * 3.2)));
        var metrics = g.getFontMetrics();
        var w = metrics.stringWidth(text) + u * 3;
        var h = metrics.getHeight() + u * 1.6;
        var tip = x;
        x = Math.min(x, screenWidth - w - u * 2);
        y = Math.max(u, y);
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Double(x, y, w, h, u * 2.4, u * 2.4));
        // Хвостик к Клоду
        var tail = new java.awt.geom.Path2D.Double();
        var base = Math.max(x + u * 1.5, Math.min(x + w - u * 4, tip));
        tail.moveTo(base, y + h - u * 0.5);
        tail.lineTo(tip - u * 2.5, y + h + u * 1.8);
        tail.lineTo(base + u * 2.5, y + h - u * 0.5);
        tail.closePath();
        g.fill(tail);
        g.setColor(new Color(30, 30, 30));
        g.drawString(text, (float) (x + u * 1.5), (float) (y + u * 0.8 + metrics.getAscent()));
    }

    /** Мягкое оранжевое свечение за Клодом — чтобы на тёмном фоне он «светился». */
    static void glow(Graphics2D g, double cx, double cy, double radius) {
        g.setPaint(new RadialGradientPaint((float) cx, (float) cy, (float) radius, new float[] {0f, 1f},
                new Color[] {new Color(217, 119, 87, 55), new Color(217, 119, 87, 0)}));
        g.fill(new Ellipse2D.Double(cx - radius, cy - radius, radius * 2, radius * 2));
    }
}
