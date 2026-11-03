package ru.virus.manager;

import javax.swing.JToggleButton;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Кнопка режима без названия — живая полоска светодиодов, которая играет
 * сам режим тем же кодом, что красит устройства (как плитки эффектов в
 * SignalRGB и WLED). Название и описание — во всплывающей подсказке.
 */
final class ModeTile extends JToggleButton {

    static final int ROWS = 3;
    static final int COLUMNS = 12;

    /** Один таймер на все плитки: 20 кадров в секунду, только пока они на экране. */
    private static final Set<ModeTile> ALL = java.util.Collections.newSetFromMap(new WeakHashMap<>());
    private static final Timer TIMER = new Timer(50, e -> {
        synchronized (ALL) {
            for (var tile : ALL) {
                if (tile.isShowing()) {
                    tile.repaint();
                }
            }
        }
    });

    private static final List<OpenRgb.Place> PLACES = new ArrayList<>();

    static {
        for (var row = 0; row < ROWS; row++) {
            for (var column = 0; column < COLUMNS; column++) {
                PLACES.add(new OpenRgb.Place((column + 0.5) / COLUMNS, (row + 0.5) / ROWS, true));
            }
        }
    }

    private static final Color OFF = new Color(0x2A, 0x2F, 0x37);
    private static final long START = System.nanoTime();

    private final int mode;
    private final SignalRgb.Live live;
    private boolean hover;

    ModeTile(int mode, SignalRgb.Live live) {
        this.mode = mode;
        this.live = live;
        setFocusable(false);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        setPreferredSize(new Dimension(150, 62));
        addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                hover = true;
                repaint();
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                hover = false;
                repaint();
            }
        });

        synchronized (ALL) {
            ALL.add(this);
        }

        if (!TIMER.isRunning()) {
            TIMER.start();
        }
    }

    int mode() {
        return mode;
    }

    /** Цвет «светодиода» на экране: погасший — тёмно-серый квадрат, как на настоящей ленте. */
    static Color shown(Color color) {
        var light = Math.max(color.getRed(), Math.max(color.getGreen(), color.getBlue()));

        if (light < 30) {
            return OFF;
        }

        return light < 90 ? Glow.mix(OFF, color, (light - 30) / 60.0) : color;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        var g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        var w = getWidth();
        var h = getHeight();
        var selected = isSelected();

        g.setColor(selected ? Theme.RAISED : Theme.CARD_SOLID);
        g.fillRoundRect(0, 0, w - 1, h - 1, 12, 12);

        // Режим идёт в своём темпе, как на устройствах: скорость из настроек
        var t = (System.nanoTime() - START) / 1e9 * Math.max(1, Math.min(100, live.speed)) / 50.0;
        var colors = Glow.frame(mode, t, PLACES, live.color, live.color2, live.cpu(), 100, true, mode * 7 + 3);
        var pad = 8;
        var gap = 3;
        var cell = Math.min((w - pad * 2 - gap * (COLUMNS - 1)) / (double) COLUMNS,
                (h - pad * 2 - gap * (ROWS - 1)) / (double) ROWS);
        var gridW = cell * COLUMNS + gap * (COLUMNS - 1);
        var gridH = cell * ROWS + gap * (ROWS - 1);
        var x0 = (w - gridW) / 2;
        var y0 = (h - gridH) / 2;

        for (var row = 0; row < ROWS; row++) {
            for (var column = 0; column < COLUMNS; column++) {
                var color = shown(colors[row * COLUMNS + column]);
                var x = x0 + column * (cell + gap);
                var y = y0 + row * (cell + gap);
                g.setColor(color);
                g.fill(new java.awt.geom.RoundRectangle2D.Double(x, y, cell, cell, 4, 4));
            }
        }

        if (selected || hover) {
            g.setStroke(new BasicStroke(selected ? 2f : 1f));
            g.setColor(selected ? Theme.ACCENT : Theme.LINE_2);
            g.drawRoundRect(1, 1, w - 3, h - 3, 12, 12);
        }

        g.dispose();
    }
}
