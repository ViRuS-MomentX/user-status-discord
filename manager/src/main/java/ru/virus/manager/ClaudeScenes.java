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
            new Scene("snow", "Снегопад"));

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
}
