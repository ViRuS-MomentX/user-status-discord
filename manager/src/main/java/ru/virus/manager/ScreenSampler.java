package ru.virus.manager;

import java.awt.Color;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;

/**
 * Цвета экрана для режима «Экран» — как Ambilight, Screen Ambience у
 * SignalRGB и Hyperion: экран делится на сетку, у каждой клетки — средний
 * цвет. Светодиод берёт цвет своего места: у клавиатуры — клетки, у ленты и
 * вентилятора — столбца.
 *
 * <p>Снимает, только пока режим кто-то просит (последние 2 секунды), и не
 * чаще 8 раз в секунду: снимок экрана — работа, а мешать играм нельзя.
 */
final class ScreenSampler {

    static final int ROWS = 9;
    static final int COLUMNS = 16;
    private static final long PERIOD_MS = 125;
    private static final long IDLE_MS = 2000;

    /** Сетка цветов: [строка][столбец]; null — снимков ещё нет. */
    private static volatile Color[][] grid;
    /** Среднее по столбцу — для лент и вентиляторов. */
    private static volatile Color[] columns;
    private static volatile long wanted;
    private static Thread thread;

    private ScreenSampler() {
    }

    /** Цвет экрана в точке (x, y от 0 до 1); заодно просит снимать дальше. */
    static Color at(double x, double y, boolean byColumn) {
        demand();
        var snapshot = byColumn ? null : grid;
        var line = columns;

        if (line == null) {
            return Color.BLACK;
        }

        var column = Math.max(0, Math.min(COLUMNS - 1, (int) (x * COLUMNS)));

        if (snapshot == null) {
            return line[column];
        }

        var row = Math.max(0, Math.min(ROWS - 1, (int) (y * ROWS)));
        return snapshot[row][column];
    }

    private static synchronized void demand() {
        wanted = System.currentTimeMillis();

        if (thread != null && thread.isAlive() || GraphicsEnvironment.isHeadless()) {
            return;
        }

        thread = new Thread(ScreenSampler::run, "virus-screen-sampler");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private static void run() {
        Robot robot;

        try {
            robot = new Robot();
        } catch (Exception e) {
            return;
        }

        var screen = new Rectangle(java.awt.Toolkit.getDefaultToolkit().getScreenSize());

        while (System.currentTimeMillis() - wanted < IDLE_MS) {
            var started = System.currentTimeMillis();

            try {
                take(robot.createScreenCapture(screen));
            } catch (RuntimeException e) {
                // экран заблокирован или сменился — попробуем на следующем кадре
            }

            try {
                Thread.sleep(Math.max(20, PERIOD_MS - (System.currentTimeMillis() - started)));
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Разложить снимок на сетку средних цветов. */
    static void take(BufferedImage image) {
        var cells = new Color[ROWS][COLUMNS];
        var sums = new long[COLUMNS][3];
        var counts = new int[COLUMNS];
        var width = image.getWidth();
        var height = image.getHeight();
        // Каждый пиксель не нужен: хватает сетки через 8 точек — в 64 раза меньше работы
        var step = 8;

        for (var row = 0; row < ROWS; row++) {
            for (var column = 0; column < COLUMNS; column++) {
                long r = 0;
                long g = 0;
                long b = 0;
                var n = 0;
                var x0 = column * width / COLUMNS;
                var x1 = (column + 1) * width / COLUMNS;
                var y0 = row * height / ROWS;
                var y1 = (row + 1) * height / ROWS;

                for (var y = y0; y < y1; y += step) {
                    for (var x = x0; x < x1; x += step) {
                        var rgb = image.getRGB(x, y);
                        r += (rgb >> 16) & 0xFF;
                        g += (rgb >> 8) & 0xFF;
                        b += rgb & 0xFF;
                        n++;
                    }
                }

                n = Math.max(1, n);
                cells[row][column] = vivid(new Color((int) (r / n), (int) (g / n), (int) (b / n)));
                sums[column][0] += r;
                sums[column][1] += g;
                sums[column][2] += b;
                counts[column] += n;
            }
        }

        var line = new Color[COLUMNS];

        for (var column = 0; column < COLUMNS; column++) {
            var n = Math.max(1, counts[column]);
            line[column] = vivid(new Color((int) (sums[column][0] / n), (int) (sums[column][1] / n),
                    (int) (sums[column][2] / n)));
        }

        grid = cells;
        columns = line;
    }

    /**
     * Средний цвет почти всегда сероват — у Hyperion и Ambilight его делают
     * насыщеннее, иначе подсветка выглядит грязной. Тёмное не трогаем: чёрный
     * экран — тёмная подсветка.
     */
    static Color vivid(Color color) {
        var hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        var saturation = Math.min(1f, hsb[1] * 1.6f);
        return Color.getHSBColor(hsb[0], saturation, hsb[2]);
    }
}
