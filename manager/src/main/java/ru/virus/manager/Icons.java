package ru.virus.manager;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Значки кнопок, нарисованные линиями.
 *
 * <p>Символы вроде ✎ или ⚙ есть не в каждом шрифте — на одной Windows они
 * выйдут картинкой, на другой пустым квадратом. Нарисованные — одинаковы везде.
 */
final class Icons implements Icon {

    enum Kind { PLAY, RESTART, STOP, EDIT, GEAR, PLUS, POWER, FOLDER, REPORT, UP, TRASH, TASKS, DEVICE }

    private final Kind kind;
    private final Color color;
    private final int size;

    Icons(Kind kind, Color color, int size) {
        this.kind = kind;
        this.color = color;
        this.size = size;
    }

    static Icons of(Kind kind, Color color) {
        return new Icons(kind, color, 14);
    }

    @Override
    public int getIconWidth() {
        return size;
    }

    @Override
    public int getIconHeight() {
        return size;
    }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        var g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.translate(x, y);
        // Рисуем в квадрате 14×14 и масштабируем
        var scale = size / 14.0;
        g.scale(scale, scale);

        var enabled = component == null || component.isEnabled();
        g.setColor(enabled ? color : Theme.alpha(color, 90));
        g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        switch (kind) {
            case PLAY -> {
                var p = new Path2D.Double();
                p.moveTo(3.5, 2);
                p.lineTo(12, 7);
                p.lineTo(3.5, 12);
                p.closePath();
                g.fill(p);
            }
            case STOP -> g.fill(new RoundRectangle2D.Double(2.5, 2.5, 9, 9, 2.5, 2.5));
            case RESTART -> {
                g.draw(new Arc2D.Double(2, 2, 10, 10, 60, 280, Arc2D.OPEN));
                var p = new Path2D.Double();
                p.moveTo(9.5, 0.8);
                p.lineTo(10.6, 3.6);
                p.lineTo(7.6, 4.2);
                g.draw(p);
            }
            case EDIT -> {
                var p = new Path2D.Double();
                p.moveTo(2.5, 11.5);
                p.lineTo(3.2, 8.6);
                p.lineTo(9.8, 2);
                p.lineTo(12, 4.2);
                p.lineTo(5.4, 10.8);
                p.closePath();
                g.draw(p);
                g.draw(new java.awt.geom.Line2D.Double(8.4, 3.4, 10.6, 5.6));
            }
            case GEAR -> {
                for (var i = 0; i < 8; i++) {
                    var angle = Math.PI / 4 * i;
                    g.draw(new java.awt.geom.Line2D.Double(7 + Math.cos(angle) * 4.6, 7 + Math.sin(angle) * 4.6,
                            7 + Math.cos(angle) * 6.3, 7 + Math.sin(angle) * 6.3));
                }
                g.draw(new Ellipse2D.Double(2.8, 2.8, 8.4, 8.4));
                g.draw(new Ellipse2D.Double(5.4, 5.4, 3.2, 3.2));
            }
            case PLUS -> {
                g.draw(new java.awt.geom.Line2D.Double(7, 2.5, 7, 11.5));
                g.draw(new java.awt.geom.Line2D.Double(2.5, 7, 11.5, 7));
            }
            case FOLDER -> {
                var p = new Path2D.Double();
                p.moveTo(1.5, 3.5);
                p.lineTo(5.5, 3.5);
                p.lineTo(7, 5);
                p.lineTo(12.5, 5);
                p.lineTo(12.5, 11.5);
                p.lineTo(1.5, 11.5);
                p.closePath();
                g.draw(p);
            }
            case REPORT -> {
                var p = new Path2D.Double();
                p.moveTo(3, 1.5);
                p.lineTo(8.5, 1.5);
                p.lineTo(11.5, 4.5);
                p.lineTo(11.5, 12.5);
                p.lineTo(3, 12.5);
                p.closePath();
                g.draw(p);
                g.draw(new java.awt.geom.Line2D.Double(5, 7, 9.5, 7));
                g.draw(new java.awt.geom.Line2D.Double(5, 9.5, 9.5, 9.5));
            }
            case UP -> {
                g.draw(new java.awt.geom.Line2D.Double(7, 12, 7, 2.5));
                var p = new Path2D.Double();
                p.moveTo(3, 6.5);
                p.lineTo(7, 2.5);
                p.lineTo(11, 6.5);
                g.draw(p);
            }
            case TRASH -> {
                g.draw(new java.awt.geom.Line2D.Double(2, 3.5, 12, 3.5));
                g.draw(new java.awt.geom.Line2D.Double(5.5, 3.5, 5.5, 1.8));
                g.draw(new java.awt.geom.Line2D.Double(5.5, 1.8, 8.5, 1.8));
                g.draw(new java.awt.geom.Line2D.Double(8.5, 1.8, 8.5, 3.5));
                var p = new Path2D.Double();
                p.moveTo(3.5, 3.5);
                p.lineTo(4.3, 12.5);
                p.lineTo(9.7, 12.5);
                p.lineTo(10.5, 3.5);
                g.draw(p);
            }
            case TASKS -> {
                g.draw(new RoundRectangle2D.Double(1.5, 2, 4, 4, 1.5, 1.5));
                var p = new Path2D.Double();
                p.moveTo(2.4, 9.8);
                p.lineTo(3.5, 11);
                p.lineTo(5.6, 8.4);
                g.draw(p);
                g.draw(new java.awt.geom.Line2D.Double(8, 4, 12.5, 4));
                g.draw(new java.awt.geom.Line2D.Double(8, 9.8, 12.5, 9.8));
            }
            case DEVICE -> {
                g.draw(new RoundRectangle2D.Double(1.5, 2, 11, 7.5, 2, 2));
                g.draw(new java.awt.geom.Line2D.Double(7, 9.5, 7, 12));
                g.draw(new java.awt.geom.Line2D.Double(4.5, 12.2, 9.5, 12.2));
            }
            case POWER -> {
                g.draw(new Arc2D.Double(2, 2.5, 10, 10, 120, 300, Arc2D.OPEN));
                g.draw(new java.awt.geom.Line2D.Double(7, 1.2, 7, 6.5));
            }
        }

        g.dispose();
    }
}
