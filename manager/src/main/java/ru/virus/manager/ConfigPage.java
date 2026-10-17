package ru.virus.manager;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Настройки ботов формой: разделы, пояснения из комментариев, секреты под
 * точками. Или — текстом целиком, для тех, кто привык к Блокноту.
 */
final class ConfigPage extends Ui.Card {

    private final ConfigService config;
    private final Consumer<String> toast;
    private final Consumer<Boolean> saved;

    private final JLabel path = Ui.label("", Theme.mono.deriveFont(12f), Theme.MUTED);
    private final JTextField search = new JTextField(18);
    private final JButton mode = Ui.button("Текстом");
    private final JPanel banners = Ui.transparent(null);
    private final JPanel nav = new Ui.FitWidth();
    private final JPanel form = new Ui.FitWidth();
    private final JScrollPane formScroll = new JScrollPane(form);
    private final JTextArea raw = new JTextArea();
    private final CardLayout cards = new CardLayout();
    private final JPanel center = Ui.transparent(cards);
    private final JPanel savebar = new Ui.Card(new BorderLayout(10, 0), new Color(0x14, 0x1C, 0x18, 245));
    private final JLabel count = Ui.label("", Theme.ui.deriveFont(Font.BOLD), Theme.ACCENT);

    private List<ConfigFile.Field> fields = List.of();
    private final Map<String, String> original = new LinkedHashMap<>();
    private final Map<String, String> changes = new LinkedHashMap<>();
    private boolean rawMode;
    private String rawOriginal = "";

    ConfigPage(MatrixBackground background, ConfigService config, Consumer<String> toast, Consumer<Boolean> saved) {
        super(new BorderLayout(0, 14));
        this.config = config;
        this.toast = toast;
        this.saved = saved;
        setBorder(BorderFactory.createEmptyBorder(20, 22, 20, 22));
        Ui.rippleThrough(this, background);

        // Шапка
        var titles = Ui.transparent(new BorderLayout(0, 4));
        titles.add(Ui.label("Конфиг ботов", Theme.ui.deriveFont(Font.BOLD, 22f), Theme.TEXT), BorderLayout.NORTH);
        titles.add(path, BorderLayout.CENTER);

        search.putClientProperty("JTextField.placeholderText", "Найти настройку…");
        search.putClientProperty("JTextField.showClearButton", true);
        search.getDocument().addDocumentListener(onChange(this::renderForm));
        mode.addActionListener(e -> switchMode());

        var tools = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        tools.add(search);
        tools.add(mode);

        var head = Ui.transparent(new BorderLayout(12, 0));
        head.add(titles, BorderLayout.CENTER);
        head.add(tools, BorderLayout.EAST);

        banners.setLayout(new BoxLayout(banners, BoxLayout.Y_AXIS));

        var top = Ui.transparent(new BorderLayout(0, 12));
        top.add(head, BorderLayout.NORTH);
        top.add(banners, BorderLayout.CENTER);

        // Форма: навигация по разделам слева, поля справа
        nav.setLayout(new BoxLayout(nav, BoxLayout.Y_AXIS));
        var navScroll = new JScrollPane(nav);
        navScroll.setOpaque(false);
        navScroll.getViewport().setOpaque(false);
        navScroll.setPreferredSize(new Dimension(236, 10));
        navScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        navScroll.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.LINE));

        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setOpaque(false);
        formScroll.setOpaque(false);
        formScroll.getViewport().setOpaque(false);
        formScroll.getVerticalScrollBar().setUnitIncrement(22);
        formScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        formScroll.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 0));

        var formView = Ui.transparent(new BorderLayout());
        formView.add(navScroll, BorderLayout.WEST);
        formView.add(formScroll, BorderLayout.CENTER);

        raw.setFont(Theme.mono);
        raw.setBackground(new Color(0x05, 0x07, 0x06));
        raw.setForeground(Theme.TEXT);
        raw.setCaretColor(Theme.ACCENT);
        raw.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        raw.getDocument().addDocumentListener(onChange(this::updateSavebar));
        var rawScroll = new JScrollPane(raw);
        rawScroll.setBorder(BorderFactory.createLineBorder(Theme.LINE));

        center.add(formView, "form");
        center.add(rawScroll, "raw");

        // Полоса сохранения — видна, только когда есть что сохранять
        var discard = Ui.ghost("Отменить");
        discard.addActionListener(e -> reload());
        var save = Ui.button("Сохранить");
        save.addActionListener(e -> save(false));
        var saveRestart = Ui.primary("Сохранить и перезапустить ботов");
        saveRestart.addActionListener(e -> save(true));

        var actions = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.add(discard);
        actions.add(save);
        actions.add(saveRestart);
        savebar.setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 12));
        savebar.add(count, BorderLayout.WEST);
        savebar.add(actions, BorderLayout.EAST);
        savebar.setVisible(false);

        add(top, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        add(savebar, BorderLayout.SOUTH);
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

    boolean isDirty() {
        return rawMode ? !raw.getText().equals(rawOriginal) : !changes.isEmpty();
    }

    /** Перечитать файл и показать заново, без несохранённого. */
    void reload() {
        changes.clear();
        path.setText(config.file().toString());

        try {
            var file = config.read();
            fields = file.fields();
            original.clear();
            fields.forEach(field -> original.put(field.key(), field.value()));
            rawOriginal = file.text();
            raw.setText(rawOriginal);
            raw.setCaretPosition(0);
            renderBanners();
        } catch (IOException e) {
            toast.accept("Не прочитался конфиг: " + e.getMessage());
        }

        renderForm();
        updateSavebar();
    }

    /** Сколько настроек из образца нет в файле — для значка в меню. */
    int missing() {
        try {
            return config.missing();
        } catch (IOException e) {
            return 0;
        }
    }

    private void renderBanners() throws IOException {
        banners.removeAll();

        if (!config.exists()) {
            var create = Ui.primary("Создать из образца");
            create.addActionListener(e -> {
                try {
                    config.createFromExample();
                    toast.accept("config.properties создан");
                    reload();
                } catch (IOException ex) {
                    toast.accept(ex.getMessage());
                }
            });
            banners.add(banner("Файла настроек ещё нет.", Theme.WARN, create));
        } else {
            var missing = config.missing();

            if (missing > 0) {
                var add = Ui.primary("Добавить");
                add.addActionListener(e -> {
                    try {
                        toast.accept("Добавлено настроек: " + config.addMissing());
                        reload();
                    } catch (IOException ex) {
                        toast.accept(ex.getMessage());
                    }
                });
                banners.add(banner("В новой версии появились настройки, которых нет в твоём файле: " + missing
                        + ". Они добавятся в конец со значениями по умолчанию и пояснениями.", Theme.ACCENT, add));
            }
        }

        banners.revalidate();
        banners.repaint();
    }

    private static JComponent banner(String text, Color tone, JButton action) {
        var panel = new Ui.Card(new BorderLayout(12, 0), Theme.alpha(tone, 22));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 10));
        panel.add(Ui.label("<html>" + escape(text) + "</html>", Theme.ui, Theme.TEXT), BorderLayout.CENTER);
        panel.add(action, BorderLayout.EAST);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));
        return panel;
    }

    private void renderForm() {
        form.removeAll();
        nav.removeAll();

        var query = search.getText().trim().toLowerCase(Locale.ROOT);
        var sections = new LinkedHashMap<String, java.util.ArrayList<ConfigFile.Field>>();

        for (var field : fields) {
            if (!query.isEmpty() && !field.key().toLowerCase(Locale.ROOT).contains(query)
                    && !field.hint().toLowerCase(Locale.ROOT).contains(query)
                    && !field.section().toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }

            sections.computeIfAbsent(field.section(), name -> new java.util.ArrayList<>()).add(field);
        }

        if (sections.isEmpty()) {
            form.add(Ui.muted(fields.isEmpty() ? "Настроек пока нет." : "Ничего не нашлось.", 14f));
        }

        for (var entry : sections.entrySet()) {
            var section = Ui.transparent(null);
            section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
            section.setAlignmentX(LEFT_ALIGNMENT);

            var header = Ui.label(entry.getKey().toUpperCase(), Theme.ui.deriveFont(Font.BOLD, 12.5f), Theme.ACCENT);
            header.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.LINE),
                    BorderFactory.createEmptyBorder(14, 2, 8, 0)));
            header.setAlignmentX(LEFT_ALIGNMENT);
            header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            section.add(header);

            for (var field : entry.getValue()) {
                section.add(row(field));
            }

            form.add(section);

            var link = Ui.ghost("<html>" + escape(entry.getKey()) + "&nbsp;&nbsp;<font color='#39ff8a'>"
                    + entry.getValue().size() + "</font></html>");
            link.setHorizontalAlignment(JButton.LEFT);
            link.setAlignmentX(LEFT_ALIGNMENT);
            link.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
            link.addActionListener(e -> SwingUtilities.invokeLater(() -> {
                var bounds = section.getBounds();
                bounds.height = Math.max(bounds.height, formScroll.getViewport().getHeight());
                form.scrollRectToVisible(bounds);
            }));
            nav.add(link);
        }

        form.add(Box.createVerticalStrut(30));
        form.revalidate();
        form.repaint();
        nav.revalidate();
        nav.repaint();
    }

    /** Строка настройки: ключ и пояснение слева, поле справа. */
    private JComponent row(ConfigFile.Field field) {
        var row = Ui.transparent(new GridBagLayout());
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.alpha(Theme.LINE, 140)),
                BorderFactory.createEmptyBorder(12, 4, 12, 4)));

        var key = Ui.label(field.key(), Theme.mono.deriveFont(Font.BOLD), Theme.TEXT);
        var left = Ui.transparent(new BorderLayout(0, 4));
        left.add(key, BorderLayout.NORTH);

        if (!field.hint().isBlank()) {
            var hint = Ui.label("<html><div style='width:205px'>" + escape(field.hint()).replace("\n", "<br>")
                    + "</div></html>", Theme.ui.deriveFont(12.5f), Theme.MUTED);
            left.add(hint, BorderLayout.CENTER);
        }

        Runnable mark = () -> {
            var dirty = changes.containsKey(field.key());
            key.setText(dirty ? field.key() + "  ●" : field.key());
            key.setForeground(dirty ? Theme.ACCENT : Theme.TEXT);
            updateSavebar();
        };

        Consumer<String> change = value -> {
            if (value.equals(original.get(field.key()))) {
                changes.remove(field.key());
            } else {
                changes.put(field.key(), value);
            }
            mark.run();
        };

        var value = changes.getOrDefault(field.key(), field.value());
        JComponent input;

        if (field.kind().equals("bool")) {
            var toggle = new Ui.Switch(value.strip().equalsIgnoreCase("true"));
            var state = Ui.muted(toggle.isOn() ? "включено" : "выключено", 13f);
            toggle.onChange(on -> {
                state.setText(on ? "включено" : "выключено");
                change.accept(on ? "true" : "false");
            });
            var box = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
            box.add(toggle);
            box.add(state);
            input = box;
        } else if (field.secret()) {
            var secret = new JPasswordField(value);
            secret.setFont(Theme.mono);
            secret.putClientProperty("JTextField.placeholderText", "не задано");
            // Глазок FlatLaf: показать и спрятать
            secret.putClientProperty("JPasswordField.showRevealButton", true);
            secret.getDocument().addDocumentListener(onChange(() -> change.accept(new String(secret.getPassword()))));
            input = secret;
        } else {
            var text = new JTextField(value);
            text.setFont(Theme.mono);
            text.getDocument().addDocumentListener(onChange(() -> change.accept(text.getText())));
            input = text;
        }

        var c = new GridBagConstraints();
        c.gridx = 0;
        c.weightx = 0;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(0, 0, 0, 22);
        left.setPreferredSize(new Dimension(300, left.getPreferredSize().height));
        row.add(left, c);

        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 0, 0);
        row.add(input, c);

        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height + 40));
        mark.run();
        return row;
    }

    private void updateSavebar() {
        var dirty = isDirty();
        count.setText(rawMode ? "Текст изменён" : "Изменено: " + changes.size());

        if (savebar.isVisible() != dirty) {
            savebar.setVisible(dirty);
            revalidate();
        }
    }

    private void switchMode() {
        if (isDirty() && JOptionPane.showConfirmDialog(this, "Несохранённые изменения пропадут. Переключить?",
                "V.I.R.U.S.", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }

        rawMode = !rawMode;
        mode.setText(rawMode ? "Формой" : "Текстом");
        search.setVisible(!rawMode);
        cards.show(center, rawMode ? "raw" : "form");
        reload();
    }

    private void save(boolean restart) {
        try {
            if (rawMode) {
                config.saveRaw(raw.getText());
                toast.accept("Конфиг сохранён");
            } else {
                toast.accept("Сохранено: " + config.save(changes).size());
            }

            reload();
            saved.accept(restart);
        } catch (IOException e) {
            toast.accept("Не сохранилось: " + e.getMessage());
        }
    }

    /** Текст из файла настроек — в HTML надписи только как текст, не разметкой. */
    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
