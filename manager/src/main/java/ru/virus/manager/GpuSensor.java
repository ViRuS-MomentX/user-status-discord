package ru.virus.manager;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Температура видеокарты для режима «Температура» — как Temperature Lighting
 * в iCUE. Берётся у nvidia-smi, который ставится вместе с драйвером NVIDIA;
 * опрос — раз в 2 секунды и только пока режим кто-то просит.
 */
final class GpuSensor {

    private static final long PERIOD_MS = 2000;
    private static final long IDLE_MS = 5000;

    /** Градусы; −1 — неизвестно (нет NVIDIA или nvidia-smi). */
    private static volatile int celsius = -1;
    private static volatile long wanted;
    private static Thread thread;

    private GpuSensor() {
    }

    static int celsius() {
        demand();
        return celsius;
    }

    private static synchronized void demand() {
        wanted = System.currentTimeMillis();

        if (thread != null && thread.isAlive() || !SystemInfo.isWindows()) {
            return;
        }

        thread = new Thread(GpuSensor::run, "virus-gpu-sensor");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private static void run() {
        while (System.currentTimeMillis() - wanted < IDLE_MS) {
            try {
                var process = new ProcessBuilder("nvidia-smi", "--query-gpu=temperature.gpu",
                        "--format=csv,noheader,nounits").redirectErrorStream(true).start();
                var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                process.waitFor(5, TimeUnit.SECONDS);
                celsius = parse(out);
            } catch (Exception e) {
                celsius = -1;
            }

            try {
                Thread.sleep(PERIOD_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Первая строка ответа nvidia-smi — градусы первой видеокарты. */
    static int parse(String output) {
        for (var line : output.lines().toList()) {
            try {
                return Integer.parseInt(line.strip());
            } catch (NumberFormatException ignored) {
                // строка не число — дальше
            }
        }

        return -1;
    }

    /** Цвет температуры: 40° и ниже — зелёный, 65° — жёлтый, 85° и выше — красный. */
    static java.awt.Color color(int celsius) {
        if (celsius < 0) {
            return new java.awt.Color(0x20, 0x50, 0xFF);
        }

        var k = Math.max(0, Math.min(1, (celsius - 40) / 45.0));
        return k < 0.55 ? Glow.mix(new java.awt.Color(0, 255, 80), new java.awt.Color(255, 210, 0), k / 0.55)
                : Glow.mix(new java.awt.Color(255, 210, 0), java.awt.Color.RED, (k - 0.55) / 0.45);
    }
}
