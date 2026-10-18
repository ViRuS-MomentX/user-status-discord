package ru.virus.manager;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.font.TextAttribute;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Задачи: идеи для плагина и всё прочее, с номерами, статусами и поиском.
 *
 * <p>Сохраняется само — см. {@link TaskStore}. В заметках работают списки:
 * Enter после «1. …» начинает «2. », после «- », «• » и «[ ] » — такой же
 * пункт; Ctrl+D отмечает галочку в строке.
 */
final class TasksPage extends Ui.Card {

    /** Пункт списка в начале строки: отступ, затем номер, маркер или галочка. */
    static final Pattern LIST_ITEM = Pattern.compile("^(\\s*)(\\d+[.)]|[-•*]|\\[[ xX]])(\\s+)(.*)$");

    private final TaskStore store;
    private final Consumer<String> toast;
    private final Supplier<List<String>> projects;

    private final DefaultListModel<TaskStore.Task> model = new DefaultListModel<>();
    private final JList<TaskStore.Task> list = new JList<>(model);
    private final JTextField search = new JTextField();
    private final JComboBox<String> projectFilter = new JComboBox<>();
    private final Map<String, JToggleButton> chips = new LinkedHashMap<>();
    private final JLabel saved = Ui.muted("", 12.5f);

    private final CardLayout editorCards = new CardLayout();
    private final JPanel editor = Ui.transparent(editorCards);
    private final JLabel number = Ui.label("", Theme.mono.deriveFont(Font.BOLD, 20f), Theme.ACCENT);
    private final JLabel dates = Ui.muted("", 12f);
    private final JTextField title = new JTextField();
    private final JComboBox<TaskStore.Status> status = new JComboBox<>(TaskStore.Status.values());
    private final JComboBox<TaskStore.Priority> priority = new JComboBox<>(TaskStore.Priority.values());
    private final JComboBox<String> project = new JComboBox<>();
    private final JTextArea notes = new JTextArea();
    private final JLabel progress = Ui.muted("", 12.5f);

    private String filter = "all";
    private TaskStore.Task current;
    /** Пока задача выкладывается в поля, их «правки» — не правки. */
    private boolean loading;

    TasksPage(MatrixBackground background, TaskStore store, Consumer<String> toast, Supplier<List<String>> projects) {
        super(new BorderLayout(0, 14));
        this.store = store;
        this.toast = toast;
        this.projects = projects;
        setBorder(BorderFactory.createEmptyBorder(20, 22, 20, 22));
        Ui.rippleThrough(this, background);

        store.onSaved(time -> SwingUtilities.invokeLater(() ->
                saved.setText("✓ сохранено в " + new SimpleDateFormat("HH:mm:ss").format(new Date(time)))));
        store.onError(error -> SwingUtilities.invokeLater(() -> {
            saved.setText("не сохранилось!");
            saved.setForeground(Theme.ERROR);
            toast.accept(error);
        }));

        add(header(), BorderLayout.NORTH);

        var body = Ui.transparent(new BorderLayout(16, 0));
        body.add(listPane(), BorderLayout.WEST);
        body.add(editorPane(), BorderLayout.CENTER);
        add(body, BorderLayout.CENTER);

        bindKeys();
        reload();
    }

    // ----------------------------------------------------------------- вёрстка

    private JComponent header() {
        var titles = Ui.transparent(new BorderLayout(0, 4));
        titles.add(Ui.label("Задачи", Theme.ui.deriveFont(Font.BOLD, 22f), Theme.TEXT), BorderLayout.NORTH);
        titles.add(Ui.muted("Идеи для плагина и всё прочее. Сохраняется само — даже если выключится свет.", 13f),
                BorderLayout.CENTER);

        var copy = Ui.button("Копировать список");
        copy.setIcon(Icons.of(Icons.Kind.REPORT, Theme.TEXT));
        copy.setToolTipText("Видимые задачи текстом — вставить в чат или заметку");
        copy.addActionListener(e -> copyList());

        var create = Ui.primary("Новая задача");
        create.setIcon(Icons.of(Icons.Kind.PLUS, new Color(0x04, 0x14, 0x0B)));
        create.setToolTipText("Ctrl+N");
        create.addActionListener(e -> create());

        var right = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.add(saved);
        right.add(copy);
        right.add(create);

        var head = Ui.transparent(new BorderLayout(12, 0));
        head.add(titles, BorderLayout.CENTER);
        head.add(right, BorderLayout.EAST);

        // Фильтры: статус, проект, поиск
        var group = new ButtonGroup();
        var chipRow = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));

        for (var key : List.of("all", "idea", "doing", "done")) {
            var chip = new JToggleButton();
            chip.putClientProperty("JButton.buttonType", "roundRect");
            chip.setFocusable(false);
            chip.addActionListener(e -> {
                filter = key;
                refilter();
            });
            group.add(chip);
            chips.put(key, chip);
            chipRow.add(chip);
        }

        chips.get("all").setSelected(true);

        search.putClientProperty("JTextField.placeholderText", "Поиск: текст или #номер   (Ctrl+F)");
        search.putClientProperty("JTextField.showClearButton", true);
        search.setPreferredSize(new Dimension(260, 32));
        search.getDocument().addDocumentListener(onChange(this::refilter));

        projectFilter.setPreferredSize(new Dimension(180, 32));
        projectFilter.addActionListener(e -> refilter());

        var filters = Ui.transparent(new BorderLayout(10, 0));
        filters.add(chipRow, BorderLayout.WEST);
        var rightFilters = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rightFilters.add(projectFilter);
        rightFilters.add(search);
        filters.add(rightFilters, BorderLayout.EAST);

        var top = Ui.transparent(new BorderLayout(0, 14));
        top.add(head, BorderLayout.NORTH);
        top.add(filters, BorderLayout.CENTER);
        return top;
    }

    private JComponent listPane() {
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new Row());
        list.setBackground(new Color(0x08, 0x0B, 0x0A));
        list.setFixedCellHeight(-1);
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && list.getSelectedValue() != null && list.getSelectedValue() != current) {
                open(list.getSelectedValue());
            }
        });

        var scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.LINE));
        scroll.getViewport().setBackground(list.getBackground());
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setPreferredSize(new Dimension(370, 10));
        return scroll;
    }

    private JComponent editorPane() {
        // Пусто — подсказка
        var empty = Ui.transparent(new BorderLayout());
        var hint = Ui.muted("<html><div style='text-align:center'>Выбери задачу слева или создай новую — "
                + "<b>Ctrl+N</b>.<br><br>Номер у задачи навсегда: на «#12» можно сослаться где угодно.</div></html>", 14f);
        hint.setHorizontalAlignment(JLabel.CENTER);
        empty.add(hint, BorderLayout.CENTER);

        // Поля задачи
        title.setFont(Theme.ui.deriveFont(Font.BOLD, 18f));
        title.putClientProperty("JTextField.placeholderText", "О чём задача?");
        title.getDocument().addDocumentListener(onChange(() -> edit(task -> task.title = title.getText())));

        status.addActionListener(e -> edit(task -> task.status = (TaskStore.Status) status.getSelectedItem()));
        priority.addActionListener(e -> edit(task -> task.priority = (TaskStore.Priority) priority.getSelectedItem()));
        project.setEditable(true);
        project.addActionListener(e -> edit(task -> task.project = projectText()));
        var projectEditor = (JTextField) project.getEditor().getEditorComponent();
        projectEditor.putClientProperty("JTextField.placeholderText", "без проекта");
        projectEditor.getDocument().addDocumentListener(onChange(() -> edit(task -> task.project = projectText())));

        var fields = Ui.transparent(new GridLayout(1, 3, 10, 0));
        fields.add(labelled("Статус", status));
        fields.add(labelled("Приоритет", priority));
        fields.add(labelled("Проект", project));

        notes.setLineWrap(true);
        notes.setWrapStyleWord(true);
        notes.setFont(Theme.ui.deriveFont(14.5f));
        notes.setMargin(new java.awt.Insets(10, 12, 10, 12));
        notes.putClientProperty("JTextArea.placeholderText", "Подробности, шаги, ссылки…");
        notes.getDocument().addDocumentListener(onChange(() -> {
            edit(task -> task.notes = notes.getText());
            showProgress();
        }));
        smartLists(notes);

        var notesScroll = new JScrollPane(notes);
        notesScroll.setBorder(BorderFactory.createLineBorder(Theme.LINE));

        var notesHint = Ui.muted("Enter — список · Ctrl+D — галочка · Alt+↑↓ — двигать", 12f);
        notesHint.setToolTipText("Списки: «1.», «-», «•», «[ ]». Пустой пункт + Enter — конец списка.");
        var notesFoot = Ui.transparent(new BorderLayout(10, 0));
        notesFoot.add(notesHint, BorderLayout.CENTER);
        notesFoot.add(progress, BorderLayout.EAST);

        var notesBlock = Ui.transparent(new BorderLayout(0, 6));
        notesBlock.add(Ui.caption("Заметки"), BorderLayout.NORTH);
        notesBlock.add(notesScroll, BorderLayout.CENTER);
        var under = Ui.transparent(new BorderLayout(0, 8));
        under.add(notesFoot, BorderLayout.NORTH);
        under.add(photoBlock(), BorderLayout.SOUTH);
        notesBlock.add(under, BorderLayout.SOUTH);
        pasteImages(notes);

        var headLine = Ui.transparent(new BorderLayout(12, 0));
        headLine.add(number, BorderLayout.WEST);
        headLine.add(dates, BorderLayout.CENTER);

        var upper = Ui.transparent(new BorderLayout(0, 12));
        upper.add(headLine, BorderLayout.NORTH);
        upper.add(title, BorderLayout.CENTER);
        upper.add(fields, BorderLayout.SOUTH);

        var up = Ui.button("Выше");
        up.setIcon(Icons.of(Icons.Kind.UP, Theme.TEXT));
        up.setToolTipText("Alt+↑");
        up.addActionListener(e -> move(-1));
        var down = Ui.button("Ниже");
        down.setToolTipText("Alt+↓");
        down.addActionListener(e -> move(1));
        var done = Ui.button("Готово");
        done.setToolTipText("Отметить выполненной — Ctrl+Enter");
        done.addActionListener(e -> toggleDone());
        var delete = Ui.danger("Удалить");
        delete.setIcon(Icons.of(Icons.Kind.TRASH, Theme.ERROR));
        delete.addActionListener(e -> delete());

        var actions = Ui.transparent(new BorderLayout());
        var leftActions = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        leftActions.add(up);
        leftActions.add(down);
        var rightActions = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rightActions.add(done);
        rightActions.add(delete);
        actions.add(leftActions, BorderLayout.WEST);
        actions.add(rightActions, BorderLayout.EAST);

        var form = Ui.transparent(new BorderLayout(0, 14));
        form.add(upper, BorderLayout.NORTH);
        form.add(notesBlock, BorderLayout.CENTER);
        form.add(actions, BorderLayout.SOUTH);

        editor.add(empty, "empty");
        editor.add(form, "task");
        return editor;
    }

    // ----------------------------------------------------------------- фото

    private final JPanel photos = Ui.transparent(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 4));
    private final java.util.concurrent.ExecutorService thumbs = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "virus-thumbs");
        thread.setDaemon(true);
        return thread;
    });

    private JComponent photoBlock() {
        var scroll = new JScrollPane(photos, JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setPreferredSize(new Dimension(10, 104));

        var block = Ui.transparent(new BorderLayout(0, 4));
        block.add(Ui.caption("Фото — кнопкой, перетаскиванием или Ctrl+V в заметках"), BorderLayout.NORTH);
        block.add(scroll, BorderLayout.CENTER);

        // Файлы картинок можно бросить прямо на полоску
        var drop = new javax.swing.TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return current != null && support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport support) {
                try {
                    var files = (List<java.io.File>) support.getTransferable()
                            .getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
                    attachFiles(files.stream().map(java.io.File::toPath).toList());
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
        };
        photos.setTransferHandler(drop);
        scroll.setTransferHandler(drop);
        block.setTransferHandler(drop);
        return block;
    }

    static final String[] IMAGE_TYPES = { "png", "jpg", "jpeg", "gif", "bmp", "webp" };

    private void choosePhotos() {
        if (current == null) {
            return;
        }

        attachFiles(NativeFiles.open(this, "Фото к задаче #" + current.number, "task-photos", true, IMAGE_TYPES));
    }

    private void attachFiles(List<java.nio.file.Path> files) {
        var task = current;

        if (task == null) {
            return;
        }

        var added = 0;

        for (var file : files) {
            if (NativeFiles.fits(file, IMAGE_TYPES)) {
                try {
                    store.attach(task, file);
                    added++;
                } catch (java.io.IOException e) {
                    toast.accept("Фото не прикрепилось: " + e.getMessage());
                }
            }
        }

        if (added == 0 && !files.isEmpty()) {
            toast.accept("Это не картинки — подходят png, jpg, gif, bmp, webp.");
        }

        showPhotos();
        list.repaint();
    }

    /** Ctrl+V в заметках: картинка из буфера — в фото задачи, текст — как обычно. */
    private void pasteImages(JTextArea area) {
        var paste = area.getActionMap().get("paste");
        area.getActionMap().put("paste", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();

                try {
                    if (current != null && clipboard.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.imageFlavor)) {
                        var picture = (java.awt.Image) clipboard.getData(java.awt.datatransfer.DataFlavor.imageFlavor);
                        store.attach(current, toBuffered(picture));
                        showPhotos();
                        list.repaint();
                        toast.accept("Картинка из буфера прикреплена к #" + current.number);
                        return;
                    }
                } catch (Exception ex) {
                    toast.accept("Картинка не вставилась: " + ex.getMessage());
                    return;
                }

                if (paste != null) {
                    paste.actionPerformed(e);
                }
            }
        });
    }

    private static java.awt.image.BufferedImage toBuffered(java.awt.Image image) {
        if (image instanceof java.awt.image.BufferedImage buffered) {
            return buffered;
        }

        var copy = new java.awt.image.BufferedImage(Math.max(1, image.getWidth(null)), Math.max(1, image.getHeight(null)),
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var g = copy.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return copy;
    }

    private void showPhotos() {
        photos.removeAll();
        var task = current;

        if (task != null) {
            for (var name : List.copyOf(task.images)) {
                photos.add(new Thumb(task, name));
            }
        }

        var add = Ui.button("Фото");
        add.setIcon(Icons.of(Icons.Kind.PLUS, Theme.ACCENT));
        add.setToolTipText("Прикрепить фото — откроется Проводник");
        add.addActionListener(e -> choosePhotos());
        add.setPreferredSize(new Dimension(96, 84));
        photos.add(add);
        photos.revalidate();
        photos.repaint();
    }

    /** Миниатюра фото: клик — открыть, крестик в углу — убрать. */
    private final class Thumb extends JComponent {
        private final TaskStore.Task task;
        private final String name;
        private java.awt.Image picture;
        private boolean hover;

        Thumb(TaskStore.Task task, String name) {
            this.task = task;
            this.name = name;
            setPreferredSize(new Dimension(112, 84));
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            setToolTipText(name + " — открыть");

            var file = store.image(task, name);
            thumbs.submit(() -> {
                try {
                    var full = javax.imageio.ImageIO.read(file.toFile());

                    if (full != null) {
                        var scale = Math.max(112.0 / full.getWidth(), 84.0 / full.getHeight());
                        var scaled = full.getScaledInstance((int) Math.ceil(full.getWidth() * scale),
                                (int) Math.ceil(full.getHeight() * scale), java.awt.Image.SCALE_SMOOTH);
                        var ready = toBuffered(scaled);
                        SwingUtilities.invokeLater(() -> {
                            picture = ready;
                            repaint();
                        });
                    }
                } catch (Exception ignored) {
                    // не картинка или нет файла — останется рамка с именем
                }
            });

            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    if (e.getX() > getWidth() - 24 && e.getY() < 24) {
                        remove();
                    } else {
                        open();
                    }
                }

                @Override
                public void mouseEntered(java.awt.event.MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(java.awt.event.MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
        }

        private void open() {
            try {
                java.awt.Desktop.getDesktop().open(store.image(task, name).toFile());
            } catch (Exception e) {
                toast.accept("Фото не открылось: " + e.getMessage());
            }
        }

        private void remove() {
            if (JOptionPane.showConfirmDialog(TasksPage.this, "Убрать это фото из задачи #" + task.number + "?",
                    Virus.NAME, JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
                return;
            }

            try {
                store.detach(task, name);
            } catch (java.io.IOException e) {
                toast.accept("Фото не удалилось: " + e.getMessage());
            }

            showPhotos();
            list.repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            var g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            var clip = new java.awt.geom.RoundRectangle2D.Double(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            g.setColor(Theme.RAISED);
            g.fill(clip);

            if (picture != null) {
                g.setClip(clip);
                g.drawImage(picture, (getWidth() - picture.getWidth(null)) / 2,
                        (getHeight() - picture.getHeight(null)) / 2, null);
                g.setClip(null);
            } else {
                g.setColor(Theme.MUTED);
                g.setFont(Theme.ui.deriveFont(11f));
                g.drawString("загрузка…", 10, getHeight() / 2 + 4);
            }

            g.setColor(hover ? Theme.ACCENT : Theme.LINE_2);
            g.draw(clip);

            if (hover) {
                g.setColor(new Color(0, 0, 0, 170));
                g.fillOval(getWidth() - 22, 4, 18, 18);
                g.setColor(Color.WHITE);
                g.setStroke(new java.awt.BasicStroke(1.8f));
                g.drawLine(getWidth() - 17, 9, getWidth() - 9, 17);
                g.drawLine(getWidth() - 9, 9, getWidth() - 17, 17);
            }

            g.dispose();
        }
    }

    private static JComponent labelled(String caption, JComponent field) {
        var panel = Ui.transparent(new BorderLayout(0, 5));
        panel.add(Ui.caption(caption), BorderLayout.NORTH);
        panel.add(field, BorderLayout.CENTER);
        return panel;
    }

    private void bindKeys() {
        var inputs = getInputMap(WHEN_IN_FOCUSED_WINDOW);
        var actions = getActionMap();
        var menu = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

        bind(inputs, actions, KeyStroke.getKeyStroke(KeyEvent.VK_N, menu), "new", this::create);
        bind(inputs, actions, KeyStroke.getKeyStroke(KeyEvent.VK_F, menu), "find", search::requestFocusInWindow);
        bind(inputs, actions, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, menu), "done", this::toggleDone);
        bind(inputs, actions, KeyStroke.getKeyStroke(KeyEvent.VK_UP, InputEvent.ALT_DOWN_MASK), "up", () -> move(-1));
        bind(inputs, actions, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK), "down", () -> move(1));
    }

    private static void bind(javax.swing.InputMap inputs, javax.swing.ActionMap actions, KeyStroke key,
                             String name, Runnable action) {
        inputs.put(key, name);
        actions.put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    // ----------------------------------------------------------------- данные

    /** Перечитать проекты в списках и показать задачи заново. */
    void reload() {
        var names = new ArrayList<String>();

        for (var task : store.all()) {
            if (!task.project.isBlank() && !names.contains(task.project)) {
                names.add(task.project);
            }
        }

        for (var name : projects.get()) {
            if (!names.contains(name)) {
                names.add(name);
            }
        }

        names.sort(String.CASE_INSENSITIVE_ORDER);

        var chosen = (String) projectFilter.getSelectedItem();
        loading = true;
        projectFilter.removeAllItems();
        projectFilter.addItem("Все проекты");
        names.forEach(projectFilter::addItem);
        projectFilter.setSelectedItem(chosen != null && names.contains(chosen) ? chosen : "Все проекты");

        var typed = current == null ? "" : current.project;
        project.removeAllItems();
        project.addItem("");
        names.forEach(project::addItem);
        project.setSelectedItem(typed);
        loading = false;

        refilter();

        if (current == null && !model.isEmpty()) {
            list.setSelectedIndex(0);
        }
    }

    private void refilter() {
        if (loading) {
            return;
        }

        var all = store.all();
        var query = search.getText();
        var chosenProject = projectFilter.getSelectedIndex() <= 0 ? null : (String) projectFilter.getSelectedItem();

        // Счётчики на кнопках — по всем задачам, без учёта поиска
        var counts = new int[4];

        for (var task : all) {
            counts[0]++;
            counts[1 + task.status.ordinal()]++;
        }

        chips.get("all").setText("Все  " + counts[0]);
        chips.get("idea").setText("Идеи  " + counts[1]);
        chips.get("doing").setText("В работе  " + counts[2]);
        chips.get("done").setText("Готово  " + counts[3]);

        loading = true;
        model.clear();

        for (var task : all) {
            // Открытую задачу не прячем, даже если она перестала подходить под фильтр:
            // сменил статус — а она исчезла из-под руки, так неудобно
            if (task == current || (statusFits(task) && task.matches(query)
                    && (chosenProject == null || chosenProject.equals(task.project)))) {
                model.addElement(task);
            }
        }

        list.setSelectedValue(current, true);
        loading = false;

        if (current == null || !all.contains(current)) {
            current = null;
            editorCards.show(editor, "empty");
        }
    }

    private boolean statusFits(TaskStore.Task task) {
        return switch (filter) {
            case "idea" -> task.status == TaskStore.Status.IDEA;
            case "doing" -> task.status == TaskStore.Status.DOING;
            case "done" -> task.status == TaskStore.Status.DONE;
            default -> true;
        };
    }

    private void open(TaskStore.Task task) {
        if (loading) {
            return;
        }

        current = task;
        loading = true;
        number.setText("#" + task.number);
        var format = new SimpleDateFormat("dd.MM.yyyy HH:mm");
        dates.setText("создана " + format.format(new Date(task.created))
                + (task.updated > task.created + 1000 ? " · изменена " + format.format(new Date(task.updated)) : ""));
        title.setText(task.title);
        status.setSelectedItem(task.status);
        priority.setSelectedItem(task.priority);
        project.setSelectedItem(task.project);
        notes.setText(task.notes);
        notes.setCaretPosition(0);
        loading = false;
        showPhotos();
        showProgress();
        editorCards.show(editor, "task");
    }

    private void edit(Consumer<TaskStore.Task> change) {
        if (loading || current == null) {
            return;
        }

        store.edit(current, change);
        list.repaint();
    }

    private String projectText() {
        var text = ((JTextField) project.getEditor().getEditorComponent()).getText();
        return text == null ? "" : text.strip();
    }

    private void showProgress() {
        if (current == null) {
            return;
        }

        var checklist = current.checklist();
        progress.setText(checklist[1] == 0 ? "" : "выполнено " + checklist[0] + " из " + checklist[1]);
    }

    private void create() {
        // Новая — в общий список, иначе под фильтром «Готово» её не было бы видно
        filter = "all";
        chips.get("all").setSelected(true);
        search.setText("");
        projectFilter.setSelectedIndex(0);

        var task = store.add();
        current = null;
        refilter();
        list.setSelectedValue(task, true);
        open(task);
        title.requestFocusInWindow();
    }

    private void move(int step) {
        if (current != null && store.move(current, step)) {
            refilter();
        }
    }

    private void toggleDone() {
        if (current == null) {
            return;
        }

        var next = current.status == TaskStore.Status.DONE ? TaskStore.Status.IDEA : TaskStore.Status.DONE;
        status.setSelectedItem(next);
        toast.accept("#" + current.number + (next == TaskStore.Status.DONE ? " — готово" : " — снова в идеях"));
    }

    private void delete() {
        if (current == null) {
            return;
        }

        var name = current.title.isBlank() ? "без названия" : "«" + current.title + "»";

        if (JOptionPane.showConfirmDialog(this, "Удалить задачу #" + current.number + " " + name + "?",
                Virus.NAME, JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }

        var index = Math.max(0, list.getSelectedIndex());
        store.remove(current);
        current = null;
        refilter();

        if (!model.isEmpty()) {
            list.setSelectedIndex(Math.min(index, model.size() - 1));
        }
    }

    private void copyList() {
        var visible = new ArrayList<TaskStore.Task>();

        for (var i = 0; i < model.size(); i++) {
            visible.add(model.get(i));
        }

        if (visible.isEmpty()) {
            toast.accept("Нечего копировать.");
            return;
        }

        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(TaskStore.asText(visible)), null);
        toast.accept("Скопировано задач: " + visible.size());
    }

    /** Сохранить сразу — при выходе. */
    void flush() {
        try {
            store.saveNow();
        } catch (IOException e) {
            toast.accept("Задачи не сохранились: " + e.getMessage());
        }
    }

    // ----------------------------------------------------------------- списки в заметках

    /**
     * Что вставить после Enter в конце строки {@code line}: продолжение списка,
     * пустая строка вместо пустого пункта (второй Enter заканчивает список) или
     * {@code null} — обычный перенос.
     *
     * @return {@code ""} — пункт был пустой, его маркер надо стереть
     */
    static String continueList(String line) {
        var matcher = LIST_ITEM.matcher(line);

        if (!matcher.matches()) {
            return null;
        }

        if (matcher.group(4).isBlank()) {
            return "";
        }

        var indent = matcher.group(1);
        var marker = matcher.group(2);
        var gap = matcher.group(3);

        if (Character.isDigit(marker.charAt(0))) {
            var digits = marker.substring(0, marker.length() - 1);
            marker = (Long.parseLong(digits) + 1) + marker.substring(marker.length() - 1);
        } else if (marker.startsWith("[")) {
            marker = "[ ]";
        }

        return indent + marker + gap;
    }

    /** Отметить или снять галочку в строке; строка без галочки — получает её. */
    static String toggleCheck(String line) {
        var matcher = Pattern.compile("^(\\s*)\\[([ xX])](.*)$").matcher(line);

        if (matcher.matches()) {
            var mark = matcher.group(2).isBlank() ? "x" : " ";
            return matcher.group(1) + "[" + mark + "]" + matcher.group(3);
        }

        var item = LIST_ITEM.matcher(line);

        if (item.matches()) {
            return item.group(1) + "[ ] " + item.group(4);
        }

        var indent = line.length() - line.stripLeading().length();
        return line.substring(0, indent) + "[ ] " + line.substring(indent);
    }

    private static void smartLists(JTextArea area) {
        var enter = area.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0));
        var plain = area.getActionMap().get(enter);

        area.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "virus-list-enter");
        area.getActionMap().put("virus-list-enter", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                try {
                    var caret = area.getCaretPosition();
                    var row = area.getLineOfOffset(caret);
                    var start = area.getLineStartOffset(row);
                    var end = area.getLineEndOffset(row);
                    var line = area.getText(start, end - start).replace("\n", "");

                    // Только в конце строки: посреди текста Enter просто переносит
                    if (caret == start + line.length()) {
                        var next = continueList(line);

                        if (next != null && next.isEmpty()) {
                            area.replaceRange("", start, start + line.length());
                            return;
                        }

                        if (next != null) {
                            area.insert("\n" + next, caret);
                            return;
                        }
                    }
                } catch (BadLocationException ignored) {
                    // упадём в обычный Enter
                }

                if (plain != null) {
                    plain.actionPerformed(e);
                } else {
                    area.replaceSelection("\n");
                }
            }
        });

        var menu = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        area.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_D, menu), "virus-list-check");
        area.getActionMap().put("virus-list-check", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                try {
                    var caret = area.getCaretPosition();
                    var row = area.getLineOfOffset(caret);
                    var start = area.getLineStartOffset(row);
                    var end = area.getLineEndOffset(row);
                    var line = area.getText(start, end - start).replace("\n", "");
                    var toggled = toggleCheck(line);
                    area.replaceRange(toggled, start, start + line.length());
                    area.setCaretPosition(Math.min(start + toggled.length(),
                            Math.max(start, caret + toggled.length() - line.length())));
                } catch (BadLocationException ignored) {
                    // строка уехала — ничего не делаем
                }
            }
        });
    }

    private static DocumentListener onChange(Runnable action) {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                action.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                action.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                action.run();
            }
        };
    }

    // ----------------------------------------------------------------- строка списка

    private static Color tone(TaskStore.Status status) {
        return switch (status) {
            case IDEA -> new Color(0x7F, 0xB8, 0xFF);
            case DOING -> Theme.WARN;
            case DONE -> Theme.ACCENT;
        };
    }

    /** Строка в списке: номер, название, статус, проект, галочки. */
    private static final class Row extends JPanel implements ListCellRenderer<TaskStore.Task> {

        private final JLabel number = Ui.label("", Theme.mono.deriveFont(Font.BOLD, 13.5f), Theme.ACCENT);
        private final JLabel title = Ui.text("");
        private final JLabel meta = Ui.muted("", 12f);
        private final JLabel flag = Ui.label("", Theme.ui.deriveFont(Font.BOLD, 13f), Theme.ERROR);
        private boolean selected;
        private Color stripe = Theme.MUTED;

        Row() {
            super(new BorderLayout(10, 2));
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(9, 14, 9, 12));
            number.setPreferredSize(new Dimension(46, 18));
            number.setVerticalAlignment(JLabel.TOP);

            var text = Ui.transparent(new BorderLayout(0, 3));
            text.add(title, BorderLayout.NORTH);
            text.add(meta, BorderLayout.CENTER);

            add(number, BorderLayout.WEST);
            add(text, BorderLayout.CENTER);
            add(flag, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends TaskStore.Task> list, TaskStore.Task task,
                                                      int index, boolean isSelected, boolean cellHasFocus) {
            selected = isSelected;
            stripe = tone(task.status);
            var done = task.status == TaskStore.Status.DONE;

            number.setText("#" + task.number);
            number.setForeground(done ? Theme.DIM : Theme.ACCENT);

            var name = task.title.isBlank() ? "без названия" : task.title;
            title.setText(name);
            Function<Font, Font> strike = font -> done
                    ? font.deriveFont(Map.of(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON)) : font;
            title.setFont(strike.apply(Theme.ui.deriveFont(task.title.isBlank() ? Font.ITALIC : Font.BOLD, 14f)));
            title.setForeground(done || task.title.isBlank() ? Theme.MUTED : Theme.TEXT);

            var parts = new ArrayList<String>();
            parts.add("<font color='" + hex(stripe) + "'>" + task.status.title + "</font>");

            if (!task.project.isBlank()) {
                parts.add(ConfigPage.escape(task.project));
            }

            var checklist = task.checklist();

            if (checklist[1] > 0) {
                parts.add("☑ " + checklist[0] + "/" + checklist[1]);
            }

            if (!task.images.isEmpty()) {
                parts.add("фото: " + task.images.size());
            }

            meta.setText("<html>" + String.join(" · ", parts) + "</html>");
            flag.setText(task.priority == TaskStore.Priority.HIGH ? "!" : task.priority == TaskStore.Priority.LOW ? "↓" : "");
            flag.setForeground(task.priority == TaskStore.Priority.HIGH ? Theme.ERROR : Theme.DIM);
            flag.setToolTipText(task.priority.title);
            return this;
        }

        private static String hex(Color color) {
            return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            var g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            if (selected) {
                g.setColor(new Color(0x16, 0x3A, 0x27));
                g.fillRect(0, 0, getWidth(), getHeight());
            }

            // Цветная черта слева — статус видно, не читая
            g.setColor(stripe);
            g.fillRoundRect(4, 8, 3, getHeight() - 16, 3, 3);
            g.setColor(Theme.LINE);
            g.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
            g.dispose();
            super.paintComponent(graphics);
        }
    }
}
