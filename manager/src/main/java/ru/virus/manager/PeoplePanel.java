package ru.virus.manager;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Раздел «Участники» в конфиге ботов: ID — как называть — как шутить.
 *
 * <p>Пишется в parody-people.json рядом с config.properties сразу, без кнопки
 * «Сохранить»: бот перечитывает файл, когда тот меняется.
 */
final class PeoplePanel extends JPanel {

    private final Supplier<Path> where;
    private final Consumer<String> toast;
    private final JPanel rows = Ui.transparent(null);
    private final JLabel status = Ui.muted("", 12f);
    private final Timer saver;
    /** Как людей видно в Telegram — из памяти бота: ID → имя. */
    private Map<Long, String> seen = Map.of();
    private boolean loading;

    PeoplePanel(Supplier<Path> where, Consumer<String> toast) {
        super(new BorderLayout(0, 8));
        this.where = where;
        this.toast = toast;
        setOpaque(false);
        setAlignmentX(LEFT_ALIGNMENT);

        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));

        var header = Ui.label("УЧАСТНИКИ: КАК НАЗЫВАТЬ И КАК ШУТИТЬ", Theme.ui.deriveFont(Font.BOLD, 12.5f), Theme.ACCENT);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.LINE),
                BorderFactory.createEmptyBorder(14, 2, 8, 0)));

        var hint = Ui.muted("<html><body style='width:430px'>Бот будет звать человека так и шутить над ним "
                + "только так, как здесь написано — по-дружески. ID в Telegram узнаётся у @userinfobot (перешли "
                + "ему сообщение человека); если бот уже видел его в чате, рядом появится его имя. "
                + "Сохраняется само, бот подхватывает без перезапуска.</body></html>", 12.5f);
        hint.setBorder(BorderFactory.createEmptyBorder(8, 2, 6, 0));

        var top = Ui.transparent(new BorderLayout());
        top.add(header, BorderLayout.NORTH);
        top.add(hint, BorderLayout.CENTER);

        var add = Ui.button("Добавить участника");
        add.setIcon(Icons.of(Icons.Kind.PLUS, Theme.ACCENT));
        add.addActionListener(e -> {
            var row = new Row("", "", "");
            rows.add(row);
            rows.revalidate();
            row.id.requestFocusInWindow();
        });

        var bottom = Ui.transparent(new BorderLayout());
        bottom.add(add, BorderLayout.WEST);
        bottom.add(status, BorderLayout.EAST);

        add(top, BorderLayout.NORTH);
        add(rows, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        saver = new Timer(600, e -> save());
        saver.setRepeats(false);
    }

    Path file() {
        return where.get().resolveSibling("parody-people.json");
    }

    void reload() {
        loading = true;
        rows.removeAll();
        seen = readSeen(where.get().resolveSibling("parody-memory").resolve("names.json"));

        try {
            if (Files.isRegularFile(file())) {
                var json = Json.object(Files.readString(file(), StandardCharsets.UTF_8));

                if (json.get("people") instanceof List<?> list) {
                    for (var item : list) {
                        if (item instanceof Map<?, ?> map) {
                            rows.add(new Row(text(map.get("id")), text(map.get("name")), text(map.get("jokes"))));
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            status.setText("Список не прочитался: " + e.getMessage());
        }

        loading = false;
        rows.revalidate();
        rows.repaint();
    }

    private static String text(Object value) {
        if (value instanceof Number number) {
            return String.valueOf(number.longValue());
        }

        return value == null ? "" : String.valueOf(value);
    }

    /** Имена из памяти бота: parody-memory/names.json — { "ID": { "name": … } }. */
    static Map<Long, String> readSeen(Path file) {
        var result = new LinkedHashMap<Long, String>();

        try {
            if (Files.isRegularFile(file)) {
                Json.object(Files.readString(file, StandardCharsets.UTF_8)).forEach((key, value) -> {
                    if (value instanceof Map<?, ?> person && person.get("name") != null) {
                        try {
                            result.put(Long.parseLong(key), String.valueOf(person.get("name")));
                        } catch (NumberFormatException ignored) {
                            // не ID — пропускаем
                        }
                    }
                });
            }
        } catch (IOException | RuntimeException ignored) {
            // нет памяти — просто без подсказок
        }

        return result;
    }

    private void changed() {
        if (!loading) {
            status.setText("сохраняю…");
            saver.restart();
        }
    }

    private void save() {
        var people = new ArrayList<Map<String, Object>>();

        for (var component : rows.getComponents()) {
            if (component instanceof Row row) {
                var id = row.id.getText().strip();
                var name = row.name.getText().strip();
                var jokes = row.jokes.getText().strip();

                if (!id.isEmpty() || !name.isEmpty() || !jokes.isEmpty()) {
                    var person = new LinkedHashMap<String, Object>();
                    person.put("id", id);
                    person.put("name", name);
                    person.put("jokes", jokes);
                    people.add(person);
                }
            }
        }

        try {
            var file = file();
            Files.createDirectories(file.toAbsolutePath().getParent());
            var temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, Json.write(Map.of("people", people)), StandardCharsets.UTF_8);
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            status.setText("✓ сохранено, участников: " + people.size());
        } catch (IOException e) {
            status.setText("не сохранилось");
            toast.accept("Список участников не сохранился: " + e.getMessage());
        }
    }

    /** Строка: ID, как называть, как шутить, крестик. */
    private final class Row extends JPanel {
        final JTextField id = new JTextField();
        final JTextField name = new JTextField();
        final JTextField jokes = new JTextField();
        final JLabel who = Ui.muted("", 11.5f);

        Row(String idText, String nameText, String jokesText) {
            super(new BorderLayout());
            setOpaque(false);
            setAlignmentX(LEFT_ALIGNMENT);
            setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));

            id.setText(idText);
            name.setText(nameText);
            jokes.setText(jokesText);
            id.setCaretPosition(0);
            name.setCaretPosition(0);
            jokes.setCaretPosition(0);
            id.setFont(Theme.mono);
            id.putClientProperty("JTextField.placeholderText", "ID в Telegram");
            name.putClientProperty("JTextField.placeholderText", "как называть — Санёк");
            jokes.putClientProperty("JTextField.placeholderText", "как над ним шутить — про вечные опоздания");
            showWho();

            var remove = Ui.ghost("");
            remove.setIcon(Icons.of(Icons.Kind.TRASH, Theme.MUTED));
            remove.setToolTipText("Убрать участника");
            remove.addActionListener(e -> {
                rows.remove(this);
                rows.revalidate();
                rows.repaint();
                changed();
            });

            var idBox = Ui.transparent(new BorderLayout(0, 2));
            idBox.add(id, BorderLayout.CENTER);
            idBox.add(who, BorderLayout.SOUTH);

            // Ширина колонок одна на все строки — иначе поля «гуляют» от строки к строке
            id.setPreferredSize(new Dimension(170, id.getPreferredSize().height));
            name.setPreferredSize(new Dimension(170, name.getPreferredSize().height));
            idBox.setPreferredSize(new Dimension(170, idBox.getPreferredSize().height));
            var nameBox = Ui.transparent(new BorderLayout());
            nameBox.add(name, BorderLayout.NORTH);

            var left = Ui.transparent(new BorderLayout(8, 0));
            left.add(idBox, BorderLayout.WEST);
            left.add(nameBox, BorderLayout.CENTER);
            var jokesBox = Ui.transparent(new BorderLayout());
            jokesBox.add(jokes, BorderLayout.NORTH);
            var removeBox = Ui.transparent(new BorderLayout());
            removeBox.add(remove, BorderLayout.NORTH);

            setLayout(new BorderLayout(8, 0));
            add(left, BorderLayout.WEST);
            add(jokesBox, BorderLayout.CENTER);
            add(removeBox, BorderLayout.EAST);

            DocumentListener listener = new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    edited();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    edited();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    edited();
                }
            };

            for (var field : List.of(id, name, jokes)) {
                field.getDocument().addDocumentListener(listener);
            }
        }

        private void edited() {
            showWho();
            changed();
        }

        private void showWho() {
            var text = id.getText().strip();

            if (text.isEmpty()) {
                who.setText(" ");
                return;
            }

            try {
                var known = seen.get(Long.parseLong(text));
                who.setText(known == null ? "бот его ещё не видел" : "в Telegram: " + known);
                who.setForeground(known == null ? Theme.DIM : Theme.ACCENT);
            } catch (NumberFormatException e) {
                who.setText("ID — только цифры");
                who.setForeground(Theme.ERROR);
            }
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }
}
