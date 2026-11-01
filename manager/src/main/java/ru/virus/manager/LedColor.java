package ru.virus.manager;

import java.awt.Color;

/**
 * Цвет с экрана → цвет для светодиода.
 *
 * <p>Экран и палитра живут в sRGB, где 50% яркости — это не половина
 * света. Светодиод же светит линейно: значение 32 из 255 у него — восьмая
 * часть мощности, а глазу кажется заметно ярче. Без поправки красный
 * (255, 32, 32) горит розовым, оранжевый уходит в персиковый, а все цвета
 * выглядят выцветшими. Так делают WLED, Hyperion и прошивки лент: перед
 * отправкой — гамма-коррекция (2.2 — как у WLED по умолчанию) и баланс
 * белого по каналам, если какой-то цвет у ленты сильнее.
 */
final class LedColor {

    /** Гамма: 1 — без поправки, 2.2 — как sRGB, 2.8 — «плотнее», темнее середина. */
    static volatile double gamma = 2.2;
    /** Баланс белого: множитель канала, 0..1. Синий у дешёвых лент часто сильнее остальных. */
    static volatile double red = 1;
    static volatile double green = 1;
    static volatile double blue = 1;

    private static double tableGamma = -1;
    private static int[] table = new int[256];

    private LedColor() {
    }

    /** Цвет, который надо отправить светодиоду, чтобы глаз увидел {@code color}. */
    static Color correct(Color color) {
        var lut = table();
        return new Color(channel(lut, color.getRed(), red), channel(lut, color.getGreen(), green),
                channel(lut, color.getBlue(), blue));
    }

    private static int channel(int[] lut, int value, double gain) {
        return (int) Math.round(lut[value] * Math.max(0, Math.min(1, gain)));
    }

    private static synchronized int[] table() {
        var g = gamma;

        if (g != tableGamma) {
            var fresh = new int[256];

            for (var i = 0; i < 256; i++) {
                fresh[i] = (int) Math.round(Math.pow(i / 255.0, Math.max(1, g)) * 255);
            }

            table = fresh;
            tableGamma = g;
        }

        return table;
    }
}
