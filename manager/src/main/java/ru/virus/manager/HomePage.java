package ru.virus.manager;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Главная: всё важное одним взглядом. Процессы плитками (что работает,
 * что упало — и кнопка запуска прямо на плитке), нагрузка компьютера,
 * состояние подсветки, экранов и подставки, задачи в работе. Отсюда —
 * один щелчок до любого раздела.
 */
final class HomePage extends Ui.Card {

    private final Supervisor supervisor;
    private final TaskStore tasks;
    private final DevicePage device;
    private final ExecutorService worker;
    private final Consumer<String> open;
    private final Function<ManagedProcess, List<Doctor.Issue>> issues;

    private final JLabel summary = Ui.muted("", 13f);
    private final JPanel processGrid = Ui.transparent(new GridLayout(0, 3, 12, 12));
    private final Map<String, Tile> tiles = new LinkedHashMap<>();
    private final Bar cpu = new Bar();
    private final Bar memory = new Bar();
    private final JPanel glance = Ui.transparent(null);
    private final JPanel taskList = Ui.transparent(null);
    private final JButton dark = Ui.button("Погасить всё");
    private String glanceShown = "";
    private String tasksShown = "";
    private int tick;

    HomePage(MatrixBackground background, Supervisor supervisor, TaskStore tasks, DevicePage device, ExecutorService worker,
             Consumer<String> open, Function<ManagedProcess, List<Doctor.Issue>> issues) {
        super(new BorderLayout(0, 16));
        this.supervisor = supervisor;
        this.tasks = tasks;
        this.device = device;
        this.worker = worker;
        this.open = open;
        this.issues = issues;
        setBorder(BorderFactory.createEmptyBorder(20, 22, 20, 22));
        Ui.rippleThrough(this, background);

        var titles = Ui.transparent(new BorderLayout(0, 4));
        titles.add(Ui.label("Главная", Theme.ui.deriveFont(Font.BOLD, 22f), Theme.TEXT), BorderLayout.NORTH);
        titles.add(summary, BorderLayout.CENTER);
        var startAll = Ui.button("Запустить автозапускаемые");
        startAll.setIcon(Icons.of(Icons.Kind.PLAY, Theme.ACCENT));
        startAll.setToolTipText("Запустить все процессы, у которых включён автозапуск");
        startAll.addActionListener(e -> worker.submit(() -> supervisor.all().stream()
                .filter(process -> process.spec().autostart() && process.state() == ManagedProcess.State.STOPPED)
                .forEach(ManagedProcess::start)));
        var actions = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.add(startAll);
        var head = Ui.transparent(new BorderLayout(12, 0));
        head.add(titles, BorderLayout.CENTER);
        head.add(actions, BorderLayout.EAST);
        add(head, BorderLayout.NORTH);

        var page = new Ui.FitWidth();
        page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
        page.add(left(Ui.caption("Процессы")));
        page.add(Box.createVerticalStrut(10));
        page.add(left(processGrid));
        page.add(Box.createVerticalStrut(18));

        // Компьютер
        var computer = Ui.transparent(null);
        computer.setLayout(new BoxLayout(computer, BoxLayout.Y_AXIS));
        computer.add(left(Ui.muted("Процессор", 12.5f)));
        computer.add(Box.createVerticalStrut(4));
        computer.add(left(cpu));
        computer.add(Box.createVerticalStrut(12));
        computer.add(left(Ui.muted("Память", 12.5f)));
        computer.add(Box.createVerticalStrut(4));
        computer.add(left(memory));
        computer.add(Box.createVerticalStrut(12));
        computer.add(left(link("Интернет, связь и железо →", "device")));

        // Устройство
        glance.setLayout(new BoxLayout(glance, BoxLayout.Y_AXIS));
        var deviceBody = Ui.transparent(new BorderLayout(0, 10));
        deviceBody.add(glance, BorderLayout.CENTER);
        dark.setIcon(Icons.of(Icons.Kind.POWER, Theme.TEXT));
        dark.setToolTipText("Погасить или включить подсветку и экраны корпуса");
        dark.addActionListener(e -> device.setDark(!device.dark()));
        var deviceButtons = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        deviceButtons.add(dark);
        deviceButtons.add(Box.createHorizontalStrut(10));
        deviceButtons.add(link("Открыть →", "device"));
        deviceBody.add(deviceButtons, BorderLayout.SOUTH);

        // Задачи
        taskList.setLayout(new BoxLayout(taskList, BoxLayout.Y_AXIS));
        var tasksBody = Ui.transparent(new BorderLayout(0, 10));
        tasksBody.add(taskList, BorderLayout.CENTER);
        tasksBody.add(left(link("Все задачи →", "tasks")), BorderLayout.SOUTH);

        var cards = Ui.transparent(new GridLayout(1, 3, 14, 0));
        cards.add(DevicePage.inner("Компьютер", top(computer)));
        cards.add(DevicePage.inner("Подсветка и экраны", deviceBody));
        cards.add(DevicePage.inner("Задачи в работе", tasksBody));
        page.add(left(cards));

        var scroll = new JScrollPane(page);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(22);
        add(scroll, BorderLayout.CENTER);
        refresh();
    }

    private static <T extends JComponent> T left(T component) {
        component.setAlignmentX(LEFT_ALIGNMENT);
        return component;
    }

    /** Содержимое прижато кверху: карточки в ряду одной высоты, а строки не растягиваются. */
    private static JComponent top(JComponent content) {
        var holder = Ui.transparent(new BorderLayout());
        holder.add(content, BorderLayout.NORTH);
        return holder;
    }

    private JButton link(String text, String view) {
        var button = Ui.ghost(text);
        button.setForeground(Theme.ACCENT);
        button.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        button.addActionListener(e -> open.accept(view));
        return button;
    }

    /** Раз в секунду, пока «Главная» на экране. */
    void refresh() {
        var all = supervisor.all();
        var ids = all.stream().map(process -> process.spec().id()).toList();

        if (!ids.equals(new ArrayList<>(tiles.keySet()))) {
            tiles.clear();
            processGrid.removeAll();

            for (var process : all) {
                var tile = new Tile(process);
                tiles.put(process.spec().id(), tile);
                processGrid.add(tile);
            }

            processGrid.revalidate();
        }

        var running = 0;
        var broken = 0;

        for (var process : all) {
            var found = issues.apply(process);
            tiles.get(process.spec().id()).update(process, found);
            running += process.state() == ManagedProcess.State.RUNNING ? 1 : 0;
            broken += process.state() == ManagedProcess.State.FAILED || !found.isEmpty() ? 1 : 0;
        }

        var date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM · HH:mm", Locale.of("ru")));
        summary.setText(date + " · работает " + running + " из " + all.size()
                + (broken > 0 ? " · требует внимания: " + broken : ""));
        summary.setForeground(broken > 0 ? Theme.WARN : Theme.MUTED);

        showGlance();
        showTasks();

        // Нагрузка — раз в 2 секунды и не на потоке окна
        if (tick++ % 2 == 0) {
            worker.submit(() -> {
                var load = SystemInfo.load();
                SwingUtilities.invokeLater(() -> {
                    cpu.set(load.cpu() < 0 ? 0 : load.cpu(), load.cpu() < 0 ? "—" : Math.round(load.cpu() * 100) + "%");
                    var used = load.memoryTotal() - load.memoryFree();
                    memory.set(load.memoryTotal() > 0 ? used / (double) load.memoryTotal() : 0,
                            String.format(Locale.ROOT, "%.1f из %.0f ГБ", used / 1073741824.0, load.memoryTotal() / 1073741824.0));
                });
            });
        }
    }

    private void showGlance() {
        var rows = device.glance();
        dark.setText(device.dark() ? "Включить всё" : "Погасить всё");
        var key = rows.toString();

        if (key.equals(glanceShown)) {
            return;
        }

        glanceShown = key;
        glance.removeAll();

        if (rows.isEmpty()) {
            glance.add(left(Ui.muted("Подсветка и экраны — только в Windows.", 13f)));
        }

        for (var row : rows) {
            var line = Ui.transparent(new BorderLayout(10, 0));
            var color = row.good() == null ? Theme.DIM : row.good() ? Theme.ACCENT : Theme.WARN;
            line.add(new Lamp(color), BorderLayout.WEST);
            var title = Ui.muted(row.title(), 13f);
            title.setPreferredSize(new Dimension(118, title.getPreferredSize().height));
            var texts = Ui.transparent(new BorderLayout(8, 0));
            texts.add(title, BorderLayout.WEST);
            var value = Ui.label(row.text(), Theme.ui.deriveFont(13f), row.good() == null ? Theme.MUTED : Theme.TEXT);
            value.setToolTipText(row.text());
            texts.add(value, BorderLayout.CENTER);
            line.add(texts, BorderLayout.CENTER);
            line.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
            line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
            glance.add(left(line));
        }

        glance.revalidate();
        glance.repaint();
    }

    private void showTasks() {
        var all = tasks.all();
        var doing = all.stream().filter(task -> task.status == TaskStore.Status.DOING).toList();
        var ideas = all.stream().filter(task -> task.status == TaskStore.Status.IDEA).count();
        var key = doing.stream().map(task -> task.number + task.title).toList() + " " + ideas;

        if (key.equals(tasksShown)) {
            return;
        }

        tasksShown = key;
        taskList.removeAll();

        if (doing.isEmpty()) {
            taskList.add(left(Ui.muted("Сейчас в работе ничего нет.", 13f)));
        }

        for (var task : doing.stream().limit(5).toList()) {
            var line = Ui.transparent(new BorderLayout(8, 0));
            line.add(Ui.label("#" + task.number, Theme.mono.deriveFont(Font.BOLD, 12.5f), Theme.ACCENT), BorderLayout.WEST);
            var title = Ui.label(task.title.isBlank() ? "без названия" : task.title, Theme.ui.deriveFont(13f), Theme.TEXT);
            title.setToolTipText(task.title);
            line.add(title, BorderLayout.CENTER);
            line.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
            line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
            line.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            line.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    open.accept("tasks");
                }
            });
            taskList.add(left(line));
        }

        if (doing.size() > 5) {
            taskList.add(left(Ui.muted("и ещё " + (doing.size() - 5), 12.5f)));
        }

        taskList.add(Box.createVerticalStrut(6));
        taskList.add(left(Ui.muted("Идей в запасе: " + ideas, 12.5f)));
        taskList.revalidate();
        taskList.repaint();
    }

    /** Цветная точка состояния в сводке. */
    private static final class Lamp extends JComponent {

        private final Color color;

        Lamp(Color color) {
            this.color = color;
            setPreferredSize(new Dimension(10, 18));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            var g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(color);
            g.fillOval(1, getHeight() / 2 - 4, 8, 8);
            g.dispose();
        }
    }

    /** Полоска нагрузки с подписью: зелёная, с 75% — жёлтая, с 90% — красная. */
    private static final class Bar extends JComponent {

        private double value;
        private String text = "—";

        Bar() {
            setPreferredSize(new Dimension(120, 24));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        }

        void set(double value, String text) {
            this.value = Math.max(0, Math.min(1, value));
            this.text = text;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            var g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(0x1C, 0x26, 0x21));
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
            var fill = value >= 0.9 ? Theme.ERROR : value >= 0.75 ? Theme.WARN : Theme.ACCENT_DIM;
            g.setColor(fill);
            g.fillRoundRect(0, 0, (int) Math.max(value > 0 ? 8 : 0, getWidth() * value), getHeight(), 8, 8);
            g.setFont(Theme.ui.deriveFont(Font.BOLD, 12.5f));
            g.setColor(Theme.TEXT);
            g.drawString(text, 10, getHeight() / 2 + g.getFontMetrics().getAscent() / 2 - 2);
            g.dispose();
        }
    }

    /** Плитка процесса: состояние цветом, щелчок — открыть, кнопка — запустить или остановить. */
    private final class Tile extends JPanel {

        private final Ui.Dot dot = new Ui.Dot();
        private final JLabel name = Ui.label("", Theme.ui.deriveFont(Font.BOLD, 14.5f), Theme.TEXT);
        private final JLabel sub = Ui.muted("", 12.5f);
        private final JButton toggle = Ui.button("");
        private ManagedProcess.State state = ManagedProcess.State.STOPPED;
        private boolean warn;
        private boolean hover;

        Tile(ManagedProcess process) {
            super(new BorderLayout(10, 0));
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 12));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(200, 66));
            var texts = Ui.transparent(new BorderLayout(0, 3));
            texts.add(name, BorderLayout.NORTH);
            texts.add(sub, BorderLayout.CENTER);
            add(dot, BorderLayout.WEST);
            add(texts, BorderLayout.CENTER);
            toggle.setFocusable(false);
            toggle.setMargin(new java.awt.Insets(4, 8, 4, 8));
            toggle.addActionListener(e -> {
                var running = state != ManagedProcess.State.STOPPED && state != ManagedProcess.State.FAILED;
                worker.submit(running ? process::stop : process::start);
            });
            var east = Ui.transparent(new java.awt.GridBagLayout());
            east.add(toggle);
            add(east, BorderLayout.EAST);
            var id = process.spec().id();
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    open.accept("process:" + id);
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

        void update(ManagedProcess process, List<Doctor.Issue> found) {
            state = process.state();
            warn = !found.isEmpty();
            dot.setState(state);
            name.setText(process.spec().name());
            var json = process.toJson();
            String text;

            if (warn) {
                text = found.getFirst().title();
            } else if (state == ManagedProcess.State.RUNNING && json.get("startedAt") instanceof Long since) {
                text = "работает " + ProcessPage.uptime(since);
            } else if (!String.valueOf(json.get("note")).isBlank()) {
                text = String.valueOf(json.get("note"));
            } else {
                text = ProcessPage.STATE_NAMES.get(state);
            }

            sub.setText(text);
            sub.setToolTipText(text);
            sub.setForeground(warn ? Theme.WARN : state == ManagedProcess.State.FAILED ? Theme.ERROR : Theme.MUTED);
            var running = state != ManagedProcess.State.STOPPED && state != ManagedProcess.State.FAILED;
            toggle.setIcon(Icons.of(running ? Icons.Kind.STOP : Icons.Kind.PLAY, running ? Theme.ERROR : Theme.ACCENT));
            toggle.setToolTipText(running ? "Остановить" : "Запустить");
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            var g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(hover ? new Color(0x18, 0x22, 0x1D) : new Color(0x11, 0x18, 0x15));
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
            var edge = warn ? Theme.WARN : Ui.Dot.color(state);
            g.setColor(state == ManagedProcess.State.STOPPED && !warn ? Theme.LINE : Theme.alpha(edge, 140));
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
            g.dispose();
            super.paintComponent(graphics);
        }
    }
}
