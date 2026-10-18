package ru.virus.manager;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.net.URI;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * «Статус в Discord»: номер приложения, картинка, подпись — и как их получить.
 */
final class PresenceDialog {

    static final String PORTAL = "https://discord.com/developers/applications";

    private PresenceDialog() {
    }

    static void show(JFrame owner, VirusSettings settings, DiscordPresence presence, Supplier<String> status,
                     Consumer<String> toast) {
        var dialog = new JDialog(owner, "Статус в Discord", true);
        var form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(18, 20, 10, 20));

        var enabled = new JCheckBox("Показывать «Играет в V.I.R.U.S.» с картинкой",
                settings.flag("discord.enabled", true));
        var app = new JTextField(settings.text("discord.app", ""), 24);
        app.putClientProperty("JTextField.placeholderText", "например 1300000000000000000");
        var image = new JTextField(settings.text("discord.image", "virus"), 24);
        var state = new JTextField(settings.text("discord.state", ""), 24);
        state.putClientProperty("JTextField.placeholderText", "необязательно: своя строка под названием");
        var now = Ui.muted("Сейчас: " + status.get(), 12.5f);

        var steps = Ui.label("<html><div style='width:430px'>"
                + "<b>Как получить номер — один раз, пара минут:</b><br>"
                + "1. Открой Developer Portal (кнопка ниже) → <b>New Application</b>, имя — <b>V.I.R.U.S.</b> "
                + "(оно и будет написано в «Играет в»).<br>"
                + "2. На странице <b>General Information</b> поставь значок — PNG можно сохранить кнопкой ниже — "
                + "и скопируй <b>Application ID</b> сюда.<br>"
                + "3. <b>Rich Presence → Art Assets</b> → загрузи тот же PNG с именем <b>virus</b>. "
                + "Картинка появится в Discord минут через 5–10.<br>"
                + "4. В настройках Discord → <b>Зарегистрированные игры</b> удали добавленный вручную V.I.R.U.S. — "
                + "иначе будет две строки, одна со знаком вопроса.<br><br>"
                + "Токены не нужны: V.I.R.U.S. говорит с Discord на этом же компьютере.</div></html>",
                Theme.ui.deriveFont(13f), Theme.TEXT);

        var c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, 14, 0);
        form.add(enabled, c);

        row(form, 1, "Application ID", app);
        row(form, 2, "Картинка (имя ассета)", image);
        row(form, 3, "Вторая строка", state);

        c.gridy = 4;
        c.insets = new Insets(4, 0, 12, 0);
        form.add(now, c);
        c.gridy = 5;
        c.insets = new Insets(0, 0, 0, 0);
        form.add(steps, c);

        var portal = Ui.button("Открыть Developer Portal");
        portal.addActionListener(e -> {
            try {
                Desktop.getDesktop().browse(URI.create(PORTAL));
            } catch (Exception ex) {
                toast.accept("Открой сам: " + PORTAL);
            }
        });

        var savePng = Ui.button("Сохранить значок PNG…");
        savePng.addActionListener(e -> {
            var target = NativeFiles.save(dialog, "Сохранить значок", "presence-png", "virus.png");

            if (target != null) {
                try {
                    ImageIO.write(AppIcon.image(512), "png", target.toFile());
                    toast.accept("Значок сохранён: " + target.getFileName());
                } catch (Exception ex) {
                    toast.accept("Не сохранился: " + ex.getMessage());
                }
            }
        });

        var save = Ui.primary("Сохранить");
        save.addActionListener(e -> {
            var id = app.getText().strip();

            if (!id.isEmpty() && !id.matches("\\d{15,25}")) {
                now.setText("Application ID — это длинное число, только цифры.");
                now.setForeground(Theme.ERROR);
                return;
            }

            try {
                settings.put(Map.of("discord.enabled", enabled.isSelected(), "discord.app", id,
                        "discord.image", image.getText().strip(), "discord.state", state.getText().strip()));
                presence.poke();
                toast.accept(enabled.isSelected() && !id.isEmpty() ? "Статус в Discord обновится через пару секунд"
                        : "Статус в Discord выключен");
                dialog.dispose();
            } catch (Exception ex) {
                now.setText("Не сохранилось: " + ex.getMessage());
                now.setForeground(Theme.ERROR);
            }
        });

        var cancel = Ui.button("Отмена");
        cancel.addActionListener(e -> dialog.dispose());

        var left = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.add(portal);
        left.add(savePng);
        var right = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.add(cancel);
        right.add(save);
        var buttons = new JPanel(new BorderLayout());
        buttons.setBorder(BorderFactory.createEmptyBorder(8, 14, 14, 14));
        buttons.add(left, BorderLayout.WEST);
        buttons.add(right, BorderLayout.EAST);

        // Пока окно открыто — живой статус связи
        var refresh = new Timer(1000, e -> {
            if (now.getForeground() != Theme.ERROR) {
                now.setText("Сейчас: " + status.get());
            }
        });
        refresh.start();

        dialog.setLayout(new BorderLayout());
        dialog.add(form, BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.getRootPane().setDefaultButton(save);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        refresh.stop();
    }

    private static void row(JPanel form, int y, String caption, JTextField field) {
        var c = new GridBagConstraints();
        c.gridy = y;
        c.gridx = 0;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, 8, 12);
        var label = new JLabel(caption);
        label.setFont(Theme.ui.deriveFont(Font.BOLD, 13f));
        form.add(label, c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(0, 0, 8, 0);
        form.add(field, c);
    }
}
