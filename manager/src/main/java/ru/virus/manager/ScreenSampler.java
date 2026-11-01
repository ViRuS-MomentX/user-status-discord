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
    /** Предыдущий снимок и когда пришёл новый — между ними цвет перетекает плавно. */
    private static volatile Color[][] previousGrid;
    private static volatile Color[] previousColumns;
    private static volatile long takenAt;
    private static Thread thread;

    private ScreenSampler() {
    }

    /** Цвет экрана в точке (x, y от 0 до 1); заодно просит снимать дальше. */
    static Color at(double x, double y, boolean byColumn) {
        demand();
        var now = sample(x, y, byColumn, grid, columns);

        if (now == null) {
            return Color.BLACK;
        }

        return smooth(now, sample(x, y, byColumn, previousGrid, previousColumns),
                (System.currentTimeMillis() - takenAt) / (double) PERIOD_MS);
    }

    /**
     * Сглаживание, как Smoothing у Hyperion: экран снимается 8 раз в секунду,
     * а подсветка рисует 30 кадров — без него цвет менялся бы ступеньками.
     * За время между снимками цвет перетекает от прошлого к новому.
     *
     * @param progress сколько прошло от нового снимка, в долях периода
     */
    static Color smooth(Color now, Color before, double progress) {
        if (before == null || progress >= 1) {
            return now;
        }

        return Glow.mix(before, now, Math.max(0, progress));
    }

    private static Color sample(double x, double y, boolean byColumn, Color[][] cells, Color[] line) {
        // Снимка ещё нет
        if (line == null) {
            return null;
        }

        var column = Math.max(0, Math.min(COLUMNS - 1, (int) (x * COLUMNS)));

        if (byColumn || cells == null) {
            return line[column];
        }

        var row = Math.max(0, Math.min(ROWS - 1, (int) (y * ROWS)));
        return cells[row][column];
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
        var border = settle(detect(image));
        var cells = new Color[ROWS][COLUMNS];
        var sums = new long[COLUMNS][3];
        var counts = new int[COLUMNS];
        // Считаем только картинку, без чёрных полос
        var left = border[1];
        var top = border[0];
        var width = image.getWidth() - 2 * left;
        var height = image.getHeight() - 2 * top;
        // Каждый пиксель не нужен: хватает сетки через 8 точек — в 64 раза меньше работы
        var step = 8;

        for (var row = 0; row < ROWS; row++) {
            for (var column = 0; column < COLUMNS; column++) {
                long r = 0;
                long g = 0;
                long b = 0;
                var n = 0;
                var x0 = left + column * width / COLUMNS;
                var x1 = left + (column + 1) * width / COLUMNS;
                var y0 = top + row * height / ROWS;
                var y1 = top + (row + 1) * height / ROWS;

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

        previousGrid = grid;
        previousColumns = columns;
        grid = cells;
        columns = line;
        takenAt = System.currentTimeMillis();
    }

    /** Ярче этого — уже картинка, а не полоса (запас на шум сжатия видео). */
    private static final int BLACK = 24;
    /** Сколько снимков подряд полосы должны быть одинаковыми, чтобы им поверить. */
    private static final int STABLE = 3;

    private static int[] border = {0, 0};
    private static int[] candidate = {0, 0};
    private static int seen;

    /**
     * Чёрные полосы фильма — сверху/снизу и слева/справа, в пикселях: {верх, бок}.
     * Как Blackborder detector у Hyperion: полоса — строка, где все точки
     * почти чёрные; берём меньшую из двух сторон (полосы симметричны) и не
     * больше трети экрана. Совсем чёрный экран — не полосы, а тёмная сцена.
     */
    static int[] detect(BufferedImage image) {
        var width = image.getWidth();
        var height = image.getHeight();
        var top = 0;

        while (top < height / 3 && blackRow(image, top) && blackRow(image, height - 1 - top)) {
            top++;
        }

        var side = 0;

        while (side < width / 3 && blackColumn(image, side) && blackColumn(image, width - 1 - side)) {
            side++;
        }

        // Дошли до трети — значит, тёмная сцена или чёрный экран, а не полосы
        return new int[] {top >= height / 3 ? 0 : top, side >= width / 3 ? 0 : side};
    }

    private static boolean blackRow(BufferedImage image, int y) {
        for (var x = 0; x < image.getWidth(); x += 8) {
            if (!dark(image.getRGB(x, y))) {
                return false;
            }
        }

        return true;
    }

    private static boolean blackColumn(BufferedImage image, int x) {
        for (var y = 0; y < image.getHeight(); y += 8) {
            if (!dark(image.getRGB(x, y))) {
                return false;
            }
        }

        return true;
    }

    private static boolean dark(int rgb) {
        return ((rgb >> 16) & 0xFF) < BLACK && ((rgb >> 8) & 0xFF) < BLACK && (rgb & 0xFF) < BLACK;
    }

    /**
     * Полосы меняем, только когда они продержались несколько снимков: в
     * тёмной сцене края на миг чернеют, и без этого подсветка дёргалась бы.
     */
    static synchronized int[] settle(int[] found) {
        if (java.util.Arrays.equals(found, border)) {
            seen = 0;
            return border;
        }

        if (java.util.Arrays.equals(found, candidate)) {
            seen++;
        } else {
            candidate = found;
            seen = 1;
        }

        if (seen >= STABLE) {
            border = found;
            seen = 0;
        }

        return border;
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
