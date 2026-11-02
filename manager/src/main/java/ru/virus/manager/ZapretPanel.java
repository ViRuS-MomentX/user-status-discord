package ru.virus.manager;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Настройки zapret на странице его процесса: версия и обновление, стратегия,
 * игровой фильтр, IPSet, запуск с Windows и свои списки доменов. Всё, что
 * есть в меню service.bat у Flowseal, — кнопками.
 */
final class ZapretPanel extends JPanel {

    private static final List<String> GAME_NAMES = List.of("выключен", "TCP и UDP", "только TCP", "только UDP");
    private static final List<String> IPSET_NAMES = List.of("обычный список", "ничего (только домены)", "любые адреса");

    private final ExecutorService worker;
    private final JLabel version = Ui.label("", Theme.ui.deriveFont(13f), Theme.TEXT);
    private final JLabel status = new JLabel() {
        @Override
        public void setText(String text) {
            // Длинные ответы (ошибки winws) — переносом, а не обрезкой
            super.setText(text == null || text.isBlank() ? "" : "<html><body style='width:760px'>"
                    + ConfigPage.escape(text) + "</body></html>");
        }
    };
    private final JComboBox<String> strategy = new JComboBox<>();
    private final JComboBox<String> game = new JComboBox<>(GAME_NAMES.toArray(String[]::new));
    private final JComboBox<String> ipset = new JComboBox<>(IPSET_NAMES.toArray(String[]::new));
    private final Ui.Switch withWindows = new Ui.Switch(false);
    private final Ui.Switch autoUpdate = new Ui.Switch(true);
    private final JButton update = Ui.button("Обновить");
    private final JButton apply = Ui.primary("Применить");
    private final JButton remove = Ui.ghost("Удалить службу");
    private Zapret zapret;
    private boolean quiet;

    ZapretPanel(ExecutorService worker) {
        this.worker = worker;
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        status.setFont(Theme.ui.deriveFont(12.5f));
        status.setForeground(Theme.MUTED);

        var head = row();
        head.add(Ui.caption("zapret"));
        head.add(version);
        head.add(update);
        head.add(apply);
        head.add(remove);
        var check = Ui.ghost("Проверить запуск");
        check.setToolTipText("Запустить winws.exe напрямую на 5 секунд и показать, что он скажет (нужны права администратора)");
        check.addActionListener(e -> run("Проверяю winws.exe — подтверди права администратора…", () -> zapret.diagnose()));
        head.add(check);
        var defender = Ui.ghost("В исключения Защитника");
        defender.setToolTipText("Защитник Windows часто блокирует WinDivert из zapret («Access is denied»). Добавить папки "
                + "zapret в исключения — так советует и сам Flowseal (нужны права администратора)");
        defender.addActionListener(e -> run("Добавляю папки zapret в исключения Защитника — подтверди права…", () -> {
            var problem = zapret.exclude();
            return problem == null ? "Готово: Защитник больше не трогает папки zapret. Нажми «Обновить» (скачать заново, "
                    + "если файлы удалены), потом «Применить»." : "Не получилось: " + problem;
        }));
        head.add(defender);

        var options = row();
        options.add(Ui.muted("Стратегия", 12.5f));
        strategy.setPreferredSize(new Dimension(230, strategy.getPreferredSize().height));
        strategy.setToolTipText("Если Discord или YouTube не открываются — попробуй следующую (ALT, ALT2…)");
        options.add(strategy);
        options.add(Ui.muted("игры", 12.5f));
        game.setToolTipText("Обход и для игр: порты 1024–65535. Включай, если не пускает в игру");
        options.add(game);
        options.add(Ui.muted("IPSet", 12.5f));
        ipset.setToolTipText("Обходить ещё и по списку IP-адресов (как пункт IPSet в service.bat)");
        options.add(ipset);

        var more = row();
        more.add(withWindows);
        more.add(Ui.muted("работать с запуска Windows", 12.5f));
        more.add(Box.createHorizontalStrut(10));
        more.add(autoUpdate);
        more.add(Ui.muted("сам обновлять с GitHub Flowseal", 12.5f));
        more.add(Box.createHorizontalStrut(10));
        var domains = Ui.ghost("Свои домены…");
        domains.setToolTipText("Сайты, которые тоже надо обходить (list-general-user.txt)");
        domains.addActionListener(e -> editList("list-general-user.txt", "Свои домены для обхода, по одному в строке"));
        var exclude = Ui.ghost("Исключения…");
        exclude.setToolTipText("Сайты, которые трогать не надо (list-exclude-user.txt)");
        exclude.addActionListener(e -> editList("list-exclude-user.txt", "Домены, которые zapret не трогает"));
        more.add(domains);
        more.add(exclude);

        add(head);
        add(Box.createVerticalStrut(6));
        add(options);
        add(Box.createVerticalStrut(6));
        add(more);
        add(Box.createVerticalStrut(4));
        var statusRow = row();
        statusRow.add(status);
        add(statusRow);
        setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

        Runnable save = () -> {
            if (quiet || zapret == null) {
                return;
            }

            var props = zapret.settings();

            if (strategy.getSelectedItem() != null) {
                props.setProperty("strategy", (String) strategy.getSelectedItem());
            }

            props.setProperty("game", Zapret.GAME_MODES.get(game.getSelectedIndex()));
            props.setProperty("ipset", Zapret.IPSET_MODES.get(ipset.getSelectedIndex()));
            props.setProperty("withWindows", String.valueOf(withWindows.isOn()));
            props.setProperty("autoUpdate", String.valueOf(autoUpdate.isOn()));

            try {
                zapret.save(props);
                status.setText("Сохранено. Нажми «Применить» — zapret перезапустится с новыми настройками "
                        + "(Windows спросит права администратора).");
            } catch (Exception ex) {
                status.setText("Не сохранилось: " + ex.getMessage());
            }
        };
        strategy.addActionListener(e -> save.run());
        game.addActionListener(e -> save.run());
        ipset.addActionListener(e -> save.run());
        withWindows.onChange(on -> save.run());
        autoUpdate.onChange(on -> save.run());

        update.addActionListener(e -> run("Проверяю GitHub…", () -> {
            var release = zapret.latest();

            if (!Zapret.newer(release.tag(), zapret.version())) {
                return "Последняя версия уже скачана: " + release.tag() + ".";
            }

            zapret.download(release);
            var problem = zapret.installed().isBlank() ? null : zapret.apply(true);
            return problem == null ? "Скачан zapret " + release.tag() + (zapret.installed().isBlank()
                    ? ". Нажми «Применить», чтобы поставить." : " и поставлен.") : "Скачан " + release.tag()
                    + ", но не поставился: " + problem;
        }));
        apply.addActionListener(e -> run("Ставлю zapret — подтверди права администратора…", () -> {
            if (zapret.version().isEmpty()) {
                zapret.download(zapret.latest());
            }

            var problem = zapret.apply(true);
            return problem == null ? "Готово: zapret стоит службой и работает. Запускать и останавливать — "
                    + "кнопками процесса выше." : "Не получилось: " + problem;
        }));
        remove.addActionListener(e -> run("Удаляю службу — подтверди права администратора…", () -> {
            var problem = zapret.remove();
            return problem == null ? "Служба zapret удалена." : "Не получилось: " + problem;
        }));
    }

    private static JPanel row() {
        var row = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.setAlignmentX(LEFT_ALIGNMENT);
        return row;
    }

    /** Показать настройки zapret из папки V.I.R.U.S. */
    void bind(Path home) {
        if (zapret == null) {
            zapret = new Zapret(home);
        }

        refresh();
    }

    private void refresh() {
        quiet = true;
        var props = zapret.settings();
        var current = zapret.version();
        var installed = zapret.installed();
        version.setText(current.isBlank() ? "ещё не скачан — нажми «Применить», V.I.R.U.S. скачает и поставит"
                : "версия " + current + (installed.isBlank() ? " · служба не установлена" : " · стоит: " + installed));
        strategy.removeAllItems();
        zapret.strategies().forEach(strategy::addItem);
        strategy.setSelectedItem(props.getProperty("strategy"));
        game.setSelectedIndex(Math.max(0, Zapret.GAME_MODES.indexOf(props.getProperty("game"))));
        ipset.setSelectedIndex(Math.max(0, Zapret.IPSET_MODES.indexOf(props.getProperty("ipset"))));
        withWindows.setOn(Boolean.parseBoolean(props.getProperty("withWindows")));
        autoUpdate.setOn(Boolean.parseBoolean(props.getProperty("autoUpdate", "true")));
        remove.setEnabled(!installed.isBlank());
        quiet = false;
    }

    private interface Job {
        String run() throws Exception;
    }

    private void run(String doing, Job job) {
        if (zapret == null) {
            return;
        }

        for (var button : List.of(update, apply, remove)) {
            button.setEnabled(false);
        }

        status.setText(doing);
        worker.submit(() -> {
            String text;

            try {
                text = job.run();
            } catch (Exception e) {
                text = "Не получилось: " + e.getMessage();
            }

            var result = text;
            SwingUtilities.invokeLater(() -> {
                update.setEnabled(true);
                apply.setEnabled(true);
                refresh();
                status.setText(result);
            });
        });
    }

    /** Правка своего списка доменов: сохранили — нужно «Применить». */
    private void editList(String name, String title) {
        if (zapret == null || zapret.version().isEmpty()) {
            status.setText("Сначала поставь zapret кнопкой «Применить».");
            return;
        }

        var file = zapret.userList(name);
        String text;

        try {
            text = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (Exception e) {
            text = "";
        }

        var area = new JTextArea(text, 16, 40);
        area.setFont(Theme.mono.deriveFont(13f));

        if (JOptionPane.showConfirmDialog(this, new JScrollPane(area), title, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }

        try {
            Files.createDirectories(file.getParent());
            var lines = area.getText().strip();
            // Пустой список winws не любит — оставляем пример
            Files.writeString(file, lines.isEmpty() ? "domain.example.abc\n" : lines + "\n", StandardCharsets.UTF_8);
            status.setText("Список сохранён. Нажми «Применить», чтобы zapret его увидел.");
        } catch (Exception e) {
            status.setText("Не сохранилось: " + e.getMessage());
        }
    }

    /**
     * Автообновление: раз в несколько часов смотрим GitHub. Вышла новая
     * версия, а служба уже стоит — качаем и ставим (Windows спросит права).
     */
    static String autoUpdate(Path home) {
        var zapret = new Zapret(home);

        if (!Boolean.parseBoolean(zapret.settings().getProperty("autoUpdate", "true")) || zapret.installed().isBlank()) {
            return null;
        }

        try {
            var release = zapret.latest();

            if (!Zapret.newer(release.tag(), zapret.version())) {
                return null;
            }

            zapret.download(release);
            var problem = zapret.apply(true);
            return problem == null ? "zapret обновлён до " + release.tag() + "." : "Новый zapret " + release.tag()
                    + " скачан, но не поставился: " + problem;
        } catch (Exception e) {
            return null;
        }
    }
}
