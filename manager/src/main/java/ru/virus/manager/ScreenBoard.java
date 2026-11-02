package ru.virus.manager;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Живое табло для экрана в корпусе: время, процессор, видеокарта,
 * состояние процессов и режим подсветки — в стиле V.I.R.U.S. (тёмный фон,
 * неоновый зелёный). Рисуется в «лежачем» виде; повернуть под то, как
 * стоит экран, — дело {@link CaseScreens#rotate}.
 */
final class ScreenBoard {

    static final Color BG = new Color(8, 14, 11);
    static final Color ACCENT = new Color(0x39, 0xff, 0x8a);
    static final Color DIM = new Color(0x6f, 0x86, 0x7a);
    static final Color WARN = new Color(0xff, 0xb5, 0x47);
    static final Color ERROR = new Color(0xff, 0x40, 0x50);

    /** Что показать. */
    record Data(LocalTime time, double cpu, int gpuCelsius, String mode, Map<String, String> processes) {
    }

    private ScreenBoard() {
    }

    /** Цвет и подпись состояния процесса. */
    static String label(String state) {
        return switch (state) {
            case "RUNNING" -> "работает";
            case "WAITING" -> "перезапуск";
            case "FAILED" -> "упал";
            case "STOPPING" -> "стоп…";
            default -> "выключен";
        };
    }

    static Color color(String state) {
        return switch (state) {
            case "RUNNING" -> ACCENT;
            case "WAITING", "STOPPING" -> WARN;
            case "FAILED" -> ERROR;
            default -> DIM;
        };
    }

    static BufferedImage render(int width, int height, Data data) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(BG);
        g.fillRect(0, 0, width, height);

        var unit = Math.min(width, height) / 320.0;
        var pad = (int) (14 * unit);

        // Время крупно и подпись
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, (int) (64 * unit)));
        var clock = data.time().format(DateTimeFormatter.ofPattern("HH:mm"));
        g.drawString(clock, pad, pad + (int) (58 * unit));
        g.setColor(ACCENT);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, (int) (14 * unit)));
        g.drawString("V.I.R.U.S.", pad, pad + (int) (80 * unit));

        // Процессор и видеокарта — кольца справа сверху
        var ring = (int) (74 * unit);
        var top = pad;
        ring(g, width - pad - ring * 2 - pad / 2, top, ring, data.cpu(), "CPU", (int) Math.round(data.cpu() * 100) + "%", unit);
        var gpu = data.gpuCelsius();
        ring(g, width - pad - ring, top, ring, gpu < 0 ? 0 : Math.min(1, gpu / 90.0), "GPU",
                gpu < 0 ? "—" : gpu + "°", unit);

        // Процессы
        var y = top + ring + (int) (34 * unit);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, (int) (17 * unit)));

        for (var entry : data.processes().entrySet()) {
            if (y > height - (int) (40 * unit)) {
                break;
            }

            g.setColor(color(entry.getValue()));
            g.fillOval(pad, y - (int) (12 * unit), (int) (12 * unit), (int) (12 * unit));
            g.setColor(Color.WHITE);
            g.drawString(entry.getKey(), pad + (int) (20 * unit), y);
            var state = label(entry.getValue());
            var metrics = g.getFontMetrics();
            g.setColor(color(entry.getValue()));
            g.drawString(state, width - pad - metrics.stringWidth(state), y);
            y += (int) (26 * unit);
        }

        // Режим подсветки снизу
        g.setColor(new Color(20, 36, 28));
        g.fillRect(0, height - (int) (30 * unit), width, (int) (30 * unit));
        g.setColor(DIM);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, (int) (14 * unit)));
        g.drawString("подсветка: " + data.mode(), pad, height - (int) (10 * unit));
        g.dispose();
        return image;
    }

    private static void ring(Graphics2D g, int x, int y, int size, double value, String title, String text, double unit) {
        var stroke = (int) Math.max(4, 9 * unit);
        g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(28, 46, 37));
        g.drawOval(x + stroke / 2, y + stroke / 2, size - stroke, size - stroke);
        g.setColor(value > 0.85 ? ERROR : value > 0.6 ? WARN : ACCENT);
        g.drawArc(x + stroke / 2, y + stroke / 2, size - stroke, size - stroke, 90,
                (int) -Math.round(360 * Math.max(0, Math.min(1, value))));
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, (int) (20 * unit)));
        var metrics = g.getFontMetrics();
        g.drawString(text, x + (size - metrics.stringWidth(text)) / 2, y + size / 2 + metrics.getAscent() / 3);
        g.setColor(DIM);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, (int) (11 * unit)));
        metrics = g.getFontMetrics();
        g.drawString(title, x + (size - metrics.stringWidth(title)) / 2, y + size + (int) (14 * unit));
    }
}
