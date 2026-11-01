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
    /** Размеры разъёмов ARGB, заданные хозяином: «устройство|зона» → светодиодов. */
    volatile java.util.Map<String, Integer> zoneSizes = java.util.Map.of();
    /**
     * Яркость по устройствам, как в SignalRGB: имя → 0..100. 0 — устройство
     * не трогаем вовсе (им управляет родная программа). Нет в списке — 100.
     */
    volatile java.util.Map<String, Integer> levels = java.util.Map.of();
    /** Память (RAM) ходит по медленной шине SMBus: чаще 10 раз в секунду писать в неё — тормозить игры. */
    static final long SLOW_BUS_MS = 100;
    /** Даже без изменений кадр повторяется раз в столько: некоторые контроллеры сами возвращаются в свой режим. */
    static final long KEEPALIVE_MS = 5000;
    /**
     * Как часто спрашивать OpenRGB, не прибавилось ли устройств. Он находит их
     * не сразу после запуска (память и плату — последними), а режим, подключённый
     * раньше, так и играл бы на половине.
     */
    static final long RECHECK_MS = 10_000;
    /** Устройство без светодиодов — одна точка посередине. */
    private static final List<OpenRgb.Place> STILL_PLACE = List.of(new OpenRgb.Place(0.5, 0.5, false));
    /** Как часто менять цвет устройству без светодиодов: смена режима у него медленная. */
    static final long STILL_MS = 200;

    /** Смена режима или цвета — плавным переходом, как в iCUE и SignalRGB, а не скачком. */
    static final long FADE_MS = 400;

    /**
     * Единый холст, как у SignalRGB: устройства стоят на общей полосе по
     * порядку, и эффект перетекает с одного на другое, а не повторяется в
     * каждом заново.
     */
    volatile boolean canvas;
    /** Порядок устройств на холсте: имена; кого нет — в конце, как их отдал OpenRGB. */
    volatile List<String> order = List.of();

    /** Свой режим у устройства: имя → номер режима. Нет в списке — общий. */
    volatile java.util.Map<String, Integer> modes = java.util.Map.of();

    /** Что сделать, если OpenRGB не отвечает: поднять его задачей Планировщика. Не чаще раза в минуту. */
    volatile Runnable whenMissing = () -> { };
    private long missingAt;

    /** То же, но меняется в проверках. */
    volatile long recheckMs = RECHECK_MS;

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

                // Вентиляторы на разъёмах платы горят, только когда у зоны задано число светодиодов
                if (OpenRgb.applySizes(rgb, devices, zoneSizes)) {
                    devices = rgb.devices();
                }

                var wanted = devices.stream().filter(device -> level(device) > 0).toList();
                var animated = wanted.stream().filter(device -> device.leds() > 0).toList();
                var still = wanted.stream().filter(device -> device.leds() == 0 && device.solid() != null).toList();

                for (var device : animated) {
                    rgb.customMode(device);
                }

                status.accept("Режим играет на устройствах: " + animated.size()
                        + (still.isEmpty() ? "" : " (ещё " + still.size() + " — только одним цветом)"));
                loop(rgb, animated, still, devices.size(), me);

                // Список устройств поменялся — переподключаемся сразу, без паузы
                if (thread == me) {
                    continue;
                }
            } catch (IOException | RuntimeException e) {
                status.accept("Жду OpenRGB — запусти его кнопкой справа (с сервером SDK).");

                if (System.currentTimeMillis() - missingAt > 60_000) {
                    missingAt = System.currentTimeMillis();
                    whenMissing.run();
                }
            }

            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** За сколько приглушение проходит путь от полной яркости до нуля. */
    static final double DIM_FADE_MS = 2000;

    /**
     * Шаг приглушения к цели: ровно, 100% за {@link #DIM_FADE_MS}. Как плавное
     * затемнение у iCUE и Hue — ночь и простой приходят мягко, а не щелчком.
     */
    static double approach(double shown, double target, double elapsedMs) {
        var step = 100 * Math.max(0, elapsedMs) / DIM_FADE_MS;
        return shown < target ? Math.min(target, shown + step) : Math.max(target, shown - step);
    }

    /** Кадр опоздал так сильно, что это не задержка, а сон или гибернация. */
    static final long WAKE_GAP_MS = 10_000;

    static boolean woke(long gapNanos) {
        return gapNanos / 1_000_000 >= WAKE_GAP_MS;
    }

    /** Сигнал поверх кадра: весь кадр тянется к цвету сигнала. */
    static Color[] overlay(Color[] frame, Color color, double level) {
        if (level <= 0) {
            return frame;
        }

        var result = new Color[frame.length];

        for (var i = 0; i < frame.length; i++) {
            result[i] = Glow.mix(frame[i], color, level);
        }

        return result;
    }

    /** Что видно глазу: режим, цвета, вкл/выкл. Поменялось — пора плавного перехода. */
    private String look() {
        return settings.mode + "|" + settings.reverse + "|" + settings.scale + "|" + modes + "|" + settings.color.getRGB() + "|" + settings.color2.getRGB() + "|" + settings.on;
    }

    /** Кадр посередине перехода: от старого к новому, сглаженно (быстро в начале, мягко в конце). */
    static Color[] blend(Color[] from, Color[] to, double progress) {
        if (from.length != to.length) {
            return to;
        }

        var k = Math.max(0, Math.min(1, progress));
        var eased = 1 - (1 - k) * (1 - k);
        var result = new Color[to.length];

        for (var i = 0; i < to.length; i++) {
            result[i] = Glow.mix(from[i], to[i], eased);
        }

        return result;
    }

    /**
     * Места светодиодов: на едином холсте каждое устройство занимает свой
     * отрезок по ширине (по порядку), иначе — весь холст целиком.
     */
    static List<List<OpenRgb.Place>> canvas(List<OpenRgb.Device> devices, boolean shared, List<String> order) {
        var result = new java.util.ArrayList<List<OpenRgb.Place>>();

        if (!shared || devices.size() < 2) {
            devices.forEach(device -> result.add(device.places()));
            return result;
        }

        var sorted = new java.util.ArrayList<>(devices);
        sorted.sort(java.util.Comparator.comparingInt(device -> {
            var at = order.indexOf(device.name());
            return at < 0 ? order.size() + devices.indexOf(device) : at;
        }));

        for (var device : devices) {
            var slot = sorted.indexOf(device);
            var start = (double) slot / devices.size();
            var width = 1.0 / devices.size();
            var moved = new java.util.ArrayList<OpenRgb.Place>();

            for (var place : device.places()) {
                moved.add(new OpenRgb.Place(start + place.x() * width, place.y(), place.grid()));
            }

            result.add(moved);
        }

        return result;
    }

    private int level(OpenRgb.Device device) {
        return Math.max(0, Math.min(100, levels.getOrDefault(device.name(), 100)));
    }

    static boolean slow(OpenRgb.Device device) {
        return device.kind().equals("память");
    }

    /**
     * Слать ли кадр: только если он поменялся (статичный режим — один раз, а
     * не 30 раз в секунду), не чаще шины устройства и раз в несколько секунд
     * для надёжности.
     */
    static boolean due(Color[] frame, Color[] last, long since, boolean slowBus) {
        if (last == null || since >= KEEPALIVE_MS) {
            return true;
        }

        if (slowBus && since < SLOW_BUS_MS) {
            return false;
        }

        return !java.util.Arrays.equals(frame, last);
    }

    /** Играет, пока список устройств у OpenRGB тот же; поменялся — возвращается, чтобы перечитать. */
    private void loop(OpenRgb rgb, List<OpenRgb.Device> animated, List<OpenRgb.Device> still, int known, Thread me)
            throws IOException {
        var checked = System.nanoTime() / 1_000_000;
        var start = System.nanoTime();
        var time = 0.0;
        var last = start;
        var lastWall = System.currentTimeMillis();
        // Приглушение (ночь, простой, блокировка) плывёт медленно, а не щёлкает
        var dimShown = (double) Math.max(0, Math.min(100, settings.dim));
        var places = canvas(animated, canvas, order);
        var sent = new Color[animated.size()][];
        var sentAt = new long[animated.size()];
        var stillSent = new Color[still.size()];
        var stillAt = new long[still.size()];
        // Для плавного перехода: что горело в момент смены и когда она была
        var shown = new Color[animated.size()][];
        var from = new Color[animated.size()][];
        var look = look();
        var fadeStart = -FADE_MS;

        while (thread == me) {
            var now = System.nanoTime();

            // Компьютер спал: после пробуждения плата и память сами вернулись к встроенному
            // эффекту — заново берём их под свой режим, как iCUE и SignalRGB после сна
            var wall = System.currentTimeMillis();
            var slept = Math.max(now - last, (wall - lastWall) * 1_000_000L);
            lastWall = wall;

            if (woke(slept)) {
                status.accept("Компьютер проснулся — заново включаю подсветку на устройствах.");
                return;
            }

            dimShown = approach(dimShown, Math.max(0, Math.min(100, settings.dim)), (now - last) / 1e6);
            var brightness = (int) Math.round(settings.brightness * dimShown / 100);
            time += (now - last) / 1e9 * Math.max(1, Math.min(100, settings.speed)) / 50.0;
            last = now;
            var cpu = settings.cpu();
            var millis = now / 1_000_000;
            var nextLook = look();

            if (!nextLook.equals(look)) {
                look = nextLook;
                fadeStart = millis;

                for (var d = 0; d < animated.size(); d++) {
                    from[d] = shown[d];
                }
            }

            var fade = (millis - fadeStart) / (double) FADE_MS;

            for (var d = 0; d < animated.size(); d++) {
                var device = animated.get(d);
                var deviceMode = modes.getOrDefault(device.name(), settings.mode);
                var shaped = Glow.positional(deviceMode) ? places.get(d)
                        : Glow.shape(places.get(d), settings.reverse, settings.scale);
                var frame = Glow.frame(deviceMode, time, shaped,
                        settings.color, settings.color2, cpu,
                        brightness * level(device) / 100, settings.on, d);

                if (fade < 1 && from[d] != null) {
                    frame = blend(from[d], frame, fade);
                }

                frame = overlay(frame, settings.flashColor, settings.flashLevel(System.currentTimeMillis()));

                shown[d] = frame;

                if (due(frame, sent[d], millis - sentAt[d], slow(device))) {
                    rgb.leds(device, frame);
                    sent[d] = frame;
                    sentAt[d] = millis;
                }
            }

            // Устройства без своих светодиодов: целиком одним цветом — тем, что эффект даёт в их точке.
            // Смена режима для них тяжелее кадра, поэтому реже: 5 раз в секунду (как плагин эффектов OpenRGB)
            for (var s = 0; s < still.size(); s++) {
                var device = still.get(s);
                var color = Glow.frame(modes.getOrDefault(device.name(), settings.mode), time, STILL_PLACE,
                        settings.color, settings.color2, cpu, brightness * level(device) / 100, settings.on,
                        100 + s)[0];

                if (!color.equals(stillSent[s]) && millis - stillAt[s] >= STILL_MS) {
                    rgb.paint(device, color);
                    stillSent[s] = color;
                    stillAt[s] = millis;
                }
            }

            if (millis - checked >= recheckMs) {
                checked = millis;

                if (rgb.count() != known) {
                    status.accept("OpenRGB нашёл новые устройства — подключаю.");
                    return;
                }
            }

            try {
                Thread.sleep(Math.max(1, 1000 / FPS - (System.nanoTime() - now) / 1_000_000));
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
