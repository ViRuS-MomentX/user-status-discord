package ru.virus.manager;

import java.awt.Color;

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
            default -> a;
        };
    }

    static Color hue(double degrees) {
        return Color.getHSBColor((float) (((degrees % 360) + 360) % 360 / 360), 1f, 1f);
    }

    static Color mix(Color a, Color b, double k) {
        k = Math.max(0, Math.min(1, k));
        return new Color((int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * k),
                (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * k),
                (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * k));
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
