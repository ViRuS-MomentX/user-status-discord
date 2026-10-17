package ru.virus.manager;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;

/**
 * Окно «добавить процесс» и «изменить процесс».
 */
final class ProcessDialog extends JDialog {

    /** Что решил человек. */
    sealed interface Result permits Saved, Deleted, Cancelled {
    }

    record Saved(ProcessSpec spec) implements Result {
    }

    record Deleted(String id) implements Result {
    }

    record Cancelled() implements Result {
    }

    private final JTextField name = new JTextField();
    private final JTextField id = new JTextField();
    private final JTextField command = new JTextField();
    private final JTextField workdir = new JTextField();
    private final JCheckBox autostart = new JCheckBox("Запускать вместе с V.I.R.U.S.");
    private final JCheckBox restart = new JCheckBox("Поднимать, если упал", true);
    private final JCheckBox supervised = new JCheckBox("Понимает «stop» на вход (наши боты)");

    private Result result = new Cancelled();

    private ProcessDialog(JFrame owner, ProcessSpec existing) {
        super(owner, existing == null ? "Новый процесс" : "Процесс: " + existing.name(), true);

        var body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(20, 22, 18, 22));
        body.setBackground(Theme.CARD_SOLID);

        var title = Ui.label(existing == null ? "Новый процесс" : existing.name(),
                Theme.ui.deriveFont(Font.BOLD, 18f), Theme.TEXT);
        title.setAlignmentX(LEFT_ALIGNMENT);
        body.add(title);
        body.add(Box.createVerticalStrut(14));

        command.setFont(Theme.mono);
        workdir.setFont(Theme.mono);
        id.setFont(Theme.mono);

        body.add(field("Имя", "", name));
        body.add(field("Короткое имя", "латиница, цифры и дефис — для файлов, потом не меняется", id));
        body.add(field("Команда", "{java} — та же Java, {home} — папка V.I.R.U.S.", command));
        body.add(field("Папка запуска", "пусто — папка V.I.R.U.S.", workdir));

        for (var box : new JCheckBox[] { autostart, restart, supervised }) {
            box.setAlignmentX(LEFT_ALIGNMENT);
            box.setOpaque(false);
            body.add(box);
            body.add(Box.createVerticalStrut(4));
        }

        if (existing != null) {
            name.setText(existing.name());
            id.setText(existing.id());
            id.setEditable(false);
            command.setText(ProcessSpec.join(existing.command()));
            workdir.setText(existing.workdir());
            autostart.setSelected(existing.autostart());
            restart.setSelected(existing.restart());
            supervised.setSelected(existing.supervised());
        } else {
            command.setText("{java} -jar ");
        }

        var buttons = Ui.transparent(new BorderLayout());
        var right = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        var cancel = Ui.ghost("Отмена");
        cancel.addActionListener(e -> dispose());
        var save = Ui.primary("Сохранить");
        save.addActionListener(e -> save());
        right.add(cancel);
        right.add(save);
        buttons.add(right, BorderLayout.EAST);

        if (existing != null) {
            var delete = Ui.danger("Удалить");
            delete.addActionListener(e -> {
                if (JOptionPane.showConfirmDialog(this, "Удалить «" + existing.name()
                        + "»? Если он работает — остановлю.", "V.I.R.U.S.",
                        JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                    result = new Deleted(existing.id());
                    dispose();
                }
            });
            buttons.add(delete, BorderLayout.WEST);
        }

        buttons.setAlignmentX(LEFT_ALIGNMENT);
        body.add(Box.createVerticalStrut(14));
        body.add(buttons);

        getRootPane().setDefaultButton(save);
        setContentPane(body);
        setMinimumSize(new Dimension(560, 10));
        pack();
        setLocationRelativeTo(owner);
    }

    private static JComponent field(String label, String hint, JTextField input) {
        var box = Ui.transparent(new BorderLayout(0, 6));
        var caption = Ui.label(label + (hint.isEmpty() ? "" : "   "), Theme.ui.deriveFont(13f), Theme.MUTED);
        var top = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        top.add(caption);

        if (!hint.isEmpty()) {
            top.add(Ui.label(hint, Theme.ui.deriveFont(12f), Theme.DIM));
        }

        box.add(top, BorderLayout.NORTH);
        box.add(input, BorderLayout.CENTER);
        box.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));
        box.setAlignmentX(LEFT_ALIGNMENT);
        box.setMaximumSize(new Dimension(Integer.MAX_VALUE, box.getPreferredSize().height));
        return box;
    }

    private void save() {
        try {
            result = new Saved(new ProcessSpec(id.getText().strip(), name.getText().strip(),
                    ProcessSpec.split(command.getText()), workdir.getText().strip(),
                    autostart.isSelected(), restart.isSelected(), supervised.isSelected()));
            dispose();
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "V.I.R.U.S.", JOptionPane.WARNING_MESSAGE);
        }
    }

    static Result ask(JFrame owner, ProcessSpec existing) {
        var dialog = new ProcessDialog(owner, existing);
        dialog.setVisible(true);
        return dialog.result;
    }
}
