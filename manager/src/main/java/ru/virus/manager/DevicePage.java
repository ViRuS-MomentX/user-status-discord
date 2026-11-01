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
    private final JPanel appRows = Ui.transparent(null);
    private Map<String, String> startApps = Map.of();
    private boolean appsChecked;
    private final JLabel litStatus = Ui.muted("Нажми «Найти», чтобы увидеть, кто чем красится.", 12.5f);
    private List<LightScan.Device> lit = List.of();
    private List<OpenRgb.Device> devices = List.of();

    // SignalRGB
    private final SignalRgb signal = new SignalRgb();
    private final SignalRgb.Live glow;
    private final JLabel signalStatus = Ui.muted("Не подключено.", 13f);
    private final Ui.Switch signalOn = new Ui.Switch(true);
    private final JSlider signalBrightness = new JSlider(0, 100, 100);
    /** Направление и масштаб узора — полями, чтобы профиль мог их поставить. */
    private final Ui.Switch reverseSwitch = new Ui.Switch(false);
    private final JSlider scaleSlider = new JSlider(10, 40, 10);
    private final JPanel modeTiles = Ui.transparent(null);
    /** Кнопки режимов по номеру — чтобы профиль мог подсветить свою. */
    private final Map<Integer, JToggleButton> tileById = new LinkedHashMap<>();

    /** Группы режимов, как в библиотеке SignalRGB: подпись → номера. */
    static final Map<String, List<Integer>> MODE_GROUPS = groups();

    /** Пара цветов для двухцветных режимов. */
    record Palette(String name, Color a, Color b) {
    }

    /** Готовые пары — по мотивам палитр WLED и пресетов SignalRGB. */
    static final List<Palette> PALETTES = List.of(
            new Palette("Закат", new Color(0xFF3A00), new Color(0x9B00FF)),
            new Palette("Океан", new Color(0x00E5FF), new Color(0x0030FF)),
            new Palette("Лес", new Color(0x00FF40), new Color(0xB8FF00)),
            new Palette("Лава", new Color(0xFF0000), new Color(0xFF9000)),
            new Palette("Неон", new Color(0xFF00C8), new Color(0x00F0FF)),
            new Palette("Лёд", new Color(0xFFFFFF), new Color(0x00A0FF)),
            new Palette("Киберпанк", new Color(0xFFE600), new Color(0xFF0050)),
            new Palette("V.I.R.U.S.", new Color(0x39FF8A), new Color(0x8A2BE2)));

    /** Значок палитры: две половинки цвета. */
    private record PaletteIcon(Color a, Color b) implements javax.swing.Icon {
        @Override
        public void paintIcon(java.awt.Component c, java.awt.Graphics g, int x, int y) {
            for (var i = 0; i < 28; i++) {
                g.setColor(Glow.mix(a, b, i / 27.0));
                g.fillRect(x + i, y, 1, 12);
            }
        }

        @Override
        public int getIconWidth() {
            return 28;
        }

        @Override
        public int getIconHeight() {
            return 12;
        }
    }

    private static Map<String, List<Integer>> groups() {
        var groups = new LinkedHashMap<String, List<Integer>>();
        groups.put("Спокойные", List.of(0, 19, 20, 1, 4, 8, 11));
        groups.put("Динамичные", List.of(2, 3, 5, 6, 7, 9, 14, 15, 16, 17));
        groups.put("Реагируют", List.of(12, 13, 21, 10, 18));
        return java.util.Collections.unmodifiableMap(groups);
    }
    private final JPanel profileRow = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
    /** Профиль, который включила программа из правил; null — включён вручную. */
    private String autoProfile;
    /** Что было до того, как программа переключила профиль, — вернуть, когда она закроется. */
    private LightProfiles.Profile beforeAuto;
    private final JPanel effectTiles = new Ui.FitWidth();
    private final javax.swing.JTextField effectFilter = new javax.swing.JTextField();
    private final Swatch glowColor = new Swatch(30);
    private final Swatch glowColor2 = new Swatch(30);
    private final JSlider glowSpeed = new JSlider(1, 100, 50);
    private final Timer urlFallback;
    private List<SignalRgb.Effect> effects = List.of();
    private String currentEffect = "";
    private boolean signalQuiet;
    /** API SignalRGB закрыт (403): эффекты — ссылками, яркость — внутри эффекта VIRUS. */
    private boolean signalLocked;
    private List<String> diskEffects = List.of();
    private JComponent signalArrows;
    /** Режимы через OpenRGB: V.I.R.U.S. сам считает кадры — бесплатно и для вентиляторов на плате. */
    private final RgbAnimator animator;
    private final JLabel animatorStatus = Ui.muted("", 13f);
    private final JPanel signalOnly = Ui.transparent(null);
    private final JPanel openRgbOnly = Ui.transparent(null);
    private JToggleButton viaOpenRgb;
    private JToggleButton viaSignal;

    /**
     * Подсветка временно убрана из интерфейса: вкладки «Кастомизация» нет,
     * режимы не запускаются, порт для эффекта SignalRGB не открывается. Код
     * остаётся — вернуть всё можно, поставив true.
     */
    static final boolean LIGHTING = true;

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
        rotateSpinner.setValue(settings.rotateMinutes);
        this.glow = new SignalRgb.Live(DevicePage::cpuLoad);
        glow.mode = settings.glowMode;
        glow.speed = settings.glowSpeed;
        glow.color = settings.glowColor;
        glow.color2 = settings.glowColor2;
        glow.reverse = settings.glowReverse;
        glow.scale = Math.max(1, Math.min(4, settings.glowScale));

        if ("openrgb".equals(settings.engine)) {
            glow.on = settings.glowOn;
            glow.brightness = settings.glowBrightness;
        }

        try {
            // Эффект «VIRUS» в SignalRGB спрашивает настройки здесь — всё время, пока открыт V.I.R.U.S.
            if (LIGHTING) {
                glow.start(SignalRgb.OUR_PORT);
            }
        } catch (IOException e) {
            // порт занят — значит, V.I.R.U.S. уже запущен ещё раз; эффект спросит у того
        }

        urlFallback = new Timer(700, e -> pushByUrl());
        urlFallback.setRepeats(false);
        animator = new RgbAnimator(glow, text -> SwingUtilities.invokeLater(() -> {
            // Длинный список «не горят» переносится, а не обрезается многоточием
            animatorStatus.setText("<html><body style='width:640px'>" + ConfigPage.escape(text) + "</body></html>");
            animatorStatus.setToolTipText(text);
        }));
        animator.zoneSizes = Map.copyOf(settings.zoneSizes);
        animator.levels = Map.copyOf(settings.deviceLevels);
        animator.onDevices = list -> SwingUtilities.invokeLater(() -> {
            // Тот же состав — список не перестраиваем, чтобы не сбивать то, что сейчас крутишь
            if (!sameDevices(list, devices)) {
                showDevices(list);
            }
        });
        animator.modes = Map.copyOf(settings.deviceModes);
        animator.canvas = settings.canvas;
        animator.order = List.copyOf(settings.canvasOrder);
        // OpenRGB закрыли или он ещё не стартовал после перезагрузки — поднимаем его задачей, без окна UAC
        animator.whenMissing = () -> {
            if (settings.openRgbTask) {
                OpenRgbTask.start();
            }
        };
        setBorder(BorderFactory.createEmptyBorder(20, 22, 20, 22));
        Ui.rippleThrough(this, background);

        add(header(), BorderLayout.NORTH);
        body.add(overview(), "overview");

        if (LIGHTING) {
            body.add(customization(), "custom");
        }

        add(body, BorderLayout.CENTER);

        if (LIGHTING) {
            new Timer(5000, e -> {
                watchPrograms();
                updateDim();
                rotate();
            }).start();
            new Timer(10_000, e -> followAccent()).start();
        }

        if (LIGHTING && openRgbEngine()) {
            // Режим, выбранный в прошлый раз, играет сразу с запуском V.I.R.U.S.
            animator.start(settings.host);
        }

        if (LIGHTING) {
            Runtime.getRuntime().addShutdownHook(new Thread(this::farewell, "virus-rgb-farewell"));
        }

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
        titles.add(Ui.muted(LIGHTING ? "Компьютер, интернет, связь с Discord и Telegram — и подсветка."
                        : "Компьютер, интернет и связь с Discord и Telegram.", 13f),
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

        // Одна вкладка — переключатель не нужен
        if (LIGHTING) {
            head.add(chips, BorderLayout.EAST);
        }
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
                + "устройств разных фирм. Запусти её (кнопка справа) и нажми «Подключиться» — здесь появятся "
                + "устройства: у каждого своя яркость, режим, порядок цветов и проверка. Сам цвет и режим — "
                + "в «Режимах подсветки» выше.</body></html>", 13f));

        var devicesScroll = new JScrollPane(rgbDevices);
        devicesScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        devicesScroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.LINE));
        devicesScroll.setOpaque(false);
        devicesScroll.getViewport().setOpaque(false);
        Ui.passWheel(devicesScroll);

        var rgbTop = Ui.transparent(null);
        rgbTop.setLayout(new BoxLayout(rgbTop, BoxLayout.Y_AXIS));
        rgbTop.add(left(statusRow));
        rgbTop.add(Box.createVerticalStrut(12));
        // Цвет и режим задаются в одном месте — наверху (статичный цвет — режим «Статичный»);
        // здесь — только устройства и их настройки, как список устройств у iCUE и SignalRGB

        var rgbBody = Ui.transparent(new BorderLayout());
        rgbBody.add(rgbTop, BorderLayout.NORTH);
        rgbBody.add(devicesScroll, BorderLayout.CENTER);
        var rgb = inner("Устройства OpenRGB", rgbBody);

        // Программы производителей
        vendorRows.setLayout(new BoxLayout(vendorRows, BoxLayout.Y_AXIS));
        showVendors();
        var vendorBody = Ui.transparent(new BorderLayout(0, 8));
        vendorBody.add(vendorRows, BorderLayout.CENTER);
        vendorBody.add(Ui.muted("Пока программа производителя работает, она может перебивать цвет "
                + "из OpenRGB — закрой её, если красишь отсюда.", 12.5f), BorderLayout.SOUTH);
        var vendors = inner("Программы производителей", vendorBody);

        // Карточек стало много — страница прокручивается, а не сплющивает их
        devicesScroll.setPreferredSize(new Dimension(10, 200));
        var page = new Ui.FitWidth();
        page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));

        for (var card : List.of(signalCard(), rgb, apps(), lighting(), vendors)) {
            card.setAlignmentX(LEFT_ALIGNMENT);
            page.add(card);
            page.add(Box.createVerticalStrut(14));
        }

        var scroll = new JScrollPane(page);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(22);
        return scroll;
    }

    /** Сглаженная загрузка и когда её мерили в последний раз. */
    private static double cpuSmooth;
    private static long cpuAt;

    /**
     * Загрузка процессора для режима «Нагрузка ЦП». Мерить её каждый кадр
     * (30 раз в секунду) нельзя: на таких коротких отрезках Windows отдаёт
     * шум, и цвет скачет зелёный↔красный. Как датчики в iCUE: замер раз в
     * полсекунды и плавное сглаживание.
     */
    private static synchronized double cpuLoad() {
        var now = System.currentTimeMillis();

        if (now - cpuAt >= CPU_PERIOD_MS) {
            cpuAt = now;
            var raw = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                    instanceof com.sun.management.OperatingSystemMXBean bean ? bean.getCpuLoad() : 0;
            cpuSmooth = smoothLoad(cpuSmooth, raw);
        }

        return cpuSmooth;
    }

    static final long CPU_PERIOD_MS = 500;

    /** Следующее сглаженное значение; неизмеренное (−1, NaN) пропускаем. */
    static double smoothLoad(double previous, double raw) {
        if (Double.isNaN(raw) || raw < 0) {
            return previous;
        }

        return previous + (Math.min(1, raw) - previous) * 0.35;
    }

    /**
     * SignalRGB без его окна: режимы V.I.R.U.S. (свой эффект «VIRUS»), все
     * эффекты SignalRGB, яркость и вкл/выкл через его локальный API.
     */
    private JComponent signalCard() {
        var connect = Ui.button("Подключиться");
        connect.setIcon(Icons.of(Icons.Kind.RESTART, Theme.TEXT));
        connect.addActionListener(e -> connectSignal(true));
        var restart = Ui.ghost("Перезапустить SignalRGB");
        restart.setToolTipText("Нужно один раз после установки эффекта VIRUS — чтобы SignalRGB его увидел");
        restart.addActionListener(e -> restartSignal());
        var previous = Ui.ghost("Пред.");
        previous.setToolTipText("Предыдущий эффект");
        previous.addActionListener(e -> signalCall(signal::previous));
        var next = Ui.ghost("След.");
        next.setToolTipText("Следующий эффект");
        next.addActionListener(e -> signalCall(signal::next));

        var statusRow = Ui.transparent(new BorderLayout(10, 0));
        statusRow.add(signalStatus, BorderLayout.CENTER);
        var statusButtons = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        statusButtons.add(restart);
        statusButtons.add(connect);
        statusRow.add(statusButtons, BorderLayout.EAST);

        signalOn.onChange(on -> {
            SwingUtilities.invokeLater(this::fillTrayMenu);
            if (openRgbEngine()) {
                glow.on = on;
                settings.glowOn = on;
                settings.save();
            } else if (signalLocked) {
                glow.on = on;
                glowNudge();
            } else {
                signalCall(() -> signal.enabled(on));
            }
        });
        signalBrightness.setOpaque(false);
        signalBrightness.setPreferredSize(new Dimension(170, 28));
        signalBrightness.addChangeListener(e -> {
            if (openRgbEngine()) {
                glow.brightness = signalBrightness.getValue();
                settings.glowBrightness = glow.brightness;

                if (!signalBrightness.getValueIsAdjusting()) {
                    settings.save();
                }
            } else if (signalLocked) {
                glow.brightness = signalBrightness.getValue();

                if (!signalBrightness.getValueIsAdjusting()) {
                    glowNudge();
                }
            } else if (!signalBrightness.getValueIsAdjusting() && !signalQuiet) {
                var value = signalBrightness.getValue();
                signalCall(() -> signal.brightness(value));
            }
        });
        var powerRow = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 10, 0));
        powerRow.add(signalOn);
        powerRow.add(Ui.muted("подсветка", 13f));
        powerRow.add(Box.createHorizontalStrut(12));
        powerRow.add(Ui.muted("Яркость", 13f));
        powerRow.add(signalBrightness);
        powerRow.add(Box.createHorizontalStrut(12));
        sleepButton.setIcon(Icons.of(Icons.Kind.POWER, Theme.TEXT));
        sleepButton.addActionListener(e -> {
            var menu = new javax.swing.JPopupMenu();

            for (var minutes : List.of(15, 30, 60, 90)) {
                var item = new javax.swing.JMenuItem("Погасить плавно за " + minutes + " мин");
                item.addActionListener(a -> sleepIn(minutes));
                menu.add(item);
            }

            if (sleepUntil > 0) {
                menu.addSeparator();
                var cancel = new javax.swing.JMenuItem("Отменить таймер");
                cancel.addActionListener(a -> sleepIn(0));
                menu.add(cancel);
            }

            menu.show(sleepButton, 0, sleepButton.getHeight());
        });
        powerRow.add(sleepButton);
        powerRow.add(Box.createHorizontalStrut(12));
        var arrows = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
        arrows.add(previous);
        arrows.add(next);
        signalArrows = arrows;
        powerRow.add(arrows);

        // Режимы V.I.R.U.S.
        preview = new GlowPreview(glow);
        var group = new ButtonGroup();
        modeTiles.setLayout(new BoxLayout(modeTiles, BoxLayout.Y_AXIS));

        for (var entry : MODE_GROUPS.entrySet()) {
            var caption = Ui.muted(entry.getKey(), 12f);
            caption.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 0));
            modeTiles.add(left(caption));
            var grid = Ui.transparent(new GridLayout(0, 6, 8, 8));

            for (var id : entry.getValue()) {
                var mode = SignalRgb.mode(id);
                var tile = new JToggleButton(mode.name());
                tile.setFont(Theme.ui.deriveFont(13f));
                tile.setFocusable(false);
                // Узкие поля, чтобы длинные названия («Северное сияние») не обрезались многоточием
                tile.setMargin(new java.awt.Insets(3, 4, 3, 4));
                tile.setToolTipText("<html><body style='width:240px'><b>" + mode.name() + "</b><br>"
                        + SignalRgb.describe(id) + "</body></html>");
                tile.setSelected(mode.id() == glow.mode);
                tile.addActionListener(e -> chooseMode(mode));
                tile.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseEntered(MouseEvent e) {
                        preview.peek(mode.id());
                    }

                    @Override
                    public void mouseExited(MouseEvent e) {
                        preview.peek(-1);
                    }
                });
                group.add(tile);
                tileById.put(id, tile);
                grid.add(tile);
            }

            modeTiles.add(left(grid));
        }

        glowColor.setColor(glow.color);
        glowColor.setToolTipText("Цвет режима");
        glowColor.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                var chosen = JColorChooser.showDialog(DevicePage.this, "Цвет", glow.color);

                if (chosen != null) {
                    chooseColors(chosen, glow.color2);
                }
            }
        });
        glowColor2.setColor(glow.color2);
        glowColor2.setToolTipText("Второй цвет — для «Переливов» и «Звёзд»");
        glowColor2.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                var chosen = JColorChooser.showDialog(DevicePage.this, "Второй цвет", glow.color2);

                if (chosen != null) {
                    glow.color2 = chosen;
                    glowColor2.setColor(chosen);
                    glowChanged();
                }
            }
        });

        var presets = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));

        for (var preset : PRESETS) {
            var small = new Swatch(20);
            small.setColor(preset);
            small.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    chooseColors(preset, glow.color2);
                }
            });
            presets.add(small);
        }

        glowSpeed.setValue(glow.speed);
        glowSpeed.setOpaque(false);
        glowSpeed.setPreferredSize(new Dimension(150, 28));
        glowSpeed.addChangeListener(e -> {
            glow.speed = glowSpeed.getValue();

            if (!glowSpeed.getValueIsAdjusting()) {
                glowChanged();
            }
        });

        var colorRow = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 10, 0));
        colorRow.add(Ui.muted("Цвет", 13f));
        colorRow.add(glowColor);
        colorRow.add(presets);
        var accent = accentSwitch;
        accent.setOn(settings.followAccent);
        accent.setToolTipText("Основной цвет — цвет оформления Windows, как в «Динамическом освещении»");
        accent.onChange(on -> {
            settings.followAccent = on;
            settings.save();
            followAccent();
        });
        colorRow.add(accent);
        colorRow.add(Ui.muted("как в Windows", 13f));
        colorRow.add(Ui.muted("Второй", 13f));
        colorRow.add(glowColor2);

        // Готовые пары, как палитры WLED: подобраны так, чтобы градиент и переливы смотрелись
        var palettes = Ui.ghost("Палитры");
        palettes.setToolTipText("Готовые пары цветов для двухцветных режимов");
        palettes.addActionListener(e -> {
            var menu = new javax.swing.JPopupMenu();

            for (var palette : PALETTES) {
                var item = new javax.swing.JMenuItem(palette.name(), new PaletteIcon(palette.a(), palette.b()));
                item.addActionListener(a -> chooseColors(palette.a(), palette.b()));
                menu.add(item);
            }

            menu.show(palettes, 0, palettes.getHeight());
        });
        colorRow.add(palettes);
        var patternRow = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 10, 0));
        patternRow.add(Ui.muted("Узор: скорость", 13f));
        patternRow.add(glowSpeed);

        // Направление и масштаб узора — как Reverse в WLED и Scale в SignalRGB
        var reverse = reverseSwitch;
        reverse.setOn(glow.reverse);
        reverse.setToolTipText("Узор бежит в другую сторону");
        reverse.onChange(on -> {
            glow.reverse = on;
            settings.glowReverse = on;
            settings.save();
        });
        var scale = scaleSlider;
        scale.setValue((int) Math.round(glow.scale * 10));
        scale.setOpaque(false);
        scale.setPreferredSize(new Dimension(90, 28));
        scale.setToolTipText("Сколько раз узор помещается на устройстве: 1 — одна радуга на всё, 4 — четыре коротких");
        scale.addChangeListener(e -> {
            glow.scale = scale.getValue() / 10.0;
            settings.glowScale = glow.scale;

            if (!scale.getValueIsAdjusting()) {
                settings.save();
            }
        });
        patternRow.add(reverse);
        patternRow.add(Ui.muted("в обратную сторону", 13f));
        patternRow.add(Ui.muted("масштаб", 13f));
        patternRow.add(scale);

        // Для «Экрана» — какой монитор повторять; выбор виден, только когда мониторов больше одного
        var monitors = ScreenSampler.monitorNames();
        ScreenSampler.monitor = settings.screenMonitor;

        if (monitors.size() > 1) {
            var choices = new java.util.ArrayList<String>();
            choices.add("основной монитор");
            choices.addAll(monitors);
            var monitor = new javax.swing.JComboBox<>(choices.toArray(String[]::new));
            monitor.setSelectedIndex(settings.screenMonitor >= 0 && settings.screenMonitor < monitors.size()
                    ? settings.screenMonitor + 1 : 0);
            monitor.setToolTipText("Какой монитор повторяет режим «Экран»");
            monitor.addActionListener(e -> {
                settings.screenMonitor = monitor.getSelectedIndex() - 1;
                ScreenSampler.monitor = settings.screenMonitor;
                settings.save();
            });
            patternRow.add(Box.createHorizontalStrut(12));
            patternRow.add(Ui.muted("«Экран»:", 13f));
            patternRow.add(monitor);
        }

        // Все эффекты SignalRGB
        effectTiles.setLayout(new GridLayout(0, 4, 8, 8));
        effectTiles.add(Ui.muted("Нажми «Подключиться»", 12.5f));
        effectFilter.putClientProperty("JTextField.placeholderText", "поиск эффекта");
        effectFilter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                showEffects();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                showEffects();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                showEffects();
            }
        });
        effectFilter.setPreferredSize(new Dimension(220, effectFilter.getPreferredSize().height));
        var effectsHead = Ui.transparent(new BorderLayout(10, 0));
        effectsHead.add(Ui.caption("Эффекты SignalRGB"), BorderLayout.WEST);
        effectsHead.add(effectFilter, BorderLayout.EAST);
        var effectsScroll = new JScrollPane(effectTiles);
        effectsScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        effectsScroll.setBorder(null);
        effectsScroll.setOpaque(false);
        effectsScroll.getViewport().setOpaque(false);
        effectsScroll.setPreferredSize(new Dimension(10, 150));
        Ui.passWheel(effectsScroll);

        var stack = Ui.transparent(null);
        stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
        // Чем играть режимы: OpenRGB (бесплатно, всё что он видит) или SignalRGB
        viaOpenRgb = new JToggleButton("Через OpenRGB — бесплатно");
        viaSignal = new JToggleButton("Через SignalRGB");
        var engines = new ButtonGroup();

        for (var button : List.of(viaOpenRgb, viaSignal)) {
            button.setFont(Theme.ui.deriveFont(13f));
            button.setFocusable(false);
            engines.add(button);
        }

        viaOpenRgb.setSelected(openRgbEngine());
        viaSignal.setSelected(!openRgbEngine());
        viaOpenRgb.addActionListener(e -> useEngine("openrgb"));
        viaSignal.addActionListener(e -> useEngine("signalrgb"));
        var engineRow = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        engineRow.add(viaOpenRgb);
        engineRow.add(viaSignal);

        var startOpenRgb = Ui.button("Запустить OpenRGB");
        startOpenRgb.setIcon(Icons.of(Icons.Kind.PLAY, Theme.TEXT));
        startOpenRgb.addActionListener(e -> launchOpenRgb());
        var stopAnimation = Ui.ghost("Остановить");
        stopAnimation.addActionListener(e -> {
            animator.stop();
            animatorStatus.setText("Остановлено. Выбери режим — запустится снова.");
        });
        openRgbOnly.setLayout(new BorderLayout(10, 0));
        openRgbOnly.add(animatorStatus, BorderLayout.CENTER);
        var openRgbButtons = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        openRgbButtons.add(stopAnimation);
        openRgbButtons.add(startOpenRgb);
        openRgbOnly.add(openRgbButtons, BorderLayout.EAST);
        var atLogon = new Ui.Switch(settings.openRgbTask);
        atLogon.onChange(this::toggleOpenRgbTask);
        var logonRow = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        logonRow.add(atLogon);
        logonRow.add(Ui.muted("OpenRGB сам при входе в Windows, с правами администратора (разрешение спросит один раз)",
                12.5f));
        openRgbOnly.add(logonRow, BorderLayout.SOUTH);
        animatorStatus.setText(openRgbEngine() ? "Жду OpenRGB…" : "");

        signalOnly.setLayout(new BoxLayout(signalOnly, BoxLayout.Y_AXIS));
        signalOnly.add(left(statusRow));

        stack.add(left(engineRow));
        stack.add(Box.createVerticalStrut(12));
        stack.add(left(openRgbOnly));
        stack.add(left(signalOnly));
        stack.add(Box.createVerticalStrut(12));
        stack.add(left(powerRow));
        stack.add(Box.createVerticalStrut(14));
        stack.add(left(Ui.caption("Режимы V.I.R.U.S.")));
        stack.add(Box.createVerticalStrut(6));
        stack.add(left(modeTiles));
        stack.add(Box.createVerticalStrut(10));
        stack.add(left(preview));
        stack.add(Box.createVerticalStrut(10));
        stack.add(left(profileRow));
        // Автосмена — своей строкой: с несколькими профилями в одну не помещается
        var rotateRow = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
        rotateRow.add(Ui.muted("Менять режим сам каждые", 13f));
        rotateRow.add(rotateSpinner);
        rotateRow.add(Ui.muted("мин (0 — нет), по кругу спокойные и динамичные", 13f));
        stack.add(Box.createVerticalStrut(6));
        stack.add(left(rotateRow));
        showProfiles();
        stack.add(Box.createVerticalStrut(10));
        stack.add(left(colorRow));
        stack.add(Box.createVerticalStrut(10));
        stack.add(left(patternRow));
        stack.add(Box.createVerticalStrut(10));
        stack.add(left(colorCorrection()));
        stack.add(Box.createVerticalStrut(10));
        stack.add(left(scheduleRow()));
        stack.add(Box.createVerticalStrut(10));
        var canvasSwitch = new Ui.Switch(settings.canvas);
        canvasSwitch.onChange(on -> {
            settings.canvas = on;
            settings.save();
            animator.canvas = on;
            restartAnimator();

            if (!devices.isEmpty()) {
                showDevices(devices);
            }
        });
        var canvasRow = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        canvasRow.add(canvasSwitch);
        canvasRow.add(Ui.muted("единый холст: эффект перетекает с устройства на устройство (порядок — «Левее/Правее» "
                + "в списке устройств)", 13f));
        stack.add(left(canvasRow));
        stack.add(Box.createVerticalStrut(10));
        var hardware = Ui.button("Записать цвет в устройства");
        hardware.setToolTipText("Основной цвет — в память самих устройств: будет гореть и при загрузке, и без V.I.R.U.S. "
                + "с OpenRGB (если устройство умеет)");
        hardware.addActionListener(e -> saveToHardware());
        var hardwareRow = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        hardwareRow.add(hardware);
        hardwareRow.add(Ui.muted("как Hardware Lighting в iCUE: цвет остаётся, даже когда программы выключены", 12.5f));
        stack.add(left(hardwareRow));
        stack.add(Box.createVerticalStrut(10));
        var signalsSwitch = new Ui.Switch(settings.signals);
        signalsSwitch.onChange(on -> {
            settings.signals = on;
            settings.save();

            if (on) {
                glow.flash(Theme.ACCENT);
            }
        });
        var signalsRow = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        signalsRow.add(signalsSwitch);
        signalsRow.add(Ui.muted("мигать о событиях: красным — упал процесс, оранжевым — поломка, зелёным — встал плагин",
                12.5f));
        stack.add(left(signalsRow));
        signalOnly.add(Box.createVerticalStrut(16));
        signalOnly.add(left(effectsHead));
        signalOnly.add(Box.createVerticalStrut(6));
        signalOnly.add(left(effectsScroll));
        showEngine();

        worker.submit(this::installEffect);
        return inner("Режимы подсветки V.I.R.U.S.", stack);
    }

    /** Положить эффект «VIRUS» в SignalRGB. @return true — положили только что */
    private boolean installEffect() {
        try {
            return SystemInfo.isWindows() && SignalRgb.install(SignalRgb.effectsFolder());
        } catch (IOException e) {
            SwingUtilities.invokeLater(() -> toast.accept("Эффект VIRUS не записался: " + e.getMessage()));
            return false;
        }
    }

    private void connectSignal(boolean loud) {
        signalStatus.setForeground(Theme.MUTED);
        signalStatus.setText("Подключаюсь к SignalRGB…");

        worker.submit(() -> {
            try {
                var found = signal.effects();
                var state = signal.state();
                SwingUtilities.invokeLater(() -> showSignal(found, state));
            } catch (SignalRgb.Locked e) {
                // Запущен, но API закрыт: эффекты найдём на диске, включать будем ссылками
                var found = SignalRgb.localEffects(SignalRgb.effectRoots());
                SwingUtilities.invokeLater(() -> showLocked(found));
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> {
                    signalStatus.setForeground(Theme.ERROR);
                    signalStatus.setText(e.getMessage() + ". Запусти его — окно можно свернуть.");

                    if (loud) {
                        toast.accept("SignalRGB не отвечает — он запущен?");
                    }
                });
            }
        });
    }

    private void showLocked(List<String> found) {
        signalLocked = true;
        diskEffects = found;
        signalArrows.setVisible(false);
        signalStatus.setForeground(Theme.ACCENT);
        signalStatus.setText("<html>SignalRGB найден. Его API закрыт (так в бесплатной версии), поэтому эффекты "
                + "включаются ссылками SignalRGB, а яркость и вкл/выкл действуют на режимы V.I.R.U.S.</html>");
        signalQuiet = true;
        signalOn.setOn(glow.on);
        signalBrightness.setValue(glow.brightness);
        signalQuiet = false;
        showEffects();
    }

    private void showSignal(List<SignalRgb.Effect> found, SignalRgb.State state) {
        signalLocked = false;
        signalArrows.setVisible(true);
        effects = found;
        currentEffect = state.effectId();
        var name = state.effectName().isBlank()
                ? found.stream().filter(effect -> effect.id().equals(state.effectId())).map(SignalRgb.Effect::name)
                        .findFirst().orElse("—")
                : state.effectName();
        var hasOurs = found.stream().anyMatch(effect -> effect.name().equals(SignalRgb.EFFECT));
        signalStatus.setForeground(Theme.ACCENT);
        signalStatus.setText("SignalRGB подключён. Эффектов: " + found.size() + ". Сейчас: " + name
                + (hasOurs ? "" : " · эффекта VIRUS пока нет — нажми «Перезапустить SignalRGB»"));
        signalQuiet = true;
        signalOn.setOn(state.enabled());
        signalBrightness.setValue(state.brightness());
        signalQuiet = false;
        showEffects();
    }

    private void showEffects() {
        effectTiles.removeAll();
        var filter = effectFilter.getText().strip().toLowerCase(java.util.Locale.ROOT);

        if (signalLocked) {
            for (var name : diskEffects) {
                if (!filter.isEmpty() && !name.toLowerCase(java.util.Locale.ROOT).contains(filter)) {
                    continue;
                }

                var tile = Ui.button(name);
                tile.setToolTipText(name);
                tile.addActionListener(e -> openSignalUrl(SignalRgb.applyUrl(name)));
                effectTiles.add(tile);
            }

            if (diskEffects.isEmpty()) {
                effectTiles.add(Ui.muted("Эффекты на диске не нашлись", 12.5f));
            }

            effectTiles.revalidate();
            effectTiles.repaint();
            return;
        }

        for (var effect : effects) {
            if (effect.name().equals(SignalRgb.EFFECT) || !filter.isEmpty()
                    && !effect.name().toLowerCase(java.util.Locale.ROOT).contains(filter)) {
                continue;
            }

            var tile = effect.id().equals(currentEffect) ? Ui.primary(effect.name()) : Ui.button(effect.name());
            tile.setToolTipText(effect.name());
            tile.addActionListener(e -> applyEffect(effect));
            effectTiles.add(tile);
        }

        effectTiles.revalidate();
        effectTiles.repaint();
    }

    private void applyEffect(SignalRgb.Effect effect) {
        worker.submit(() -> {
            try {
                signal.apply(effect);
                var state = signal.state();
                SwingUtilities.invokeLater(() -> showSignal(effects, state));
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> toast.accept("SignalRGB: " + e.getMessage()));
            }
        });
    }

    private interface SignalAction {
        void run() throws IOException;
    }

    private void signalCall(SignalAction action) {
        if (signalQuiet) {
            return;
        }

        worker.submit(() -> {
            try {
                action.run();
                var state = signal.state();
                SwingUtilities.invokeLater(() -> showSignal(effects, state));
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> toast.accept("SignalRGB: " + e.getMessage()));
            }
        });
    }

    private java.awt.Point lastPointer;
    private long lastActivity = System.currentTimeMillis();

    /** Цвет как в Windows: поменялся акцент — поменялся и основной цвет подсветки. */
    private void followAccent() {
        if (!settings.followAccent) {
            return;
        }

        worker.submit(() -> {
            var accent = WindowsAccent.read();

            if (accent != null && !accent.equals(glow.color)) {
                SwingUtilities.invokeLater(() -> {
                    glow.color = accent;
                    glowColor.setColor(accent);
                    glowChanged();
                });
            }
        });
    }

    /** Ночь и простой — во множитель яркости; режим плавно перетечёт сам. */
    private void updateDim() {
        try {
            var pointer = java.awt.MouseInfo.getPointerInfo().getLocation();

            if (!pointer.equals(lastPointer)) {
                lastPointer = pointer;
                lastActivity = System.currentTimeMillis();
            }
        } catch (RuntimeException e) {
            // нет мыши или экран заблокирован — считаем, что хозяин здесь
            lastActivity = System.currentTimeMillis();
        }

        // Простой — как его считает Windows (любой ввод); не вышло узнать — по мыши
        var idle = System.currentTimeMillis() - lastActivity;

        if (settings.idleMinutes > 0) {
            var system = IdleWatch.seconds();

            if (system >= 0) {
                idle = system * 1000;
            }
        }

        // Экран заблокирован — хозяин ушёл: гасим, как SignalRGB и iCUE; разблокировал — вернётся само
        if (settings.offWhenLocked && IdleWatch.locked()) {
            idle = Long.MAX_VALUE;
        }

        var dim = dimFor(settings, java.time.LocalTime.now().getHour(), idle);

        if (sleepUntil > 0) {
            var left = sleepLevel(sleepFrom, sleepUntil, System.currentTimeMillis());

            if (left == 0) {
                // Таймер вышел — гасим совсем, как ночник WLED; утром включишь из трея
                sleepFrom = sleepUntil = 0;
                setLighting(false);
                showSleep();
            } else {
                dim = dim * left / 100;
            }
        }

        glow.dim = dim;
    }

    /** Живой предпросмотр режима; при наведении на плитку показывает её режим. */
    private GlowPreview preview;

    /** Кнопка таймера сна на странице; подпись — когда погаснет. */
    private final JButton sleepButton = Ui.button("Таймер сна");

    /** «23:40» — когда погаснет подсветка по таймеру. */
    private String sleepTime() {
        return java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(sleepUntil), java.time.ZoneId.systemDefault())
                .withSecond(0).withNano(0).toString();
    }

    private void showSleep() {
        sleepButton.setText(sleepUntil > 0 ? "Погаснет в " + sleepTime() : "Таймер сна");
        fillTrayMenu();
    }

    /** Таймер сна: когда запущен и когда подсветка погаснет; 0 — не запущен. */
    private long sleepFrom;
    private long sleepUntil;

    /**
     * Яркость таймера сна, %: от 100 в начале ровно до 0 к концу — как
     * Nightlight у WLED, который гасит ленту плавно, а не щелчком.
     */
    static int sleepLevel(long from, long until, long now) {
        if (until <= from || now >= until) {
            return 0;
        }

        return (int) Math.ceil(100.0 * (until - Math.max(now, from)) / (until - from));
    }

    /** Запустить таймер сна на столько минут; 0 — отменить. */
    void sleepIn(int minutes) {
        if (minutes <= 0) {
            sleepFrom = sleepUntil = 0;
        } else {
            if (!glow.on) {
                setLighting(true);
            }

            sleepFrom = System.currentTimeMillis();
            sleepUntil = sleepFrom + minutes * 60_000L;
        }

        updateDim();
        showSleep();
    }

    /** Множитель яркости, %: ночью — ночная яркость, в простое — 0. */
    static int dimFor(Settings settings, int hour, long idleMillis) {
        // Экран заблокирован — гасим совсем; простой — до выбранной яркости
        if (idleMillis == Long.MAX_VALUE) {
            return 0;
        }

        if (settings.idleMinutes > 0 && idleMillis >= settings.idleMinutes * 60_000L) {
            var idle = Math.max(0, Math.min(90, settings.idleLevel));
            // Ночью в простое — не ярче ночного
            return settings.nightOn && inHours(hour, settings.nightFrom, settings.nightTo)
                    ? Math.min(idle, settings.nightLevel) : idle;
        }

        if (settings.nightOn && inHours(hour, settings.nightFrom, settings.nightTo)) {
            return settings.nightLevel;
        }

        return 100;
    }

    /** Попадает ли час в промежуток; «с 23 до 8» — через полночь. */
    static boolean inHours(int hour, int from, int to) {
        return from <= to ? hour >= from && hour < to : hour >= from || hour < to;
    }

    /** Строка «Ночью приглушать» и «гасить в простое». */
    private JComponent scheduleRow() {
        var night = new Ui.Switch(settings.nightOn);
        var from = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(settings.nightFrom, 0, 23, 1));
        var to = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(settings.nightTo, 0, 23, 1));
        var level = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(settings.nightLevel, 0, 100, 5));
        var idle = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(settings.idleMinutes, 0, 240, 5));
        var idleLevel = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(settings.idleLevel, 0, 90, 5));

        Runnable save = () -> {
            settings.nightOn = night.isOn();
            settings.nightFrom = (Integer) from.getValue();
            settings.nightTo = (Integer) to.getValue();
            settings.nightLevel = (Integer) level.getValue();
            settings.idleMinutes = (Integer) idle.getValue();
            settings.idleLevel = (Integer) idleLevel.getValue();
            settings.save();
            updateDim();
        };
        night.onChange(on -> save.run());

        for (var spinner : List.of(from, to, level, idle, idleLevel)) {
            spinner.setPreferredSize(new Dimension(58, spinner.getPreferredSize().height));
            spinner.addChangeListener(e -> save.run());
        }

        var row = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.add(night);
        row.add(Ui.muted("ночью с", 13f));
        row.add(from);
        row.add(Ui.muted("до", 13f));
        row.add(to);
        row.add(Ui.muted("ч — яркость", 13f));
        row.add(level);
        row.add(Ui.muted("%", 13f));
        // Простой — своей строкой: как «притушить при бездействии» в iCUE, не обязательно в ноль
        var idleRow = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
        idleRow.add(Ui.muted("если не трогали компьютер", 13f));
        idleRow.add(idle);
        idleRow.add(Ui.muted("мин (0 — никогда), приглушать до", 13f));
        idleRow.add(idleLevel);
        idleRow.add(Ui.muted("% (0 — погасить)", 13f));
        var lockedRow = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
        var lockedSwitch = new Ui.Switch(settings.offWhenLocked);
        lockedSwitch.onChange(on -> {
            settings.offWhenLocked = on;
            settings.save();
            updateDim();
        });
        lockedRow.add(lockedSwitch);
        lockedRow.add(Ui.muted("гасить, пока экран заблокирован (Win+L)", 13f));
        lockedRow.add(Box.createHorizontalStrut(16));
        lockedRow.add(Ui.muted("при выходе из V.I.R.U.S.:", 13f));
        var exit = new javax.swing.JComboBox<>(EXIT_CHOICES.toArray(String[]::new));
        exit.setSelectedItem(settings.exitLight);
        exit.setToolTipText("Без этого OpenRGB застынет на последнем кадре эффекта");
        exit.addActionListener(e -> {
            settings.exitLight = (String) exit.getSelectedItem();
            settings.save();
        });
        lockedRow.add(exit);

        var rows = Ui.transparent(null);
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        row.setAlignmentX(LEFT_ALIGNMENT);
        idleRow.setAlignmentX(LEFT_ALIGNMENT);
        lockedRow.setAlignmentX(LEFT_ALIGNMENT);
        rows.add(row);
        rows.add(Box.createVerticalStrut(6));
        rows.add(idleRow);
        rows.add(Box.createVerticalStrut(6));
        rows.add(lockedRow);
        return rows;
    }

    /** Меню «Подсветка» в трее — пересобирается, когда меняются профили. */
    private final java.awt.Menu trayMenu = new java.awt.Menu("Подсветка");

    /**
     * Подсветка из трея, как меню значка у iCUE и SignalRGB: включить или
     * выключить, профиль, режим — не открывая окно.
     */
    java.awt.Menu trayMenu() {
        fillTrayMenu();
        return trayMenu;
    }

    private void fillTrayMenu() {
        trayMenu.removeAll();
        var power = new java.awt.MenuItem(glow.on ? "Выключить" : "Включить");
        power.addActionListener(e -> SwingUtilities.invokeLater(() -> setLighting(!glow.on)));
        trayMenu.add(power);

        var sleep = new java.awt.Menu(sleepUntil > 0 ? "Таймер сна: погаснет в " + sleepTime() : "Таймер сна");

        for (var minutes : List.of(15, 30, 60, 90)) {
            var item = new java.awt.MenuItem("Погасить плавно за " + minutes + " мин");
            item.addActionListener(e -> SwingUtilities.invokeLater(() -> sleepIn(minutes)));
            sleep.add(item);
        }

        if (sleepUntil > 0) {
            var cancel = new java.awt.MenuItem("Отменить");
            cancel.addActionListener(e -> SwingUtilities.invokeLater(() -> sleepIn(0)));
            sleep.add(cancel);
        }

        trayMenu.add(sleep);

        var palettes = new java.awt.Menu("Палитры");

        for (var palette : PALETTES) {
            var item = new java.awt.MenuItem(palette.name());
            item.addActionListener(e -> SwingUtilities.invokeLater(() -> chooseColors(palette.a(), palette.b())));
            palettes.add(item);
        }

        trayMenu.add(palettes);

        if (!settings.profiles.isEmpty()) {
            trayMenu.addSeparator();

            for (var profile : settings.profiles) {
                var item = new java.awt.MenuItem("Профиль: " + profile.name());
                item.addActionListener(e -> SwingUtilities.invokeLater(() -> {
                    autoProfile = null;
                    beforeAuto = null;
                    applyProfile(profile);
                }));
                trayMenu.add(item);
            }
        }

        trayMenu.addSeparator();

        for (var group : MODE_GROUPS.entrySet()) {
            var sub = new java.awt.Menu(group.getKey());

            for (var id : group.getValue()) {
                var mode = SignalRgb.mode(id);
                var item = new java.awt.MenuItem(mode.name());
                item.addActionListener(e -> SwingUtilities.invokeLater(() -> {
                    var tile = tileById.get(id);

                    if (tile != null) {
                        tile.setSelected(true);
                    }

                    chooseMode(mode);
                }));
                sub.add(item);
            }

            trayMenu.add(sub);
        }
    }

    /** Включить или выключить подсветку — как переключатель «подсветка» на странице. */
    void setLighting(boolean on) {
        signalQuiet = true;
        signalOn.setOn(on);
        signalQuiet = false;

        if (openRgbEngine()) {
            glow.on = on;
            settings.glowOn = on;
            settings.save();
        } else if (signalLocked) {
            glow.on = on;
            glowNudge();
        } else {
            signalCall(() -> signal.enabled(on));
        }

        fillTrayMenu();
    }

    /** Профили: кнопки, «сохранить как», правила для программ. */
    private void showProfiles() {
        fillTrayMenu();
        profileRow.removeAll();
        profileRow.add(Ui.muted("Профили", 13f));

        for (var profile : settings.profiles) {
            var chip = Ui.button(profile.name() + (profile.name().equals(autoProfile) ? " ●" : ""));
            // Миниатюра, как у профилей iCUE: цвета профиля видны, не включая его
            var mode = SignalRgb.mode(profile.mode());
            chip.setIcon(mode.usesColor() ? new PaletteIcon(profile.color(), mode.usesSecond() ? profile.color2()
                    : profile.color()) : new PaletteIcon(Glow.hue(0), Glow.hue(240)));
            chip.setToolTipText("<html><b>" + ConfigPage.escape(profile.name()) + "</b>: " + mode.name()
                    + (profile.brightness() >= 0 ? ", яркость " + profile.brightness() + "%" : "")
                    + "<br>Нажми — включить. Правой кнопкой — удалить</html>");
            chip.addActionListener(e -> {
                autoProfile = null;
                beforeAuto = null;
                applyProfile(profile);
            });
            chip.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (SwingUtilities.isRightMouseButton(e) && javax.swing.JOptionPane.showConfirmDialog(DevicePage.this,
                            "Удалить профиль «" + profile.name() + "»?", Virus.NAME, javax.swing.JOptionPane.OK_CANCEL_OPTION)
                            == javax.swing.JOptionPane.OK_OPTION) {
                        settings.profiles.remove(profile);
                        settings.save();
                        showProfiles();
                    }
                }
            });
            profileRow.add(chip);
        }

        var save = Ui.ghost("+ Сохранить как…");
        save.addActionListener(e -> {
            var name = javax.swing.JOptionPane.showInputDialog(this, "Как назвать профиль? Например: Игра, Ночь, Работа",
                    Virus.NAME, javax.swing.JOptionPane.QUESTION_MESSAGE);

            if (name == null || name.isBlank()) {
                return;
            }

            settings.profiles.removeIf(old -> old.name().equalsIgnoreCase(name.strip()));
            settings.profiles.add(current(name.strip()));
            settings.save();
            showProfiles();
            toast.accept("Профиль «" + name.strip() + "» сохранён.");
        });
        profileRow.add(save);

        var rules = Ui.ghost("Для программ…");
        rules.setToolTipText("Запустил игру — подсветка переключилась сама");
        rules.addActionListener(e -> editRules());
        profileRow.add(rules);
        profileRow.revalidate();
        profileRow.repaint();
    }

    /** «Менять режим каждые N минут» — как Effect Rotation у SignalRGB и плейлисты WLED. */
    private final javax.swing.JSpinner rotateSpinner = rotateSpinner();
    private long rotatedAt = System.currentTimeMillis();

    private javax.swing.JSpinner rotateSpinner() {
        var spinner = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(0, 0, 240, 1));
        spinner.setPreferredSize(new Dimension(58, spinner.getPreferredSize().height));
        spinner.setToolTipText("Подсветка сама переходит к следующему спокойному или динамичному режиму");
        spinner.addChangeListener(e -> {
            settings.rotateMinutes = (Integer) spinner.getValue();
            rotatedAt = System.currentTimeMillis();
            settings.save();
        });
        return spinner;
    }

    /** Режимы для автосмены: спокойные и динамичные; реагирующим нужен свой повод. */
    static List<Integer> rotation() {
        var list = new ArrayList<Integer>();
        list.addAll(MODE_GROUPS.get("Спокойные"));
        list.addAll(MODE_GROUPS.get("Динамичные"));
        return list;
    }

    /** Следующий режим по кругу; текущего в круге нет — первый. */
    static int nextInRotation(int current) {
        var list = rotation();
        var index = list.indexOf(current);
        return list.get((index + 1) % list.size());
    }

    /** Пора ли сменить режим — проверяется раз в 5 секунд. */
    private void rotate() {
        if (settings.rotateMinutes <= 0 || autoProfile != null
                || System.currentTimeMillis() - rotatedAt < settings.rotateMinutes * 60_000L) {
            return;
        }

        rotatedAt = System.currentTimeMillis();
        var mode = SignalRgb.mode(nextInRotation(glow.mode));
        var tile = tileById.get(mode.id());

        if (tile != null) {
            tile.setSelected(true);
        }

        chooseMode(mode);
    }

    private LightProfiles.Profile current(String name) {
        return new LightProfiles.Profile(name, glow.mode, glow.color, glow.color2, glow.speed, glow.brightness,
                glow.reverse, glow.scale);
    }

    private void applyProfile(LightProfiles.Profile profile) {
        glow.color = profile.color();
        glow.color2 = profile.color2();
        glow.speed = profile.speed();
        glowColor.setColor(profile.color());
        glowColor2.setColor(profile.color2());
        glowSpeed.setValue(profile.speed());

        // Яркость и узор — если профиль их помнит (старые профили не трогают)
        if (profile.brightness() >= 0) {
            signalBrightness.setValue(profile.brightness());
            reverseSwitch.setOn(profile.reverse());
            glow.reverse = profile.reverse();
            settings.glowReverse = profile.reverse();

            if (profile.scale() > 0) {
                scaleSlider.setValue((int) Math.round(profile.scale() * 10));
            }
        }

        var tile = tileById.get(profile.mode());

        if (tile != null) {
            tile.setSelected(true);
        }

        chooseMode(SignalRgb.mode(profile.mode()));
        showProfiles();
    }

    private void editRules() {
        var area = new javax.swing.JTextArea(LightProfiles.rulesText(settings.appRules), 8, 36);
        area.setFont(Theme.mono.deriveFont(13f));
        var names = settings.profiles.stream().map(LightProfiles.Profile::name).toList();
        var panel = Ui.transparent(new BorderLayout(0, 8));
        panel.add(new JLabel("<html>По строке: <b>программа.exe = Профиль</b>. Например:<br>"
                + "<tt>MarvelRivals.exe = Игра</tt><br>Профили: " + (names.isEmpty() ? "пока нет — сохрани сначала"
                : ConfigPage.escape(String.join(", ", names))) + "</html>"), BorderLayout.NORTH);
        panel.add(new JScrollPane(area), BorderLayout.CENTER);

        if (javax.swing.JOptionPane.showConfirmDialog(this, panel, "Подсветка для программ",
                javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.PLAIN_MESSAGE)
                == javax.swing.JOptionPane.OK_OPTION) {
            settings.appRules.clear();
            settings.appRules.putAll(LightProfiles.parseRules(area.getText()));
            settings.save();
            toast.accept("Правил: " + settings.appRules.size() + ".");
        }
    }

    /** Раз в 5 секунд: не запущена ли программа из правил — тогда её профиль; закрылась — прежний. */
    private void watchPrograms() {
        if (settings.appRules.isEmpty()) {
            return;
        }

        worker.submit(() -> {
            var want = LightProfiles.wanted(settings.appRules, LightProfiles.running());

            SwingUtilities.invokeLater(() -> {
                if (want != null && !want.equals(autoProfile)) {
                    settings.profiles.stream().filter(p -> p.name().equalsIgnoreCase(want)).findFirst().ifPresent(p -> {
                        if (autoProfile == null) {
                            beforeAuto = current("до программы");
                        }

                        autoProfile = p.name();
                        applyProfile(p);
                        toast.accept("Подсветка: профиль «" + p.name() + "» — запущена программа из правил.");
                    });
                } else if (want == null && autoProfile != null) {
                    autoProfile = null;

                    if (beforeAuto != null) {
                        applyProfile(beforeAuto);
                        beforeAuto = null;
                    }
                }
            });
        });
    }

    private void chooseMode(SignalRgb.Mode mode) {
        glow.mode = mode.id();
        glowChanged();

        if (openRgbEngine()) {
            animator.start(settings.host);
            return;
        }

        if (signalLocked) {
            // Без API: включить VIRUS ссылкой, если он сейчас не играет (а если играет — сам подхватит)
            if (!glow.heard()) {
                openSignalUrl(SignalRgb.applyUrl(glow));
            }

            return;
        }

        // Включить сам эффект VIRUS, если в SignalRGB сейчас другой
        worker.submit(() -> {
            try {
                var state = signal.state();
                var all = effects.isEmpty() ? signal.effects() : effects;
                var ours = all.stream().filter(effect -> effect.name().equals(SignalRgb.EFFECT)).findFirst();

                if (ours.isEmpty()) {
                    var fresh = installEffect();
                    SwingUtilities.invokeLater(() -> toast.accept(fresh
                            ? "Эффект VIRUS добавлен в SignalRGB — нажми «Перезапустить SignalRGB», и режимы заработают."
                            : "SignalRGB ещё не видит эффект VIRUS — нажми «Перезапустить SignalRGB»."));
                    return;
                }

                if (!ours.get().id().equals(state.effectId())) {
                    signal.apply(ours.get());
                }

                var fresh = signal.state();
                var list = all;
                SwingUtilities.invokeLater(() -> showSignal(list, fresh));
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> toast.accept("SignalRGB: " + e.getMessage()));
            }
        });
    }

    /** Что делать с подсветкой при выходе: ровный цвет, погасить, оставить как есть. */
    static final List<String> EXIT_CHOICES = List.of("ровный цвет", "погасить", "оставить как есть");

    /**
     * Цвет, который останется после выхода; null — ничего не трогать. Как у
     * iCUE и SignalRGB: без этого OpenRGB застывает на последнем кадре —
     * половина кометы, недорисованная волна.
     */
    static Color exitColor(String choice, Color color, int brightness, boolean on) {
        if ("оставить как есть".equals(choice)) {
            return null;
        }

        if ("погасить".equals(choice) || !on) {
            return Color.BLACK;
        }

        return Glow.scale(color, Math.max(0, Math.min(100, brightness)) / 100.0);
    }

    /** При выходе из V.I.R.U.S. — спокойная подсветка вместо застывшего кадра. */
    private void farewell() {
        if (!openRgbEngine() || !animator.running()) {
            return;
        }

        var color = exitColor(settings.exitLight, glow.color, glow.effective(), glow.on);

        if (color == null) {
            return;
        }

        animator.stop();

        try (var rgb = new OpenRgb(settings.host, OpenRgb.PORT)) {
            paintEach(rgb, rgb.devices(), color, settings.deviceLevels);
        } catch (IOException | RuntimeException e) {
            // OpenRGB уже закрыт — красить нечего
        }
    }

    /** Переключатель «как в Windows». */
    private final Ui.Switch accentSwitch = new Ui.Switch(false);

    /**
     * Цвета выбраны руками — кнопкой, палитрой или из трея. Если основной цвет
     * брался из Windows, это выключается: иначе через 10 секунд цвет Windows
     * перебил бы выбор, как бывает в «Динамическом освещении».
     */
    void chooseColors(Color first, Color second) {
        if (settings.followAccent && !first.equals(glow.color)) {
            settings.followAccent = false;
            accentSwitch.setOn(false);
        }

        glow.color = first;
        glow.color2 = second;
        glowColor.setColor(first);
        glowColor2.setColor(second);
        glowChanged();
    }

    /** Режим, цвет или скорость поменялись: эффект подхватит сам, а если не спрашивает — передадим ссылкой. */
    private void glowChanged() {
        settings.glowMode = glow.mode;
        settings.glowSpeed = glow.speed;
        settings.glowColor = glow.color;
        settings.glowColor2 = glow.color2;
        settings.save();
        urlFallback.restart();
    }

    /** Яркость или вкл/выкл без API: эффект VIRUS подхватит сам; если он не играет — включим его. */
    private void glowNudge() {
        urlFallback.restart();
    }

    private void openSignalUrl(String url) {
        if (!SystemInfo.isWindows()) {
            return;
        }

        try {
            SignalRgb.open(url);
        } catch (IOException e) {
            toast.accept("SignalRGB не открылся: " + e.getMessage());
        }
    }

    /** Один цвет кнопками OpenRGB — режим остановить, иначе он закрасит этот цвет со следующего кадра. */
    private void stopAnimation() {
        if (animator.running()) {
            animator.stop();
            animatorStatus.setText("Режим остановлен: покрашено одним цветом. Выбери режим — запустится снова.");
        }
    }

    private boolean openRgbEngine() {
        return "openrgb".equals(settings.engine);
    }

    private void useEngine(String engine) {
        settings.engine = engine;
        settings.save();

        if (openRgbEngine()) {
            var answer = javax.swing.JOptionPane.showConfirmDialog(this,
                    "Закрыть SignalRGB? Пока он открыт, он перебивает цвета OpenRGB и подсветка мигает.",
                    "Режимы через OpenRGB", javax.swing.JOptionPane.YES_NO_OPTION);

            if (answer == javax.swing.JOptionPane.YES_OPTION) {
                worker.submit(() -> {
                    try {
                        new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                                "Get-Process | Where-Object { $_.ProcessName -like 'SignalRgb*' } | Stop-Process -Force")
                                .start().waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (Exception ignored) {
                        // не закрылся — пусть закроет сам
                    }
                });
            }

            glow.on = settings.glowOn;
            glow.brightness = settings.glowBrightness;
            animator.start(settings.host);
        } else {
            animator.stop();
            glow.on = true;
            glow.brightness = 100;
            connectSignal(false);
        }

        showEngine();
    }

    private void showEngine() {
        var open = openRgbEngine();
        openRgbOnly.setVisible(open);
        signalOnly.setVisible(!open);

        if (signalArrows != null) {
            signalArrows.setVisible(!open && !signalLocked);
        }

        if (open) {
            signalQuiet = true;
            signalOn.setOn(glow.on);
            signalBrightness.setValue(glow.brightness);
            signalQuiet = false;
        }

        revalidate();
        repaint();
    }

    private void pushByUrl() {
        // Эффект спрашивает V.I.R.U.S. сам — ссылка не нужна (и SignalRGB лишний раз не дёргаем)
        if (openRgbEngine() || glow.heard() || !SystemInfo.isWindows()) {
            return;
        }

        try {
            SignalRgb.open(SignalRgb.applyUrl(glow));
        } catch (IOException ignored) {
            // нет SignalRGB — сообщит «Подключиться»
        }
    }

    /** Закрыть и открыть SignalRGB: новые эффекты он видит только после запуска. */
    private void restartSignal() {
        if (!SystemInfo.isWindows()) {
            return;
        }

        signalStatus.setForeground(Theme.MUTED);
        signalStatus.setText("Перезапускаю SignalRGB…");
        worker.submit(() -> {
            installEffect();

            try {
                var script = "Get-Process | Where-Object { $_.ProcessName -like 'SignalRgb*' } | Stop-Process -Force";
                new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
                        .start().waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
                Thread.sleep(1500);
                var app = RgbApps.ALL.stream().filter(a -> a.name().equals("SignalRGB")).findFirst().orElseThrow();
                RgbApps.open(app, RgbApps.find(app, RgbApps.startApps()));
                Thread.sleep(15000);
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> toast.accept("SignalRGB не перезапустился: " + e.getMessage()));
                return;
            }

            SwingUtilities.invokeLater(() -> connectSignal(false));
        });
    }

    /**
     * Программы для подсветки и экранов разных фирм: SignalRGB/OpenRGB для
     * всего сразу, родные MSI и GIGABYTE, AIDA64 и Wallpaper Engine для экранов.
     */
    private JComponent apps() {
        appRows.setLayout(new BoxLayout(appRows, BoxLayout.Y_AXIS));
        var recheck = Ui.button("Проверить");
        recheck.setIcon(Icons.of(Icons.Kind.RESTART, Theme.TEXT));
        recheck.addActionListener(e -> checkApps());

        var top = Ui.transparent(new BorderLayout(10, 0));
        top.add(Ui.muted("<html><body style='width:560px'>Ставь <b>одну</b> общую программу — SignalRGB или "
                + "OpenRGB. Две сразу, да ещё с MSI Center, перебивают друг друга, и подсветка мигает. "
                + "Экраны корпуса: если Windows видит их как мониторы (вкладка «Обзор» → «Мониторы»), на них "
                + "работают обои из Wallpaper Engine; если нет — датчики выводит AIDA64 (SensorPanel / "
                + "LCD).</body></html>", 12.5f), BorderLayout.CENTER);
        var right = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.add(recheck);
        top.add(right, BorderLayout.EAST);

        var body = Ui.transparent(new BorderLayout(0, 10));
        body.add(top, BorderLayout.NORTH);
        body.add(appRows, BorderLayout.CENTER);
        showApps();
        return inner("Программы для подсветки и экранов", body);
    }

    private void checkApps() {
        if (!SystemInfo.isWindows()) {
            appsChecked = true;
            showApps();
            return;
        }

        worker.submit(() -> {
            var found = RgbApps.startApps();
            SwingUtilities.invokeLater(() -> {
                startApps = found;
                appsChecked = true;
                showApps();
            });
        });
    }

    private void showApps() {
        appRows.removeAll();

        for (var app : RgbApps.ALL) {
            var id = RgbApps.find(app, startApps);
            var row = Ui.transparent(new BorderLayout(12, 0));
            row.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
            row.setAlignmentX(LEFT_ALIGNMENT);

            var name = Ui.label(app.name(), Theme.ui.deriveFont(Font.BOLD, 14f), Theme.TEXT);
            name.setPreferredSize(new Dimension(200, 20));
            var purpose = Ui.muted(app.purpose(), 12.5f);
            var state = !appsChecked ? Ui.muted("проверяю…", 12f)
                    : id != null ? Ui.label("✓ установлена", Theme.ui.deriveFont(12f), Theme.ACCENT)
                    : Ui.label("не установлена", Theme.ui.deriveFont(12f), Theme.DIM);
            var text = Ui.transparent(new BorderLayout(0, 2));
            text.add(name, BorderLayout.NORTH);
            text.add(purpose, BorderLayout.CENTER);
            text.add(state, BorderLayout.SOUTH);
            row.add(text, BorderLayout.CENTER);

            var buttons = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));

            if (id == null && app.installable()) {
                var install = Ui.button("Установить");
                install.setIcon(Icons.of(Icons.Kind.PLUS, Theme.TEXT));
                install.addActionListener(e -> {
                    try {
                        RgbApps.install(app);
                        toast.accept("Установка " + app.name() + " — в отдельном окне. Когда закончится, нажми «Проверить».");
                    } catch (IOException ex) {
                        toast.accept("Установка не запустилась: " + ex.getMessage());
                    }
                });
                buttons.add(install);
            } else if (id == null) {
                var get = Ui.button("Где взять");
                get.addActionListener(e -> browse(app.page()));
                buttons.add(get);
            }

            var open = Ui.button("Открыть");
            open.setIcon(Icons.of(Icons.Kind.PLAY, Theme.TEXT));
            open.setEnabled(id != null || !app.launch().isEmpty());
            open.addActionListener(e -> {
                try {
                    RgbApps.open(app, id);
                } catch (IOException ex) {
                    toast.accept("Не открылось: " + ex.getMessage());
                }
            });
            buttons.add(open);
            var buttonsBox = Ui.transparent(new BorderLayout());
            buttonsBox.add(buttons, BorderLayout.CENTER);
            row.add(buttonsBox, BorderLayout.EAST);
            appRows.add(row);
        }

        appRows.revalidate();
        appRows.repaint();
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
        Ui.passWheel(scroll);

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

            if (LIGHTING) {
                checkApps();

                if (!openRgbEngine()) {
                    connectSignal(false);
                }
            }
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
                var listed = rgb.devices();
                var found = OpenRgb.applySizes(rgb, listed, settings.zoneSizes) ? rgb.devices() : listed;
                SwingUtilities.invokeLater(() -> showDevices(found));
            } catch (IOException | RuntimeException e) {
                SwingUtilities.invokeLater(() -> {
                    rgbStatus.setText(e.getMessage());
                    rgbStatus.setForeground(Theme.ERROR);
                });
            }
        });
    }

    /** Разъём ARGB: сколько на нём светодиодов. 0 — OpenRGB не красит то, что к нему подключено. */
    private JComponent zoneRow(OpenRgb.Device device, OpenRgb.Zone zone) {
        var key = OpenRgb.key(device, zone);
        var wanted = settings.zoneSizes.getOrDefault(key, zone.count());
        var spinner = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(
                Math.max(zone.min(), Math.min(zone.max(), wanted)), zone.min(), zone.max(), 1));
        spinner.setPreferredSize(new Dimension(80, spinner.getPreferredSize().height));
        var apply = Ui.button("Задать");
        apply.addActionListener(e -> resizeZone(device, zone, (Integer) spinner.getValue()));

        var label = Ui.label("<html>&nbsp;&nbsp;&nbsp;&nbsp;разъём «" + ConfigPage.escape(zone.name()) + "» "
                + "<font color='" + (zone.count() == 0 ? "#FFB547'>светодиодов не задано — то, что на нём, не горит"
                : "#7C8C84'>светодиодов: " + zone.count()) + "</font></html>", Theme.ui.deriveFont(12.5f), Theme.TEXT);
        var controls = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        controls.add(spinner);
        controls.add(apply);

        var row = Ui.transparent(new BorderLayout(10, 0));
        row.setBorder(BorderFactory.createEmptyBorder(2, 2, 6, 2));
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        row.add(label, BorderLayout.CENTER);
        row.add(controls, BorderLayout.EAST);
        return row;
    }

    /**
     * Яркость устройства, как в SignalRGB: память может быть ярче вентиляторов.
     * 0 — «не трогать»: им управляет родная программа, режимы его обходят.
     */
    private JComponent levelControl(OpenRgb.Device device) {
        var level = settings.deviceLevels.getOrDefault(device.name(), 100);
        var slider = new JSlider(0, 100, level);
        slider.setOpaque(false);
        slider.setPreferredSize(new Dimension(110, 26));
        var value = Ui.label(level == 0 ? "не трогать" : level + "%", Theme.mono.deriveFont(12f),
                level == 0 ? Theme.DIM : Theme.MUTED);
        value.setPreferredSize(new Dimension(80, 18));
        slider.setToolTipText("Яркость этого устройства. В ноль — V.I.R.U.S. его не трогает, им правит родная программа");
        slider.addChangeListener(e -> {
            var now = slider.getValue();
            value.setText(now == 0 ? "не трогать" : now + "%");
            value.setForeground(now == 0 ? Theme.DIM : Theme.MUTED);

            if (slider.getValueIsAdjusting()) {
                return;
            }

            var before = settings.deviceLevels.getOrDefault(device.name(), 100);

            if (now == 100) {
                settings.deviceLevels.remove(device.name());
            } else {
                settings.deviceLevels.put(device.name(), now);
            }

            settings.save();
            animator.levels = Map.copyOf(settings.deviceLevels);

            // Вкл/выкл устройства меняет состав — режим подключится заново
            if ((before == 0) != (now == 0) && animator.running()) {
                animator.stop();
                animator.start(settings.host);
            }
        });

        var box = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        box.add(slider);
        box.add(value);
        return box;
    }

    private JComponent findingRow(LightingCheck.Finding finding) {
        var text = new javax.swing.JTextArea(finding.text());
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setEditable(false);
        text.setOpaque(false);
        text.setBorder(null);
        text.setFont(Theme.ui.deriveFont(13f));
        text.setForeground(Theme.WARN);

        var row = Ui.transparent(new BorderLayout(10, 0));
        row.setOpaque(true);
        row.setBackground(new Color(0x26, 0x1D, 0x0E));
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(4, 0, 4, 0),
                BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.alpha(Theme.WARN, 100)),
                        BorderFactory.createEmptyBorder(8, 12, 8, 12))));
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.add(text, BorderLayout.CENTER);

        if (!finding.action().isEmpty()) {
            var fix = Ui.button(finding.action().equals("admin") ? "Перезапустить от администратора" : "Закрыть SignalRGB");
            fix.addActionListener(e -> {
                if (finding.action().equals("admin")) {
                    worker.submit(() -> {
                        try {
                            // Закрыть обычный OpenRGB, чтобы поднять его же с правами администратора
                            new ProcessBuilder("taskkill", "/IM", "OpenRGB.exe", "/F").start().waitFor();
                        } catch (Exception ignored) {
                            // не было запущен — тоже хорошо
                        }

                        SwingUtilities.invokeLater(this::launchOpenRgb);
                    });
                } else {
                    worker.submit(() -> {
                        try {
                            new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                                    "Get-Process | Where-Object { $_.ProcessName -like 'SignalRgb*' } | Stop-Process -Force")
                                    .start().waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (Exception ignored) {
                            // не закрылся — пусть закроет сам
                        }

                        SwingUtilities.invokeLater(this::connectRgb);
                    });
                }
            });
            var box = Ui.transparent(new BorderLayout());
            box.add(fix, BorderLayout.NORTH);
            row.add(box, BorderLayout.EAST);
        }

        return row;
    }

    /** Световой сигнал о событии — если хозяин их не выключил. */
    void signal(Color color) {
        if (LIGHTING && settings.signals) {
            glow.flash(color);
        }
    }

    /** Основной цвет — в память устройств, которые это умеют. */
    private void saveToHardware() {
        var color = glow.color;
        worker.submit(() -> {
            try (var rgb = new OpenRgb(settings.host, OpenRgb.PORT)) {
                var all = rgb.devices();
                var saved = 0;

                for (var device : all) {
                    var level = settings.deviceLevels.getOrDefault(device.name(), 100);

                    if (level > 0 && rgb.save(device, Glow.scale(color, level / 100.0))) {
                        saved++;
                    }
                }

                var count = saved;
                var protocol = rgb.protocol();
                SwingUtilities.invokeLater(() -> toast.accept(protocol < 3
                        ? "OpenRGB слишком старый для записи в устройства — обнови его до 0.9 или новее."
                        : "Записано в устройства: " + count + " из " + all.size()
                        + (count < all.size() ? " (остальные не умеют хранить цвет)." : ".")));
                restartAnimator();
            } catch (IOException | RuntimeException e) {
                SwingUtilities.invokeLater(() -> toast.accept("Не записалось: " + e.getMessage()));
            }
        });
    }

    /** Подвинуть устройство на холсте левее или правее. */
    private JComponent moveButton(OpenRgb.Device device, List<OpenRgb.Device> found, int step) {
        var button = Ui.ghost(step < 0 ? "Левее" : "Правее");
        button.addActionListener(e -> {
            // Порядок — по сохранённому, недостающие устройства в конец, как их отдал OpenRGB
            var order = new ArrayList<>(settings.canvasOrder);
            found.forEach(other -> {
                if (!order.contains(other.name())) {
                    order.add(other.name());
                }
            });
            var at = order.indexOf(device.name());
            var to = Math.max(0, Math.min(order.size() - 1, at + step));
            order.remove(at);
            order.add(to, device.name());
            settings.canvasOrder.clear();
            settings.canvasOrder.addAll(order);
            settings.save();
            animator.order = List.copyOf(order);
            restartAnimator();
            toast.accept("«" + device.name() + "» — " + (to + 1) + "-е на холсте.");
        });
        return button;
    }

    /** Места светодиодов считаются при подключении — новое расположение вступит после переподключения. */
    private void restartAnimator() {
        if (animator.running()) {
            animator.stop();
            animator.start(settings.host);
        }
    }

    /** Свой режим у устройства, как в iCUE и SignalRGB: клавиатура — «Экран», вентиляторы — «Радуга». */
    private JComponent modeControl(OpenRgb.Device device) {
        var names = new java.util.ArrayList<String>();
        names.add("как у всех");
        SignalRgb.MODES.forEach(mode -> names.add(mode.name()));
        var box = new javax.swing.JComboBox<>(names.toArray(String[]::new));
        var own = settings.deviceModes.get(device.name());
        box.setSelectedItem(own == null ? "как у всех" : SignalRgb.mode(own).name());
        box.setPreferredSize(new Dimension(150, box.getPreferredSize().height));
        box.addActionListener(e -> {
            var index = box.getSelectedIndex();

            if (index <= 0) {
                settings.deviceModes.remove(device.name());
            } else {
                settings.deviceModes.put(device.name(), SignalRgb.MODES.get(index - 1).id());
            }

            settings.save();
            animator.modes = Map.copyOf(settings.deviceModes);
        });
        return box;
    }

    /** Порядок цветов, как в WLED: красный горит зелёным — значит, устройству нужен GRB. */
    private JComponent orderControl(OpenRgb.Device device) {
        var box = new javax.swing.JComboBox<>(LedColor.ORDERS.toArray(String[]::new));
        box.setSelectedItem(settings.colorOrders.getOrDefault(device.name(), "RGB"));
        box.setToolTipText("Порядок цветов. Нажми «Проверка»: если вместо красного горит другой цвет — поменяй");
        box.setPreferredSize(new Dimension(72, box.getPreferredSize().height));
        box.addActionListener(e -> {
            var order = String.valueOf(box.getSelectedItem());

            if (order.equals("RGB")) {
                settings.colorOrders.remove(device.name());
            } else {
                settings.colorOrders.put(device.name(), order);
            }

            settings.applyColor();
            settings.save();
        });
        return box;
    }

    /**
     * «Проверка», как «опознать» в iCUE и OpenRGB: устройство по очереди
     * горит красным, зелёным, синим и белым — видно, какое это и верен ли
     * порядок цветов. Режим на это время встаёт на паузу.
     */
    private JComponent testButton(OpenRgb.Device device) {
        var test = Ui.ghost("Проверка");
        test.setToolTipText("Красный → зелёный → синий → белый на этом устройстве");
        test.setEnabled(device.paintable());
        test.addActionListener(e -> {
            test.setEnabled(false);
            worker.submit(() -> {
                var wasPlaying = animator.running();
                animator.stop();

                try (var rgb = new OpenRgb(settings.host, OpenRgb.PORT)) {
                    for (var color : List.of(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE)) {
                        rgb.paint(device, color);
                        Thread.sleep(800);
                    }
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> toast.accept("Проверка не прошла: " + ex.getMessage()));
                } finally {
                    if (wasPlaying) {
                        animator.start(settings.host);
                    }

                    SwingUtilities.invokeLater(() -> {
                        test.setEnabled(true);
                        toast.accept("Было: красный, зелёный, синий, белый. Если цвета шли не так — поменяй порядок "
                                + "у «" + device.name() + "».");
                    });
                }
            });
        });
        return test;
    }

    private void resizeZone(OpenRgb.Device device, OpenRgb.Zone zone, int size) {
        settings.zoneSizes.put(OpenRgb.key(device, zone), size);
        settings.save();
        animator.zoneSizes = Map.copyOf(settings.zoneSizes);

        worker.submit(() -> {
            try (var rgb = new OpenRgb(settings.host, OpenRgb.PORT)) {
                rgb.resize(device, zone, size);
                var fresh = rgb.devices();
                SwingUtilities.invokeLater(() -> {
                    showDevices(fresh);
                    toast.accept("Разъём «" + zone.name() + "»: светодиодов " + size + ".");
                });

                // Режим подхватит новое число светодиодов только с нового подключения
                if (animator.running()) {
                    animator.stop();
                    animator.start(settings.host);
                }
            } catch (IOException | RuntimeException e) {
                SwingUtilities.invokeLater(() -> toast.accept("Не задалось: " + e.getMessage()));
            }
        });
    }

    /**
     * Цветокоррекция: гамма и баланс белого. Без неё светодиоды показывают
     * экранные цвета выцветшими — красный уходит в розовый.
     */
    private JComponent colorCorrection() {
        var gamma = new JSlider(10, 30, (int) Math.round(settings.gamma * 10));
        var gammaValue = Ui.label(String.format(java.util.Locale.ROOT, "%.1f", settings.gamma),
                Theme.mono.deriveFont(12.5f), Theme.ACCENT);
        gamma.setOpaque(false);
        gamma.setPreferredSize(new Dimension(130, 28));
        gamma.setToolTipText("1.0 — без поправки (цвета блеклые), 2.2 — как экран, 2.8 — насыщеннее, но темнее");
        gamma.addChangeListener(e -> {
            settings.gamma = gamma.getValue() / 10.0;
            gammaValue.setText(String.format(java.util.Locale.ROOT, "%.1f", settings.gamma));
            settings.applyColor();

            if (!gamma.getValueIsAdjusting()) {
                settings.save();
            }
        });

        var row = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.add(Ui.muted("Цветокоррекция: гамма", 13f));
        row.add(gamma);
        row.add(gammaValue);
        row.add(Box.createHorizontalStrut(10));

        var sliders = new java.util.ArrayList<JSlider>();

        for (var channel : List.of("R", "G", "B")) {
            var value = switch (channel) {
                case "R" -> settings.balanceRed;
                case "G" -> settings.balanceGreen;
                default -> settings.balanceBlue;
            };
            var slider = new JSlider(40, 100, value);
            slider.setOpaque(false);
            slider.setPreferredSize(new Dimension(80, 28));
            slider.setToolTipText("Баланс белого: если белый отдаёт синим — убавь B, если розовым — R");
            slider.addChangeListener(e -> {
                switch (channel) {
                    case "R" -> settings.balanceRed = slider.getValue();
                    case "G" -> settings.balanceGreen = slider.getValue();
                    default -> settings.balanceBlue = slider.getValue();
                }

                settings.applyColor();

                if (!slider.getValueIsAdjusting()) {
                    settings.save();
                }
            });
            sliders.add(slider);
            row.add(Ui.label(channel, Theme.mono.deriveFont(Font.BOLD, 12.5f),
                    channel.equals("R") ? Theme.ERROR : channel.equals("G") ? Theme.ACCENT : new Color(0x5A, 0x8C, 0xFF)));
            row.add(slider);
        }

        var reset = Ui.ghost("Сбросить");
        reset.addActionListener(e -> {
            gamma.setValue(22);
            sliders.forEach(slider -> slider.setValue(100));
        });
        row.add(reset);
        return row;
    }

    /** Те же устройства с тем же числом светодиодов. */
    static boolean sameDevices(List<OpenRgb.Device> a, List<OpenRgb.Device> b) {
        if (a.size() != b.size()) {
            return false;
        }

        for (var i = 0; i < a.size(); i++) {
            if (!a.get(i).name().equals(b.get(i).name()) || a.get(i).leds() != b.get(i).leds()) {
                return false;
            }
        }

        return true;
    }

    private void showDevices(List<OpenRgb.Device> found) {
        devices = found;
        rgbStatus.setForeground(Theme.ACCENT);
        rgbStatus.setText(found.isEmpty() ? "OpenRGB подключён, но устройств не нашёл."
                : "OpenRGB подключён. Устройств: " + found.size() + ".");
        rgbDevices.removeAll();

        // Что не так — сверху, до списка: как предупреждения у устройств в iCUE
        for (var finding : LightingCheck.analyze(found, settings.zoneSizes, LightProfiles.running(), openRgbEngine())) {
            rgbDevices.add(findingRow(finding));
        }

        for (var device : found) {
            var row = Ui.transparent(new BorderLayout(10, 0));
            row.setBorder(BorderFactory.createEmptyBorder(8, 2, 2, 2));
            row.setAlignmentX(LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
            var how = device.leds() > 0 && !device.streams() && device.solid() != null
                    ? "светодиодов: " + device.leds() + ", красится целиком (нет режима Direct)"
                    : device.leds() > 0 && !device.streams() ? "OpenRGB не даёт задать цвет — см. подсказку ниже"
                    : device.leds() > 0 ? "светодиодов: " + device.leds()
                    : device.solid() != null ? "цвет режимом «" + device.solid().name() + "»"
                    : "OpenRGB не даёт задать цвет — см. подсказку ниже";
            var name = Ui.label("<html><b>" + ConfigPage.escape(device.name()) + "</b> <font color='#7C8C84'>· "
                    + device.kind() + " · " + ConfigPage.escape(how) + "</font></html>", Theme.ui, Theme.TEXT);
            row.add(name, BorderLayout.CENTER);
            var controls = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            controls.add(levelControl(device));
            row.add(controls, BorderLayout.EAST);
            rgbDevices.add(row);

            // Вторая строка: свой режим, порядок цветов, проверка
            var tune = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
            tune.setBorder(BorderFactory.createEmptyBorder(0, 14, 8, 2));
            tune.setAlignmentX(LEFT_ALIGNMENT);
            tune.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
            tune.add(Ui.muted("режим", 12.5f));
            tune.add(modeControl(device));
            tune.add(Box.createHorizontalStrut(8));
            tune.add(Ui.muted("порядок цветов", 12.5f));
            tune.add(orderControl(device));
            tune.add(testButton(device));

            if (settings.canvas) {
                tune.add(Box.createHorizontalStrut(8));
                tune.add(Ui.muted("на холсте", 12.5f));
                tune.add(moveButton(device, found, -1));
                tune.add(moveButton(device, found, 1));
            }
            rgbDevices.add(tune);

            for (var zone : device.resizable()) {
                rgbDevices.add(zoneRow(device, zone));
            }
        }

        var hint = Ui.muted("<html><body style='width:640px'><b>Нет клавиатуры, вентиляторов или памяти?</b><br>"
                + "1. Запусти OpenRGB <b>от имени администратора</b> — без этого он не видит память и часть "
                + "платы.<br>"
                + "2. В OpenRGB: Settings → <b>Supported Devices</b> — проверь, что нужные устройства включены, "
                + "и нажми Rescan.<br>"
                + "3. Вентиляторы на ARGB-разъёме платы — это зоны под устройством платы выше («разъём …»). "
                + "Впиши, сколько на нём светодиодов (не знаешь — 40), и нажми «Задать»: V.I.R.U.S. будет "
                + "ставить это число сам при каждом подключении.<br>"
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
        stopAnimation();
        if (devices.isEmpty()) {
            // Ещё не подключались — подключимся и покрасим разом
            worker.submit(() -> {
                try (var rgb = new OpenRgb(settings.host, OpenRgb.PORT)) {
                    var found = rgb.devices();

                    var painted = paintEach(rgb, found, color, settings.deviceLevels);

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
        stopAnimation();
        worker.submit(() -> {
            try (var rgb = new OpenRgb(settings.host, OpenRgb.PORT)) {
                var painted = paintEach(rgb, which, color, settings.deviceLevels);
                SwingUtilities.invokeLater(() -> toast.accept("Покрашено устройств: " + painted + " из " + which.size()));
            } catch (IOException | RuntimeException e) {
                SwingUtilities.invokeLater(() -> toast.accept("Не покрасилось: " + e.getMessage()));
            }
        });
    }

    /** Красит, что красится; одно упрямое устройство не мешает остальным. */
    static int paintEach(OpenRgb rgb, List<OpenRgb.Device> which, Color color, Map<String, Integer> levels)
            throws IOException {
        var painted = 0;

        for (var device : which) {
            var level = levels.getOrDefault(device.name(), 100);

            // 0 — «не трогать»: устройство остаётся родной программе
            if (device.paintable() && level > 0) {
                rgb.paint(device, Glow.scale(color, level / 100.0));
                painted++;
            }
        }

        return painted;
    }

    /** Найти OpenRGB.exe: сохранённый путь, Program Files или спросить. null — не нашли. */
    private String openRgbPath() {
        var path = settings.openRgb;

        if (path.isBlank() || !Files.isRegularFile(Path.of(path))) {
            var guess = Path.of(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"), "OpenRGB", "OpenRGB.exe");
            path = Files.isRegularFile(guess) ? guess.toString() : choose("Где OpenRGB.exe?");

            if (path == null) {
                return null;
            }

            settings.openRgb = path;
            settings.save();
        }

        return path;
    }

    private void toggleOpenRgbTask(boolean on) {
        var path = on ? openRgbPath() : settings.openRgb;

        if (on && path == null) {
            return;
        }

        worker.submit(() -> {
            try {
                if (on) {
                    OpenRgbTask.install(path);
                } else {
                    OpenRgbTask.remove();
                }

                var exists = OpenRgbTask.exists();
                settings.openRgbTask = exists;
                settings.save();
                SwingUtilities.invokeLater(() -> toast.accept(exists
                        ? "Готово: OpenRGB будет запускаться при входе в Windows, а V.I.R.U.S. поднимет его, если он закроется."
                        : on ? "Задача не создалась — Windows не дала разрешение?" : "OpenRGB больше не запускается сам."));

                if (exists) {
                    OpenRgbTask.start();
                }
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> toast.accept("Не получилось: " + e.getMessage()));
            }
        });
    }

    private void launchOpenRgb() {
        // Задача Планировщика есть — запускаем через неё: от администратора и без вопроса
        if (settings.openRgbTask) {
            worker.submit(() -> {
                var started = OpenRgbTask.start();
                SwingUtilities.invokeLater(() -> rgbStatus.setText(started
                        ? "Запускаю OpenRGB… через несколько секунд он подключится сам."
                        : "Задача запуска OpenRGB не сработала — проверь путь к OpenRGB.exe."));
            });
            return;
        }

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

        // От администратора: иначе OpenRGB не видит память и часть платы (шина SMBus закрыта)
        if (SystemInfo.isWindows()) {
            try {
                var script = "Start-Process -FilePath '" + path.replace("'", "''") + "' -ArgumentList "
                        + "'--server','--startminimized' -Verb RunAs";
                new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                        "-Command", script).start();
            } catch (IOException e) {
                start(Path.of(path), "--server", "--startminimized");
            }
        } else {
            start(Path.of(path), "--server", "--startminimized");
        }

        rgbStatus.setText("Запускаю OpenRGB от администратора — Windows спросит разрешение. Через несколько секунд "
                + "нажми «Подключиться».");
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

        // Режим через OpenRGB запускает сама страница «Устройство» — один цвет поверх не нужен
        if (!LIGHTING || !saved.applyOnStart || "openrgb".equals(saved.engine)) {
            return;
        }

        var thread = new Thread(() -> {
            for (var attempt = 0; attempt < 12; attempt++) {
                try (var rgb = new OpenRgb(saved.host, OpenRgb.PORT)) {
                    var color = dim(saved.color, saved.brightness);

                    paintEach(rgb, rgb.devices(), color, saved.deviceLevels);
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
        int glowMode = 3;
        /** Цветокоррекция для светодиодов: гамма и баланс белого по каналам, %. */
        double gamma = 2.2;
        int balanceRed = 100;
        int balanceGreen = 100;
        int balanceBlue = 100;
        /** Сохранённые профили подсветки и правила «программа → профиль». */
        final List<LightProfiles.Profile> profiles = new ArrayList<>();
        final Map<String, String> appRules = new LinkedHashMap<>();
        /** Создана ли задача Планировщика, которая запускает OpenRGB от администратора. */
        boolean openRgbTask;
        /** Узор: в обратную сторону и масштаб. */
        boolean glowReverse;
        double glowScale = 1;
        /** Мигать подсветкой о событиях: упал процесс, поломка, плагин. */
        boolean signals = true;
        /** Основной цвет берётся из цвета оформления Windows. */
        boolean followAccent;
        /** Ночь: с какого по какой час приглушать и до скольких процентов. */
        boolean nightOn;
        int nightFrom = 0;
        int nightTo = 8;
        int nightLevel = 20;
        /** Простой: через сколько минут без движения мыши гасить; 0 — не гасить. */
        int idleMinutes = 0;
        int idleLevel = 0;
        int rotateMinutes = 0;
        boolean offWhenLocked = true;
        String exitLight = "ровный цвет";
        int screenMonitor = -1;
        /** Единый холст и порядок устройств на нём. */
        boolean canvas;
        final List<String> canvasOrder = new ArrayList<>();
        /** Свой режим у устройства: имя → номер режима; нет в списке — общий. */
        final Map<String, Integer> deviceModes = new LinkedHashMap<>();
        /** Порядок цветов по устройствам: «GRB» и т.п. — для лент и вентиляторов на WS2812. */
        final Map<String, String> colorOrders = new LinkedHashMap<>();
        /** Яркость по устройствам, %: 0 — не трогать устройство вовсе. */
        final Map<String, Integer> deviceLevels = new LinkedHashMap<>();
        /** Сколько светодиодов на разъёмах ARGB: «устройство|зона» → число. */
        final Map<String, Integer> zoneSizes = new LinkedHashMap<>();
        /** Чем играть режимы: «openrgb» или «signalrgb». */
        String engine = "openrgb";
        int glowBrightness = 100;
        boolean glowOn = true;
        int glowSpeed = 50;
        Color glowColor = Theme.ACCENT;
        Color glowColor2 = new Color(0x8A2BE2);
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
                    settings.glowMode = json.get("glowMode") instanceof Number n ? n.intValue() : 3;
                    settings.gamma = json.get("gamma") instanceof Number n ? n.doubleValue() : 2.2;
                    settings.balanceRed = json.get("balanceRed") instanceof Number n ? n.intValue() : 100;
                    settings.balanceGreen = json.get("balanceGreen") instanceof Number n ? n.intValue() : 100;
                    settings.balanceBlue = json.get("balanceBlue") instanceof Number n ? n.intValue() : 100;

                    if (json.get("profiles") instanceof List<?> list) {
                        for (var item : list) {
                            if (item instanceof Map<?, ?> map) {
                                settings.profiles.add(LightProfiles.Profile.fromJson(map));
                            }
                        }
                    }

                    if (json.get("appRules") instanceof Map<?, ?> rules) {
                        rules.forEach((key, value) -> settings.appRules.put(String.valueOf(key), String.valueOf(value)));
                    }

                    settings.nightOn = Boolean.TRUE.equals(json.get("nightOn"));
                    settings.followAccent = Boolean.TRUE.equals(json.get("followAccent"));
                    settings.signals = !Boolean.FALSE.equals(json.get("signals"));
                    settings.glowReverse = Boolean.TRUE.equals(json.get("glowReverse"));
                    settings.glowScale = json.get("glowScale") instanceof Number n ? n.doubleValue() : 1;
                    settings.openRgbTask = Boolean.TRUE.equals(json.get("openRgbTask"));
                    settings.nightFrom = json.get("nightFrom") instanceof Number n ? n.intValue() : 0;
                    settings.nightTo = json.get("nightTo") instanceof Number n ? n.intValue() : 8;
                    settings.nightLevel = json.get("nightLevel") instanceof Number n ? n.intValue() : 20;
                    settings.idleMinutes = json.get("idleMinutes") instanceof Number n ? n.intValue() : 0;
                    settings.idleLevel = json.get("idleLevel") instanceof Number n ? n.intValue() : 0;
                    settings.rotateMinutes = json.get("rotateMinutes") instanceof Number n ? n.intValue() : 0;
                    settings.offWhenLocked = !Boolean.FALSE.equals(json.get("offWhenLocked"));
                    settings.screenMonitor = json.get("screenMonitor") instanceof Number n ? n.intValue() : -1;
                    settings.exitLight = json.get("exitLight") instanceof String exit && EXIT_CHOICES.contains(exit)
                            ? exit : "ровный цвет";

                    settings.canvas = Boolean.TRUE.equals(json.get("canvas"));

                    if (json.get("canvasOrder") instanceof List<?> order) {
                        order.forEach(name -> settings.canvasOrder.add(String.valueOf(name)));
                    }

                    if (json.get("deviceModes") instanceof Map<?, ?> modes) {
                        modes.forEach((key, value) -> {
                            if (value instanceof Number n) {
                                settings.deviceModes.put(String.valueOf(key), n.intValue());
                            }
                        });
                    }

                    if (json.get("colorOrders") instanceof Map<?, ?> orders) {
                        orders.forEach((key, value) -> settings.colorOrders.put(String.valueOf(key), String.valueOf(value)));
                    }

                    if (json.get("deviceLevels") instanceof Map<?, ?> levels) {
                        levels.forEach((key, value) -> {
                            if (value instanceof Number n) {
                                settings.deviceLevels.put(String.valueOf(key), n.intValue());
                            }
                        });
                    }

                    if (json.get("zoneSizes") instanceof Map<?, ?> sizes) {
                        sizes.forEach((key, value) -> {
                            if (value instanceof Number n) {
                                settings.zoneSizes.put(String.valueOf(key), n.intValue());
                            }
                        });
                    }
                    settings.engine = String.valueOf(json.getOrDefault("engine", "openrgb"));
                    settings.glowBrightness = json.get("glowBrightness") instanceof Number n ? n.intValue() : 100;
                    settings.glowOn = !Boolean.FALSE.equals(json.get("glowOn"));
                    settings.glowSpeed = json.get("glowSpeed") instanceof Number n ? n.intValue() : 50;
                    settings.glowColor = Color.decode(String.valueOf(json.getOrDefault("glowColor", "#39FF8A")));
                    settings.glowColor2 = Color.decode(String.valueOf(json.getOrDefault("glowColor2", "#8A2BE2")));

                    if (json.get("apps") instanceof Map<?, ?> apps) {
                        apps.forEach((key, value) -> settings.apps.put(String.valueOf(key), String.valueOf(value)));
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // испорченный файл — начинаем с умолчаний
            }

            settings.applyColor();
            return settings;
        }

        /** Цветокоррекцию — в общий конвейер цвета: им пользуются и режимы, и «Покрасить всё». */
        void applyColor() {
            LedColor.gamma = Math.max(1, Math.min(3, gamma));
            LedColor.red = balanceRed / 100.0;
            LedColor.green = balanceGreen / 100.0;
            LedColor.blue = balanceBlue / 100.0;
            LedColor.orders = Map.copyOf(colorOrders);
        }

        void save() {
            var json = new LinkedHashMap<String, Object>();
            json.put("host", host);
            json.put("openRgb", openRgb);
            json.put("color", String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue()));
            json.put("brightness", brightness);
            json.put("applyOnStart", applyOnStart);
            json.put("glowMode", glowMode);
            json.put("gamma", gamma);
            json.put("balanceRed", balanceRed);
            json.put("balanceGreen", balanceGreen);
            json.put("balanceBlue", balanceBlue);
            json.put("zoneSizes", zoneSizes);
            json.put("deviceLevels", deviceLevels);
            json.put("colorOrders", colorOrders);
            json.put("deviceModes", deviceModes);
            json.put("canvas", canvas);
            json.put("canvasOrder", canvasOrder);
            json.put("nightOn", nightOn);
            json.put("followAccent", followAccent);
            json.put("signals", signals);
            json.put("glowReverse", glowReverse);
            json.put("glowScale", glowScale);
            json.put("openRgbTask", openRgbTask);
            json.put("nightFrom", nightFrom);
            json.put("nightTo", nightTo);
            json.put("nightLevel", nightLevel);
            json.put("idleMinutes", idleMinutes);
            json.put("idleLevel", idleLevel);
            json.put("rotateMinutes", rotateMinutes);
            json.put("offWhenLocked", offWhenLocked);
            json.put("exitLight", exitLight);
            json.put("screenMonitor", screenMonitor);
            json.put("profiles", profiles.stream().map(LightProfiles.Profile::toJson).toList());
            json.put("appRules", appRules);
            json.put("engine", engine);
            json.put("glowBrightness", glowBrightness);
            json.put("glowOn", glowOn);
            json.put("glowSpeed", glowSpeed);
            json.put("glowColor", SignalRgb.hex(glowColor));
            json.put("glowColor2", SignalRgb.hex(glowColor2));
            json.put("apps", apps);

            try {
                Files.writeString(file, Json.write(json), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // не записалось — в следующий раз попробуем снова
            }
        }
    }
}
