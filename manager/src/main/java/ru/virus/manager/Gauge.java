package ru.virus.manager;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Line2D;

/**
 * Спидометр интернетометра: дуга со шкалой и стрелка, которая плавно идёт
 * к текущей скорости. Шкала растёт сама, если скорость за неё вылезла.
 */
final class Gauge extends JComponent {

    private static final double[] SCALES = { 50, 100, 250, 500, 1000, 2500, 10000 };
    private static final double START = 225;
    private static final double SWEEP = 270;

    private double target;
    private double shown;
    private double scale = 100;
    private String caption = "";
    private String unit = "Мбит/с";
    private final Timer timer;

    Gauge() {
        setOpaque(false);
        setPreferredSize(new Dimension(300, 230));
        timer = new Timer(16, e -> step());
    }

    /** Новое значение; стрелка дойдёт до него сама. */
    void set(double value, String caption) {
        target = Math.max(0, value);
        this.caption = caption;

        for (var candidate : SCALES) {
            if (target <= candidate * 0.95 || candidate == SCALES[SCALES.length - 1]) {
                scale = Math.max(scale, candidate);
                break;
            }
        }

        if (!timer.isRunning()) {
            timer.start();
        }
    }

    void reset() {
        target = 0;
        shown = 0;
        scale = 100;
        caption = "";
        repaint();
    }

    private void step() {
        shown += (target - shown) * 0.18;

        if (Math.abs(target - shown) < 0.05) {
            shown = target;
            timer.stop();
        }

        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        var g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        var size = Math.min(getWidth(), (int) (getHeight() * 1.15)) - 24;
        var x = (getWidth() - size) / 2.0;
        var y = 12.0;
        var cx = x + size / 2.0;
        var cy = y + size / 2.0;
        var thickness = Math.max(10f, size / 16f);

        // Дорожка и заполненная часть
        g.setStroke(new BasicStroke(thickness, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(Theme.LINE_2);
        g.draw(new Arc2D.Double(x, y, size, size, START, -SWEEP, Arc2D.OPEN));

        var share = Math.min(1, shown / scale);

        if (share > 0.001) {
            g.setColor(Theme.ACCENT);
            g.draw(new Arc2D.Double(x, y, size, size, START, -SWEEP * share, Arc2D.OPEN));
        }

        // Деления с подписями
        g.setStroke(new BasicStroke(1.5f));
        g.setFont(Theme.mono.deriveFont(11f));
        var metrics = g.getFontMetrics();

        for (var i = 0; i <= 5; i++) {
            var angle = Math.toRadians(START - SWEEP * i / 5);
            var outer = size / 2.0 - thickness;
            var inner = outer - 8;
            g.setColor(Theme.MUTED);
            g.draw(new Line2D.Double(cx + Math.cos(angle) * inner, cy - Math.sin(angle) * inner,
                    cx + Math.cos(angle) * outer, cy - Math.sin(angle) * outer));

            var label = format(scale * i / 5);
            var text = inner - 14;
            g.setColor(Theme.DIM);
            g.drawString(label, (float) (cx + Math.cos(angle) * text - metrics.stringWidth(label) / 2.0),
                    (float) (cy - Math.sin(angle) * text + metrics.getAscent() / 2.5));
        }

        // Стрелка
        var angle = Math.toRadians(START - SWEEP * share);
        var length = size / 2.0 - thickness - 18;
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(Theme.TEXT);
        g.draw(new Line2D.Double(cx, cy, cx + Math.cos(angle) * length, cy - Math.sin(angle) * length));
        g.setColor(Theme.ACCENT);
        g.fillOval((int) cx - 6, (int) cy - 6, 12, 12);

        // Число и подпись
        var value = shown < 10 ? String.format("%.1f", shown) : String.valueOf(Math.round(shown));
        g.setFont(Theme.mono.deriveFont(Font.BOLD, Math.max(22f, size / 7f)));
        var big = g.getFontMetrics();
        g.setColor(Theme.TEXT);
        var valueY = (float) (cy + size * 0.28);
        g.drawString(value, (float) (cx - big.stringWidth(value) / 2.0), valueY);

        g.setFont(Theme.ui.deriveFont(12.5f));
        var small = g.getFontMetrics();
        var line = caption.isEmpty() ? unit : caption + " · " + unit;
        g.setColor(Theme.MUTED);
        g.drawString(line, (float) (cx - small.stringWidth(line) / 2.0), valueY + small.getHeight() + 2);
        g.dispose();
    }

    private static String format(double value) {
        return value >= 1000 ? (value % 1000 == 0 ? (int) (value / 1000) + "k" : String.format("%.1fk", value / 1000))
                : String.valueOf((int) value);
    }

    /** Для проверок. */
    double scale() {
        return scale;
    }
}
