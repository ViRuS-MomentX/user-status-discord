package ru.virus.manager;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Консоль процесса: цвет по уровню, фильтр и прокрутка, которая следует за
 * новыми строками, только пока ты внизу, — отмотал наверх читать, и консоль
 * тебя не дёргает.
 */
final class ConsoleView extends JPanel {

    enum Level { ALL, WARN, ERROR }

    /** Строка логгера ботов: «12:34:56 [поток] WARN Класс - текст». */
    private static final Pattern LOG_LINE =
            Pattern.compile("^(\\d\\d:\\d\\d:\\d\\d)\\s+\\[([^\\]]+)]\\s+(TRACE|DEBUG|INFO|WARN|ERROR)\\s+(.*)$");

    private static final Pattern STACK =
            Pattern.compile("^\\s*(at |Caused by:|Exception in thread|[\\w.]+(Exception|Error)\\b).*");

    /** Больше не держим — как и буфер процесса. */
    private static final int KEEP = LogBuffer.KEEP;

    private final JTextPane pane = new JTextPane();
    private final JScrollPane scroll;
    private final List<LogBuffer.Line> lines = new ArrayList<>();

    private String filter = "";
    private Level level = Level.ALL;
    private boolean stick = true;
    private int shown;

    ConsoleView() {
        super(new BorderLayout());
        setOpaque(false);

        pane.setEditable(false);
        pane.setFont(Theme.mono);
        pane.setForeground(Theme.TEXT);
        pane.setBackground(new Color(0x05, 0x07, 0x06));
        pane.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        pane.setCaretColor(Theme.ACCENT);

        scroll = new JScrollPane(pane) {
            @Override
            protected void paintComponent(Graphics graphics) {
                var g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(pane.getBackground());
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                g.dispose();
            }
        };
        scroll.setBorder(BorderFactory.createLineBorder(Theme.LINE));
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        scroll.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (!e.getValueIsAdjusting()) {
                var bar = scroll.getVerticalScrollBar();
                stick = bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 40;
            }
        });

        add(scroll, BorderLayout.CENTER);
    }

    void setFilter(String filter) {
        this.filter = filter.trim().toLowerCase(Locale.ROOT);
        rerender();
    }

    void setLevel(Level level) {
        this.level = level;
        rerender();
    }

    /** Новый процесс выбран — показать всё, что у него есть. */
    void replace(List<LogBuffer.Line> all) {
        lines.clear();
        lines.addAll(all);
        stick = true;
        rerender();
    }

    void append(List<LogBuffer.Line> fresh) {
        if (fresh.isEmpty()) {
            return;
        }

        lines.addAll(fresh);

        if (lines.size() > KEEP) {
            lines.subList(0, lines.size() - KEEP).clear();
            rerender();
            return;
        }

        var doc = pane.getStyledDocument();

        if (shown == 0) {
            pane.setText("");
        }

        for (var line : fresh) {
            if (visible(line)) {
                write(doc, line);
                shown++;
            }
        }

        follow();
    }

    void clear() {
        lines.clear();
        rerender();
    }

    private void rerender() {
        pane.setText("");
        shown = 0;
        var doc = pane.getStyledDocument();

        for (var line : lines) {
            if (visible(line)) {
                write(doc, line);
                shown++;
            }
        }

        if (shown == 0) {
            try {
                doc.insertString(0, lines.isEmpty()
                        ? "Консоль пуста. Запусти процесс — его вывод появится здесь."
                        : "Ничего не подходит под фильтр.", style(Theme.DIM, false));
            } catch (BadLocationException ignored) {
                // пустой документ — вставлять есть куда
            }
        }

        follow();
    }

    private void follow() {
        if (stick) {
            pane.setCaretPosition(pane.getDocument().getLength());
        }
    }

    enum Kind { PLAIN, WARN, ERROR, SYSTEM, SAY }

    static Kind classify(String text) {
        if (text.contains("[V.I.R.U.S.]")) {
            return Kind.SYSTEM;
        }

        var match = LOG_LINE.matcher(text);

        if (match.matches()) {
            return switch (match.group(3)) {
                case "ERROR" -> Kind.ERROR;
                case "WARN" -> Kind.WARN;
                default -> match.group(4).contains("Пародия сказала:") ? Kind.SAY : Kind.PLAIN;
            };
        }

        return STACK.matcher(text).matches() ? Kind.ERROR : Kind.PLAIN;
    }

    private boolean visible(LogBuffer.Line line) {
        var kind = classify(line.text());

        if (level == Level.ERROR && kind != Kind.ERROR) {
            return false;
        }

        if (level == Level.WARN && kind != Kind.WARN && kind != Kind.ERROR) {
            return false;
        }

        return filter.isEmpty() || line.text().toLowerCase(Locale.ROOT).contains(filter);
    }

    private void write(javax.swing.text.StyledDocument doc, LogBuffer.Line line) {
        var text = line.text();
        var kind = classify(text);
        var color = switch (kind) {
            case WARN -> Theme.WARN;
            case ERROR -> Theme.ERROR;
            case SYSTEM -> Theme.ACCENT;
            case SAY -> Theme.SAY;
            case PLAIN -> Theme.TEXT;
        };

        try {
            var match = LOG_LINE.matcher(text);

            if (match.matches()) {
                doc.insertString(doc.getLength(), match.group(1) + " ", style(Theme.DIM, false));
                doc.insertString(doc.getLength(), "[" + match.group(2) + "] ", style(Theme.THREAD, false));
                doc.insertString(doc.getLength(), match.group(3) + " " + match.group(4) + "\n",
                        style(color, kind == Kind.ERROR));
            } else {
                doc.insertString(doc.getLength(), text + "\n", style(color, kind == Kind.ERROR));
            }
        } catch (BadLocationException ignored) {
            // вставляем в конец — места всегда хватает
        }
    }

    private static SimpleAttributeSet style(Color color, boolean bold) {
        var style = new SimpleAttributeSet();
        StyleConstants.setForeground(style, color);
        StyleConstants.setBold(style, bold);
        return style;
    }
}
