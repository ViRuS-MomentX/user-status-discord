package ru.virus.manager;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Подставка сна одним взглядом: крупно — что сейчас (платы нет, свободно,
 * фигурка стоит), ниже — шкалы магнита и весов с чертой порога. По шкале
 * сразу видно, где ставить порог: посередине между «пусто» и «с фигуркой».
 */
final class StandGauge extends JComponent {

    private boolean on;
    private boolean connected;
    private boolean placed;
    private boolean asleep;
    private SleepStand.Reading reading;
    private int magnetThreshold = 20;
    private int gramThreshold = 250;

    StandGauge() {
        setPreferredSize(new Dimension(520, 118));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 118));
    }

    void set(boolean on, boolean connected, SleepStand.Reading reading, boolean asleep, int magnetThreshold, int gramThreshold) {
        this.on = on;
        this.connected = connected && reading != null;
        this.reading = reading;
        this.asleep = asleep;
        this.magnetThreshold = magnetThreshold;
        this.gramThreshold = gramThreshold;
        this.placed = this.connected && reading.placed(gramThreshold, magnetThreshold);
        repaint();
    }

    /** Заголовок состояния — отдельно, чтобы проверить словами. */
    static String title(boolean on, boolean connected, boolean placed, boolean asleep) {
        if (!on) {
            return "Подставка выключена";
        }

        if (!connected) {
            return "Плата не на связи";
        }

        return asleep ? "Фигурка на подставке — компьютер спит" : placed ? "Фигурка на подставке" : "Подставка свободна";
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        var g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        var color = !on ? Theme.DIM : !connected ? Theme.WARN : placed ? Theme.ACCENT : Theme.MUTED;
        g.setColor(color);
        g.fillOval(2, 8, 14, 14);
        g.setFont(Theme.ui.deriveFont(Font.BOLD, 17f));
        g.setColor(on ? Theme.TEXT : Theme.MUTED);
        g.drawString(title(on, connected, placed, asleep), 26, 21);

        var width = Math.min(getWidth() - 4, 560);
        scale(g, 44, width, "Магнит", connected && reading.magnet() >= 0 ? reading.magnet() : -1, magnetThreshold,
                Math.max(60, magnetThreshold * 3), "");
        scale(g, 84, width, "Весы", connected ? reading.grams() : -1, gramThreshold, Math.max(600, gramThreshold * 2), " г");
        g.dispose();
    }

    private void scale(Graphics2D g, int y, int width, String name, int value, int threshold, int max, String unit) {
        var x = 80;
        var w = width - x - 110;
        g.setFont(Theme.ui.deriveFont(12.5f));
        g.setColor(Theme.MUTED);
        g.drawString(name, 2, y + 14);
        g.setColor(new Color(0x1C, 0x26, 0x21));
        g.fillRoundRect(x, y, w, 18, 8, 8);

        if (value >= 0) {
            var over = value >= threshold;
            g.setColor(over ? Theme.ACCENT_DIM : new Color(0x3A, 0x4A, 0x43));
            g.fillRoundRect(x, y, (int) Math.max(6, Math.min(1.0, value / (double) max) * w), 18, 8, 8);
        }

        // Черта порога
        var tx = x + (int) (Math.min(1.0, threshold / (double) max) * w);
        g.setColor(Theme.WARN);
        g.fillRect(tx - 1, y - 4, 2, 26);
        g.setFont(Theme.ui.deriveFont(10.5f));
        g.drawString("порог " + threshold + unit, Math.min(tx + 5, x + w - 60), y + 32);
        g.setFont(Theme.mono.deriveFont(Font.BOLD, 14f));
        g.setColor(value >= 0 ? Theme.TEXT : Theme.DIM);
        g.drawString(value >= 0 ? value + unit : name.equals("Весы") && reading != null && connected ? "нет" : "—", x + w + 12, y + 14);
    }
}
