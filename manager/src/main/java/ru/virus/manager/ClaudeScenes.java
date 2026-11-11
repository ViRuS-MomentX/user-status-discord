package ru.virus.manager;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.util.List;

/**
 * Сцены, на которых танцует Клод, — нарочно не в стиле V.I.R.U.S. Каждая
 * рисует фон целиком; пол у всех на 80% высоты, туда Клод и встаёт.
 */
final class ClaudeScenes {

    record Scene(String id, String title) {
    }

    static final List<Scene> SCENES = List.of(
            new Scene("auto", "Меняются сами"),
            new Scene("stage", "Сцена"),
            new Scene("synthwave", "Ретро-закат"),
            new Scene("space", "Космос"),
            new Scene("beach", "Пляж"),
            new Scene("lasers", "Клуб"),
            new Scene("snow", "Снегопад"),
            new Scene("disco", "Диско-шар"),
            new Scene("city", "Ночной город"),
            new Scene("forest", "Лес и светлячки"),
            new Scene("underwater", "Под водой"),
            new Scene("mountains", "Горы на закате"),
            new Scene("aurora", "Северное сияние"),
            new Scene("meadow", "Лужайка"));

    static final double FLOOR = 0.8;
    /** «Меняются сами»: каждая сцена столько. */
    static final long SCENE_TURN_MS = 60_000;

    private ClaudeScenes() {
    }

    static String current(String scene, long now) {
        if ("auto".equals(scene) || SCENES.stream().noneMatch(s -> s.id().equals(scene))) {
            return SCENES.get(1 + (int) Math.floorMod(now / SCENE_TURN_MS, (long) (SCENES.size() - 1))).id();
        }

        return scene;
    }

    static void draw(Graphics2D g, String scene, int width, int height, long now) {
        switch (current(scene, now)) {
            case "synthwave" -> synthwave(g, width, height, now);
            case "space" -> space(g, width, height, now);
            case "beach" -> beach(g, width, height, now);
            case "lasers" -> lasers(g, width, height, now);
            case "snow" -> snow(g, width, height, now);
            case "disco" -> disco(g, width, height, now);
            case "city" -> city(g, width, height, now);
            case "forest" -> forest(g, width, height, now);
            case "underwater" -> underwater(g, width, height, now);
            case "mountains" -> mountains(g, width, height, now);
            case "aurora" -> aurora(g, width, height, now);
            case "meadow" -> meadow(g, width, height, now);
            default -> stage(g, width, height, now);
        }
    }

    private static double hash(int n) {
        return ScreenBoard.hash(n);
    }

    private static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a)));
    }

    /** Вечерняя сцена: звёзды, качающийся прожектор, мигающий танцпол. */
    static void stage(Graphics2D g, int width, int height, long now) {
        var t = now / 1000.0;
        g.setPaint(new GradientPaint(0, 0, new Color(28, 14, 48), 0, height, new Color(58, 20, 52)));
        g.fillRect(0, 0, width, height);
        stars(g, width, height * 0.62, t, 40, new Color(255, 230, 200));

        var swing = Math.sin(t * 0.7) * width * 0.06;
        var beam = new Path2D.Double();
        beam.moveTo(width / 2.0 - width * 0.04, 0);
        beam.lineTo(width / 2.0 + width * 0.04, 0);
        beam.lineTo(width / 2.0 + width * 0.32 + swing, height * FLOOR);
        beam.lineTo(width / 2.0 - width * 0.32 + swing, height * FLOOR);
        beam.closePath();
        g.setPaint(new GradientPaint(0, 0, new Color(255, 200, 140, 70), 0, (float) (height * FLOOR), new Color(255, 160, 110, 8)));
        g.fill(beam);
        tiles(g, width, height, t, new Color[] {new Color(255, 94, 160), new Color(120, 90, 255),
                new Color(70, 210, 255), new Color(255, 196, 70)});
    }

    private static void stars(Graphics2D g, int width, double maxY, double t, int count, Color color) {
        for (var i = 0; i < count; i++) {
            var x = hash(i * 7 + 1) * width;
            var y = hash(i * 13 + 5) * maxY;
            var twinkle = 0.5 + 0.5 * Math.sin(t * (1 + hash(i + 3) * 2) + i);
            var size = Math.max(1.2, width / 400.0) * (1 + hash(i + 9) * 1.5);
            g.setColor(alpha(color, (int) (60 + 150 * twinkle)));
            g.fill(new Ellipse2D.Double(x, y, size, size));
        }
    }

    private static void tiles(Graphics2D g, int width, int height, double t, Color[] colors) {
        var floor = height * FLOOR;
        var count = 10;
        var tileW = width / (double) count;
        var tick = (long) Math.floor(t * 2);

        for (var i = 0; i < count; i++) {
            for (var row = 0; row < 2; row++) {
                var beat = Math.floorMod(tick + i + row * 3, 4L);
                var c = colors[(int) Math.floorMod(tick + i + row, (long) colors.length)];
                g.setColor(alpha(c, beat == 0 ? 150 : 45));
                var y = floor + row * (height - floor) / 2;
                g.fillRect((int) Math.round(i * tileW) + 1, (int) Math.round(y) + 1, (int) Math.ceil(tileW) - 2,
                        (int) Math.ceil((height - floor) / 2) - 2);
            }
        }
    }

    /** Ретро-закат: полосатое солнце и неоновая сетка, бегущая навстречу. */
    static void synthwave(Graphics2D g, int width, int height, long now) {
        var t = now / 1000.0;
        var horizon = height * 0.6;
        g.setPaint(new GradientPaint(0, 0, new Color(18, 6, 40), 0, (float) horizon, new Color(255, 60, 120)));
        g.fillRect(0, 0, width, (int) Math.ceil(horizon));
        stars(g, width, horizon * 0.5, t, 25, Color.WHITE);

        // Солнце с полосками
        var r = height * 0.22;
        var cx = width / 2.0;
        var cy = horizon - r * 0.35;
        g.setPaint(new GradientPaint(0, (float) (cy - r), new Color(255, 230, 90), 0, (float) (cy + r), new Color(255, 70, 150)));
        g.fill(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
        g.setColor(new Color(200, 40, 110));

        for (var i = 0; i < 5; i++) {
            var y = cy + r * (0.1 + i * 0.18);
            g.fillRect((int) (cx - r), (int) y, (int) (r * 2), (int) Math.max(2, r * 0.05 * (i + 1)));
        }

        // Земля и сетка
        g.setPaint(new GradientPaint(0, (float) horizon, new Color(40, 0, 60), 0, height, new Color(10, 0, 20)));
        g.fillRect(0, (int) horizon, width, height - (int) horizon);
        g.setStroke(new BasicStroke(Math.max(1f, width / 400f)));
        g.setColor(new Color(255, 60, 220, 180));

        for (var i = -10; i <= 10; i++) {
            g.draw(new Line2D.Double(cx + i * width * 0.04, horizon, cx + i * width * 0.25, height));
        }

        var move = (t * 0.5) % 1;

        for (var i = 0; i < 8; i++) {
            var k = (i + move) / 8.0;
            var y = horizon + (height - horizon) * k * k;
            g.setColor(new Color(80, 220, 255, (int) (60 + 160 * k)));
            g.draw(new Line2D.Double(0, y, width, y));
        }
    }

    /** Космос: звёзды летят мимо, планета с кольцом, Клод стоит на астероиде. */
    static void space(Graphics2D g, int width, int height, long now) {
        var t = now / 1000.0;
        g.setPaint(new GradientPaint(0, 0, new Color(4, 4, 18), 0, height, new Color(12, 18, 50)));
        g.fillRect(0, 0, width, height);

        for (var i = 0; i < 70; i++) {
            var speed = 10 + hash(i + 2) * 60;
            var x = Math.floorMod((long) (hash(i * 3 + 1) * width - t * speed), (long) width);
            var y = hash(i * 11 + 7) * height;
            var size = Math.max(1, width / 500.0) * (speed / 30);
            g.setColor(alpha(Color.WHITE, (int) (80 + speed * 2)));
            g.fill(new Ellipse2D.Double(x, y, size, size));
        }

        // Планета с кольцом
        var pr = height * 0.16;
        var px = width * 0.8;
        var py = height * 0.25;
        g.setPaint(new GradientPaint((float) (px - pr), (float) (py - pr), new Color(120, 200, 255), (float) (px + pr),
                (float) (py + pr), new Color(60, 40, 160)));
        g.fill(new Ellipse2D.Double(px - pr, py - pr, pr * 2, pr * 2));
        g.setStroke(new BasicStroke((float) (pr * 0.12)));
        g.setColor(new Color(255, 220, 160, 170));
        g.draw(new Ellipse2D.Double(px - pr * 1.8, py - pr * 0.35, pr * 3.6, pr * 0.7));

        // Астероид-площадка
        var floor = height * FLOOR;
        g.setColor(new Color(70, 64, 80));
        g.fill(new Ellipse2D.Double(width * 0.22, floor - height * 0.03, width * 0.56, height * 0.2));
        g.setColor(new Color(110, 100, 120));
        g.fill(new Ellipse2D.Double(width * 0.25, floor - height * 0.035, width * 0.5, height * 0.07));
    }

    /** Пляж на закате: солнце садится в море, волны, песок. */
    static void beach(Graphics2D g, int width, int height, long now) {
        var t = now / 1000.0;
        var sea = height * 0.58;
        g.setPaint(new GradientPaint(0, 0, new Color(255, 140, 90), 0, (float) sea, new Color(255, 205, 140)));
        g.fillRect(0, 0, width, (int) Math.ceil(sea));
        var r = height * 0.13;
        g.setColor(new Color(255, 240, 180));
        g.fill(new Ellipse2D.Double(width * 0.3 - r, sea - r * 0.9, r * 2, r * 2));
        g.setPaint(new GradientPaint(0, (float) sea, new Color(40, 110, 170), 0, (float) (height * FLOOR), new Color(20, 70, 130)));
        g.fillRect(0, (int) sea, width, (int) (height * FLOOR - sea) + 1);

        // Волны — светлые чёрточки бегут по воде
        g.setStroke(new BasicStroke(Math.max(1.5f, width / 300f)));

        for (var i = 0; i < 18; i++) {
            var y = sea + (height * FLOOR - sea) * hash(i * 5 + 3);
            var x = Math.floorMod((long) (hash(i * 7) * width + t * 25), (long) width);
            g.setColor(new Color(255, 255, 255, 110));
            g.draw(new Line2D.Double(x, y, x + width * 0.05, y));
        }

        // Песок
        g.setPaint(new GradientPaint(0, (float) (height * FLOOR), new Color(240, 210, 150), 0, height, new Color(210, 170, 110)));
        g.fillRect(0, (int) (height * FLOOR), width, height);
    }

    /** Клуб: лазеры качаются из углов, диско-шар, дымка. */
    static void lasers(Graphics2D g, int width, int height, long now) {
        var t = now / 1000.0;
        g.setPaint(new GradientPaint(0, 0, new Color(6, 4, 14), 0, height, new Color(20, 8, 30)));
        g.fillRect(0, 0, width, height);
        Color[] colors = {new Color(255, 40, 80), new Color(40, 255, 120), new Color(60, 120, 255), new Color(255, 220, 40)};
        g.setStroke(new BasicStroke(Math.max(1.5f, width / 260f)));

        for (var i = 0; i < 8; i++) {
            var fromLeft = i % 2 == 0;
            var angle = Math.sin(t * (0.8 + i * 0.13) + i) * 0.7;
            var x0 = fromLeft ? 0 : width;
            var dir = fromLeft ? 1 : -1;
            var x1 = x0 + dir * Math.cos(angle) * width * 1.2;
            var y1 = height * 0.1 + Math.sin(angle) * height + height * 0.4;
            g.setColor(alpha(colors[i % colors.length], 150));
            g.draw(new Line2D.Double(x0, height * 0.08, x1, y1));
        }

        // Диско-шар
        var r = height * 0.08;
        var bx = width / 2.0;
        var by = height * 0.12;
        g.setColor(new Color(200, 200, 215));
        g.fill(new Ellipse2D.Double(bx - r, by - r, r * 2, r * 2));

        for (var i = 0; i < 6; i++) {
            var a = t * 2 + i;
            g.setColor(new Color(255, 255, 255, (int) (120 + 120 * Math.sin(a))));
            g.fill(new Ellipse2D.Double(bx + Math.cos(a) * r * 0.6 - 2, by + Math.sin(a * 1.3) * r * 0.6 - 2, 4, 4));
        }

        g.setPaint(new RadialGradientPaint((float) (width / 2.0), (float) (height * FLOOR), (float) (width * 0.5),
                new float[] {0f, 1f}, new Color[] {new Color(180, 120, 255, 50), new Color(180, 120, 255, 0)}));
        g.fillRect(0, 0, width, height);
        g.setColor(new Color(12, 10, 20));
        g.fillRect(0, (int) (height * FLOOR), width, height);
        g.setColor(new Color(120, 90, 200, 90));
        g.fillRect(0, (int) (height * FLOOR), width, Math.max(2, height / 120));
    }

    /** Снегопад: ночь, луна, снежинки падают, сугроб под ногами. */
    static void snow(Graphics2D g, int width, int height, long now) {
        var t = now / 1000.0;
        g.setPaint(new GradientPaint(0, 0, new Color(10, 20, 50), 0, height, new Color(40, 70, 120)));
        g.fillRect(0, 0, width, height);
        var r = height * 0.08;
        g.setColor(new Color(240, 240, 220));
        g.fill(new Ellipse2D.Double(width * 0.15, height * 0.1, r * 2, r * 2));

        for (var i = 0; i < 90; i++) {
            var speed = 15 + hash(i + 4) * 35;
            var y = Math.floorMod((long) (hash(i * 9 + 2) * height + t * speed), (long) height);
            var x = hash(i * 5 + 1) * width + Math.sin(t + i) * width * 0.01;
            var size = Math.max(1.5, width / 320.0) * (0.6 + hash(i + 8));
            g.setColor(new Color(255, 255, 255, 200));
            g.fill(new Ellipse2D.Double(x, y, size, size));
        }

        var floor = height * FLOOR;
        var drift = new Path2D.Double();
        drift.moveTo(0, height);
        drift.lineTo(0, floor);

        for (var x = 0; x <= width; x += Math.max(4, width / 40)) {
            drift.lineTo(x, floor - Math.sin(x / (double) width * Math.PI * 3) * height * 0.015);
        }

        drift.lineTo(width, height);
        drift.closePath();
        g.setColor(new Color(235, 240, 250));
        g.fill(drift);
    }

    /** Диско-шар: большой зеркальный шар, зайчики бегут по стенам, цветной пол. */
    static void disco(Graphics2D g, int width, int height, long now) {
        var t = now / 1000.0;
        g.setPaint(new GradientPaint(0, 0, new Color(30, 8, 50), 0, height, new Color(70, 14, 70)));
        g.fillRect(0, 0, width, height);
        Color[] colors = {new Color(255, 90, 180), new Color(90, 220, 255), new Color(255, 220, 90), new Color(150, 110, 255)};

        // Зайчики от шара плывут по залу
        for (var i = 0; i < 46; i++) {
            var x = Math.floorMod((long) (hash(i * 3 + 2) * width + t * (20 + hash(i) * 30)), (long) width);
            var y = hash(i * 7 + 4) * height * FLOOR + Math.sin(t * 0.8 + i) * height * 0.02;
            var size = Math.max(3, width / 110.0) * (0.6 + hash(i + 11));
            g.setColor(alpha(colors[i % colors.length], (int) (50 + 90 * (0.5 + 0.5 * Math.sin(t * 2 + i)))));
            g.fill(new Ellipse2D.Double(x, y, size, size * 0.7));
        }

        // Шар на подвесе: клетки-зеркала вспыхивают по очереди
        var r = height * 0.13;
        var bx = width / 2.0;
        var by = height * 0.17;
        g.setColor(new Color(150, 150, 170));
        g.fillRect((int) bx - 1, 0, 3, (int) (by - r));
        var clip = g.getClip();
        g.clip(new Ellipse2D.Double(bx - r, by - r, r * 2, r * 2));
        g.setColor(new Color(120, 124, 150));
        g.fillRect((int) (bx - r), (int) (by - r), (int) (r * 2) + 1, (int) (r * 2) + 1);
        var cell = r / 3.2;
        var shift = (t * cell * 1.5) % cell;

        for (var row = 0; row < 8; row++) {
            for (var column = -1; column < 8; column++) {
                var flash = hash(row * 17 + column * 5 + (int) (t * 3)) > 0.7;
                g.setColor(flash ? new Color(255, 255, 255) : new Color(170, 176, 205));
                g.fill(new java.awt.geom.Rectangle2D.Double(bx - r + column * cell + shift + 1, by - r + row * cell + 1,
                        cell - 2, cell - 2));
            }
        }

        g.setClip(clip);
        tiles(g, width, height, t, colors);
    }

    /** Ночной город: небоскрёбы с окнами, луна, Клод танцует на крыше. */
    static void city(Graphics2D g, int width, int height, long now) {
        var t = now / 1000.0;
        g.setPaint(new GradientPaint(0, 0, new Color(10, 12, 40), 0, height, new Color(60, 40, 90)));
        g.fillRect(0, 0, width, height);
        stars(g, width, height * 0.4, t, 30, new Color(220, 230, 255));
        var r = height * 0.07;
        g.setColor(new Color(250, 244, 210));
        g.fill(new Ellipse2D.Double(width * 0.78, height * 0.09, r * 2, r * 2));
        var floor = height * FLOOR;

        // Два ряда домов: дальний темнее и ниже
        for (var layer = 0; layer < 2; layer++) {
            var count = layer == 0 ? 9 : 7;
            var w = width / (double) count;

            for (var i = 0; i < count; i++) {
                var h = height * (layer == 0 ? 0.2 + hash(i * 3 + 1) * 0.25 : 0.3 + hash(i * 5 + 9) * 0.3);
                var x = i * w;
                g.setColor(layer == 0 ? new Color(26, 24, 60) : new Color(16, 16, 40));
                g.fillRect((int) x, (int) (floor - h), (int) Math.ceil(w) - (layer == 0 ? 0 : 3), (int) h + 1);
                var win = Math.max(2, w / 9);

                for (var row = 0; row * win * 2.2 < h - win * 2; row++) {
                    for (var column = 0; column < 3; column++) {
                        var seed = layer * 1000 + i * 37 + row * 7 + column;
                        // Окна изредка гаснут и зажигаются
                        var lit = hash(seed + (int) (t / (4 + hash(seed) * 8))) > 0.45;

                        if (lit) {
                            g.setColor(layer == 0 ? new Color(255, 214, 120, 120) : new Color(255, 224, 140, 220));
                            g.fill(new java.awt.geom.Rectangle2D.Double(x + win * (1 + column * 2.4), floor - h + win * (1 + row * 2.2),
                                    win, win * 1.2));
                        }
                    }
                }
            }
        }

        // Крыша
        g.setColor(new Color(34, 30, 52));
        g.fillRect(0, (int) floor, width, height);
        g.setColor(new Color(90, 80, 130));
        g.fillRect(0, (int) floor, width, Math.max(2, height / 100));
    }

    /** Лес ночью: ёлки, светлячки, костёр сбоку. */
    static void forest(Graphics2D g, int width, int height, long now) {
        var t = now / 1000.0;
        g.setPaint(new GradientPaint(0, 0, new Color(8, 24, 34), 0, height, new Color(18, 54, 44)));
        g.fillRect(0, 0, width, height);
        stars(g, width, height * 0.3, t, 18, new Color(230, 240, 220));
        var floor = height * FLOOR;

        for (var layer = 0; layer < 2; layer++) {
            var count = layer == 0 ? 11 : 7;

            for (var i = 0; i < count; i++) {
                var x = (i + 0.5 + (hash(i * 3 + layer) - 0.5) * 0.5) * width / count;
                var h = height * (layer == 0 ? 0.3 + hash(i + 20) * 0.12 : 0.42 + hash(i + 40) * 0.16);
                var w = h * 0.42;
                g.setColor(layer == 0 ? new Color(14, 48, 44) : new Color(8, 34, 30));

                // Ёлка — три яруса
                for (var tier = 0; tier < 3; tier++) {
                    var top = floor - h + tier * h * 0.24;
                    var half = w * (0.45 + tier * 0.28) / 2 * 1.4;
                    var tree = new Path2D.Double();
                    tree.moveTo(x, top);
                    tree.lineTo(x + half, top + h * 0.42);
                    tree.lineTo(x - half, top + h * 0.42);
                    tree.closePath();
                    g.fill(tree);
                }

                g.fillRect((int) (x - w * 0.06), (int) (floor - h * 0.2), (int) Math.max(2, w * 0.12), (int) (h * 0.2) + 1);
            }
        }

        // Светлячки плавают и мерцают
        for (var i = 0; i < 26; i++) {
            var x = hash(i * 7 + 3) * width + Math.sin(t * 0.5 + i * 2) * width * 0.03;
            var y = height * (0.35 + hash(i * 11 + 1) * 0.42) + Math.cos(t * 0.7 + i) * height * 0.02;
            var glow = 0.5 + 0.5 * Math.sin(t * (1.2 + hash(i)) + i * 3);
            var size = Math.max(2.5, width / 180.0);
            g.setColor(new Color(210, 255, 120, (int) (40 * glow)));
            g.fill(new Ellipse2D.Double(x - size * 1.5, y - size * 1.5, size * 4, size * 4));
            g.setColor(new Color(230, 255, 150, (int) (60 + 190 * glow)));
            g.fill(new Ellipse2D.Double(x, y, size, size));
        }

        g.setPaint(new GradientPaint(0, (float) floor, new Color(22, 50, 30), 0, height, new Color(10, 26, 18)));
        g.fillRect(0, (int) floor, width, height);

        // Костёр
        var fx = width * 0.86;
        var flame = height * (0.09 + 0.02 * Math.sin(t * 9) + 0.01 * Math.sin(t * 23));
        g.setPaint(new RadialGradientPaint((float) fx, (float) floor, (float) (height * 0.3), new float[] {0f, 1f},
                new Color[] {new Color(255, 150, 60, 70), new Color(255, 150, 60, 0)}));
        g.fillRect(0, 0, width, height);

        for (var i = 0; i < 3; i++) {
            var fw = height * (0.05 - i * 0.012);
            var fire = new Path2D.Double();
            fire.moveTo(fx - fw, floor);
            fire.quadTo(fx - fw * 0.6, floor - flame * (0.5 - i * 0.1), fx + Math.sin(t * 7 + i) * fw * 0.3, floor - flame * (1 - i * 0.25));
            fire.quadTo(fx + fw * 0.6, floor - flame * (0.5 - i * 0.1), fx + fw, floor);
            fire.closePath();
            g.setColor(i == 0 ? new Color(255, 96, 40) : i == 1 ? new Color(255, 170, 50) : new Color(255, 240, 150));
            g.fill(fire);
        }

        g.setColor(new Color(60, 36, 24));
        g.fillRect((int) (fx - height * 0.06), (int) floor - 2, (int) (height * 0.12), Math.max(4, height / 60));
    }

    /** Под водой: лучи света, пузырьки вверх, рыбки, водоросли и песчаное дно. */
    static void underwater(Graphics2D g, int width, int height, long now) {
        var t = now / 1000.0;
        g.setPaint(new GradientPaint(0, 0, new Color(40, 170, 210), 0, height, new Color(6, 40, 90)));
        g.fillRect(0, 0, width, height);
        var floor = height * FLOOR;

        // Лучи с поверхности покачиваются
        for (var i = 0; i < 5; i++) {
            var x = width * (0.1 + i * 0.2) + Math.sin(t * 0.4 + i) * width * 0.03;
            var ray = new Path2D.Double();
            ray.moveTo(x, 0);
            ray.lineTo(x + width * 0.05, 0);
            ray.lineTo(x + width * 0.16, floor);
            ray.lineTo(x + width * 0.03, floor);
            ray.closePath();
            g.setPaint(new GradientPaint(0, 0, new Color(220, 255, 255, 46), 0, (float) floor, new Color(220, 255, 255, 0)));
            g.fill(ray);
        }

        // Рыбки
        for (var i = 0; i < 6; i++) {
            var right = i % 2 == 0;
            var speed = 18 + hash(i + 5) * 26;
            var raw = Math.floorMod((long) (hash(i * 9) * width + t * speed), (long) (width + 60)) - 30;
            var x = right ? raw : width - raw;
            var y = height * (0.15 + hash(i * 4 + 2) * 0.45) + Math.sin(t + i) * height * 0.01;
            var size = height * (0.025 + hash(i + 1) * 0.02);
            g.setColor(i % 3 == 0 ? new Color(255, 170, 60) : i % 3 == 1 ? new Color(255, 230, 90) : new Color(250, 120, 150));
            g.fill(new Ellipse2D.Double(x - size, y - size * 0.5, size * 2, size));
            var tail = new Path2D.Double();
            var back = right ? -1 : 1;
            tail.moveTo(x + back * size * 0.8, y);
            tail.lineTo(x + back * size * 1.7, y - size * 0.6);
            tail.lineTo(x + back * size * 1.7, y + size * 0.6);
            tail.closePath();
            g.fill(tail);
        }

        // Пузырьки
        g.setStroke(new BasicStroke(Math.max(1f, width / 500f)));

        for (var i = 0; i < 34; i++) {
            var speed = 20 + hash(i + 6) * 40;
            var y = height - Math.floorMod((long) (hash(i * 5 + 3) * height + t * speed), (long) height);
            var x = hash(i * 13 + 2) * width + Math.sin(t * 1.5 + i) * width * 0.008;
            var size = Math.max(2.5, width / 200.0) * (0.5 + hash(i + 2) * 1.4);
            g.setColor(new Color(230, 250, 255, 120));
            g.draw(new Ellipse2D.Double(x, y, size, size));
        }

        // Водоросли качаются
        g.setStroke(new BasicStroke(Math.max(3f, width / 110f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        for (var i = 0; i < 9; i++) {
            var x = width * (0.03 + hash(i * 3 + 8) * 0.94);

            // Посередине — место для Клода
            if (Math.abs(x - width / 2.0) < width * 0.2) {
                continue;
            }

            var h = height * (0.12 + hash(i + 14) * 0.14);
            var sway = Math.sin(t * 1.1 + i) * width * 0.02;
            var weed = new Path2D.Double();
            weed.moveTo(x, floor + 2);
            weed.quadTo(x - sway, floor - h * 0.5, x + sway, floor - h);
            g.setColor(i % 2 == 0 ? new Color(30, 150, 90) : new Color(20, 120, 100));
            g.draw(weed);
        }

        g.setPaint(new GradientPaint(0, (float) floor, new Color(226, 200, 140), 0, height, new Color(150, 130, 100)));
        g.fillRect(0, (int) floor, width, height);
    }

    /** Горы на закате: солнце за хребтами, облака плывут, птицы. */
    static void mountains(Graphics2D g, int width, int height, long now) {
        var t = now / 1000.0;
        g.setPaint(new GradientPaint(0, 0, new Color(70, 50, 120), 0, (float) (height * 0.7), new Color(255, 150, 90)));
        g.fillRect(0, 0, width, height);
        var r = height * 0.12;
        g.setPaint(new RadialGradientPaint((float) (width * 0.62), (float) (height * 0.45), (float) (r * 3), new float[] {0f, 1f},
                new Color[] {new Color(255, 230, 150, 150), new Color(255, 230, 150, 0)}));
        g.fillRect(0, 0, width, height);
        g.setColor(new Color(255, 236, 170));
        g.fill(new Ellipse2D.Double(width * 0.62 - r, height * 0.45 - r, r * 2, r * 2));

        // Облака
        for (var i = 0; i < 5; i++) {
            var x = Math.floorMod((long) (hash(i * 4 + 1) * width + t * (4 + i * 2)), (long) (width + 200)) - 100;
            var y = height * (0.08 + hash(i * 6 + 3) * 0.22);
            var w = width * (0.12 + hash(i + 7) * 0.1);
            g.setColor(new Color(255, 200, 190, 130));
            g.fill(new java.awt.geom.RoundRectangle2D.Double(x, y, w, w * 0.16, w * 0.16, w * 0.16));
            g.fill(new java.awt.geom.RoundRectangle2D.Double(x + w * 0.2, y - w * 0.07, w * 0.5, w * 0.16, w * 0.16, w * 0.16));
        }

        var floor = height * FLOOR;
        Color[] ridge = {new Color(140, 80, 130), new Color(90, 50, 110), new Color(50, 30, 80)};

        for (var layer = 0; layer < 3; layer++) {
            var path = new Path2D.Double();
            path.moveTo(0, floor);
            var peaks = 5 + layer * 2;

            for (var i = 0; i <= peaks * 2; i++) {
                var x = width * i / (peaks * 2.0);
                var top = i % 2 == 1 ? height * (0.3 + layer * 0.13 + hash(i * 5 + layer * 31) * 0.14) : height * (0.56 + layer * 0.07);
                path.lineTo(x, top);
            }

            path.lineTo(width, floor);
            path.closePath();
            g.setColor(ridge[layer]);
            g.fill(path);
        }

        // Птицы — галочки
        g.setStroke(new BasicStroke(Math.max(1.5f, width / 320f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(40, 24, 60));

        for (var i = 0; i < 4; i++) {
            var x = Math.floorMod((long) (hash(i * 8 + 2) * width + t * (22 + i * 5)), (long) (width + 60)) - 30;
            var y = height * (0.16 + hash(i * 3 + 5) * 0.16) + Math.sin(t * 2 + i) * height * 0.01;
            var wing = width * 0.014 * (1 + 0.3 * Math.sin(t * 9 + i));
            var bird = new Path2D.Double();
            bird.moveTo(x - wing, y - wing * 0.5);
            bird.lineTo(x, y);
            bird.lineTo(x + wing, y - wing * 0.5);
            g.draw(bird);
        }

        g.setPaint(new GradientPaint(0, (float) floor, new Color(44, 28, 66), 0, height, new Color(22, 14, 36)));
        g.fillRect(0, (int) floor, width, height);
    }

    /** Северное сияние: зелёно-фиолетовые ленты колышутся над снежной равниной. */
    static void aurora(Graphics2D g, int width, int height, long now) {
        var t = now / 1000.0;
        g.setPaint(new GradientPaint(0, 0, new Color(4, 8, 28), 0, height, new Color(10, 30, 50)));
        g.fillRect(0, 0, width, height);
        stars(g, width, height * 0.7, t, 55, Color.WHITE);
        Color[] bands = {new Color(60, 255, 160), new Color(80, 220, 255), new Color(180, 100, 255)};
        var step = Math.max(3, width / 110);

        for (var band = 0; band < 3; band++) {
            for (var x = 0; x < width; x += step) {
                var k = x / (double) width;
                var top = height * (0.12 + band * 0.1) + Math.sin(k * 6 + t * 0.5 + band * 2) * height * 0.06
                        + Math.sin(k * 15 - t * 0.8 + band) * height * 0.02;
                var length = height * (0.2 + 0.08 * Math.sin(k * 9 + t * 0.7 + band * 3));
                var strength = 0.55 + 0.45 * Math.sin(k * 12 + t * 1.3 + band * 5);
                g.setPaint(new GradientPaint(0, (float) top, alpha(bands[band], (int) (110 * strength)), 0, (float) (top + length),
                        alpha(bands[band], 0)));
                g.fillRect(x, (int) top, step, (int) length);
            }
        }

        var floor = height * FLOOR;
        var hills = new Path2D.Double();
        hills.moveTo(0, height);
        hills.lineTo(0, floor);

        for (var x = 0; x <= width; x += Math.max(4, width / 40)) {
            hills.lineTo(x, floor - Math.sin(x / (double) width * Math.PI * 2 + 1) * height * 0.012);
        }

        hills.lineTo(width, height);
        hills.closePath();
        g.setPaint(new GradientPaint(0, (float) floor, new Color(190, 230, 235), 0, height, new Color(110, 150, 180)));
        g.fill(hills);
    }

    /** Лужайка днём: голубое небо, облака, радуга, трава с цветами. */
    static void meadow(Graphics2D g, int width, int height, long now) {
        var t = now / 1000.0;
        g.setPaint(new GradientPaint(0, 0, new Color(70, 160, 255), 0, (float) (height * FLOOR), new Color(190, 230, 255)));
        g.fillRect(0, 0, width, height);
        var floor = height * FLOOR;

        // Радуга
        Color[] arc = {new Color(255, 80, 80), new Color(255, 170, 60), new Color(255, 240, 90), new Color(90, 220, 120),
                new Color(80, 170, 255), new Color(160, 110, 255)};
        var band = height * 0.022;
        g.setStroke(new BasicStroke((float) band));

        for (var i = 0; i < arc.length; i++) {
            var r = width * 0.44 - i * band;
            g.setColor(alpha(arc[i], 150));
            g.draw(new java.awt.geom.Arc2D.Double(width / 2.0 - r, floor - r * 0.8, r * 2, r * 1.6, 0, 180, java.awt.geom.Arc2D.OPEN));
        }

        // Солнце с лучами
        var sx = width * 0.14;
        var sy = height * 0.16;
        var sr = height * 0.07;
        g.setStroke(new BasicStroke(Math.max(2f, width / 220f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(255, 236, 120));

        for (var i = 0; i < 10; i++) {
            var a = t * 0.3 + i * Math.PI / 5;
            g.draw(new Line2D.Double(sx + Math.cos(a) * sr * 1.3, sy + Math.sin(a) * sr * 1.3, sx + Math.cos(a) * sr * 1.8,
                    sy + Math.sin(a) * sr * 1.8));
        }

        g.fill(new Ellipse2D.Double(sx - sr, sy - sr, sr * 2, sr * 2));

        // Облака
        for (var i = 0; i < 4; i++) {
            var x = Math.floorMod((long) (hash(i * 6 + 4) * width + t * (6 + i * 3)), (long) (width + 240)) - 120;
            var y = height * (0.07 + hash(i * 2 + 9) * 0.2);
            var w = width * (0.13 + hash(i + 3) * 0.08);
            g.setColor(new Color(255, 255, 255, 235));
            g.fill(new Ellipse2D.Double(x, y, w * 0.5, w * 0.26));
            g.fill(new Ellipse2D.Double(x + w * 0.25, y - w * 0.09, w * 0.5, w * 0.32));
            g.fill(new Ellipse2D.Double(x + w * 0.52, y, w * 0.48, w * 0.26));
        }

        g.setPaint(new GradientPaint(0, (float) floor, new Color(96, 200, 90), 0, height, new Color(40, 130, 60)));
        g.fillRect(0, (int) floor, width, height);

        // Травинки и цветы покачиваются
        g.setStroke(new BasicStroke(Math.max(1.5f, width / 320f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        for (var i = 0; i < 40; i++) {
            var x = hash(i * 9 + 6) * width;
            var y = floor + hash(i * 4 + 1) * (height - floor) * 0.8 + (height - floor) * 0.15;
            var h = height * 0.03;
            var sway = Math.sin(t * 1.4 + i) * h * 0.3;
            g.setColor(new Color(30, 110, 50));
            g.draw(new Line2D.Double(x, y, x + sway, y - h));

            if (i % 4 == 0) {
                var petal = Math.max(3, width / 150.0);
                g.setColor(i % 8 == 0 ? new Color(255, 120, 170) : new Color(255, 250, 240));
                g.fill(new Ellipse2D.Double(x + sway - petal, y - h - petal, petal * 2, petal * 2));
                g.setColor(new Color(255, 210, 60));
                g.fill(new Ellipse2D.Double(x + sway - petal * 0.4, y - h - petal * 0.4, petal * 0.8, petal * 0.8));
            }
        }
    }
}
