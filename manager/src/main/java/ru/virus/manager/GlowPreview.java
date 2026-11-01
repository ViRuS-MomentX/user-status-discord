package ru.virus.manager;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;

/**
 * Живой предпросмотр режима, как в iCUE и SignalRGB: вентилятор, лента и
 * клавиатура рисуются тем же кодом, что красит устройства, — видно эффект
 * ещё до того, как он ушёл на железо. Цвета здесь экранные, без поправки для
 * светодиодов.
 */
final class GlowPreview extends JComponent {

    private static final int FAN = 16;
    private static final int STRIP = 36;
    private static final int ROWS = 5;
    private static final int COLUMNS = 16;

    private final SignalRgb.Live settings;
    private final List<OpenRgb.Place> fan = new ArrayList<>();
    private final List<OpenRgb.Place> strip = new ArrayList<>();
    private final List<OpenRgb.Place> keys = new ArrayList<>();
    private final Timer timer;
    private double time;
    /** Режим под мышью — показываем его, пока наведена плитка; −1 — выбранный. */
    private volatile int peek = -1;
    private long last = System.nanoTime();

    GlowPreview(SignalRgb.Live settings) {
        this.settings = settings;
        setOpaque(false);
        setPreferredSize(new Dimension(10, 96));

        for (var i = 0; i < FAN; i++) {
            fan.add(new OpenRgb.Place((double) i / FAN, 0.5, false));
        }

        for (var i = 0; i < STRIP; i++) {
            strip.add(new OpenRgb.Place((double) i / STRIP, 0.5, false));
        }

        for (var row = 0; row < ROWS; row++) {
            for (var column = 0; column < COLUMNS; column++) {
                keys.add(new OpenRgb.Place((column + 0.5) / COLUMNS, (row + 0.5) / ROWS, true));
            }
        }

        // Рисуем, только пока предпросмотр на экране: незачем греть процессор во вкладке, которую не видно
        timer = new Timer(33, e -> {
            if (isShowing()) {
                repaint();
            }
        });
    }

    /**
     * Показать режим, не включая его на железе, — как предпросмотр эффекта при
     * наведении в библиотеке SignalRGB; −1 — вернуть выбранный.
     */
    void peek(int mode) {
        peek = mode;
        repaint();
    }

    /** Какой режим сейчас нарисован. */
    int shown() {
        return peek >= 0 ? peek : settings.mode;
    }

    @Override
    public void addNotify() {
        super.addNotify();
        timer.start();
    }

    @Override
    public void removeNotify() {
        timer.stop();
        super.removeNotify();
    }

    private Color[] frame(List<OpenRgb.Place> places, int seed) {
        var mode = shown();
        var shaped = Glow.positional(mode) ? places : Glow.shape(places, settings.reverse, settings.scale);
        return RgbAnimator.overlay(Glow.frame(mode, time, shaped, settings.color, settings.color2, settings.cpu(),
                Math.max(25, settings.brightness), settings.on, seed), settings.flashColor,
                settings.flashLevel(System.currentTimeMillis()));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        var now = System.nanoTime();
        time += (now - last) / 1e9 * Math.max(1, Math.min(100, settings.speed)) / 50.0;
        last = now;

        var g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        var height = getHeight();

        // Вентилятор — кольцо светодиодов
        var size = height - 8;
        var cx = 4 + size / 2.0;
        var cy = height / 2.0;
        var ring = frame(fan, 0);

        for (var i = 0; i < FAN; i++) {
            var angle = 2 * Math.PI * i / FAN - Math.PI / 2;
            var x = cx + Math.cos(angle) * size * 0.38;
            var y = cy + Math.sin(angle) * size * 0.38;
            dot(g, ring[i], x, y, 9);
        }

        // Лента
        var left = (int) (cx + size / 2.0 + 18);
        var keyboardWidth = Math.min(300, Math.max(160, getWidth() / 3));
        var stripWidth = Math.max(80, getWidth() - left - keyboardWidth - 30);
        var line = frame(strip, 1);

        for (var i = 0; i < STRIP; i++) {
            dot(g, line[i], left + (i + 0.5) * stripWidth / STRIP, cy, Math.min(9, stripWidth / (double) STRIP));
        }

        // Клавиатура
        var keyLeft = left + stripWidth + 26;
        var cell = Math.min((getWidth() - keyLeft - 6) / (double) COLUMNS, (height - 8) / (double) ROWS);
        var board = frame(keys, 2);

        for (var row = 0; row < ROWS; row++) {
            for (var column = 0; column < COLUMNS; column++) {
                var color = board[row * COLUMNS + column];
                var x = keyLeft + column * cell;
                var y = 4 + row * cell + (height - 8 - ROWS * cell) / 2;
                g.setColor(new Color(0x0C, 0x12, 0x0F));
                g.fillRoundRect((int) x, (int) y, (int) cell - 2, (int) cell - 2, 4, 4);
                g.setColor(color);
                g.fillRoundRect((int) x + 1, (int) y + 1, (int) cell - 4, (int) cell - 4, 3, 3);
            }
        }

        g.dispose();
    }

    /** Светодиод со свечением вокруг. */
    private static void dot(Graphics2D g, Color color, double x, double y, double radius) {
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 60));
        g.fill(new java.awt.geom.Ellipse2D.Double(x - radius, y - radius, radius * 2, radius * 2));
        g.setColor(color);
        g.fill(new java.awt.geom.Ellipse2D.Double(x - radius / 2, y - radius / 2, radius, radius));
    }
}
