package ru.virus.manager;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Страница одного процесса: что с ним, кнопки и его консоль.
 */
final class ProcessPage extends Ui.Card {

    static final Map<ManagedProcess.State, String> STATE_NAMES = Map.of(
            ManagedProcess.State.RUNNING, "работает",
            ManagedProcess.State.STOPPED, "остановлен",
            ManagedProcess.State.STOPPING, "останавливается",
            ManagedProcess.State.WAITING, "перезапуск",
            ManagedProcess.State.FAILED, "упал");

    private final JLabel title = Ui.label("", Theme.ui.deriveFont(Font.BOLD, 22f), Theme.TEXT);
    private final Ui.Badge badge = new Ui.Badge();
    private final JLabel meta = Ui.label("", Theme.mono.deriveFont(12f), Theme.MUTED);
    private final JLabel note = Ui.label("", Theme.ui.deriveFont(13f), new java.awt.Color(0xFF, 0xB3, 0xBB));
    private final JButton start = Ui.primary("Запустить");
    private final JButton restart = Ui.button("Перезапустить");
    private final JButton stop = Ui.danger("Остановить");
    private final JButton edit = Ui.button("Изменить");
    private final JTextField filter = new JTextField();
    private final ConsoleView console = new ConsoleView();
    private final ExecutorService worker;

    private ManagedProcess process;
    private long seq;

    ProcessPage(MatrixBackground background, ExecutorService worker, Consumer<ManagedProcess> onEdit) {
        super(new BorderLayout(0, 14));
        this.worker = worker;
        setBorder(BorderFactory.createEmptyBorder(20, 22, 20, 22));
        Ui.rippleThrough(this, background);

        // Шапка: имя, значок состояния, сведения, кнопки
        var head = Ui.transparent(new BorderLayout(0, 6));
        var titleRow = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        titleRow.add(title);
        titleRow.add(Box.createHorizontalStrut(12));
        titleRow.add(badge);
        head.add(titleRow, BorderLayout.NORTH);
        head.add(meta, BorderLayout.CENTER);

        var buttons = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        start.setIcon(Icons.of(Icons.Kind.PLAY, new java.awt.Color(0x04, 0x14, 0x0B)));
        restart.setIcon(Icons.of(Icons.Kind.RESTART, Theme.TEXT));
        stop.setIcon(Icons.of(Icons.Kind.STOP, Theme.ERROR));
        edit.setIcon(Icons.of(Icons.Kind.EDIT, Theme.MUTED));

        for (var button : new JButton[] { start, restart, stop, edit }) {
            button.setIconTextGap(8);
        }

        buttons.add(start);
        buttons.add(restart);
        buttons.add(stop);
        edit.setToolTipText("Изменить процесс");
        buttons.add(edit);
        head.add(buttons, BorderLayout.SOUTH);

        start.addActionListener(e -> act(ManagedProcess::start));
        stop.addActionListener(e -> act(ManagedProcess::stop));
        restart.addActionListener(e -> act(ManagedProcess::restart));
        edit.addActionListener(e -> {
            if (process != null) {
                onEdit.accept(process);
            }
        });

        note.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.alpha(Theme.ERROR, 90)),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));
        note.setOpaque(true);
        note.setBackground(new java.awt.Color(0x24, 0x10, 0x13));

        var top = Ui.transparent(null);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        head.setAlignmentX(LEFT_ALIGNMENT);
        note.setAlignmentX(LEFT_ALIGNMENT);
        top.add(head);
        top.add(Box.createVerticalStrut(10));
        top.add(note);

        // Панель консоли: фильтр, уровни, очистка
        filter.putClientProperty("JTextField.placeholderText", "Фильтр по тексту…");
        filter.putClientProperty("JTextField.showClearButton", true);
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                console.setFilter(filter.getText());
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                console.setFilter(filter.getText());
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                console.setFilter(filter.getText());
            }
        });

        var levels = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        var group = new ButtonGroup();

        for (var level : ConsoleView.Level.values()) {
            var toggle = new JToggleButton(switch (level) {
                case ALL -> "Всё";
                case WARN -> "Предупреждения";
                case ERROR -> "Ошибки";
            });
            toggle.putClientProperty("JButton.buttonType", "roundRect");
            toggle.setSelected(level == ConsoleView.Level.ALL);
            toggle.addActionListener(e -> console.setLevel(level));
            Ui.hand(toggle);
            group.add(toggle);
            levels.add(toggle);
        }

        var clear = Ui.ghost("Очистить");
        clear.addActionListener(e -> {
            if (process != null) {
                process.log().clear();
                console.clear();
            }
        });

        var bar = Ui.transparent(new BorderLayout(10, 0));
        bar.add(filter, BorderLayout.CENTER);
        var right = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.add(levels);
        right.add(clear);
        bar.add(right, BorderLayout.EAST);

        var body = Ui.transparent(new BorderLayout(0, 10));
        body.add(bar, BorderLayout.NORTH);
        body.add(console, BorderLayout.CENTER);

        add(top, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
    }

    private void act(Consumer<ManagedProcess> action) {
        var target = process;

        if (target != null) {
            // Остановка ждёт процесс — окну ждать незачем
            worker.submit(() -> action.accept(target));
        }
    }

    void show(ManagedProcess process) {
        if (this.process != process) {
            this.process = process;
            seq = 0;
            filter.setText("");
            var all = process.log().since(0);
            console.replace(all);
            seq = all.isEmpty() ? 0 : all.getLast().seq();
        }

        refresh();
    }

    ManagedProcess process() {
        return process;
    }

    /** Свежие строки консоли — зовётся по таймеру. */
    void pollLog() {
        if (process == null) {
            return;
        }

        var fresh = process.log().since(seq);

        if (!fresh.isEmpty()) {
            // Номер не растёт — консоль очищали или процесс новый: показываем заново
            if (fresh.getFirst().seq() <= seq) {
                console.replace(fresh);
            } else {
                console.append(fresh);
            }

            seq = fresh.getLast().seq();
        }
    }

    void refresh() {
        if (process == null) {
            return;
        }

        var json = process.toJson();
        var state = process.state();

        title.setText(process.spec().name());
        badge.set(STATE_NAMES.get(state), Ui.Dot.color(state));

        var parts = new java.util.ArrayList<String>();

        if (json.get("pid") != null) {
            parts.add("pid " + json.get("pid"));
        }

        if (state == ManagedProcess.State.RUNNING && json.get("startedAt") instanceof Long since) {
            parts.add("работает " + uptime(since));
        }

        if ((int) json.get("restarts") > 0) {
            parts.add("перезапусков: " + json.get("restarts"));
        }

        if (json.get("exitCode") != null && state != ManagedProcess.State.RUNNING) {
            parts.add("код выхода " + json.get("exitCode"));
        }

        parts.add(String.valueOf(json.get("commandLine")));
        meta.setText(String.join("   ·   ", parts));
        meta.setToolTipText(String.valueOf(json.get("commandLine")));

        var text = String.valueOf(json.get("note"));
        note.setText(text);
        note.setVisible(!text.isBlank() && state != ManagedProcess.State.RUNNING);

        var running = state == ManagedProcess.State.RUNNING;
        start.setEnabled(!running && state != ManagedProcess.State.STOPPING);
        stop.setEnabled(running || state == ManagedProcess.State.WAITING);
        restart.setEnabled(running);
    }

    static String uptime(long since) {
        var s = Math.max(0, (System.currentTimeMillis() - since) / 1000);
        var d = s / 86400;
        s -= d * 86400;
        var h = s / 3600;
        s -= h * 3600;
        var m = s / 60;
        s -= m * 60;

        if (d > 0) {
            return d + " д " + h + " ч";
        }
        if (h > 0) {
            return h + " ч " + m + " мин";
        }
        if (m > 0) {
            return m + " мин";
        }
        return s + " с";
    }
}
