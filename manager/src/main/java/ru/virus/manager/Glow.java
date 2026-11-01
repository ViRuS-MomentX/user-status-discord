package ru.virus.manager;

import java.awt.Color;
import java.util.List;

/**
 * Режимы V.I.R.U.S. для лент и вентиляторов: те же двенадцать, что в
 * эффекте для SignalRGB, только считаются здесь — по одному цвету на
 * светодиод. Положение светодиода на ленте — от 0 до 1.
 */
final class Glow {

    private Glow() {
    }

    /**
     * Кадр для устройства.
     *
     * @param t      время в секундах с учётом скорости
     * @param leds   сколько светодиодов
     * @param seed   у каждого устройства свой — чтобы «звёзды» и «матрица» не мигали хором
     * @param cpu    загрузка процессора 0..1
     */
    static Color[] frame(int mode, double t, int leds, Color a, Color b, double cpu, int brightness,
                         boolean on, int seed) {
        var colors = new Color[leds];
        var level = on ? Math.max(0, Math.min(100, brightness)) / 100.0 : 0;

        for (var i = 0; i < leds; i++) {
            var x = leds == 1 ? 0 : (double) i / leds;
            colors[i] = scale(pixel(mode, t, x, i + seed * 131, a, b, cpu), level);
        }

        return colors;
    }

    /**
     * Кадр по местам светодиодов: у клавиатуры — по карте клавиш, у ленты и
     * вентилятора — вдоль зоны.
     */
    static Color[] frame(int mode, double t, List<OpenRgb.Place> places, Color a, Color b, double cpu,
                         int brightness, boolean on, int seed) {
        var colors = new Color[places.size()];
        var level = on ? Math.max(0, Math.min(100, brightness)) / 100.0 : 0;

        for (var i = 0; i < colors.length; i++) {
            var place = places.get(i);
            colors[i] = scale(pixel(mode, t, place.x(), place.y(), place.grid(), i + seed * 131, a, b, cpu), level);
        }

        return colors;
    }

    /**
     * Направление и масштаб узора: зеркально по ширине и «сжатие», чтобы
     * радуга или волна повторялась несколько раз. «Экран», «Звук», «Полицию»
     * и «Сканер» так не трогаем — им важно настоящее место.
     */
    static List<OpenRgb.Place> shape(List<OpenRgb.Place> places, boolean reverse, double scale) {
        if (!reverse && scale == 1) {
            return places;
        }

        var result = new java.util.ArrayList<OpenRgb.Place>(places.size());

        for (var place : places) {
            var x = reverse ? 1 - place.x() : place.x();
            result.add(new OpenRgb.Place(x * scale, place.y(), place.grid()));
        }

        return result;
    }

    /** Режимы, которым нужно настоящее место светодиода: экран и звук. */
    static boolean positional(int mode) {
        return mode == 12 || mode == 13 || mode == 7 || mode == 15 || mode == 21;
    }

    /** Эффекты, которым есть что делать с высотой: на клавиатуре они идут по строкам и столбцам. */
    static Color pixel(int mode, double t, double x, double y, boolean grid, int i, Color a, Color b, double cpu) {
        // Экран: клавиатура повторяет картинку целиком, лента и вентилятор — по столбцам
        if (mode == 12) {
            return ScreenSampler.at(x, y, !grid);
        }

        if (mode == 13) {
            return sound(x, y, grid, i, a, b);
        }

        if (grid) {
            switch (mode) {
                case 5 -> {
                    // Матрица: капли падают по столбцам клавиатуры, у каждого столбца свой темп
                    var column = (int) Math.round(x * 64);
                    var head = fract(y * 1.2 + hash(column) * 3 - t * (0.5 + hash(column + 7) * 0.4));
                    var v = Math.pow(head, 6);
                    return v > 0.9 ? mix(a, Color.WHITE, 0.6) : scale(a, v);
                }
                case 6 -> {
                    // Огонь: снизу жарче, вверх гаснет
                    var flicker = 0.4 + 0.6 * noise(i, t * 6);
                    var heat = Math.max(0, Math.min(1, y * 1.3 - 0.2 + flicker * 0.4));
                    return scale(mix(new Color(255, 30, 0), new Color(255, 200, 60), heat * noise(i + 77, t * 3)), heat);
                }
                case 8 -> {
                    var w = 0.5 + 0.5 * Math.sin(x * 10 + t) * Math.sin(y * 6 - t * 0.7);
                    return scale(mix(new Color(0, 255, 140), new Color(140, 40, 255), w),
                            0.35 + 0.65 * Math.abs(Math.sin(x * 4 + y * 3 + t * 0.5)));
                }
                default -> {
                    // остальные — как на ленте
                }
            }
        }

        return pixel(mode, t, x, i, a, b, cpu);
    }

    /**
     * «Звук»: на ленте и вентиляторе — индикатор уровня (левый канал с одного
     * края, правый с другого), на клавиатуре — столбики эквалайзера снизу
     * вверх. Цвет идёт от первого (тихо) ко второму (громко).
     */
    static Color sound(double x, double y, boolean grid, int i, Color a, Color b) {
        var left = AudioMeter.left();
        var right = AudioMeter.right();

        if (grid) {
            // Каждому столбцу — своя доля громкости, чтобы было похоже на эквалайзер
            var level = (x < 0.5 ? left : right) * (0.65 + 0.35 * hash((int) Math.round(x * 64)));
            var height = 1 - y;
            return height <= level ? mix(a, b, height) : scale(a, 0.04);
        }

        var fromEdge = x < 0.5 ? x * 2 : (1 - x) * 2;
        var level = x < 0.5 ? left : right;
        return fromEdge <= level ? mix(a, b, fromEdge) : scale(a, 0.04);
    }

    static Color pixel(int mode, double t, double x, int i, Color a, Color b, double cpu) {
        return switch (mode) {
            case 0 -> a;
            case 1 -> scale(a, 0.08 + 0.92 * (0.5 - 0.5 * Math.cos(t * 2)));
            case 2 -> hue(t * 40);
            case 3 -> hue(x * 360 + t * 90);
            case 4 -> mix(a, b, 0.5 + 0.5 * Math.sin(x * 2 * Math.PI * 2 + t * 2));
            case 5 -> {
                // Бегущие «капли» с хвостом
                var head = fract(x * 2 + hash(i % 7) * 0.3 - t * 0.6);
                var v = Math.pow(head, 8);
                yield v > 0.92 ? mix(a, Color.WHITE, 0.6) : scale(a, v);
            }
            case 6 -> {
                var flicker = 0.55 + 0.45 * noise(i, t * 6);
                yield scale(mix(new Color(255, 40, 0), new Color(255, 200, 60), noise(i + 77, t * 3)), flicker);
            }
            case 7 -> {
                var phase = (int) Math.floor(t * 4) % 4;
                var left = x < 0.5;
                yield left ? (phase == 0 ? Color.RED : Color.BLACK) : (phase == 2 ? new Color(0, 51, 255) : Color.BLACK);
            }
            case 8 -> {
                var w = 0.5 + 0.5 * Math.sin(x * 10 + t) * Math.sin(x * 6 - t * 0.7);
                yield scale(mix(new Color(0, 255, 140), new Color(140, 40, 255), w),
                        0.35 + 0.65 * Math.abs(Math.sin(x * 4 + t * 0.5)));
            }
            case 9 -> {
                var beat = t % 1.6;
                var level = Math.max(Math.exp(-Math.pow((beat - 0.1) * 14, 2)),
                        0.7 * Math.exp(-Math.pow((beat - 0.42) * 14, 2)));
                yield scale(a, 0.05 + 0.95 * level);
            }
            case 10 -> {
                var load = Math.max(0, Math.min(1, cpu));
                yield load < 0.5 ? mix(new Color(0, 255, 80), new Color(255, 210, 0), load * 2)
                        : mix(new Color(255, 210, 0), Color.RED, (load - 0.5) * 2);
            }
            case 11 -> {
                var k = Math.pow(Math.max(0, Math.sin(hash(i) * 6.28 + t * (1.5 + hash(i + 9)))), 4);
                yield mix(scale(b, 0.12), a, k);
            }
            case 14 -> {
                // Комета: голова бежит по кругу, хвост затухает
                var behind = fract(t * 0.5 - x);
                yield behind < 0.02 ? mix(a, Color.WHITE, 0.4) : scale(a, Math.pow(Math.max(0, 1 - behind * 3), 2));
            }
            case 15 -> {
                // Сканер (Larson): огонёк ходит туда-обратно, с мягким шлейфом
                var head = 0.5 - 0.5 * Math.cos(t * 1.6);
                var distance = Math.abs(x - head);
                yield scale(a, Math.max(0, 1 - distance * 8));
            }
            case 16 -> {
                // Театр: каждый третий горит, огни бегут
                var step = (int) Math.floor(t * 6);
                yield (i + step) % 3 == 0 ? a : scale(b, 0.15);
            }
            case 17 -> {
                // Конфетти: вспышки оттенков вокруг выбранного цвета, гаснут сами
                var phase = fract(t * 0.7 + hash(i) * 7);
                var flash = Math.pow(Math.max(0, 1 - phase * 4), 2);
                var shifted = shiftHue(a, (hash(i * 31 + (int) Math.floor(t * 0.7 + hash(i) * 7)) - 0.5) * 120);
                yield scale(shifted, flash);
            }
            case 18 -> {
                // Температура видеокарты; если горячо — ещё и тревожно пульсирует
                var degrees = GpuSensor.celsius();
                var color = GpuSensor.color(degrees);
                yield degrees >= 85 ? scale(color, 0.55 + 0.45 * (0.5 + 0.5 * Math.sin(t * 6))) : color;
            }
            // Градиент: неподвижный переход a → b вдоль устройства, как Gradient в Aura и SignalRGB
            case 19 -> mix(a, b, x);
            // Естественный свет: цветовая температура по часам, как Natural Light у Hue и f.lux
            case 20 -> kelvin(daylight(java.time.LocalTime.now()));
            // Отклик на ввод: волна от каждого нажатия, в покое — тление вторым цветом
            case 21 -> mix(scale(b, 0.18), a, InputPulse.wave(InputPulse.presses(), x, System.currentTimeMillis()));
            default -> a;
        };
    }

    /**
     * Цветовая температура по времени суток, K: ночью 2000 (свеча), утром
     * поднимается, днём 6500 (дневной свет), к вечеру опускается — как
     * расписание f.lux и Natural Light у Hue. Переходы плавные, по минутам.
     */
    static double daylight(java.time.LocalTime time) {
        var hour = time.getHour() + time.getMinute() / 60.0;
        double[][] points = {{0, 2000}, {5, 2000}, {8, 4500}, {11, 6500}, {16, 6500}, {19, 3400}, {22, 2200}, {24, 2000}};

        for (var i = 1; i < points.length; i++) {
            if (hour <= points[i][0]) {
                var k = (hour - points[i - 1][0]) / (points[i][0] - points[i - 1][0]);
                return points[i - 1][1] + (points[i][1] - points[i - 1][1]) * k;
            }
        }

        return 2000;
    }

    /** Цвет абсолютно чёрного тела при такой температуре (приближение Таннера Хелланда). */
    static Color kelvin(double kelvin) {
        var t = Math.max(1000, Math.min(40000, kelvin)) / 100;
        double r;
        double g;
        double b;

        if (t <= 66) {
            r = 255;
            g = 99.4708025861 * Math.log(t) - 161.1195681661;
            b = t <= 19 ? 0 : 138.5177312231 * Math.log(t - 10) - 305.0447927307;
        } else {
            r = 329.698727446 * Math.pow(t - 60, -0.1332047592);
            g = 288.1221695283 * Math.pow(t - 60, -0.0755148492);
            b = 255;
        }

        return new Color(clamp(r), clamp(g), clamp(b));
    }

    private static int clamp(double value) {
        return (int) Math.round(Math.max(0, Math.min(255, value)));
    }

    /** Тот же цвет с оттенком, сдвинутым на {@code degrees}. */
    static Color shiftHue(Color color, double degrees) {
        var hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        var hue = (float) ((hsb[0] + degrees / 360 + 1) % 1);
        return Color.getHSBColor(hue, hsb[1], hsb[2]);
    }

    static Color hue(double degrees) {
        return Color.getHSBColor((float) (((degrees % 360) + 360) % 360 / 360), 1f, 1f);
    }

    /**
     * Смесь двух цветов в линейном свете, как у Philips Hue и LIFX. Обычная
     * смесь чисел sRGB проваливается посередине: красный с синим дают 128, а
     * после гамма-поправки это ~22% света — тёмная грязная полоса в градиенте
     * и «моргание» на плавном переходе. Здесь середина светит вполсилы, как и
     * ждёт глаз.
     */
    static Color mix(Color a, Color b, double k) {
        k = Math.max(0, Math.min(1, k));

        if (k == 0) {
            return a;
        }

        if (k == 1) {
            return b;
        }

        return new Color(channel(a.getRed(), b.getRed(), k), channel(a.getGreen(), b.getGreen(), k),
                channel(a.getBlue(), b.getBlue(), k));
    }

    /** Линейный свет канала 0–255 — таблица, чтобы не считать степень 30 раз в секунду на каждый светодиод. */
    private static final double[] LINEAR = new double[256];

    static {
        for (var i = 0; i < 256; i++) {
            LINEAR[i] = Math.pow(i / 255.0, 2.2);
        }
    }

    private static int channel(int a, int b, double k) {
        if (a == b) {
            return a;
        }

        var light = LINEAR[a] + (LINEAR[b] - LINEAR[a]) * k;
        return (int) Math.round(Math.pow(light, 1 / 2.2) * 255);
    }

    static Color scale(Color color, double k) {
        k = Math.max(0, Math.min(1, k));
        return new Color((int) Math.round(color.getRed() * k), (int) Math.round(color.getGreen() * k),
                (int) Math.round(color.getBlue() * k));
    }

    private static double fract(double value) {
        return value - Math.floor(value);
    }

    /** Псевдослучайное 0..1, всегда одно и то же для одного числа. */
    static double hash(int n) {
        var h = n * 374761393 + 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFFFF) / (double) 0xFFFFFF;
    }

    /** Плавный шум во времени: соседние кадры похожи, мерцание без рывков. */
    static double noise(int i, double t) {
        var whole = (int) Math.floor(t);
        var k = t - whole;
        var smooth = k * k * (3 - 2 * k);
        return hash(i * 7919 + whole) * (1 - smooth) + hash(i * 7919 + whole + 1) * smooth;
    }
}
