package ru.virus.manager;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Клод — пиксельный маскот Claude: прямоугольное оранжевое тельце 18×12
 * клеток, ручки по бокам, четыре ножки и глазки-щёлочки. Танцует —
 * классику или мемные танцы (флосс, гангнам, дэб, карамельдансен, лунная
 * походка, вертушка, корточки) — и каждые 10 секунд машет ручкой, если она
 * свободна. При запуске V.I.R.U.S. появляется на каждом экране, машет и
 * спрыгивает вниз.
 */
final class ClaudeMascot {

    static final Color BODY = new Color(217, 119, 87);
    static final Color EYE = new Color(20, 20, 20);
    /** Ручка перед тельцем — чуть темнее, чтобы не сливалась. */
    static final Color FRONT = new Color(178, 88, 60);
    static final long WAVE_EVERY_MS = 10_000;
    static final long WAVE_FOR_MS = 2_600;
    /** Приветствие при запуске: прилетает, машет, подпрыгивает и спрыгивает вниз. */
    static final long INTRO_MS = 4_600;
    /** «Все по очереди»: каждый танец столько. */
    static final long DANCE_TURN_MS = 12_000;

    /** Танец: код в настройках и подпись. */
    record Dance(String id, String title) {
    }

    static final List<Dance> DANCES = List.of(
            new Dance("auto", "Все по очереди"),
            new Dance("classic", "Классика"),
            new Dance("kwikflip", "Kwik Flip"),
            new Dance("floss", "Флосс"),
            new Dance("gangnam", "Гангнам стайл"),
            new Dance("dab", "Дэб"),
            new Dance("caramell", "Карамельдансен"),
            new Dance("moonwalk", "Лунная походка"),
            new Dance("spin", "Вертушка"),
            new Dance("squat", "Корточки"),
            new Dance("macarena", "Макарена"),
            new Dance("ymca", "Y.M.C.A."),
            new Dance("robot", "Робот"),
            new Dance("twist", "Твист"),
            new Dance("disco", "Диско"),
            new Dance("jack", "Прыжки «звёздочкой»"),
            new Dance("headbang", "Рок"),
            new Dance("armwave", "Волна руками"),
            new Dance("runningman", "Бегущий человек"));

    /**
     * Поза: сдвиг (в клетках, минус — вверх), растяжение вокруг ступней,
     * части тела цветом Клода (прямоугольники в клетках), моргает ли, куда
     * смотрит и видны ли глаза (спиной к нам — нет).
     */
    record Pose(double dx, double dy, double sx, double sy, List<double[]> parts, boolean blink, double look,
                boolean eyes, double tilt) {

        Pose(double dx, double dy, double sx, double sy, List<double[]> parts, boolean blink, double look, boolean eyes) {
            this(dx, dy, sx, sy, parts, blink, look, eyes, 0);
        }
    }

    private ClaudeMascot() {
    }

    /** Машет ли сейчас (первые 2,6 с каждых 10 с). */
    static boolean greeting(long now) {
        return Math.floorMod(now, WAVE_EVERY_MS) < WAVE_FOR_MS;
    }

    /** Какой танец сейчас: выбранный или, для «по очереди», — по времени. */
    static String current(String dance, long now) {
        if ("auto".equals(dance) || DANCES.stream().noneMatch(d -> d.id().equals(dance))) {
            var turn = (int) Math.floorMod(now / DANCE_TURN_MS, (long) (DANCES.size() - 1));
            return DANCES.get(turn + 1).id();
        }

        return dance;
    }

    private static List<double[]> torsoAndLegs(double[]... legs) {
        var parts = new ArrayList<double[]>();
        parts.add(new double[] {0, 0, 18, 12});
        parts.addAll(List.of(legs));
        return parts;
    }

    /** Четыре ножки; lift — насколько поджаты первая и вторая пары. */
    private static double[][] legs(double lift0, double lift1) {
        return new double[][] {{1, 12, 2, 3 - lift0}, {4, 12, 2, 3 - lift1}, {12, 12, 2, 3 - lift0}, {15, 12, 2, 3 - lift1}};
    }

    /** Поднятая правая ручка, качается туда-сюда. */
    private static void wave(List<double[]> parts, long now) {
        var tilt = (int) Math.floorMod(now / 220, 2L);
        parts.add(new double[] {18, 4, 2, 3});
        parts.add(new double[] {19 + tilt, 0, 2, 4});
        parts.add(new double[] {19 + tilt * 2, -3, 2, 3});
    }

    /** Поза танца в момент now; range — на сколько клеток можно уйти вбок (лунная походка). */
    static Pose pose(String dance, long now, double range) {
        var t = now / 1000.0;
        var blink = Math.floorMod(now, 4300L) < 140;
        var waving = greeting(now);

        switch (dance) {
            case "kwikflip" -> {
                return kwikFlip(now);
            }
            case "floss" -> {
                var s = Math.sin(t * Math.PI * 2 * 1.6);
                var parts = torsoAndLegs(legs(0, 0));

                // Обе ручки в одну сторону: одна сбоку, другая спереди поперёк тельца
                if (s > 0) {
                    parts.add(new double[] {-4, 9, 4, 2});
                    parts.add(new double[] {1, 9, 6, 2, 1});
                } else {
                    parts.add(new double[] {18, 9, 4, 2});
                    parts.add(new double[] {11, 9, 6, 2, 1});
                }

                return new Pose(s * 1.3, -Math.abs(s) * 0.4, 1, 1, parts, blink, -s, true);
            }
            case "gangnam" -> {
                var hop = Math.abs(Math.sin(t * Math.PI * 4)) * 1.2;
                var step = Math.floorMod((long) Math.floor(t * 8), 2L);
                var parts = torsoAndLegs(legs(step == 0 ? 1.5 : 0, step == 0 ? 0 : 1.5));

                if (Math.floorMod(now, 3000L) < 1000) {
                    // Лассо над головой
                    parts.add(new double[] {-3, 6, 3, 3});
                    parts.add(new double[] {18, 2, 2, 5});
                    parts.add(new double[] {16 + Math.floorMod(now / 120, 4L), -2, 3, 2});
                } else {
                    // Скрещённые запястья перед собой, как в клипе
                    parts.add(new double[] {-3, 7, 3, 2});
                    parts.add(new double[] {0, 7, 10, 2, 1});
                    parts.add(new double[] {18, 7.6, 3, 2});
                    parts.add(new double[] {8, 7.6, 10, 2, 1});
                }

                return new Pose(Math.sin(t * Math.PI * 2) * 0.8, -hop, 1, 1, parts, blink, 0, true);
            }
            case "macarena" -> {
                // Восемь счётов, как в оригинале: руки вперёд по одной, к груди, к голове, на бёдра — и качнуть бёдрами
                var step = (int) Math.floorMod(now / 450, 8L);
                var parts = torsoAndLegs(legs(0, 0));
                double[] rightOut = {18, 5, 6, 2};
                double[] rightChest = {8, 6, 8, 2, 1};
                double[] leftOut = {-6, 5, 6, 2};
                double[] leftChest = {2, 7.6, 8, 2, 1};

                switch (step) {
                    case 0 -> {
                        parts.add(rightOut);
                        parts.add(new double[] {-3, 6, 3, 3});
                    }
                    case 1 -> {
                        parts.add(rightOut);
                        parts.add(leftOut);
                    }
                    case 2 -> {
                        parts.add(rightChest);
                        parts.add(leftOut);
                    }
                    case 3 -> {
                        parts.add(rightChest);
                        parts.add(leftChest);
                    }
                    case 4 -> {
                        parts.add(new double[] {18, 1, 2, 6});
                        parts.add(new double[] {15, -1, 4, 2});
                        parts.add(leftChest);
                    }
                    case 5 -> {
                        parts.add(new double[] {18, 1, 2, 6});
                        parts.add(new double[] {15, -1, 4, 2});
                        parts.add(new double[] {-2, 1, 2, 6});
                        parts.add(new double[] {-1, -1, 4, 2});
                    }
                    default -> {
                        parts.add(new double[] {-2, 8, 2, 3});
                        parts.add(new double[] {18, 8, 2, 3});
                    }
                }

                var hips = step == 6 ? 1.2 : step == 7 ? -1.2 : 0;
                return new Pose(hips, step == 7 ? -0.8 : 0, 1, 1, parts, blink, hips == 0 ? 0 : Math.signum(hips), true,
                        hips * 2.5);
            }
            case "ymca" -> {
                // Буквы руками: Y, M, C, A — по 0,6 секунды на каждую, с подскоком на смене
                var phase = Math.floorMod(now, 2400L);
                var letter = (int) (phase / 600);
                var parts = torsoAndLegs(legs(0, 0));

                switch (letter) {
                    case 0 -> {
                        for (var part : new double[][] {{-3, 3, 3, 2}, {-5, 0, 3, 3}, {-7, -3, 3, 3}}) {
                            parts.add(part);
                            parts.add(mirror(part));
                        }
                    }
                    case 1 -> {
                        // M: локти вверх, кисти опущены на макушку
                        for (var part : new double[][] {{-3, -4, 2, 10}, {-3, -6, 7, 2}, {3, -6, 2, 5}}) {
                            parts.add(part);
                            parts.add(mirror(part));
                        }
                    }
                    case 2 -> {
                        // C: обе ручки вбок скобкой
                        parts.add(new double[] {-5, 2, 5, 2});
                        parts.add(new double[] {-7, -2, 2, 6});
                        parts.add(new double[] {-7, -4, 5, 2});
                        parts.add(new double[] {-5, 9, 5, 2});
                        parts.add(new double[] {-7, 9, 2, 5});
                        parts.add(new double[] {-7, 13, 4, 2});
                        parts.add(new double[] {18, 6, 3, 3});
                    }
                    default -> {
                        // A: ручки домиком над головой
                        for (var part : new double[][] {{0, -3, 3, 3}, {3, -6, 3, 4}, {6, -9, 3, 4}}) {
                            parts.add(part);
                            parts.add(mirror(part));
                        }
                    }
                }

                var hop = Math.sin(Math.min(1, (phase % 600) / 300.0) * Math.PI) * 1.2;
                return new Pose(letter == 2 ? 1.5 : 0, -hop, 1, 1, parts, blink, letter == 2 ? -1 : 0, true, letter == 2 ? 4 : 0);
            }
            case "robot" -> {
                // Рывками: руки под прямым углом, корпус дёргается из стороны в сторону
                var step = (int) Math.floorMod(now / 400, 4L);
                var parts = torsoAndLegs(legs(step % 2 == 0 ? 0.6 : 0, step % 2 == 0 ? 0 : 0.6));
                double[][] up = {{18, 6, 4, 2}, {20, 2, 2, 4}};
                double[][] down = {{18, 6, 4, 2}, {20, 8, 2, 4}};

                switch (step) {
                    case 0 -> {
                        parts.addAll(List.of(up));
                        parts.add(mirror(down[0]));
                        parts.add(mirror(down[1]));
                    }
                    case 1 -> {
                        parts.addAll(List.of(down));
                        parts.add(mirror(up[0]));
                        parts.add(mirror(up[1]));
                    }
                    case 2 -> {
                        parts.add(new double[] {18, 6, 6, 2});
                        parts.add(new double[] {-6, 6, 6, 2});
                    }
                    default -> {
                        parts.add(new double[] {19, 6, 2, 6});
                        parts.add(new double[] {-3, 6, 2, 6});
                    }
                }

                var jerk = step == 0 ? 1 : step == 1 ? -1 : 0;
                return new Pose(jerk * 0.8, 0, 1, 1, parts, false, jerk, true, jerk * 3);
            }
            case "twist" -> {
                // Твист: корпус ходит туда-сюда, ножки по очереди, согнутые ручки — в противоход
                var s = Math.sin(t * Math.PI * 2 * 1.5);
                var parts = torsoAndLegs(legs(s > 0 ? 1 : 0, s > 0 ? 0 : 1));
                parts.add(new double[] {-4, 7 + s * 1.5, 4, 2});
                parts.add(new double[] {18, 7 - s * 1.5, 4, 2});
                return new Pose(s * 0.9, Math.abs(s) * 0.5, 1 - Math.abs(s) * 0.06, 1, parts, blink, s, true, s * 7);
            }
            case "disco" -> {
                // «Лихорадка субботнего вечера»: палец в небо — и вниз через себя, рука на бедре
                var up = Math.floorMod(now / 500, 2L) == 0;
                var parts = torsoAndLegs(legs(up ? 0 : 0.8, up ? 0.8 : 0));
                parts.add(new double[] {-2, 8, 2, 3});

                if (up) {
                    parts.add(new double[] {18, 3, 3, 2});
                    parts.add(new double[] {20, 0, 3, 3});
                    parts.add(new double[] {22, -3, 3, 3});
                    parts.add(new double[] {24, -5, 1.5, 2});
                } else {
                    parts.add(new double[] {18, 7, 2, 3});
                    parts.add(new double[] {6, 9, 12, 2, 1});
                    parts.add(new double[] {3, 10.5, 4, 2, 1});
                }

                return new Pose(up ? 0.8 : -0.8, up ? -0.5 : 0, 1, 1, parts, blink, up ? 1 : -1, true, up ? -3 : 3);
            }
            case "jack" -> {
                // Прыжки «звёздочкой»: в прыжке ручки вверх и ножки врозь, на земле — всё вместе
                var open = Math.floorMod(now / 350, 2L) == 0;
                var parts = new ArrayList<double[]>();
                parts.add(new double[] {0, 0, 18, 12});

                if (open) {
                    for (var part : new double[][] {{-1.5, 12, 2, 3}, {3, 12, 2, 3}, {-3, 3, 3, 3}, {-6, 0, 3, 3}}) {
                        parts.add(part);
                        parts.add(mirror(part));
                    }
                } else {
                    parts.addAll(List.of(legs(0, 0)));
                    parts.add(new double[] {-2, 7, 2, 5});
                    parts.add(new double[] {18, 7, 2, 5});
                }

                return new Pose(0, open ? -1.6 : 0, 1, open ? 1 : 0.96, parts, blink, 0, true);
            }
            case "headbang" -> {
                // Рок: «коза» вверх и кивки всем тельцем в такт
                var s = Math.sin(t * Math.PI * 2 * 2);
                var nod = Math.max(0, s);
                var parts = torsoAndLegs(legs(0, 0));
                parts.add(new double[] {-3, 6, 3, 3});
                parts.add(new double[] {18, 1, 2, 6});
                parts.add(new double[] {17.4, -2, 1.2, 3});
                parts.add(new double[] {19.6, -2, 1.2, 3});
                return new Pose(0, 0, 1 + nod * 0.05, 1 - nod * 0.14, parts, blink || nod > 0.6, 0, true, nod * 5);
            }
            case "armwave" -> {
                // Волна: бежит по одной ручке, через тельце — и по другой
                var p = t * Math.PI * 2 * 0.9;
                var parts = torsoAndLegs(legs(0, 0));

                for (var i = 0; i < 3; i++) {
                    parts.add(new double[] {-3 - i * 3, 6 + Math.sin(p + (i + 1) * 0.9) * 3, 3, 2.5});
                    parts.add(new double[] {18 + i * 3, 6 + Math.sin(p - (i + 1) * 0.9 - 1.2) * 3, 3, 2.5});
                }

                return new Pose(0, Math.sin(p - 0.6) * 0.6, 1, 1, parts, blink, Math.sin(p) > 0 ? -1 : 1, true);
            }
            case "runningman" -> {
                // Бег на месте: ножки высоко по очереди, ручки работают как при беге
                var step = (int) Math.floorMod(now / 250, 2L);
                var parts = torsoAndLegs(legs(step == 0 ? 2 : 0, step == 0 ? 0 : 2));
                parts.add(new double[] {-3, step == 0 ? 4 : 8, 3, 3});
                parts.add(new double[] {18, step == 0 ? 8 : 4, 3, 3});
                var hop = Math.abs(Math.sin(t * Math.PI * 4)) * 0.9;
                return new Pose(Math.sin(t * Math.PI) * 0.6, -hop, 1, 1, parts, blink, 1, true, 3);
            }
            case "dab" -> {
                var phase = Math.floorMod(now, 1400L);
                var parts = torsoAndLegs(legs(0, 0));

                if (phase < 600) {
                    parts.add(new double[] {-3, 6, 3, 3});
                    parts.add(new double[] {18, 6, 3, 3});
                    return new Pose(0, -Math.sin(phase / 600.0 * Math.PI) * 1.5, 1, 1, parts, blink, 0, true);
                }

                // Дэб: левая рука вверх по диагонали, правая согнута у лица
                parts.add(new double[] {-3, 4, 3, 2});
                parts.add(new double[] {-6, 1, 3, 3});
                parts.add(new double[] {-9, -2, 3, 3});
                parts.add(new double[] {18, 4, 2, 3});
                parts.add(new double[] {8, 2, 12, 3, 1});
                return new Pose(-0.8, 0, 1, 1, parts, false, -1, true);
            }
            case "caramell" -> {
                var sway = Math.sin(t * Math.PI * 2 * 2);
                var flap = Math.floorMod(now / 125, 2L);
                var parts = torsoAndLegs(legs(sway > 0 ? 1 : 0, sway > 0 ? 0 : 1));
                // Ладошки над головой — «ушки», хлопают в такт
                parts.add(new double[] {-1, -4, 2, 6});
                parts.add(new double[] {1, -5 - flap, 3, 2});
                parts.add(new double[] {17, -4, 2, 6});
                parts.add(new double[] {14, -5 - flap, 3, 2});
                return new Pose(sway * 1.6, -Math.abs(sway) * 0.5, 1, 1, parts, blink, sway, true);
            }
            case "moonwalk" -> {
                // Скользит туда-обратно по сцене, ножки «шагают» на месте
                var period = 8.0;
                var k = (t % period) / period;
                var tri = k < 0.5 ? k * 2 : 2 - k * 2;
                var heel = Math.floorMod((long) Math.floor(t * 5), 2L);
                var parts = torsoAndLegs(legs(heel == 0 ? 1.2 : 0, heel == 0 ? 0 : 1.2));
                parts.add(new double[] {-3, 7, 3, 3});

                if (waving) {
                    wave(parts, now);
                } else {
                    parts.add(new double[] {18, 7, 3, 3});
                }

                // Смотрит туда, откуда ушёл, — так и ходят лунной походкой
                return new Pose((tri - 0.5) * 2 * range, 0, 1, 1, parts, blink, k < 0.5 ? -1 : 1, true);
            }
            case "spin" -> {
                var turn = Math.cos(t * Math.PI * 2 * 0.7);
                var sx = Math.abs(turn) < 0.18 ? Math.copySign(0.18, turn) : turn;
                var hop = Math.abs(Math.sin(t * Math.PI * 2 * 1.4));
                var parts = torsoAndLegs(legs(0, 0));
                parts.add(new double[] {-3, 6, 3, 3});
                parts.add(new double[] {18, 6, 3, 3});
                // Спиной к нам — глаз не видно
                return new Pose(0, -hop, sx, 1, parts, blink, 0, turn > 0);
            }
            case "squat" -> {
                var parts = new ArrayList<double[]>();
                parts.add(new double[] {0, 0, 18, 12});
                // Ножки широко, ручки на коленях — как и положено на корточках
                parts.add(new double[] {-1, 12, 2, 3});
                parts.add(new double[] {2, 12, 2, 3});
                parts.add(new double[] {14, 12, 2, 3});
                parts.add(new double[] {17, 12, 2, 3});
                parts.add(new double[] {-4, 9, 4, 2});

                if (waving) {
                    wave(parts, now);
                } else {
                    parts.add(new double[] {18, 9, 4, 2});
                }

                var look = Math.floorMod(now, 3000L) < 800 ? (Math.floorMod(now / 3000, 2L) == 0 ? -1 : 1) : 0;
                return new Pose(0, Math.sin(t * Math.PI) * 0.3, 1.1, 0.8, parts, blink, look, true);
            }
            default -> {
                // Классика: прыжки, покачивание, ножки по очереди
                var bounce = Math.abs(Math.sin(t * Math.PI * 2)) * 1.6;
                var sway = Math.sin(t * Math.PI) * 1.2;
                var step = Math.floorMod((long) Math.floor(t * 4), 2L);
                var arm = Math.sin(t * Math.PI * 2) > 0 ? -1 : 0;
                var parts = torsoAndLegs(legs(step == 0 ? 1 : 0, step == 0 ? 0 : 1));
                parts.add(new double[] {-3, 6 + arm, 3, 3});

                if (waving) {
                    wave(parts, now);
                } else {
                    parts.add(new double[] {18, 6 - arm, 3, 3});
                }

                return new Pose(sway, -bounce, 1, 1, parts, blink, 0, true);
            }
        }
    }

    /**
     * Kwik Flip (AVGN Dance) — покадрово, как в оригинале: 12 кадров в
     * секунду, 11 поз, главные (1, 4, 7, 10) держатся по три кадра. Пинок
     * влево с руками влево, центр, пинок вправо, центр, рука прямо вверх,
     * согнутая рука над головой, снова вверх.
     */
    static final int[] KWIK_HOLD = {3, 1, 1, 3, 1, 1, 3, 1, 2, 3, 1};
    static final long KWIK_FRAME_MS = 1000 / 12;

    /** Номер позы Kwik Flip (0…10) в момент now. */
    static int kwikFrame(long now) {
        var total = 0;

        for (var hold : KWIK_HOLD) {
            total += hold;
        }

        var frame = (int) Math.floorMod(now / KWIK_FRAME_MS, (long) total);

        for (var i = 0; i < KWIK_HOLD.length; i++) {
            if (frame < KWIK_HOLD[i]) {
                return i;
            }

            frame -= KWIK_HOLD[i];
        }

        return 0;
    }

    /** Зеркально по тельцу (18 клеток): левое становится правым. */
    private static double[] mirror(double[] part) {
        var copy = part.clone();
        copy[0] = 18 - part[0] - part[2];
        return copy;
    }

    private static Pose mirror(Pose pose) {
        var parts = new ArrayList<double[]>();
        pose.parts().forEach(part -> parts.add(mirror(part)));
        return new Pose(-pose.dx(), pose.dy(), pose.sx(), pose.sy(), parts, pose.blink(), -pose.look(), pose.eyes(),
                -pose.tilt());
    }

    /**
     * Руки по очереди: сперва правая Клода (на экране слева) — вверх у лица
     * и к груди, потом то же левая. Ножки почти не двигаются: в пинке
     * работает одна крайняя, остальные стоят.
     */
    static Pose kwikFlip(long now) {
        var frame = kwikFrame(now);
        var pose = kwikRaw(frame, Math.floorMod(now, 4300L) < 140);
        return frame == 6 || frame == 7 ? mirror(pose) : pose;
    }

    private static Pose kwikRaw(int frame, boolean blink) {
        var parts = new ArrayList<double[]>();
        parts.add(new double[] {0, 0, 18, 12});

        switch (frame) {
            case 0, 3 -> {
                // Пинок в сторону: одна крайняя ножка выброшена по диагонали, ручка — чуть вперёд-вниз
                parts.add(new double[] {4, 12, 2, 3});
                parts.add(new double[] {12, 12, 2, 3});
                parts.add(new double[] {15, 12, 2, 3});
                parts.add(new double[] {-0.5, 11.5, 2.5, 2});
                parts.add(new double[] {-2.5, 13, 2.5, 1.5});
                parts.add(new double[] {18, 6, 3, 3});
                parts.add(new double[] {-2, 8, 3, 2, 1});
                var pose = new Pose(0.6, -0.3, 1, 1, parts, blink, -1, true, 3);
                return frame == 3 ? mirror(pose) : pose;
            }
            case 1, 4 -> {
                // Ножка возвращается
                parts.add(new double[] {1, 12, 2, 2});
                parts.add(new double[] {4, 12, 2, 3});
                parts.add(new double[] {12, 12, 2, 3});
                parts.add(new double[] {15, 12, 2, 3});
                parts.add(new double[] {-3, 6, 3, 3});
                parts.add(new double[] {18, 6, 3, 3});
                var pose = new Pose(0.3, 0, 1, 1, parts, blink, -1, true, 1);
                return frame == 4 ? mirror(pose) : pose;
            }
            case 2, 5, 8 -> {
                // Лицом к нам, ручки по бокам; на восьмом кадре — короткое пружинистое приседание
                parts.addAll(List.of(legs(0, 0)));
                parts.add(new double[] {-3, 6, 3, 3});
                parts.add(new double[] {18, 6, 3, 3});
                return frame == 8 ? new Pose(0, 0.3, 1.03, 0.95, parts, blink, 0, true)
                        : new Pose(0, 0, 1, 1, parts, blink, 0, true);
            }
            case 6, 9 -> {
                // Ручка поднята у лица, ладошкой вверх; вторая — по боку
                parts.addAll(List.of(legs(0, 0)));
                parts.add(new double[] {-3, 6, 3, 3});
                parts.add(new double[] {18, 3, 2, 4});
                parts.add(new double[] {19, -1, 2, 4});
                return new Pose(0, -0.3, 1, 1, parts, blink, 0.5, true);
            }
            default -> {
                // Та же ручка — кулачком к груди
                parts.addAll(List.of(legs(0, 0)));
                parts.add(new double[] {-3, 6, 3, 3});
                parts.add(new double[] {13, 7, 4, 2, 1});
                return new Pose(0, 0, 1, 1, parts, blink, 0, true);
            }
        }
    }

    /** Нарисовать позу: (cx, floor) — где стоят ступни посередине, u — размер клетки в точках. */
    static void draw(Graphics2D g, double cx, double floor, double u, Pose pose) {
        var lift = Math.max(0, -pose.dy());
        var k = Math.max(0.3, 1 - lift / 6);
        var footX = cx + pose.dx() * u;
        g.setColor(new Color(0, 0, 0, (int) (100 * k)));
        g.fill(new Ellipse2D.Double(footX - 11 * u * k * Math.abs(pose.sx()), floor - 0.6 * u,
                22 * u * k * Math.abs(pose.sx()), 2.2 * u));

        var c = (Graphics2D) g.create();
        c.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        c.translate(footX, floor + pose.dy() * u);
        c.rotate(Math.toRadians(pose.tilt()));
        c.scale(u * pose.sx(), u * pose.sy());
        c.translate(-9, -15);
        c.setColor(BODY);

        for (var part : pose.parts()) {
            if (part.length < 5) {
                c.fill(new Rectangle2D.Double(part[0], part[1], part[2], part[3]));
            }
        }

        if (pose.eyes()) {
            c.setColor(EYE);
            var shift = pose.look() * 0.8;
            c.fill(new Rectangle2D.Double(3 + shift, pose.blink() ? 4.4 : 3, 1, pose.blink() ? 0.6 : 3));
            c.fill(new Rectangle2D.Double(14 + shift, pose.blink() ? 4.4 : 3, 1, pose.blink() ? 0.6 : 3));
        }

        // Ручки перед тельцем — поверх, темнее (и поверх глаза, как в дэбе)
        c.setColor(FRONT);

        for (var part : pose.parts()) {
            if (part.length >= 5) {
                c.fill(new Rectangle2D.Double(part[0], part[1], part[2], part[3]));
            }
        }

        c.dispose();
    }

    /** Танец без остановки на полу floor; ширина сцены — чтобы лунная походка не ушла за край. */
    static void dance(Graphics2D g, String dance, double cx, double floor, double u, int width, long now) {
        var range = Math.max(0, width / u / 2 - 14);
        draw(g, cx, floor, u, pose(current(dance, now), now, range));
    }

    /**
     * Приветствие при запуске поверх кадра экрана: Клод падает сверху,
     * машет, подпрыгивает и спрыгивает вниз за край.
     *
     * @param elapsed сколько прошло с начала приветствия, мс
     * @return false — приветствие закончилось, рисовать нечего
     */
    static boolean intro(Graphics2D g, int width, int height, long elapsed, long now) {
        if (elapsed < 0 || elapsed >= INTRO_MS) {
            return false;
        }

        var u = Math.min(width / 52.0, height / 24.0);
        var floor = height / 2.0 + 10 * u;
        double y;

        if (elapsed < 700) {
            var k = elapsed / 700.0;
            y = -(floor + 2 * u) * Math.pow(1 - k, 3);
        } else if (elapsed < 3000) {
            y = -Math.abs(Math.sin((elapsed - 700) / 1000.0 * Math.PI * 2)) * 0.6 * u;
        } else if (elapsed < 3500) {
            y = -Math.sin((elapsed - 3000) / 500.0 * Math.PI) * 5 * u;
        } else {
            var k = (elapsed - 3500) / (double) (INTRO_MS - 3500);
            y = k * k * (height - floor + 20 * u);
        }

        var parts = torsoAndLegs(legs(0, 0));
        parts.add(new double[] {-3, 6, 3, 3});

        if (elapsed >= 700 && elapsed < 3000) {
            wave(parts, now);
        } else {
            parts.add(new double[] {18, 6, 3, 3});
        }

        draw(g, width / 2.0, floor, u, new Pose(0, y / u, 1, 1, parts, false, 0, true));
        return true;
    }
}
