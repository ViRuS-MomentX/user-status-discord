package ru.virus.manager;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Мелкие детали интерфейса: карточки, переключатель, точка состояния, надписи.
 */
final class Ui {

    private Ui() {
    }

    static JLabel label(String text, Font font, Color color) {
        var label = new JLabel(text);
        label.setFont(font);
        label.setForeground(color);
        return label;
    }

    static JLabel text(String text) {
        return label(text, Theme.ui, Theme.TEXT);
    }

    static JLabel muted(String text, float size) {
        return label(text, Theme.ui.deriveFont(size), Theme.MUTED);
    }

    /** Заголовок раздела капителью с разрядкой: «ПРОЦЕССЫ». */
    static JLabel caption(String text) {
        var font = Theme.ui.deriveFont(Font.BOLD, 11f)
                .deriveFont(Map.of(TextAttribute.TRACKING, 0.14f));
        return label(text.toUpperCase(), font, Theme.DIM);
    }

    static JButton button(String text) {
        var button = new JButton(text);
        button.setFocusable(true);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    /** Главная кнопка — зелёная. */
    static JButton primary(String text) {
        var button = button(text);
        button.putClientProperty("JButton.buttonType", null);
        button.setBackground(Theme.ACCENT);
        button.setForeground(new Color(0x04, 0x14, 0x0B));
        button.setFont(Theme.ui.deriveFont(Font.BOLD));
        return button;
    }

    static JButton danger(String text) {
        var button = button(text);
        button.setForeground(Theme.ERROR);
        return button;
    }

    static JButton ghost(String text) {
        var button = button(text);
        button.putClientProperty("JButton.buttonType", "borderless");
        button.setForeground(Theme.MUTED);
        return button;
    }

    static JPanel transparent(LayoutManager layout) {
        var panel = new JPanel(layout);
        panel.setOpaque(false);
        return panel;
    }

    /**
     * Клик по пустому месту компонента — волна на фоне. Кнопки и поля клики
     * забирают себе, так что сюда доходят только клики мимо них.
     */
    static void rippleThrough(JComponent component, MatrixBackground background) {
        component.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                background.touch(SwingUtilities.convertPoint(component, e.getPoint(), background));
            }
        });
    }

    /**
     * Панель, которая в прокрутке занимает ровно ширину окна: перенос строк
     * вместо горизонтальной прокрутки и обрезанных справа полей.
     */
    static final class FitWidth extends JPanel implements javax.swing.Scrollable {

        FitWidth() {
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle visible, int orientation, int direction) {
            return 22;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle visible, int orientation, int direction) {
            return Math.max(22, visible.height - 40);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /** Карточка: скруглённая, полупрозрачная — сквозь неё виден код на фоне. */
    static class Card extends JPanel {

        private final Color fill;

        Card(LayoutManager layout) {
            this(layout, Theme.CARD);
        }

        Card(LayoutManager layout, Color fill) {
            super(layout);
            this.fill = fill;
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            var g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            var shape = new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, getHeight() - 1, Theme.ARC, Theme.ARC);
            g.setColor(fill);
            g.fill(shape);
            g.setColor(Theme.LINE);
            g.setStroke(new BasicStroke(1f));
            g.draw(shape);
            g.dispose();
        }
    }

    /** Переключатель вкл/выкл — нагляднее флажка. */
    static final class Switch extends JComponent {

        private boolean on;
        private final List<Consumer<Boolean>> listeners = new ArrayList<>();

        Switch(boolean on) {
            this.on = on;
            setPreferredSize(new Dimension(40, 22));
            setMinimumSize(getPreferredSize());
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFocusable(true);

            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (isEnabled()) {
                        toggle();
                    }
                }
            });

            addKeyListener(new java.awt.event.KeyAdapter() {
                @Override
                public void keyPressed(java.awt.event.KeyEvent e) {
                    if (isEnabled() && (e.getKeyCode() == java.awt.event.KeyEvent.VK_SPACE
                            || e.getKeyCode() == java.awt.event.KeyEvent.VK_ENTER)) {
                        toggle();
                    }
                }
            });
        }

        private void toggle() {
            on = !on;
            repaint();
            listeners.forEach(listener -> listener.accept(on));
        }

        boolean isOn() {
            return on;
        }

        /** Без оповещения — когда значение пришло извне. */
        void setOn(boolean on) {
            if (this.on != on) {
                this.on = on;
                repaint();
            }
        }

        void onChange(Consumer<Boolean> listener) {
            listeners.add(listener);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            var g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            var h = 20;
            var y = (getHeight() - h) / 2;
            g.setColor(on ? Theme.ACCENT_DIM : Theme.LINE_2);
            g.fillRoundRect(0, y, 38, h, h, h);

            if (isFocusOwner()) {
                g.setColor(Theme.alpha(Theme.ACCENT, 120));
                g.drawRoundRect(0, y, 37, h - 1, h, h);
            }

            g.setColor(on ? Theme.ACCENT : Theme.MUTED);
            var x = on ? 21 : 3;
            g.fillOval(x, y + 3, 14, 14);
            g.dispose();
        }
    }

    /** Точка состояния процесса; у работающего — пульсирует. */
    static final class Dot extends JComponent {

        private ManagedProcess.State state = ManagedProcess.State.STOPPED;

        Dot() {
            setPreferredSize(new Dimension(18, 18));
            setOpaque(false);
        }

        void setState(ManagedProcess.State state) {
            this.state = state;
            repaint();
        }

        static Color color(ManagedProcess.State state) {
            return switch (state) {
                case RUNNING -> Theme.ACCENT;
                case WAITING, STOPPING -> Theme.WARN;
                case FAILED -> Theme.ERROR;
                case STOPPED -> Theme.DIM;
            };
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            var g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            var cx = getWidth() / 2.0;
            var cy = getHeight() / 2.0;
            var color = color(state);

            if (state == ManagedProcess.State.RUNNING) {
                // Пульс: кольцо расходится раз в две секунды — фон перерисовывает нас и так
                var phase = (System.currentTimeMillis() % 2000) / 2000.0;
                var r = 5 + phase * 5;
                g.setColor(Theme.alpha(color, (int) (110 * (1 - phase))));
                g.fill(new java.awt.geom.Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
            }

            g.setColor(color);
            g.fill(new java.awt.geom.Ellipse2D.Double(cx - 5, cy - 5, 10, 10));
            g.dispose();
        }
    }

    /** Плашка-значок: «РАБОТАЕТ», «УПАЛ». */
    static final class Badge extends JLabel {

        private Color tone = Theme.MUTED;

        Badge() {
            setFont(Theme.ui.deriveFont(Font.BOLD, 11f).deriveFont(Map.of(TextAttribute.TRACKING, 0.06f)));
            setBorder(BorderFactory.createEmptyBorder(4, 9, 4, 9));
            setOpaque(false);
        }

        void set(String text, Color tone) {
            this.tone = tone;
            setText(text.toUpperCase());
            setForeground(tone);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            var g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Theme.alpha(tone, 32));
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    static void hand(AbstractButton button) {
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }
}
