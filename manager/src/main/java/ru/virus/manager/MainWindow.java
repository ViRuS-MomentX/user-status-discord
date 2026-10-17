package ru.virus.manager;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.font.TextAttribute;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Окно V.I.R.U.S.
 */
final class MainWindow extends JFrame {

    private final Supervisor supervisor;
    private final ConfigService config;
    private final Autostart autostart;
    private final Runnable quit;
    private final boolean tray;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "virus-ui-worker");
        thread.setDaemon(true);
        return thread;
    });

    private final MatrixBackground background = new MatrixBackground();
    private final JPanel processList = Ui.transparent(null);
    private final Map<String, ProcessRow> rows = new LinkedHashMap<>();
    private final ProcessPage processPage;
    private final ConfigPage configPage;
    private final TasksPage tasksPage;
    private final NavRow tasksRow;
    private final DevicePage devicePage;
    private final NavRow deviceRow;
    private final CardLayout pages = new CardLayout();
    private final JPanel center = Ui.transparent(pages);
    private final NavRow configRow;
    private final Ui.Switch autostartSwitch;
    private final JPanel banners = Ui.transparent(null);
    private final JLabel toast = new JLabel();
    private final Timer toastTimer;

    private String view = "";
    private Runnable onDiscord = () -> toast("Статус в Discord недоступен");
    private boolean trayHintShown;

    MainWindow(Supervisor supervisor, ConfigService config, TaskStore tasks, Autostart autostart,
               Runnable quit, boolean tray) {
        super(Virus.NAME);
        this.supervisor = supervisor;
        this.config = config;
        this.autostart = autostart;
        this.quit = quit;
        this.tray = tray;

        setIconImages(List.of(AppIcon.image(16), AppIcon.image(32), AppIcon.image(48), AppIcon.image(256)));
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(980, 640));
        setSize(1320, 860);
        setLocationRelativeTo(null);

        processPage = new ProcessPage(background, worker, this::editProcess);
        configPage = new ConfigPage(background, config, this::toast, this::configSaved);
        devicePage = new DevicePage(background, supervisor.home(), this::toast, this::bridgeApi);
        tasksPage = new TasksPage(background, tasks, this::toast, this::projectNames);

        // Фон — всё окно; поверх него карточки с отступами, чтобы между ними
        // оставалось место для кода и волн
        background.setLayout(new BorderLayout(0, 14));
        background.setBorder(BorderFactory.createEmptyBorder(14, 18, 18, 18));
        setContentPane(background);

        // Шапка
        var logo = new JLabel(new ImageIcon(AppIcon.image(40)));
        var name = Ui.label(Virus.NAME, Theme.mono.deriveFont(Font.BOLD, 21f)
                .deriveFont(Map.of(TextAttribute.TRACKING, 0.2f)), Theme.ACCENT);
        var sub = Ui.muted("Versatile Instance Runner & Unified Supervisor", 12.5f);
        var titles = Ui.transparent(new BorderLayout(0, 2));
        titles.add(name, BorderLayout.NORTH);
        titles.add(sub, BorderLayout.CENTER);
        var brand = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 12, 0));
        brand.add(logo);
        brand.add(titles);

        autostartSwitch = new Ui.Switch(autostart.isEnabled());
        autostartSwitch.setToolTipText("Запускать V.I.R.U.S. при входе в Windows");
        autostartSwitch.onChange(this::setAutostart);
        var autostartLabel = Ui.muted("Автозапуск", 13.5f);
        var report = Ui.button("Отчёт для разбора");
        report.setIcon(Icons.of(Icons.Kind.REPORT, Theme.TEXT));
        report.setIconTextGap(8);
        report.setToolTipText("Собрать логи и настройки без токенов в один архив — его можно отправить");
        report.addActionListener(e -> makeReport());

        var discord = Ui.button("Discord");
        discord.setIcon(Icons.of(Icons.Kind.GEAR, Theme.TEXT));
        discord.setIconTextGap(8);
        discord.setToolTipText("Статус «Играет в V.I.R.U.S.» с картинкой в профиле Discord");
        discord.addActionListener(e -> onDiscord.run());

        var quitButton = Ui.danger("Выключить всё");
        quitButton.setIcon(Icons.of(Icons.Kind.POWER, Theme.ERROR));
        quitButton.setIconTextGap(8);
        quitButton.setToolTipText("Остановить все процессы и закрыть V.I.R.U.S.");
        quitButton.addActionListener(e -> confirmQuit());

        var actions = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 10, 0));

        if (autostart.isSupported()) {
            actions.add(autostartSwitch);
            actions.add(autostartLabel);
            actions.add(Box.createHorizontalStrut(10));
        }

        actions.add(discord);
        actions.add(report);
        actions.add(quitButton);

        var header = Ui.transparent(new BorderLayout());
        header.add(brand, BorderLayout.WEST);
        header.add(actions, BorderLayout.EAST);

        banners.setLayout(new BoxLayout(banners, BoxLayout.Y_AXIS));
        var top = Ui.transparent(new BorderLayout(0, 10));
        top.add(header, BorderLayout.NORTH);
        top.add(banners, BorderLayout.CENTER);

        // Боковая панель
        var side = new Ui.Card(null);
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBorder(BorderFactory.createEmptyBorder(16, 12, 16, 12));
        side.setPreferredSize(new Dimension(280, 10));
        Ui.rippleThrough(side, background);

        processList.setLayout(new BoxLayout(processList, BoxLayout.Y_AXIS));
        processList.setAlignmentX(LEFT_ALIGNMENT);

        var add = Ui.ghost("Добавить процесс");
        add.setIcon(Icons.of(Icons.Kind.PLUS, Theme.MUTED));
        add.setIconTextGap(10);
        add.setHorizontalAlignment(JLabel.LEFT);
        add.setAlignmentX(LEFT_ALIGNMENT);
        add.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        add.addActionListener(e -> editProcess(null));

        configRow = new NavRow(Icons.of(Icons.Kind.GEAR, Theme.ACCENT), "Конфиг ботов", () -> show("config"));
        tasksRow = new NavRow(Icons.of(Icons.Kind.TASKS, Theme.ACCENT), "Задачи", () -> show("tasks"));
        deviceRow = new NavRow(Icons.of(Icons.Kind.DEVICE, Theme.ACCENT), "Устройство", () -> show("device"));

        side.add(indent(Ui.caption("Процессы")));
        side.add(Box.createVerticalStrut(8));
        side.add(processList);
        side.add(Box.createVerticalStrut(6));
        side.add(add);
        side.add(Box.createVerticalStrut(20));
        side.add(indent(Ui.caption("Работа")));
        side.add(Box.createVerticalStrut(8));
        side.add(tasksRow);
        side.add(Box.createVerticalStrut(4));
        side.add(deviceRow);
        side.add(Box.createVerticalStrut(20));
        side.add(indent(Ui.caption("Настройка")));
        side.add(Box.createVerticalStrut(8));
        side.add(configRow);
        side.add(Box.createVerticalGlue());
        side.add(indent(Ui.label("Кликни по фону — пойдёт волна", Theme.ui.deriveFont(11.5f), Theme.DIM)));

        // Пустая страница — пока ничего не выбрано
        var empty = new Ui.Card(new BorderLayout());
        Ui.rippleThrough(empty, background);
        var mark = Ui.label("V", Theme.mono.deriveFont(Font.BOLD, 110f), Theme.alpha(Theme.ACCENT, 60));
        mark.setHorizontalAlignment(JLabel.CENTER);
        var hint = Ui.muted("Выбери процесс слева или открой конфиг ботов.", 15f);
        hint.setHorizontalAlignment(JLabel.CENTER);
        var emptyBody = Ui.transparent(new BorderLayout(0, 8));
        emptyBody.add(mark, BorderLayout.CENTER);
        emptyBody.add(hint, BorderLayout.SOUTH);
        empty.add(emptyBody, BorderLayout.CENTER);

        center.add(empty, "");
        center.add(processPage, "process");
        center.add(configPage, "config");
        center.add(tasksPage, "tasks");
        center.add(devicePage, "device");

        var body = Ui.transparent(new BorderLayout(16, 0));
        body.add(side, BorderLayout.WEST);
        body.add(center, BorderLayout.CENTER);

        background.add(top, BorderLayout.NORTH);
        background.add(body, BorderLayout.CENTER);

        // Всплывашка поверх всего
        toast.setOpaque(true);
        toast.setBackground(new Color(0x16, 0x1E, 0x1A));
        toast.setForeground(Theme.TEXT);
        toast.setFont(Theme.ui);
        toast.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.ACCENT_DIM),
                BorderFactory.createEmptyBorder(10, 16, 10, 16)));
        toast.setVisible(false);
        getLayeredPane().add(toast, JLayeredPane.POPUP_LAYER);
        toastTimer = new Timer(3500, e -> toast.setVisible(false));
        toastTimer.setRepeats(false);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                close();
            }

            @Override
            public void windowIconified(WindowEvent e) {
                background.stop();
            }

            @Override
            public void windowDeiconified(WindowEvent e) {
                background.start();
            }
        });

        new Timer(1000, e -> refresh()).start();
        new Timer(300, e -> {
            if (view.startsWith("process:")) {
                processPage.pollLog();
            }
        }).start();

        // Проверка старой задачи ходит в планировщик Windows — не на потоке окна
        worker.submit(() -> {
            if (Autostart.oldTaskExists()) {
                SwingUtilities.invokeLater(this::showOldTaskBanner);
            }
        });

        refresh();
        var first = supervisor.all().isEmpty() ? "" : "process:" + supervisor.all().getFirst().spec().id();
        show(first);
    }

    private static JComponent indent(JComponent component) {
        component.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
        component.setAlignmentX(LEFT_ALIGNMENT);
        return component;
    }

    /** Показать окно — из трея или по второму запуску. */
    void reveal() {
        if (!isVisible()) {
            setVisible(true);
        }

        setExtendedState(getExtendedState() & ~ICONIFIED);
        toFront();
        requestFocus();
        background.start();
    }

    private void close() {
        if (configPage.isDirty() && JOptionPane.showConfirmDialog(this,
                "Изменения в конфиге не сохранены. Закрыть окно без сохранения?", Virus.NAME,
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }

        if (!tray) {
            // Трея нет — спрятаться некуда, закрытие окна значит выход
            confirmQuit();
            return;
        }

        setVisible(false);
        background.stop();
        tasksPage.flush();

        if (!trayHintShown) {
            trayHintShown = true;
            Virus.notifyTray("V.I.R.U.S. работает в трее", "Боты продолжают работать. Открыть окно — двойной клик по значку.");
        }
    }

    void onDiscord(Runnable action) {
        onDiscord = action;
    }

    /** Свой адрес Bot API из настроек бота — его тоже проверяем на связь. */
    private String bridgeApi() {
        try {
            return config.exists() ? config.read().values().getOrDefault("bridge.telegram.api", "").strip() : "";
        } catch (java.io.IOException e) {
            return "";
        }
    }

    /** Имена папок проектов — подсказки для поля «Проект» у задач. */
    private List<String> projectNames() {
        try {
            return new Projects(supervisor.home()).list().stream()
                    .map(path -> path.getFileName().toString()).toList();
        } catch (java.io.IOException e) {
            return List.of();
        }
    }

    private void confirmQuit() {
        if (JOptionPane.showConfirmDialog(this, "Остановить все процессы и выключить V.I.R.U.S.?", Virus.NAME,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION) {
            setVisible(false);
            new Thread(quit, "virus-quit").start();
        }
    }

    private void show(String next) {
        if (view.equals("config") && !next.equals("config") && configPage.isDirty()
                && JOptionPane.showConfirmDialog(this, "Изменения в конфиге не сохранены. Уйти без сохранения?",
                Virus.NAME, JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }

        if (view.equals("device") && !next.equals("device")) {
            devicePage.closed();
        }

        view = next;

        if (next.startsWith("process:")) {
            var process = supervisor.get(next.substring("process:".length()));

            if (process == null) {
                show("");
                return;
            }

            processPage.show(process);
            pages.show(center, "process");
        } else if (next.equals("config")) {
            configPage.reload();
            pages.show(center, "config");
        } else if (next.equals("device")) {
            devicePage.opened();
            pages.show(center, "device");
        } else if (next.equals("tasks")) {
            tasksPage.reload();
            pages.show(center, "tasks");
        } else {
            pages.show(center, "");
        }

        rows.forEach((id, row) -> row.setSelected(view.equals("process:" + id)));
        configRow.setSelected(view.equals("config"));
        tasksRow.setSelected(view.equals("tasks"));
        deviceRow.setSelected(view.equals("device"));
    }

    private void refresh() {
        var all = supervisor.all();
        var ids = all.stream().map(process -> process.spec().id()).toList();

        // Набор процессов поменялся — пересобираем список
        if (!ids.equals(new ArrayList<>(rows.keySet()))) {
            rows.clear();
            processList.removeAll();

            for (var process : all) {
                var id = process.spec().id();
                var row = new ProcessRow(() -> show("process:" + id));
                rows.put(id, row);
                processList.add(row);
                processList.add(Box.createVerticalStrut(4));
            }

            processList.revalidate();
            processList.repaint();
        }

        for (var process : all) {
            rows.get(process.spec().id()).update(process);
        }

        rows.forEach((id, row) -> row.setSelected(view.equals("process:" + id)));

        if (view.startsWith("process:")) {
            processPage.refresh();
        }

        autostartSwitch.setOn(autostart.isEnabled());
    }

    /** Значок «сколько настроек не хватает» у пункта конфига. */
    void refreshMissing() {
        worker.submit(() -> {
            var missing = configPage.missing();
            SwingUtilities.invokeLater(() -> configRow.setPill(missing > 0 ? String.valueOf(missing) : ""));
        });
    }

    private void editProcess(ManagedProcess process) {
        var result = ProcessDialog.ask(this, process == null ? null : process.spec());

        try {
            switch (result) {
                case ProcessDialog.Saved saved -> {
                    supervisor.put(saved.spec());
                    toast(process == null ? "Процесс добавлен" : "Сохранено. Новая команда — со следующего запуска.");
                    refresh();
                    show("process:" + saved.spec().id());
                }
                case ProcessDialog.Deleted deleted -> {
                    supervisor.remove(deleted.id());
                    refresh();
                    show("");
                }
                case ProcessDialog.Cancelled cancelled -> {
                    // ничего
                }
            }
        } catch (IOException e) {
            toast("Не сохранилось: " + e.getMessage());
        }
    }

    private void configSaved(boolean restart) {
        refreshMissing();

        if (!restart) {
            return;
        }

        var running = supervisor.all().stream()
                .filter(process -> process.state() == ManagedProcess.State.RUNNING)
                .toList();

        if (running.isEmpty()) {
            toast("Сохранено. Запущенных процессов нет.");
            return;
        }

        worker.submit(() -> running.forEach(ManagedProcess::restart));
        toast("Сохранено, перезапускаю: " + String.join(", ",
                running.stream().map(process -> process.spec().name()).toList()));
    }

    private void setAutostart(boolean on) {
        try {
            if (on) {
                autostart.enable();
                toast("V.I.R.U.S. будет запускаться при входе в Windows");
            } else {
                autostart.disable();
                toast("Автозапуск выключен");
            }
        } catch (IOException e) {
            autostartSwitch.setOn(!on);
            toast("Не вышло: " + e.getMessage());
        }
    }

    /** Отчёт для разбора: собрать и показать, где лежит. */
    private void makeReport() {
        toast("Собираю отчёт…");

        worker.submit(() -> {
            try {
                var zip = Reporter.build(supervisor.home(), supervisor, config.file());
                SwingUtilities.invokeLater(() -> toast("Отчёт готов: reports/" + zip.getFileName()));
                showInExplorer(zip);
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> toast("Отчёт не собрался: " + e.getMessage()));
            }
        });
    }

    /** Открывает Проводник с выделенным файлом; не Windows — просто папку. */
    private static void showInExplorer(java.nio.file.Path file) {
        try {
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                new ProcessBuilder("explorer.exe", "/select,", file.toString()).start();
            } else if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(file.getParent().toFile());
            }
        } catch (IOException | UnsupportedOperationException e) {
            // Не открылось — путь уже показан в подсказке
        }
    }

    private void showOldTaskBanner() {
        var banner = new Ui.Card(new BorderLayout(12, 0), Theme.alpha(Theme.WARN, 26));
        banner.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        banner.add(Ui.label("<html>Стоит старая задача автозапуска <b>VoiceBridgeBot</b> — она запускает бота мимо "
                + "V.I.R.U.S., и работали бы две копии. Убери её в PowerShell от администратора: "
                + "<font face='monospace' color='#ffd79a'>Unregister-ScheduledTask -TaskName VoiceBridgeBot "
                + "-Confirm:$false</font></html>", Theme.ui.deriveFont(13f), new Color(0xFF, 0xD7, 0x9A)));
        banners.add(banner);
        banners.revalidate();
    }

    void toast(String text) {
        toast.setText(text);
        var size = toast.getPreferredSize();
        var pane = getLayeredPane();
        toast.setBounds((pane.getWidth() - size.width) / 2, pane.getHeight() - size.height - 30, size.width, size.height);
        toast.setVisible(true);
        toastTimer.restart();
    }

    /** Строка процесса в боковой панели. */
    private static final class ProcessRow extends JPanel {

        private final Ui.Dot dot = new Ui.Dot();
        private final JLabel name = Ui.label("", Theme.ui.deriveFont(Font.BOLD), Theme.TEXT);
        private final JLabel sub = Ui.muted("", 12f);
        private boolean selected;
        private boolean hover;

        ProcessRow(Runnable open) {
            super(new BorderLayout(10, 0));
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(9, 8, 9, 10));
            setAlignmentX(LEFT_ALIGNMENT);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 58));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            var texts = Ui.transparent(new BorderLayout(0, 2));
            texts.add(name, BorderLayout.NORTH);
            texts.add(sub, BorderLayout.CENTER);
            add(dot, BorderLayout.WEST);
            add(texts, BorderLayout.CENTER);

            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    open.run();
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
        }

        void update(ManagedProcess process) {
            var json = process.toJson();
            var state = process.state();
            dot.setState(state);
            name.setText(process.spec().name());

            String text;

            if (state == ManagedProcess.State.RUNNING && json.get("startedAt") instanceof Long since) {
                text = "работает " + ProcessPage.uptime(since);
            } else if (!String.valueOf(json.get("note")).isBlank()) {
                text = String.valueOf(json.get("note"));
            } else {
                text = ProcessPage.STATE_NAMES.get(state);
            }

            sub.setText(text);
            setToolTipText(text);
        }

        void setSelected(boolean selected) {
            if (this.selected != selected) {
                this.selected = selected;
                repaint();
            }
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            if (selected || hover) {
                var g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(selected ? new Color(0x18, 0x22, 0x1D) : new Color(0x13, 0x1A, 0x17));
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);

                if (selected) {
                    g.setColor(Theme.LINE_2);
                    g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                    g.setColor(Theme.ACCENT);
                    g.fillRoundRect(0, 10, 3, getHeight() - 20, 3, 3);
                }

                g.dispose();
            }

            super.paintComponent(graphics);
        }
    }

    /** Пункт меню: «⚙ Конфиг ботов» со значком-счётчиком. */
    private static final class NavRow extends JPanel {

        private final JLabel pill = Ui.label("", Theme.ui.deriveFont(Font.BOLD, 11f), Theme.WARN);
        private boolean selected;
        private boolean hover;

        NavRow(javax.swing.Icon icon, String text, Runnable open) {
            super(new BorderLayout(10, 0));
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(9, 10, 9, 10));
            setAlignmentX(LEFT_ALIGNMENT);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            add(new JLabel(icon), BorderLayout.WEST);
            add(Ui.text(text), BorderLayout.CENTER);
            add(pill, BorderLayout.EAST);
            pill.setToolTipText("Столько настроек из новой версии нет в твоём файле");

            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    open.run();
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
        }

        void setPill(String text) {
            pill.setText(text);
        }

        void setSelected(boolean selected) {
            this.selected = selected;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            if (selected || hover) {
                var g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(selected ? new Color(0x18, 0x22, 0x1D) : new Color(0x13, 0x1A, 0x17));
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                g.dispose();
            }

            super.paintComponent(graphics);
        }
    }

    /** Для проверки снимком: фон окна. */
    MatrixBackground background() {
        return background;
    }

    boolean hasConfigOpen() {
        return view.equals("config");
    }

    void openConfig() {
        show("config");
    }

    void openProcess(String id) {
        show("process:" + id);
    }
}
