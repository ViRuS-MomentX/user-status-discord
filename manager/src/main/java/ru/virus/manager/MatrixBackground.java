package ru.virus.manager;

import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Фон окна: бинарный код и волны.
 *
 * <p>По всему окну еле видна сетка нулей и единиц. Сверху вниз бегут «капли» —
 * подсвечивают цифры на своём пути. Клик по фону переворачивает цифру под
 * курсором и пускает из этой точки зелёные круги; волна, проходя, зажигает
 * цифры, и видно, как она катится по коду.
 */
final class MatrixBackground extends JPanel {

    private static final int CELL_W = 16;
    private static final int CELL_H = 20;

    /** Кадров в секунду: глазу плавно, процессору легко. */
    private static final int FRAME_MS = 33;

    /** Сколько живёт волна и как быстро расходится, пикселей в миллисекунду. */
    private static final long RIPPLE_LIFE_MS = 2200;
    private static final double RIPPLE_SPEED = 0.42;

    /** Ширина полосы, в которой волна зажигает цифры. */
    private static final double RIPPLE_BAND = 16;

    /** Сколько колец в одной волне и на каком расстоянии друг за другом. */
    private static final int RINGS = 3;
    private static final double RING_GAP = 30;

    /** Яркость цифры в покое — чтобы код был виден, но не мешал читать. */
    private static final float BASE = 0.07f;

    /** Цвета цифр по яркости: заранее, а не по объекту на цифру в каждом кадре. */
    private static final Color[] SHADES = new Color[48];

    static {
        for (var i = 0; i < SHADES.length; i++) {
            var level = i / (float) (SHADES.length - 1);
            // Тусклые — тёмно-зелёные, яркие — почти белые с зелёным
            var r = (int) (0x39 * level + 0xC8 * level * level * 0.6);
            var g = (int) (0x50 + (0xFF - 0x50) * level);
            var b = (int) (0x8A * level + 0x60 * level * level * 0.5);
            SHADES[i] = new Color(Math.min(255, r), Math.min(255, g), Math.min(255, b),
                    (int) (18 + 237 * Math.pow(level, 0.8)));
        }
    }

    private record Ripple(double x, double y, long born) {
    }

    /** Капля: столбец, где голова и с какой скоростью падает (клеток в секунду). */
    private static final class Drop {
        int column;
        double head;
        double speed;
    }

    private final Random random = new Random();
    private final List<Ripple> ripples = new ArrayList<>();
    private final List<Drop> drops = new ArrayList<>();
    private final Timer timer;
    private final Font font;

    private int columns;
    private int rows;
    private char[] digits = new char[0];
    private float[] glow = new float[0];
    private long last = System.currentTimeMillis();

    MatrixBackground() {
        setOpaque(true);
        setBackground(Theme.BG);
        font = Theme.mono.deriveFont(Font.BOLD, 13f);

        timer = new Timer(FRAME_MS, event -> {
            step();
            repaint();
        });
        timer.setCoalesce(true);

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                resizeGrid();
            }
        });

        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                touch(e.getPoint());
            }
        });
    }

    void start() {
        last = System.currentTimeMillis();
        timer.start();
    }

    void stop() {
        timer.stop();
    }

    boolean isRunning() {
        return timer.isRunning();
    }

    /**
     * Клик по фону — сюда же приходят клики по пустым местам карточек, чтобы
     * волна шла из любой точки, где нет кнопки или поля.
     *
     * @param point в координатах этого фона
     */
    void touch(Point point) {
        var col = point.x / CELL_W;
        var row = point.y / CELL_H;

        if (col >= 0 && row >= 0 && col < columns && row < rows) {
            var index = row * columns + col;
            digits[index] = digits[index] == '0' ? '1' : '0';
            glow[index] = 1f;
        }

        ripples.add(new Ripple(point.x, point.y, System.currentTimeMillis()));

        // Двойной клик и кликанье подряд не должны копить сотни волн
        while (ripples.size() > 12) {
            ripples.removeFirst();
        }

        if (!timer.isRunning()) {
            repaint();
        }
    }

    private void resizeGrid() {
        var newColumns = Math.max(1, getWidth() / CELL_W + 1);
        var newRows = Math.max(1, getHeight() / CELL_H + 1);

        if (newColumns == columns && newRows == rows) {
            return;
        }

        var newDigits = new char[newColumns * newRows];
        var newGlow = new float[newColumns * newRows];

        for (var r = 0; r < newRows; r++) {
            for (var c = 0; c < newColumns; c++) {
                var i = r * newColumns + c;

                if (r < rows && c < columns) {
                    newDigits[i] = digits[r * columns + c];
                    newGlow[i] = glow[r * columns + c];
                } else {
                    newDigits[i] = random.nextBoolean() ? '1' : '0';
                }
            }
        }

        columns = newColumns;
        rows = newRows;
        digits = newDigits;
        glow = newGlow;

        // Капель — примерно одна на четыре столбца
        var wanted = Math.max(4, columns / 4);

        while (drops.size() < wanted) {
            drops.add(newDrop(true));
        }

        while (drops.size() > wanted) {
            drops.removeLast();
        }
    }

    private Drop newDrop(boolean anywhere) {
        var drop = new Drop();
        drop.column = random.nextInt(Math.max(1, columns));
        drop.head = anywhere ? random.nextDouble() * rows : -random.nextInt(Math.max(1, rows));
        drop.speed = 4 + random.nextDouble() * 9;
        return drop;
    }

    /** Один шаг анимации. Отдельно от рисования — так его можно проверить в тестах. */
    void step() {
        var now = System.currentTimeMillis();
        var dt = Math.min(100, now - last) / 1000.0;
        last = now;

        if (columns == 0) {
            resizeGrid();
        }

        // Свет гаснет плавно — за пару секунд
        var fade = (float) Math.pow(0.12, dt);

        for (var i = 0; i < glow.length; i++) {
            glow[i] *= fade;
        }

        for (var drop : drops) {
            var before = (int) drop.head;
            drop.head += drop.speed * dt;
            var after = (int) drop.head;

            for (var row = Math.max(0, before); row <= after && row < rows; row++) {
                var index = row * columns + drop.column;

                if (index >= 0 && index < glow.length) {
                    glow[index] = Math.max(glow[index], 0.55f);

                    // Голова капли иногда переписывает цифру — код «живой»
                    if (random.nextInt(4) == 0) {
                        digits[index] = random.nextBoolean() ? '1' : '0';
                    }
                }
            }

            if (drop.head > rows + 6) {
                var fresh = newDrop(false);
                drop.column = fresh.column;
                drop.head = fresh.head;
                drop.speed = fresh.speed;
            }
        }

        // Редкие одиночные перевороты по всему полю
        for (var i = 0; i < Math.max(1, digits.length / 400); i++) {
            var index = random.nextInt(Math.max(1, digits.length));

            if (index < digits.length) {
                digits[index] = digits[index] == '0' ? '1' : '0';
            }
        }

        ripples.removeIf(ripple -> now - ripple.born() > RIPPLE_LIFE_MS);

        for (var ripple : ripples) {
            var age = now - ripple.born();
            var strength = 1f - age / (float) RIPPLE_LIFE_MS;

            for (var k = 0; k < RINGS; k++) {
                var radius = age * RIPPLE_SPEED - k * RING_GAP;

                if (radius > 0) {
                    lightRing(ripple.x(), ripple.y(), radius, strength * (1f - k * 0.25f));
                }
            }
        }
    }

    /** Зажигает цифры, через которые сейчас проходит кольцо. */
    private void lightRing(double x, double y, double radius, float strength) {
        var minRow = Math.max(0, (int) ((y - radius - RIPPLE_BAND) / CELL_H));
        var maxRow = Math.min(rows - 1, (int) ((y + radius + RIPPLE_BAND) / CELL_H));
        var minCol = Math.max(0, (int) ((x - radius - RIPPLE_BAND) / CELL_W));
        var maxCol = Math.min(columns - 1, (int) ((x + radius + RIPPLE_BAND) / CELL_W));

        for (var row = minRow; row <= maxRow; row++) {
            for (var col = minCol; col <= maxCol; col++) {
                var cx = col * CELL_W + CELL_W / 2.0;
                var cy = row * CELL_H + CELL_H / 2.0;
                var distance = Math.abs(Math.hypot(cx - x, cy - y) - radius);

                if (distance < RIPPLE_BAND) {
                    var index = row * columns + col;
                    var light = strength * (float) (1 - distance / RIPPLE_BAND) * 0.9f;
                    glow[index] = Math.max(glow[index], light);
                }
            }
        }
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        var g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        var width = getWidth();
        var height = getHeight();

        g.setColor(Theme.BG);
        g.fillRect(0, 0, width, height);

        // Мягкое зелёное свечение в углу — чтобы фон не был плоским
        var radius = Math.max(width, height) * 0.7f;
        g.setPaint(new RadialGradientPaint(new Point2D.Float(width * 0.88f, -height * 0.1f), radius,
                new float[] { 0f, 1f }, new Color[] { new Color(57, 255, 138, 22), new Color(57, 255, 138, 0) }));
        g.fillRect(0, 0, width, height);

        g.setFont(font);
        var metrics = g.getFontMetrics();
        var baseline = (CELL_H + metrics.getAscent() - metrics.getDescent()) / 2;
        var clip = g.getClipBounds();

        for (var row = 0; row < rows; row++) {
            var y = row * CELL_H;

            if (clip != null && (y + CELL_H < clip.y || y > clip.y + clip.height)) {
                continue;
            }

            for (var col = 0; col < columns; col++) {
                var index = row * columns + col;
                var level = Math.min(1f, BASE + glow[index]);
                g.setColor(SHADES[(int) (level * (SHADES.length - 1))]);
                g.drawChars(digits, index, 1, col * CELL_W + 4, y + baseline);
            }
        }

        var now = System.currentTimeMillis();

        for (var ripple : ripples) {
            var age = now - ripple.born();
            var life = 1f - age / (float) RIPPLE_LIFE_MS;

            if (life <= 0) {
                continue;
            }

            // Вспышка в точке клика в первые мгновения
            if (age < 260) {
                var flash = 1f - age / 260f;
                var size = 18 + age * 0.12;
                g.setColor(Theme.alpha(Theme.ACCENT, (int) (90 * flash)));
                g.fill(new Ellipse2D.Double(ripple.x() - size, ripple.y() - size, size * 2, size * 2));
            }

            for (var k = 0; k < RINGS; k++) {
                var r = age * RIPPLE_SPEED - k * RING_GAP;

                if (r <= 0) {
                    continue;
                }

                var alpha = (int) (life * life * (235 - k * 60));
                // Свечение — толстое и прозрачное, сама линия — тонкая и яркая
                g.setStroke(new BasicStroke(9f));
                g.setColor(Theme.alpha(Theme.ACCENT, alpha / 5));
                g.draw(new Ellipse2D.Double(ripple.x() - r, ripple.y() - r, r * 2, r * 2));
                g.setStroke(new BasicStroke(2.2f));
                g.setColor(Theme.alpha(Theme.ACCENT, alpha));
                g.draw(new Ellipse2D.Double(ripple.x() - r, ripple.y() - r, r * 2, r * 2));
            }
        }

        g.dispose();
    }

    /** Для тестов: какая цифра в клетке. */
    char digitAt(int col, int row) {
        return digits[row * columns + col];
    }

    int rippleCount() {
        return ripples.size();
    }
}
