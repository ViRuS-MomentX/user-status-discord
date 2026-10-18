package ru.virus.manager;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
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
    private final JButton files = Ui.button("Файлы");
    private final JButton newWorld = Ui.button("Пересоздать мир");
    private final JButton plugin = Ui.button("Заменить плагин");
    /** Кнопки сервера Minecraft — отдельным рядом: в одну строку с остальными не влезают. */
    private final javax.swing.JPanel serverButtons = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
    private final JTextField commandLine = new JTextField();
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
        files.setIcon(Icons.of(Icons.Kind.FOLDER, Theme.ACCENT));

        for (var button : new JButton[] { start, restart, stop, edit, files }) {
            button.setIconTextGap(8);
        }

        buttons.add(start);
        buttons.add(restart);
        buttons.add(stop);
        edit.setToolTipText("Изменить процесс");
        buttons.add(edit);
        files.setToolTipText("Открыть папку процесса в Проводнике — туда можно докинуть файлы");
        files.addActionListener(e -> openFolder());
        buttons.add(files);

        // Только у сервера Minecraft
        newWorld.setIcon(Icons.of(Icons.Kind.RESTART, Theme.WARN));
        newWorld.setToolTipText("Удалить все три измерения — сервер создаст новые");
        newWorld.addActionListener(e -> recreateWorld());
        plugin.setIcon(Icons.of(Icons.Kind.PLUS, Theme.ACCENT));
        plugin.setToolTipText("Выбрать jar — прошлая сборка VirusSwords удалится, сервер перезапустится");
        plugin.addActionListener(e -> replacePlugin());
        serverButtons.add(newWorld);
        serverButtons.add(plugin);
        var allButtons = Ui.transparent(new BorderLayout(0, 8));
        allButtons.add(buttons, BorderLayout.NORTH);
        allButtons.add(serverButtons, BorderLayout.SOUTH);
        head.add(allButtons, BorderLayout.SOUTH);

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

        // Строка команд — серверу «op ник», «gamemode creative ник» и прочее
        commandLine.putClientProperty("JTextField.placeholderText", "Команда в консоль процесса и Enter — например: op Steve");
        commandLine.setFont(Theme.mono);
        commandLine.addActionListener(e -> {
            var text = commandLine.getText().strip();

            if (process != null && !text.isEmpty()) {
                if (process.send(text)) {
                    commandLine.setText("");
                } else {
                    Toolkit.getDefaultToolkit().beep();
                }
            }
        });

        var body = Ui.transparent(new BorderLayout(0, 10));
        body.add(bar, BorderLayout.NORTH);
        body.add(console, BorderLayout.CENTER);
        body.add(commandLine, BorderLayout.SOUTH);

        add(top, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
    }

    /** Папка процесса в Проводнике; нет её ещё — заводим. */
    private void openFolder() {
        if (process == null) {
            return;
        }

        var folder = process.workdir();

        try {
            java.nio.file.Files.createDirectories(folder);

            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                new ProcessBuilder("explorer.exe", folder.toString()).start();
            } else {
                java.awt.Desktop.getDesktop().open(folder.toFile());
            }
        } catch (java.io.IOException | RuntimeException e) {
            javax.swing.JOptionPane.showMessageDialog(this, "Папка не открылась: " + e.getMessage()
                    + "\n" + folder, Virus.NAME, javax.swing.JOptionPane.WARNING_MESSAGE);
        }
    }

    /** Остановить сервер, сделать дело, запустить снова. Дело — на рабочем потоке. */
    private void withServerStopped(String doing, boolean startAfter, Task task) {
        var target = process;

        if (target == null) {
            return;
        }

        worker.submit(() -> {
            var wasRunning = target.state() == ManagedProcess.State.RUNNING
                    || target.state() == ManagedProcess.State.WAITING;
            target.stop();

            if (!target.waitExit(150_000)) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                        "Сервер не остановился — " + doing + " не сделано.", Virus.NAME, JOptionPane.WARNING_MESSAGE));
                return;
            }

            try {
                var result = task.run(target.workdir());
                SwingUtilities.invokeLater(() -> notice(result));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                        doing + " не удалось: " + e.getMessage(), Virus.NAME, JOptionPane.ERROR_MESSAGE));
            }

            if (startAfter || wasRunning) {
                target.start();
            }
        });
    }

    private interface Task {
        String run(java.nio.file.Path folder) throws Exception;
    }

    private void notice(String text) {
        if (process != null) {
            process.log().add(java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
                    + " [V.I.R.U.S.] ▸ " + text);
        }
    }

    private void recreateWorld() {
        if (process == null) {
            return;
        }

        var level = ServerTools.levelName(process.workdir());

        if (JOptionPane.showConfirmDialog(this, "<html>Удалить мир <b>" + ConfigPage.escape(level)
                        + "</b> со всеми измерениями — обычным, адом и краем?<br>Сервер остановится и создаст новые."
                        + "<br><br>Вернуть старый мир будет нельзя.</html>",
                Virus.NAME, JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }

        withServerStopped("пересоздание мира", true,
                folder -> "Удалено измерений: " + ServerTools.deleteWorlds(folder) + " — сервер создаёт новый мир.");
    }

    private void replacePlugin() {
        if (process == null) {
            return;
        }

        var chooser = new JFileChooser();
        chooser.setDialogTitle("Новая сборка плагина");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Плагины (*.jar)", "jar"));
        var memory = java.util.prefs.Preferences.userNodeForPackage(ProcessPage.class);
        var lastDir = memory.get("plugin-dir", "");

        if (!lastDir.isEmpty()) {
            chooser.setCurrentDirectory(new java.io.File(lastDir));
        }

        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        var chosen = chooser.getSelectedFile().toPath();
        memory.put("plugin-dir", chosen.getParent().toString());

        withServerStopped("замена плагина", false, folder -> {
            var removed = ServerTools.replacePlugin(folder, chosen);
            return "Плагин заменён на " + chosen.getFileName()
                    + (removed.isEmpty() ? "" : " (убран " + String.join(", ", removed) + ")") + ".";
        });
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
        var server = ServerTools.isServer(process.spec());
        serverButtons.setVisible(server);
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
