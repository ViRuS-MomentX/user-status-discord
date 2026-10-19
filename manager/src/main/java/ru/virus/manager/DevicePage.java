package ru.virus.manager;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Устройство: что за компьютер, как с интернетом и серверами — и подсветка.
 */
final class DevicePage extends Ui.Card {

    private static final Color INNER = new Color(0x0B, 0x10, 0x0E, 220);

    /** Программы производителей подсветки, которые можно открыть отсюда. */
    static final List<String> VENDORS = List.of("JONSBO", "Attack Shark X98");

    private static final Color[] PRESETS = {
            Theme.ACCENT, new Color(0xFF, 0x20, 0x20), new Color(0xFF, 0x80, 0x00), new Color(0xFF, 0xE0, 0x00),
            new Color(0x00, 0xE5, 0xFF), new Color(0x20, 0x50, 0xFF), new Color(0x9B, 0x30, 0xFF),
            new Color(0xFF, 0x30, 0xB0), Color.WHITE };

    private final ExecutorService worker;
    private final Consumer<String> toast;
    private final Supplier<String> bridgeApi;
    private final Settings settings;
    private final NetProbe probe = new NetProbe();

    private final CardLayout tabs = new CardLayout();
    private final JPanel body = Ui.transparent(tabs);

    // Обзор
    private final JPanel facts = new Ui.FitWidth();
    private final Meter cpu = new Meter();
    private final Meter memory = new Meter();
    private final JPanel disks = Ui.transparent(null);
    private final Gauge gauge = new Gauge();
    private final JLabel ping = stat();
    private final JLabel down = stat();
    private final JLabel up = stat();
    private final JButton measure = Ui.primary("Измерить скорость");
    private final JPanel servers = Ui.transparent(null);
    private final JButton check = Ui.button("Проверить");
    private final Timer live;
    private boolean factsLoaded;

    // Кастомизация
    private final JLabel rgbStatus = Ui.muted("Не подключено.", 13f);
    private final JPanel rgbDevices = new Ui.FitWidth();
    private final Swatch swatch = new Swatch(32);
    private final JSlider brightness = new JSlider(0, 100, 100);
    private final Ui.Switch onStart = new Ui.Switch(false);
    private final JPanel vendorRows = Ui.transparent(null);
    private final JPanel litRows = new Ui.FitWidth();
    private final JLabel litStatus = Ui.muted("Нажми «Найти», чтобы увидеть, кто чем красится.", 12.5f);
    private List<LightScan.Device> lit = List.of();
    private List<OpenRgb.Device> devices = List.of();

    DevicePage(MatrixBackground background, Path home, Consumer<String> toast, Supplier<String> bridgeApi) {
        super(new BorderLayout(0, 14));
        // Свои потоки: замер скорости идёт полминуты и не должен держать кнопки процессов
        this.worker = java.util.concurrent.Executors.newCachedThreadPool(runnable -> {
            var thread = new Thread(runnable, "virus-device");
            thread.setDaemon(true);
            return thread;
        });
        this.toast = toast;
        this.bridgeApi = bridgeApi;
        this.settings = Settings.load(home.resolve("device.json"));
        setBorder(BorderFactory.createEmptyBorder(20, 22, 20, 22));
        Ui.rippleThrough(this, background);

        add(header(), BorderLayout.NORTH);
        body.add(overview(), "overview");
        body.add(customization(), "custom");
        add(body, BorderLayout.CENTER);

        live = new Timer(2000, e -> {
            if (isShowing()) {
                refreshLoad();
            }
        });
    }

    // ----------------------------------------------------------------- вёрстка

    private JComponent header() {
        var titles = Ui.transparent(new BorderLayout(0, 4));
        titles.add(Ui.label("Устройство", Theme.ui.deriveFont(Font.BOLD, 22f), Theme.TEXT), BorderLayout.NORTH);
        titles.add(Ui.muted("Компьютер, интернет, связь с Discord и Telegram — и подсветка.", 13f),
                BorderLayout.CENTER);

        var group = new ButtonGroup();
        var chips = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));

        for (var tab : List.of(Map.entry("overview", "Обзор"), Map.entry("custom", "Кастомизация"))) {
            var chip = new JToggleButton(tab.getValue());
            chip.putClientProperty("JButton.buttonType", "roundRect");
            chip.setFocusable(false);
            chip.setSelected(tab.getKey().equals("overview"));
            chip.addActionListener(e -> tabs.show(body, tab.getKey()));
            Ui.hand(chip);
            group.add(chip);
            chips.add(chip);
        }

        var head = Ui.transparent(new BorderLayout(12, 0));
        head.add(titles, BorderLayout.CENTER);
        head.add(chips, BorderLayout.EAST);
        return head;
    }

    private JComponent overview() {
        // Слева — компьютер
        facts.setLayout(new BoxLayout(facts, BoxLayout.Y_AXIS));
        facts.add(Ui.muted("Собираю сведения…", 13f));

        disks.setLayout(new BoxLayout(disks, BoxLayout.Y_AXIS));

        var loads = Ui.transparent(null);
        loads.setLayout(new BoxLayout(loads, BoxLayout.Y_AXIS));
        loads.add(left(Ui.caption("Процессор")));
        loads.add(Box.createVerticalStrut(5));
        loads.add(left(cpu));
        loads.add(Box.createVerticalStrut(12));
        loads.add(left(Ui.caption("Память")));
        loads.add(Box.createVerticalStrut(5));
        loads.add(left(memory));
        loads.add(Box.createVerticalStrut(12));
        loads.add(left(Ui.caption("Диски")));
        loads.add(Box.createVerticalStrut(5));
        loads.add(left(disks));

        var computerBody = new Ui.FitWidth();
        computerBody.setLayout(new BorderLayout(0, 16));
        computerBody.add(facts, BorderLayout.NORTH);
        computerBody.add(loads, BorderLayout.CENTER);

        var scroll = new JScrollPane(computerBody);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        var computer = inner("Компьютер", scroll);

        // Справа сверху — интернетометр
        var stats = Ui.transparent(new GridLayout(1, 3, 10, 0));
        stats.add(statBox("Пинг", ping));
        stats.add(statBox("Загрузка", down));
        stats.add(statBox("Отдача", up));

        measure.setIcon(Icons.of(Icons.Kind.PLAY, new Color(0x04, 0x14, 0x0B)));
        measure.addActionListener(e -> measureSpeed());
        var measureRow = Ui.transparent(new FlowLayout(FlowLayout.CENTER, 0, 0));
        measureRow.add(measure);

        var speedBody = Ui.transparent(new BorderLayout(0, 10));
        speedBody.add(gauge, BorderLayout.CENTER);
        var speedFoot = Ui.transparent(new BorderLayout(0, 12));
        speedFoot.add(stats, BorderLayout.NORTH);
        speedFoot.add(measureRow, BorderLayout.SOUTH);
        speedBody.add(speedFoot, BorderLayout.SOUTH);
        var speed = inner("Интернетометр", speedBody);

        // Справа снизу — серверы
        servers.setLayout(new BoxLayout(servers, BoxLayout.Y_AXIS));
        check.setIcon(Icons.of(Icons.Kind.RESTART, Theme.TEXT));
        check.addActionListener(e -> checkServers());
        var serversBody = Ui.transparent(new BorderLayout(0, 10));
        serversBody.add(servers, BorderLayout.CENTER);
        var checkRow = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        checkRow.add(check);
        serversBody.add(checkRow, BorderLayout.SOUTH);
        var connection = inner("Связь с серверами", serversBody);

        var right = Ui.transparent(new BorderLayout(0, 14));
        right.add(speed, BorderLayout.CENTER);
        right.add(connection, BorderLayout.SOUTH);

        var grid = Ui.transparent(new GridLayout(1, 2, 14, 0));
        grid.add(computer);
        grid.add(right);
        return grid;
    }

    private JComponent customization() {
        // OpenRGB: цвет для всех и по устройствам
        var connect = Ui.button("Подключиться");
        connect.setIcon(Icons.of(Icons.Kind.RESTART, Theme.TEXT));
        connect.addActionListener(e -> connectRgb());
        var launch = Ui.button("Запустить OpenRGB");
        launch.setIcon(Icons.of(Icons.Kind.PLAY, Theme.TEXT));
        launch.addActionListener(e -> launchOpenRgb());
        var site = Ui.ghost("Где взять OpenRGB");
        site.addActionListener(e -> browse("https://openrgb.org"));

        var statusRow = Ui.transparent(new BorderLayout(10, 0));
        statusRow.add(rgbStatus, BorderLayout.CENTER);
        var statusButtons = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        statusButtons.add(site);
        statusButtons.add(launch);
        statusButtons.add(connect);
        statusRow.add(statusButtons, BorderLayout.EAST);

        swatch.setColor(settings.color);
        swatch.setToolTipText("Выбрать любой цвет");
        swatch.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                var chosen = JColorChooser.showDialog(DevicePage.this, "Цвет подсветки", swatch.color());

                if (chosen != null) {
                    pick(chosen);
                }
            }
        });

        var presets = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));

        for (var preset : PRESETS) {
            var small = new Swatch(22);
            small.setColor(preset);
            small.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    pick(preset);
                }
            });
            presets.add(small);
        }

        brightness.setValue(settings.brightness);
        brightness.setOpaque(false);
        brightness.setPreferredSize(new Dimension(160, 28));
        brightness.addChangeListener(e -> {
            if (!brightness.getValueIsAdjusting()) {
                settings.brightness = brightness.getValue();
                settings.save();
            }
        });

        var applyAll = Ui.primary("Покрасить всё");
        applyAll.addActionListener(e -> paintAll(lit()));
        var off = Ui.button("Выключить подсветку");
        off.addActionListener(e -> paintAll(Color.BLACK));

        var colorRow = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 10, 0));
        colorRow.add(swatch);
        colorRow.add(presets);
        colorRow.add(Ui.muted("Яркость", 13f));
        colorRow.add(brightness);

        var actionRow = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actionRow.add(applyAll);
        actionRow.add(off);
        actionRow.add(Box.createHorizontalStrut(14));
        onStart.setOn(settings.applyOnStart);
        onStart.onChange(on -> {
            settings.applyOnStart = on;
            settings.save();
        });
        actionRow.add(onStart);
        actionRow.add(Ui.muted("ставить этот цвет при запуске V.I.R.U.S.", 13f));

        rgbDevices.setLayout(new BoxLayout(rgbDevices, BoxLayout.Y_AXIS));
        rgbDevices.add(Ui.muted("<html><body style='width:640px'>OpenRGB — бесплатная программа, которая управляет подсветкой сотен "
                + "устройств разных фирм. Запусти её, во вкладке <b>SDK Server</b> нажми <b>Start Server</b> "
                + "и нажми здесь «Подключиться». Если твоих JONSBO или Attack Shark в её списке нет — "
                + "крась их программами производителей ниже.</body></html>", 13f));

        var devicesScroll = new JScrollPane(rgbDevices);
        devicesScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        devicesScroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.LINE));
        devicesScroll.setOpaque(false);
        devicesScroll.getViewport().setOpaque(false);

        var rgbTop = Ui.transparent(null);
        rgbTop.setLayout(new BoxLayout(rgbTop, BoxLayout.Y_AXIS));
        rgbTop.add(left(statusRow));
        rgbTop.add(Box.createVerticalStrut(14));
        rgbTop.add(left(colorRow));
        rgbTop.add(Box.createVerticalStrut(10));
        rgbTop.add(left(actionRow));
        rgbTop.add(Box.createVerticalStrut(12));

        var rgbBody = Ui.transparent(new BorderLayout());
        rgbBody.add(rgbTop, BorderLayout.NORTH);
        rgbBody.add(devicesScroll, BorderLayout.CENTER);
        var rgb = inner("Подсветка через OpenRGB", rgbBody);

        // Программы производителей
        vendorRows.setLayout(new BoxLayout(vendorRows, BoxLayout.Y_AXIS));
        showVendors();
        var vendorBody = Ui.transparent(new BorderLayout(0, 8));
        vendorBody.add(vendorRows, BorderLayout.CENTER);
        vendorBody.add(Ui.muted("Пока программа производителя работает, она может перебивать цвет "
                + "из OpenRGB — закрой её, если красишь отсюда.", 12.5f), BorderLayout.SOUTH);
        var vendors = inner("Программы производителей", vendorBody);

        var page = Ui.transparent(new BorderLayout(0, 14));
        page.add(rgb, BorderLayout.CENTER);
        var bottom = Ui.transparent(new BorderLayout(0, 14));
        bottom.add(lighting(), BorderLayout.CENTER);
        bottom.add(vendors, BorderLayout.SOUTH);
        page.add(bottom, BorderLayout.SOUTH);
        return page;
    }

    /**
     * Все подключённые устройства с подсветкой и кто их может красить:
     * Windows сама («Динамическое освещение»), OpenRGB или только родная программа.
     */
    private JComponent lighting() {
        var find = Ui.button("Найти");
        find.setIcon(Icons.of(Icons.Kind.RESTART, Theme.TEXT));
        find.addActionListener(e -> scanLights(find));
        var windows = Ui.button("Динамическое освещение Windows");
        windows.addActionListener(e -> openDynamicLighting());
        var copy = Ui.ghost("Скопировать список");
        copy.setToolTipText("Скопировать названия и VID:PID — чтобы прислать их разработчику");
        copy.addActionListener(e -> copyLights());

        // Статус — отдельной строкой под кнопками: рядом с ними длинный текст обрезался
        var top = Ui.transparent(new BorderLayout(0, 8));
        var buttons = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.add(find);
        buttons.add(windows);
        buttons.add(copy);
        top.add(buttons, BorderLayout.NORTH);
        top.add(litStatus, BorderLayout.SOUTH);

        litRows.setLayout(new BoxLayout(litRows, BoxLayout.Y_AXIS));
        var scroll = new JScrollPane(litRows);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setPreferredSize(new Dimension(10, 110));

        var body = Ui.transparent(new BorderLayout(0, 8));
        body.add(top, BorderLayout.NORTH);
        body.add(scroll, BorderLayout.CENTER);
        return inner("Все устройства с подсветкой", body);
    }

    private void scanLights(JButton find) {
        find.setEnabled(false);
        litStatus.setForeground(Theme.MUTED);
        litStatus.setText("Ищу устройства…");

        worker.submit(() -> {
            List<LightScan.Device> found;
            String error = null;

            try {
                found = SystemInfo.isWindows() ? LightScan.scan() : List.of();
            } catch (Exception e) {
                found = List.of();
                error = e.getMessage();
            }

            var result = found;
            var problem = error;
            SwingUtilities.invokeLater(() -> {
                find.setEnabled(true);
                showLights(result, problem);
            });
        });
    }

    private void showLights(List<LightScan.Device> found, String error) {
        lit = found;
        litRows.removeAll();
        var lamps = found.stream().filter(LightScan.Device::lamps).count();

        if (error != null) {
            litStatus.setForeground(Theme.ERROR);
            litStatus.setText("Не получилось: " + error);
        } else {
            litStatus.setForeground(lamps > 0 ? Theme.ACCENT : Theme.MUTED);
            litStatus.setText(lamps > 0
                    ? "Windows сама красит устройств: " + lamps + " — открой «Динамическое освещение»."
                    : "Устройств USB: " + found.size() + ". «Динамическое освещение» Windows их не поддерживает.");
        }

        for (var device : found) {
            var row = Ui.transparent(new BorderLayout(10, 0));
            row.setBorder(BorderFactory.createEmptyBorder(5, 2, 5, 2));
            row.setAlignmentX(LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
            var name = Ui.label(device.name(), Theme.ui.deriveFont(device.likelyLit() ? Font.BOLD : Font.PLAIN, 13f),
                    device.likelyLit() ? Theme.TEXT : Theme.MUTED);
            var id = Ui.label(device.id(), Theme.mono.deriveFont(12f), Theme.DIM);
            id.setPreferredSize(new Dimension(90, 18));
            row.add(id, BorderLayout.WEST);
            row.add(name, BorderLayout.CENTER);
            row.add(Ui.label(device.lamps() ? "Windows красит сама" : "OpenRGB или программа",
                    Theme.ui.deriveFont(12f), device.lamps() ? Theme.ACCENT : Theme.DIM), BorderLayout.EAST);
            litRows.add(row);
        }

        litRows.revalidate();
        litRows.repaint();
    }

    /** Параметры → Персонализация → Динамическое освещение. */
    private void openDynamicLighting() {
        try {
            new ProcessBuilder("explorer.exe", "ms-settings:personalization-lighting").start();
        } catch (IOException e) {
            toast.accept("Не открылось: " + e.getMessage());
        }
    }

    private void copyLights() {
        if (lit.isEmpty()) {
            toast.accept("Сначала нажми «Найти».");
            return;
        }

        var text = new StringBuilder();

        for (var device : lit) {
            text.append(device.id()).append("  ").append(device.name())
                    .append(device.lamps() ? "  [Dynamic Lighting]" : "").append('\n');
        }

        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(text.toString()), null);
        toast.accept("Список скопирован — пришли его, и я добавлю твои устройства.");
    }

    private static JComponent inner(String title, JComponent content) {
        var card = new Ui.Card(new BorderLayout(0, 12), INNER);
        card.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        card.add(Ui.caption(title), BorderLayout.NORTH);
        card.add(content, BorderLayout.CENTER);
        return card;
    }

    private static JLabel stat() {
        var label = Ui.label("—", Theme.mono.deriveFont(Font.BOLD, 18f), Theme.TEXT);
        label.setHorizontalAlignment(JLabel.CENTER);
        return label;
    }

    private static JComponent statBox(String caption, JLabel value) {
        var box = Ui.transparent(new BorderLayout(0, 4));
        var title = Ui.muted(caption, 12f);
        title.setHorizontalAlignment(JLabel.CENTER);
        box.add(title, BorderLayout.NORTH);
        box.add(value, BorderLayout.CENTER);
        return box;
    }

    private static JComponent left(JComponent component) {
        component.setAlignmentX(LEFT_ALIGNMENT);
        return component;
    }

    // ----------------------------------------------------------------- обзор

    /** Страницу открыли: подгрузить сведения, запустить живые цифры, проверить серверы. */
    void opened() {
        live.start();
        refreshLoad();

        if (!factsLoaded) {
            factsLoaded = true;
            worker.submit(() -> {
                var hardware = SystemInfo.hardware();
                SwingUtilities.invokeLater(() -> showFacts(hardware));
            });
            checkServers();
        }
    }

    void closed() {
        live.stop();
    }

    private void showFacts(SystemInfo.Hardware hardware) {
        facts.removeAll();
        fact("Имя", hardware.computer());

        if (!hardware.model().isBlank()) {
            fact("Модель", hardware.model());
        }

        fact("Система", hardware.os());
        fact("Процессор", hardware.cpu());
        fact("Потоков", String.valueOf(hardware.cores()));
        fact(hardware.gpus().size() > 1 ? "Видеокарты" : "Видеокарта", String.join("\n", hardware.gpus()));
        fact("Мат. плата", hardware.board());

        if (!hardware.bios().isBlank()) {
            fact("BIOS", hardware.bios());
        }

        fact("Память", SystemInfo.size(hardware.memory())
                + (hardware.ram().isBlank() ? "" : "\n" + hardware.ram()));

        if (!hardware.drives().isEmpty()) {
            fact("Накопители", String.join("\n", hardware.drives()));
        }

        if (!hardware.displays().isEmpty()) {
            fact(hardware.displays().size() > 1 ? "Мониторы" : "Монитор", String.join("\n", hardware.displays()));
        }
        facts.revalidate();
        facts.repaint();
    }

    private void fact(String name, String value) {
        var row = Ui.transparent(new BorderLayout(12, 0));
        var key = Ui.muted(name, 13f);
        key.setPreferredSize(new Dimension(96, 20));
        key.setVerticalAlignment(JLabel.TOP);
        row.add(key, BorderLayout.WEST);
        // Длинные названия переносим по ширине карточки, а не обрезаем: HTML с
        // шириной в пикселях ломался при масштабе Windows 125–150%
        var text = new javax.swing.JTextArea(value);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setEditable(false);
        text.setFocusable(false);
        text.setOpaque(false);
        text.setBorder(null);
        text.setFont(Theme.ui.deriveFont(13.5f));
        text.setForeground(Theme.TEXT);
        row.add(text, BorderLayout.CENTER);
        row.setBorder(BorderFactory.createEmptyBorder(0, 0, 9, 0));
        row.setAlignmentX(LEFT_ALIGNMENT);
        facts.add(row);
    }

    private void refreshLoad() {
        worker.submit(() -> {
            var load = SystemInfo.load();
            SwingUtilities.invokeLater(() -> showLoad(load));
        });
    }

    private void showLoad(SystemInfo.Load load) {
        cpu.set(load.cpu() < 0 ? 0 : load.cpu(), load.cpu() < 0 ? "нет данных" : Math.round(load.cpu() * 100) + "%");

        var used = load.memoryTotal() - load.memoryFree();
        memory.set(load.memoryTotal() == 0 ? 0 : (double) used / load.memoryTotal(),
                SystemInfo.size(used) + " из " + SystemInfo.size(load.memoryTotal()));

        if (disks.getComponentCount() != load.disks().size()) {
            disks.removeAll();

            for (var ignored : load.disks()) {
                var meter = new Meter();
                meter.setAlignmentX(LEFT_ALIGNMENT);
                disks.add(meter);
                disks.add(Box.createVerticalStrut(6));
            }

            disks.revalidate();
        }

        for (var i = 0; i < load.disks().size(); i++) {
            var disk = load.disks().get(i);
            var meter = (Meter) disks.getComponent(i * 2);
            meter.set((double) (disk.total() - disk.free()) / disk.total(),
                    disk.name() + "  свободно " + SystemInfo.size(disk.free()) + " из " + SystemInfo.size(disk.total()));
        }
    }

    private void measureSpeed() {
        measure.setEnabled(false);
        gauge.reset();
        ping.setText("…");
        down.setText("…");
        up.setText("…");

        worker.submit(() -> {
            try {
                var latency = probe.ping();
                SwingUtilities.invokeLater(() -> ping.setText(latency + " мс"));

                var download = probe.download(value -> SwingUtilities.invokeLater(() -> gauge.set(value, "загрузка")));
                SwingUtilities.invokeLater(() -> down.setText(mbit(download)));

                var upload = probe.upload(value -> SwingUtilities.invokeLater(() -> gauge.set(value, "отдача")));
                SwingUtilities.invokeLater(() -> {
                    up.setText(mbit(upload));
                    gauge.set(download, "загрузка");
                });
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> toast.accept("Замер не удался: " + e.getMessage()));
            } finally {
                SwingUtilities.invokeLater(() -> measure.setEnabled(true));
            }
        });
    }

    private static String mbit(double value) {
        return (value < 10 ? String.format("%.1f", value) : String.valueOf(Math.round(value))) + " Мбит/с";
    }

    /** Что проверяем: имя → адрес. */
    private Map<String, String> targets() {
        var list = new LinkedHashMap<String, String>();
        list.put("Интернет (Cloudflare)", "https://1.1.1.1/cdn-cgi/trace");
        list.put("Discord API", "https://discord.com/api/v10/gateway");
        list.put("Discord голос и чат", "https://gateway.discord.gg");
        list.put("Discord файлы и аватарки", "https://cdn.discordapp.com");
        list.put("Telegram API", "https://api.telegram.org");

        var api = bridgeApi.get();

        if (api != null && api.startsWith("http")) {
            list.put("Пересыльщик Telegram", api.replaceAll("/+$", "") + "/");
        }

        return list;
    }

    private void checkServers() {
        check.setEnabled(false);
        var targets = targets();
        servers.removeAll();
        var rows = new ArrayList<ServerRow>();

        for (var name : targets.keySet()) {
            var row = new ServerRow(name);
            rows.add(row);
            servers.add(row);
        }

        servers.revalidate();
        servers.repaint();

        worker.submit(() -> {
            var index = 0;

            for (var url : targets.values()) {
                var result = probe.check(url);
                var row = rows.get(index++);
                SwingUtilities.invokeLater(() -> row.show(result));
            }

            SwingUtilities.invokeLater(() -> check.setEnabled(true));
        });
    }

    /** Строка сервера: точка, имя, задержка или почему нет. */
    private static final class ServerRow extends JPanel {
        private final StatusDot dot = new StatusDot();
        private final JLabel value = Ui.muted("проверяю…", 12.5f);

        ServerRow(String name) {
            super(new BorderLayout(10, 0));
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));
            setAlignmentX(LEFT_ALIGNMENT);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
            add(dot, BorderLayout.WEST);
            add(Ui.text(name), BorderLayout.CENTER);
            add(value, BorderLayout.EAST);
        }

        void show(NetProbe.Result result) {
            dot.color = !result.ok() ? Theme.ERROR : result.ms() > 400 ? Theme.WARN : Theme.ACCENT;
            dot.repaint();
            value.setText(result.ok() ? result.ms() + " мс" : result.note());
            value.setForeground(result.ok() ? Theme.TEXT : Theme.ERROR);
            value.setToolTipText(value.getText());
        }
    }

    private static final class StatusDot extends JComponent {
        Color color = Theme.DIM;

        StatusDot() {
            setPreferredSize(new Dimension(12, 12));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            var g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(color);
            g.fillOval(1, (getHeight() - 10) / 2, 10, 10);
            g.dispose();
        }
    }

    /** Полоска заполненности с подписью. */
    private static final class Meter extends JComponent {
        private double value;
        private String text = "";

        Meter() {
            setPreferredSize(new Dimension(200, 26));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
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
            var h = getHeight();
            g.setColor(Theme.LINE);
            g.fillRoundRect(0, 0, getWidth(), h, 8, 8);
            g.setColor(value > 0.9 ? Theme.alpha(Theme.ERROR, 150) : value > 0.75
                    ? Theme.alpha(Theme.WARN, 140) : Theme.alpha(Theme.ACCENT, 110));
            g.fillRoundRect(0, 0, (int) (getWidth() * value), h, 8, 8);
            g.setFont(Theme.ui.deriveFont(12.5f));
            g.setColor(Theme.TEXT);
            g.drawString(text, 10, (h + g.getFontMetrics().getAscent()) / 2 - 2);
            g.dispose();
        }
    }

    // ----------------------------------------------------------------- подсветка

    private void pick(Color color) {
        swatch.setColor(color);
        settings.color = color;
        settings.save();
    }

    /** Выбранный цвет с учётом яркости. */
    private Color lit() {
        return dim(swatch.color(), brightness.getValue());
    }

    static Color dim(Color color, int brightness) {
        var k = Math.max(0, Math.min(100, brightness)) / 100.0;
        return new Color((int) Math.round(color.getRed() * k), (int) Math.round(color.getGreen() * k),
                (int) Math.round(color.getBlue() * k));
    }

    private void connectRgb() {
        rgbStatus.setText("Подключаюсь…");
        worker.submit(() -> {
            try (var rgb = new OpenRgb(settings.host, OpenRgb.PORT)) {
                var found = rgb.devices();
                SwingUtilities.invokeLater(() -> showDevices(found));
            } catch (IOException | RuntimeException e) {
                SwingUtilities.invokeLater(() -> {
                    rgbStatus.setText(e.getMessage());
                    rgbStatus.setForeground(Theme.ERROR);
                });
            }
        });
    }

    private void showDevices(List<OpenRgb.Device> found) {
        devices = found;
        rgbStatus.setForeground(Theme.ACCENT);
        rgbStatus.setText(found.isEmpty() ? "OpenRGB подключён, но устройств не нашёл."
                : "OpenRGB подключён. Устройств: " + found.size() + ".");
        rgbDevices.removeAll();

        for (var device : found) {
            var row = Ui.transparent(new BorderLayout(10, 0));
            row.setBorder(BorderFactory.createEmptyBorder(8, 2, 8, 2));
            row.setAlignmentX(LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
            var how = device.leds() > 0 ? "светодиодов: " + device.leds()
                    : device.solid() != null ? "цвет режимом «" + device.solid().name() + "»"
                    : "OpenRGB не даёт задать цвет — см. подсказку ниже";
            var name = Ui.label("<html><b>" + ConfigPage.escape(device.name()) + "</b> <font color='#7C8C84'>· "
                    + device.kind() + " · " + ConfigPage.escape(how) + "</font></html>", Theme.ui, Theme.TEXT);
            row.add(name, BorderLayout.CENTER);
            var paint = Ui.button("Покрасить");
            paint.setEnabled(device.paintable());
            paint.addActionListener(e -> paint(List.of(device), lit()));
            row.add(paint, BorderLayout.EAST);
            rgbDevices.add(row);
        }

        var hint = Ui.muted("<html><body style='width:640px'><b>Нет клавиатуры, вентиляторов или памяти?</b><br>"
                + "1. Запусти OpenRGB <b>от имени администратора</b> — без этого он не видит память и часть "
                + "платы.<br>"
                + "2. В OpenRGB: Settings → <b>Supported Devices</b> — проверь, что нужные устройства включены, "
                + "и нажми Rescan.<br>"
                + "3. Вентиляторы, подключённые к ARGB-разъёму платы (часто так подключают JONSBO), — это зона "
                + "«MSI Mystic Light»: выбери её в OpenRGB, нажми <b>Resize</b> и укажи число светодиодов, затем "
                + "сохрани профиль.<br>"
                + "4. Устройства, которых OpenRGB не знает (возможно, Attack Shark X98), смотри в «Все "
                + "устройства с подсветкой» ниже: если там написано «Windows красит сама» — цвет ставится в "
                + "«Динамическом освещении» Windows, иначе — программой производителя.</body></html>", 12.5f);
        hint.setBorder(BorderFactory.createEmptyBorder(12, 2, 4, 2));
        hint.setAlignmentX(LEFT_ALIGNMENT);
        rgbDevices.add(hint);

        rgbDevices.revalidate();
        rgbDevices.repaint();
    }

    private void paintAll(Color color) {
        if (devices.isEmpty()) {
            // Ещё не подключались — подключимся и покрасим разом
            worker.submit(() -> {
                try (var rgb = new OpenRgb(settings.host, OpenRgb.PORT)) {
                    var found = rgb.devices();

                    var painted = paintEach(rgb, found, color);

                    SwingUtilities.invokeLater(() -> {
                        showDevices(found);
                        toast.accept("Покрашено устройств: " + painted + " из " + found.size());
                    });
                } catch (IOException | RuntimeException e) {
                    SwingUtilities.invokeLater(() -> {
                        rgbStatus.setText(e.getMessage());
                        rgbStatus.setForeground(Theme.ERROR);
                    });
                }
            });
            return;
        }

        paint(devices, color);
    }

    private void paint(List<OpenRgb.Device> which, Color color) {
        worker.submit(() -> {
            try (var rgb = new OpenRgb(settings.host, OpenRgb.PORT)) {
                var painted = paintEach(rgb, which, color);
                SwingUtilities.invokeLater(() -> toast.accept("Покрашено устройств: " + painted + " из " + which.size()));
            } catch (IOException | RuntimeException e) {
                SwingUtilities.invokeLater(() -> toast.accept("Не покрасилось: " + e.getMessage()));
            }
        });
    }

    /** Красит, что красится; одно упрямое устройство не мешает остальным. */
    static int paintEach(OpenRgb rgb, List<OpenRgb.Device> which, Color color) throws IOException {
        var painted = 0;

        for (var device : which) {
            if (device.paintable()) {
                rgb.paint(device, color);
                painted++;
            }
        }

        return painted;
    }

    private void launchOpenRgb() {
        var path = settings.openRgb;

        if (path.isBlank() || !Files.isRegularFile(Path.of(path))) {
            var guess = Path.of(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"), "OpenRGB", "OpenRGB.exe");
            path = Files.isRegularFile(guess) ? guess.toString() : choose("Где OpenRGB.exe?");

            if (path == null) {
                return;
            }

            settings.openRgb = path;
            settings.save();
        }

        start(Path.of(path), "--server", "--startminimized");
        rgbStatus.setText("Запускаю OpenRGB… через несколько секунд нажми «Подключиться».");
        rgbStatus.setForeground(Theme.MUTED);
    }

    private void showVendors() {
        vendorRows.removeAll();

        for (var vendor : VENDORS) {
            var path = settings.apps.getOrDefault(vendor, "");
            var row = Ui.transparent(new BorderLayout(10, 0));
            row.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
            row.setAlignmentX(LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));

            var name = Ui.label(vendor, Theme.ui.deriveFont(Font.BOLD, 14f), Theme.TEXT);
            name.setPreferredSize(new Dimension(160, 20));
            row.add(name, BorderLayout.WEST);
            row.add(Ui.label(path.isBlank() ? "программа не указана" : path, Theme.mono.deriveFont(12f),
                    path.isBlank() ? Theme.DIM : Theme.MUTED), BorderLayout.CENTER);

            var choose = Ui.button("Указать…");
            choose.addActionListener(e -> {
                var chosen = choose("Программа " + vendor);

                if (chosen != null) {
                    settings.apps.put(vendor, chosen);
                    settings.save();
                    showVendors();
                }
            });

            var open = Ui.button("Открыть");
            open.setIcon(Icons.of(Icons.Kind.PLAY, Theme.TEXT));
            open.setEnabled(!path.isBlank());
            open.addActionListener(e -> start(Path.of(path)));

            var buttons = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            buttons.add(choose);
            buttons.add(open);
            row.add(buttons, BorderLayout.EAST);
            vendorRows.add(row);
        }

        vendorRows.revalidate();
        vendorRows.repaint();
    }

    private String choose(String title) {
        var chosen = NativeFiles.open(this, title, "device-programs", false, "exe", "lnk");
        return chosen.isEmpty() ? null : chosen.getFirst().toString();
    }

    private void start(Path program, String... args) {
        try {
            if (!SystemInfo.isWindows()) {
                java.awt.Desktop.getDesktop().open(program.toFile());
                return;
            }

            // Ярлык Java открыть не умеет — его открывает Проводник, как по двойному клику
            if (program.toString().toLowerCase().endsWith(".lnk")) {
                new ProcessBuilder("explorer.exe", program.toString()).start();
                return;
            }

            var command = new ArrayList<String>();
            command.add(program.toString());
            command.addAll(List.of(args));
            new ProcessBuilder(command).directory(program.toAbsolutePath().getParent().toFile()).start();
        } catch (IOException | RuntimeException e) {
            toast.accept("Не запустилось: " + e.getMessage());
        }
    }

    private void browse(String url) {
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
        } catch (IOException | RuntimeException e) {
            toast.accept(url);
        }
    }

    /**
     * Цвет при запуске V.I.R.U.S.: ждём OpenRGB до минуты — он мог стартовать
     * вместе с Windows чуть позже нас.
     */
    static void applyOnStart(Path home) {
        var saved = Settings.load(home.resolve("device.json"));

        if (!saved.applyOnStart) {
            return;
        }

        var thread = new Thread(() -> {
            for (var attempt = 0; attempt < 12; attempt++) {
                try (var rgb = new OpenRgb(saved.host, OpenRgb.PORT)) {
                    var color = dim(saved.color, saved.brightness);

                    paintEach(rgb, rgb.devices(), color);
                    return;
                } catch (IOException | RuntimeException e) {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException interrupted) {
                        return;
                    }
                }
            }
        }, "virus-rgb-start");
        thread.setDaemon(true);
        thread.start();
    }

    /** Кружок цвета. */
    private static final class Swatch extends JComponent {
        private Color color = Theme.ACCENT;

        Swatch(int size) {
            setPreferredSize(new Dimension(size, size));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        void setColor(Color color) {
            this.color = color;
            repaint();
        }

        Color color() {
            return color;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            var g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            var size = Math.min(getWidth(), getHeight()) - 2;
            g.setColor(color);
            g.fillOval(1, 1, size, size);
            g.setColor(Theme.LINE_2);
            g.drawOval(1, 1, size, size);
            g.dispose();
        }
    }

    /** Настройки страницы: device.json рядом с virus.jar. */
    static final class Settings {
        Path file;
        String host = "127.0.0.1";
        String openRgb = "";
        Color color = Theme.ACCENT;
        int brightness = 100;
        boolean applyOnStart;
        final Map<String, String> apps = new LinkedHashMap<>();

        static Settings load(Path file) {
            var settings = new Settings();
            settings.file = file;

            try {
                if (Files.isRegularFile(file)) {
                    var json = Json.object(Files.readString(file, StandardCharsets.UTF_8));
                    settings.host = String.valueOf(json.getOrDefault("host", settings.host));
                    settings.openRgb = String.valueOf(json.getOrDefault("openRgb", ""));
                    settings.color = Color.decode(String.valueOf(json.getOrDefault("color", "#39FF8A")));
                    settings.brightness = json.get("brightness") instanceof Number n ? n.intValue() : 100;
                    settings.applyOnStart = Boolean.TRUE.equals(json.get("applyOnStart"));

                    if (json.get("apps") instanceof Map<?, ?> apps) {
                        apps.forEach((key, value) -> settings.apps.put(String.valueOf(key), String.valueOf(value)));
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // испорченный файл — начинаем с умолчаний
            }

            return settings;
        }

        void save() {
            var json = new LinkedHashMap<String, Object>();
            json.put("host", host);
            json.put("openRgb", openRgb);
            json.put("color", String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue()));
            json.put("brightness", brightness);
            json.put("applyOnStart", applyOnStart);
            json.put("apps", apps);

            try {
                Files.writeString(file, Json.write(json), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // не записалось — в следующий раз попробуем снова
            }
        }
    }
}
