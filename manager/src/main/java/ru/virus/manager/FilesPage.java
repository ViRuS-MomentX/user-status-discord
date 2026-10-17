package ru.virus.manager;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Файлы проектов: у каждого проекта своя папка в V.I.R.U.S./projects. Файлы
 * докидываются кнопкой или перетаскиванием прямо в окно.
 */
final class FilesPage extends Ui.Card {

    private final Projects projects;
    private final Supervisor supervisor;
    private final ExecutorService worker;
    private final Consumer<String> toast;
    private final Runnable changed;

    private final JPanel projectList = new Ui.FitWidth();
    private final JPanel banners = Ui.transparent(null);
    private final JLabel where = Ui.label("", Theme.mono.deriveFont(12.5f), Theme.MUTED);
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JButton up = Ui.button("Наверх");

    private Path project;
    private Path folder;

    FilesPage(MatrixBackground background, Supervisor supervisor, ExecutorService worker,
              Consumer<String> toast, Runnable changed) {
        super(new BorderLayout(0, 14));
        this.projects = new Projects(supervisor.home());
        this.supervisor = supervisor;
        this.worker = worker;
        this.toast = toast;
        this.changed = changed;
        setBorder(BorderFactory.createEmptyBorder(20, 22, 20, 22));
        Ui.rippleThrough(this, background);

        // Шапка
        var titles = Ui.transparent(new BorderLayout(0, 4));
        titles.add(Ui.label("Файлы проектов", Theme.ui.deriveFont(Font.BOLD, 22f), Theme.TEXT), BorderLayout.NORTH);
        titles.add(Ui.muted("Перетащи файлы в окно — они лягут в открытую папку.", 13f), BorderLayout.CENTER);

        var newProject = Ui.button("Новый проект");
        newProject.setIcon(Icons.of(Icons.Kind.PLUS, Theme.TEXT));
        newProject.addActionListener(e -> newProject());

        var head = Ui.transparent(new BorderLayout(12, 0));
        head.add(titles, BorderLayout.CENTER);
        head.add(newProject, BorderLayout.EAST);

        banners.setLayout(new BoxLayout(banners, BoxLayout.Y_AXIS));

        var top = Ui.transparent(new BorderLayout(0, 12));
        top.add(head, BorderLayout.NORTH);
        top.add(banners, BorderLayout.CENTER);

        // Слева — проекты
        projectList.setLayout(new BoxLayout(projectList, BoxLayout.Y_AXIS));
        var projectScroll = new JScrollPane(projectList);
        projectScroll.setOpaque(false);
        projectScroll.getViewport().setOpaque(false);
        projectScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        projectScroll.setPreferredSize(new Dimension(220, 10));
        projectScroll.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.LINE));

        // Справа — содержимое папки
        up.setIcon(Icons.of(Icons.Kind.UP, Theme.TEXT));
        up.addActionListener(e -> goUp());

        var add = Ui.primary("Добавить файлы…");
        add.setIcon(Icons.of(Icons.Kind.PLUS, new Color(0x04, 0x14, 0x0B)));
        add.addActionListener(e -> chooseAndAdd());

        var mkdir = Ui.button("Новая папка");
        mkdir.setIcon(Icons.of(Icons.Kind.FOLDER, Theme.TEXT));
        mkdir.addActionListener(e -> newFolder());

        var explorer = Ui.button("Открыть в проводнике");
        explorer.addActionListener(e -> open(folder));

        var rename = Ui.button("Переименовать");
        rename.setIcon(Icons.of(Icons.Kind.EDIT, Theme.TEXT));
        rename.addActionListener(e -> renameSelected());

        var delete = Ui.danger("Удалить");
        delete.setIcon(Icons.of(Icons.Kind.TRASH, Theme.ERROR));
        delete.addActionListener(e -> deleteSelected());

        var tools = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        tools.add(up);
        tools.add(add);
        tools.add(mkdir);
        tools.add(explorer);

        var toolRow = Ui.transparent(new BorderLayout(0, 8));
        toolRow.add(tools, BorderLayout.NORTH);
        toolRow.add(where, BorderLayout.CENTER);

        table.setRowHeight(30);
        table.setShowGrid(false);
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setBackground(new Color(0x08, 0x0B, 0x0A));
        table.setForeground(Theme.TEXT);
        table.setSelectionBackground(new Color(0x16, 0x3A, 0x27));
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(420);
        table.getColumnModel().getColumn(1).setPreferredWidth(100);
        table.getColumnModel().getColumn(2).setPreferredWidth(150);
        table.setDefaultRenderer(Object.class, new Renderer());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    var row = table.rowAtPoint(e.getPoint());

                    if (row >= 0) {
                        enter(model.entries.get(row));
                    }
                }
            }
        });

        var tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createLineBorder(Theme.LINE));
        tableScroll.getViewport().setBackground(table.getBackground());

        // Перетаскивание из Проводника — и на таблицу, и на пустое место под ней
        var drop = new DropHandler();
        table.setTransferHandler(drop);
        tableScroll.setTransferHandler(drop);
        setTransferHandler(drop);

        var selection = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        selection.add(rename);
        selection.add(delete);

        var right = Ui.transparent(new BorderLayout(0, 10));
        right.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 0));
        right.add(toolRow, BorderLayout.NORTH);
        right.add(tableScroll, BorderLayout.CENTER);
        right.add(selection, BorderLayout.SOUTH);

        var body = Ui.transparent(new BorderLayout());
        body.add(projectScroll, BorderLayout.WEST);
        body.add(right, BorderLayout.CENTER);

        add(top, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
    }

    /** Перечитать проекты и открытую папку. */
    void reload() {
        banners.removeAll();

        if (projects.botInHome()) {
            var move = Ui.primary("Разложить по проекту");
            move.addActionListener(e -> moveBot());
            var banner = new Ui.Card(new BorderLayout(12, 0), Theme.alpha(Theme.ACCENT, 22));
            banner.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 10));
            banner.add(Ui.label("<html>Бот лежит прямо в папке V.I.R.U.S. Перенести его файлы в проект "
                    + "<b>" + Projects.BOT + "</b>? Процессы бота ненадолго остановятся и запустятся "
                    + "снова уже оттуда.</html>", Theme.ui.deriveFont(13f), Theme.TEXT), BorderLayout.CENTER);
            banner.add(move, BorderLayout.EAST);
            banner.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));
            banners.add(banner);
        }

        banners.revalidate();

        List<Path> list;

        try {
            list = projects.list();
        } catch (IOException e) {
            toast.accept("Не прочиталась папка проектов: " + e.getMessage());
            list = List.of();
        }

        if (project == null || !Files.isDirectory(project)) {
            project = list.isEmpty() ? null : list.getFirst();
            folder = project;
        }

        projectList.removeAll();

        for (var path : list) {
            var button = Ui.ghost(path.getFileName().toString());
            button.setIcon(Icons.of(Icons.Kind.FOLDER, path.equals(project) ? Theme.ACCENT : Theme.MUTED));
            button.setIconTextGap(10);
            button.setHorizontalAlignment(JButton.LEFT);
            button.setAlignmentX(LEFT_ALIGNMENT);
            button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
            button.setForeground(path.equals(project) ? Theme.TEXT : Theme.MUTED);
            button.setFont(path.equals(project) ? Theme.ui.deriveFont(Font.BOLD) : Theme.ui);
            button.addActionListener(e -> {
                project = path;
                folder = path;
                reload();
            });
            projectList.add(button);
        }

        if (list.isEmpty()) {
            var empty = Ui.muted("<html>Проектов пока нет.<br>Создай — кнопка вверху.</html>", 13f);
            empty.setBorder(BorderFactory.createEmptyBorder(6, 8, 0, 0));
            projectList.add(empty);
        }

        projectList.add(Box.createVerticalGlue());
        projectList.revalidate();
        projectList.repaint();
        showFolder();
    }

    private void showFolder() {
        model.entries.clear();

        if (folder != null && Files.isDirectory(folder)) {
            try (var stream = Files.list(folder)) {
                model.entries.addAll(stream
                        .sorted(Comparator.<Path, Boolean>comparing(path -> !Files.isDirectory(path))
                                .thenComparing(path -> path.getFileName().toString().toLowerCase()))
                        .toList());
            } catch (IOException e) {
                toast.accept("Не прочиталась папка: " + e.getMessage());
            }

            where.setText(supervisor.home().relativize(folder).toString().replace('\\', '/'));
        } else {
            where.setText("Выбери проект слева или создай новый.");
        }

        up.setEnabled(folder != null && project != null && !folder.equals(project));
        model.fireTableDataChanged();
    }

    private void enter(Path path) {
        if (Files.isDirectory(path)) {
            folder = path;
            showFolder();
        } else {
            open(path);
        }
    }

    private void goUp() {
        if (folder != null && project != null && !folder.equals(project)) {
            folder = folder.getParent();
            showFolder();
        }
    }

    private void open(Path path) {
        if (path == null) {
            return;
        }

        worker.submit(() -> {
            try {
                Desktop.getDesktop().open(path.toFile());
            } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
                SwingUtilities.invokeLater(() -> toast.accept("Не открылось: " + e.getMessage()));
            }
        });
    }

    private void newProject() {
        var name = JOptionPane.showInputDialog(this, "Имя проекта:", "Новый проект", JOptionPane.PLAIN_MESSAGE);

        if (name == null) {
            return;
        }

        try {
            project = projects.create(name);
            folder = project;
            reload();
            toast.accept("Проект создан: " + project.getFileName());
        } catch (IOException e) {
            toast.accept("Не создался: " + e.getMessage());
        }
    }

    private void newFolder() {
        if (folder == null) {
            toast.accept("Сначала выбери или создай проект.");
            return;
        }

        var name = JOptionPane.showInputDialog(this, "Имя папки:", "Новая папка", JOptionPane.PLAIN_MESSAGE);

        if (name == null || name.isBlank()) {
            return;
        }

        try {
            Files.createDirectories(folder.resolve(safeName(name)));
            showFolder();
        } catch (IOException e) {
            toast.accept("Не создалась: " + e.getMessage());
        }
    }

    private void chooseAndAdd() {
        if (folder == null) {
            toast.accept("Сначала выбери или создай проект.");
            return;
        }

        var chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setDialogTitle("Что добавить в " + folder.getFileName());

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            add(List.of(chooser.getSelectedFiles()));
        }
    }

    /** Копирует файлы в открытую папку, спрашивая про совпадающие имена. */
    private void add(List<File> files) {
        if (folder == null) {
            toast.accept("Сначала выбери или создай проект.");
            return;
        }

        var sources = files.stream().map(File::toPath).toList();
        var clash = sources.stream().filter(path -> Files.exists(folder.resolve(path.getFileName().toString()))).toList();
        var replace = false;

        if (!clash.isEmpty()) {
            var answer = JOptionPane.showConfirmDialog(this, "Уже есть: " + String.join(", ",
                            clash.stream().map(path -> path.getFileName().toString()).toList())
                            + ".\nЗаменить? «Нет» — пропустить эти, добавить остальные.",
                    "V.I.R.U.S.", JOptionPane.YES_NO_CANCEL_OPTION);

            if (answer == JOptionPane.CANCEL_OPTION || answer == JOptionPane.CLOSED_OPTION) {
                return;
            }

            replace = answer == JOptionPane.YES_OPTION;
        }

        var target = folder;
        var overwrite = replace;

        worker.submit(() -> {
            try {
                var count = Projects.copyInto(target, sources, overwrite);
                SwingUtilities.invokeLater(() -> {
                    toast.accept("Добавлено: " + count);
                    showFolder();
                });
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> toast.accept("Не скопировалось: " + e.getMessage()));
            }
        });
    }

    private List<Path> selected() {
        var result = new ArrayList<Path>();

        for (var row : table.getSelectedRows()) {
            result.add(model.entries.get(row));
        }

        return result;
    }

    private void renameSelected() {
        var chosen = selected();

        if (chosen.size() != 1) {
            toast.accept("Выбери один файл или папку.");
            return;
        }

        var path = chosen.getFirst();
        var name = (String) JOptionPane.showInputDialog(this, "Новое имя:", "Переименовать",
                JOptionPane.PLAIN_MESSAGE, null, null, path.getFileName().toString());

        if (name == null || name.isBlank() || name.equals(path.getFileName().toString())) {
            return;
        }

        try {
            Files.move(path, path.resolveSibling(safeName(name)));
            showFolder();
        } catch (IOException e) {
            toast.accept("Не переименовалось: " + e.getMessage());
        }
    }

    private void deleteSelected() {
        var chosen = selected();

        if (chosen.isEmpty()) {
            toast.accept("Выбери, что удалить.");
            return;
        }

        var trash = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH);
        var names = String.join(", ", chosen.stream().map(path -> path.getFileName().toString()).toList());

        if (JOptionPane.showConfirmDialog(this, (trash ? "В корзину: " : "Удалить насовсем: ") + names + "?",
                "V.I.R.U.S.", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }

        for (var path : chosen) {
            try {
                if (trash) {
                    Desktop.getDesktop().moveToTrash(path.toFile());
                } else {
                    Projects.deleteTree(path);
                }
            } catch (IOException | RuntimeException e) {
                toast.accept("Не удалилось " + path.getFileName() + ": " + e.getMessage());
            }
        }

        showFolder();
    }

    private void moveBot() {
        try {
            var list = projects.movable().stream().map(path -> path.getFileName().toString()).toList();

            if (JOptionPane.showConfirmDialog(this, "<html>Перенесу в projects/" + Projects.BOT + ":<br><br>"
                            + ConfigPage.escape(String.join(", ", list)) + "<br><br>Процессы бота ненадолго "
                            + "остановятся.</html>", "V.I.R.U.S.", JOptionPane.OK_CANCEL_OPTION)
                    != JOptionPane.OK_OPTION) {
                return;
            }
        } catch (IOException e) {
            toast.accept(e.getMessage());
            return;
        }

        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

        worker.submit(() -> {
            try {
                var moved = projects.moveBot(supervisor);
                SwingUtilities.invokeLater(() -> {
                    toast.accept("Перенесено: " + moved + ". Бот теперь живёт в projects/" + Projects.BOT);
                    project = supervisor.home().resolve("projects").resolve(Projects.BOT);
                    folder = project;
                    reload();
                    changed.run();
                });
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> toast.accept("Не перенеслось: " + e.getMessage()));
            } finally {
                SwingUtilities.invokeLater(() -> setCursor(Cursor.getDefaultCursor()));
            }
        });
    }

    /** Имя без знаков, которые Windows в именах не терпит, и без выхода из папки. */
    private static String safeName(String name) throws IOException {
        var clean = name.strip();

        if (clean.isEmpty() || !clean.matches("[^\\\\/:*?\"<>|]+") || clean.equals(".") || clean.equals("..")) {
            throw new IOException("в имени не может быть \\ / : * ? \" < > |");
        }

        return clean;
    }

    private static final class Model extends AbstractTableModel {

        final List<Path> entries = new ArrayList<>();
        private final SimpleDateFormat date = new SimpleDateFormat("dd.MM.yyyy HH:mm");

        @Override
        public int getRowCount() {
            return entries.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "Имя";
                case 1 -> "Размер";
                default -> "Изменён";
            };
        }

        @Override
        public Object getValueAt(int row, int column) {
            var path = entries.get(row);

            try {
                return switch (column) {
                    case 0 -> path.getFileName().toString();
                    case 1 -> Files.isDirectory(path) ? "папка" : size(Files.size(path));
                    default -> date.format(new Date(Files.getLastModifiedTime(path).toMillis()));
                };
            } catch (IOException e) {
                return "";
            }
        }

        static String size(long bytes) {
            if (bytes < 1024) {
                return bytes + " Б";
            }
            if (bytes < 1024 * 1024) {
                return String.format("%.1f КБ", bytes / 1024.0);
            }
            return String.format("%.1f МБ", bytes / 1024.0 / 1024.0);
        }
    }

    private final class Renderer extends DefaultTableCellRenderer {

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            var label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, false, row, column);
            label.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
            var directory = Files.isDirectory(model.entries.get(row));

            if (column == 0) {
                label.setIcon(directory ? Icons.of(Icons.Kind.FOLDER, Theme.ACCENT) : null);
                label.setIconTextGap(10);
                label.setFont(directory ? Theme.ui.deriveFont(Font.BOLD) : Theme.ui);
                label.setForeground(isSelected ? Color.WHITE : Theme.TEXT);
            } else {
                label.setIcon(null);
                label.setFont(Theme.ui.deriveFont(13f));
                label.setForeground(isSelected ? Color.WHITE : Theme.MUTED);
            }

            return label;
        }
    }

    /** Принимает файлы, перетащенные из Проводника. */
    private final class DropHandler extends TransferHandler {

        @Override
        public boolean canImport(TransferSupport support) {
            return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) {
                return false;
            }

            try {
                var files = (List<File>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                SwingUtilities.invokeLater(() -> add(files));
                return true;
            } catch (Exception e) {
                toast.accept("Не принялось: " + e.getMessage());
                return false;
            }
        }
    }

    /** Для проверок. */
    JComponent table() {
        return table;
    }
}
