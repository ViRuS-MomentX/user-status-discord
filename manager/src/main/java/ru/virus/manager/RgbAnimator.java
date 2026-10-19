package ru.virus.manager;

import java.awt.Color;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/**
 * Режимы V.I.R.U.S. через OpenRGB: тридцать раз в секунду считает кадр и
 * отдаёт цвета светодиодов. Бесплатно и для всего, что видит OpenRGB, —
 * в том числе для вентиляторов на ARGB-разъёмах платы.
 *
 * <p>Настройки (режим, цвета, скорость, яркость) берутся из того же
 * {@link SignalRgb.Live}, что и у эффекта для SignalRGB, — переключение
 * подхватывается со следующего кадра.
 */
final class RgbAnimator {

    static final int FPS = 30;

    private final SignalRgb.Live settings;
    private final Consumer<String> status;
    private volatile String host = "127.0.0.1";
    /** Порт OpenRGB; в тестах — поддельного. */
    volatile int port = OpenRgb.PORT;
    private volatile Thread thread;

    RgbAnimator(SignalRgb.Live settings, Consumer<String> status) {
        this.settings = settings;
        this.status = status;
    }

    boolean running() {
        return thread != null && thread.isAlive();
    }

    synchronized void start(String host) {
        this.host = host;

        if (running()) {
            return;
        }

        thread = new Thread(this::run, "virus-rgb-animator");
        thread.setDaemon(true);
        thread.start();
    }

    synchronized void stop() {
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    private void run() {
        var me = Thread.currentThread();

        while (thread == me) {
            try (var rgb = new OpenRgb(host, port)) {
                var devices = rgb.devices();
                var animated = devices.stream().filter(device -> device.leds() > 0).toList();
                var still = devices.stream().filter(device -> device.leds() == 0 && device.solid() != null).toList();

                for (var device : animated) {
                    rgb.customMode(device);
                }

                status.accept("Режим играет на устройствах: " + animated.size()
                        + (still.isEmpty() ? "" : " (ещё " + still.size() + " — только одним цветом)"));
                loop(rgb, animated, still, me);
            } catch (IOException | RuntimeException e) {
                status.accept("Жду OpenRGB — запусти его кнопкой справа (с сервером SDK).");
            }

            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void loop(OpenRgb rgb, List<OpenRgb.Device> animated, List<OpenRgb.Device> still, Thread me)
            throws IOException {
        var start = System.nanoTime();
        var time = 0.0;
        var last = start;
        Color stillColor = null;

        while (thread == me) {
            var now = System.nanoTime();
            time += (now - last) / 1e9 * Math.max(1, Math.min(100, settings.speed)) / 50.0;
            last = now;
            var cpu = settings.cpu();

            for (var d = 0; d < animated.size(); d++) {
                var device = animated.get(d);
                rgb.leds(device, Glow.frame(settings.mode, time, device.leds(), settings.color, settings.color2, cpu,
                        settings.brightness, settings.on, d));
            }

            // Устройства без своих светодиодов анимировать нельзя — держим основной цвет
            var wanted = Glow.scale(settings.color, settings.on ? settings.brightness / 100.0 : 0);

            if (!wanted.equals(stillColor)) {
                for (var device : still) {
                    rgb.paint(device, wanted);
                }

                stillColor = wanted;
            }

            try {
                Thread.sleep(Math.max(1, 1000 / FPS - (System.nanoTime() - now) / 1_000_000));
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
