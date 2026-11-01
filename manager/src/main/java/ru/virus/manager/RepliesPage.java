package ru.virus.manager;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * «Реплики бота»: что пародийный бот сказал и на что отвечал. Хозяин ставит
 * «Удачно» или «Мимо» — оценки ложатся в parody-memory/ratings.json, и бот
 * подхватывает их со следующей реплики: удачные — как пример уровня, мимо —
 * как «так не говори».
 */
final class RepliesPage extends Ui.Card {

    /** Больше на странице не держим: Swing на сотнях карточек заметно тормозит. */
    static final int SHOWN = 150;

    record Reply(long id, long at, String to, String context, String text) {
    }

    private final Supplier<Path> botFolder;
    private final Consumer<String> toast;
    private final JPanel list = new Ui.FitWidth();
    private final JLabel stats = Ui.muted("", 13f);
    private List<Reply> replies = List.of();
    private Map<Long, Integer> ratings = new LinkedHashMap<>();
    private String filter = "all";

    RepliesPage(MatrixBackground background, Supplier<Path> botFolder, Consumer<String> toast) {
        super(new BorderLayout(0, 14));
        this.botFolder = botFolder;
        this.toast = toast;
        setBorder(BorderFactory.createEmptyBorder(20, 22, 20, 22));
        Ui.rippleThrough(this, background);

        var titles = Ui.transparent(new BorderLayout(0, 4));
        titles.add(Ui.label("Реплики бота", Theme.ui.deriveFont(Font.BOLD, 22f), Theme.TEXT), BorderLayout.NORTH);
        titles.add(Ui.muted("Оценки бот учитывает со следующей реплики.", 13f), BorderLayout.CENTER);

        var chips = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        var group = new ButtonGroup();

        for (var chip : List.of(Map.entry("all", "Все"), Map.entry("new", "Без оценки"),
                Map.entry("good", "Удачные"), Map.entry("bad", "Мимо"))) {
            var button = new JToggleButton(chip.getValue());
            button.putClientProperty("JButton.buttonType", "roundRect");
            button.setFocusable(false);
            button.setSelected(chip.getKey().equals(filter));
            button.addActionListener(e -> {
                filter = chip.getKey();
                render();
            });
            Ui.hand(button);
            group.add(button);
            chips.add(button);
        }

        var refresh = Ui.button("Обновить");
        refresh.setIcon(Icons.of(Icons.Kind.RESTART, Theme.TEXT));
        refresh.addActionListener(e -> reload());
        chips.add(Box.createHorizontalStrut(6));
        chips.add(refresh);

        var head = Ui.transparent(new BorderLayout(12, 8));
        head.add(titles, BorderLayout.CENTER);
        head.add(chips, BorderLayout.EAST);
        head.add(stats, BorderLayout.SOUTH);
        add(head, BorderLayout.NORTH);

        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        var scroll = new JScrollPane(list);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        add(scroll, BorderLayout.CENTER);
    }

    Path memory() {
        return botFolder.get().resolve("parody-memory");
    }

    void reload() {
        replies = read(memory().resolve("journal.jsonl"));
        ratings = readRatings(memory().resolve("ratings.json"));
        render();
    }

    /** Журнал: по строке JSON на реплику. Оборванные строки пропускаются. */
    static List<Reply> read(Path file) {
        var result = new ArrayList<Reply>();

        if (!Files.isRegularFile(file)) {
            return result;
        }

        try {
            for (var line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                try {
                    var json = Json.object(line);
                    result.add(new Reply(number(json.get("id")), number(json.get("at")), text(json.get("to")),
                            text(json.get("context")), text(json.get("reply"))));
                } catch (RuntimeException ignored) {
                    // недописанная строка — компьютер выключили посреди записи
                }
            }
        } catch (IOException ignored) {
            // нет доступа — покажем пусто
        }

        return result;
    }

    static Map<Long, Integer> readRatings(Path file) {
        var result = new LinkedHashMap<Long, Integer>();

        try {
            if (Files.isRegularFile(file)) {
                Json.object(Files.readString(file, StandardCharsets.UTF_8)).forEach((key, value) -> {
                    try {
                        result.put(Long.parseLong(key), Integer.signum((int) number(value)));
                    } catch (NumberFormatException ignored) {
                        // не ID
                    }
                });
            }
        } catch (IOException | RuntimeException ignored) {
            // испорчен — начнём заново
        }

        return result;
    }

    /** Записать оценки разом (через временный файл — бот читает их в любой момент). */
    static void writeRatings(Path file, Map<Long, Integer> ratings) throws IOException {
        var json = new LinkedHashMap<String, Object>();
        ratings.forEach((id, score) -> {
            if (score != 0) {
                json.put(String.valueOf(id), score);
            }
        });
        Files.createDirectories(file.getParent());
        var temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, Json.write(json), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : value == null ? 0 : Long.parseLong(String.valueOf(value));
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private void render() {
        list.removeAll();
        var good = ratings.values().stream().filter(score -> score > 0).count();
        var bad = ratings.values().stream().filter(score -> score < 0).count();
        stats.setText(replies.isEmpty() ? "Реплик пока нет — бот запишет их сюда, как только что-нибудь скажет."
                : "Реплик: " + replies.size() + " · удачных: " + good + " · мимо: " + bad
                + (good + bad == 0 ? " — оценки делают бота умнее" : ""));

        var shown = 0;

        for (var i = replies.size() - 1; i >= 0 && shown < SHOWN; i--) {
            var reply = replies.get(i);
            var score = ratings.getOrDefault(reply.id(), 0);

            if (filter.equals("new") && score != 0 || filter.equals("good") && score <= 0
                    || filter.equals("bad") && score >= 0) {
                continue;
            }

            list.add(card(reply, score));
            list.add(Box.createVerticalStrut(10));
            shown++;
        }

        if (shown == 0 && !replies.isEmpty()) {
            list.add(Ui.muted("Здесь пусто — выбери другой фильтр.", 13f));
        }

        list.revalidate();
        list.repaint();
    }

    private JComponent card(Reply reply, int score) {
        var card = new Ui.Card(new BorderLayout(12, 6), score > 0 ? new Color(0x10, 0x22, 0x17)
                : score < 0 ? new Color(0x24, 0x12, 0x15) : Theme.CARD);
        card.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        card.setAlignmentX(LEFT_ALIGNMENT);

        var when = new SimpleDateFormat("dd.MM HH:mm").format(new Date(reply.at()));
        var meta = Ui.muted(when + (reply.to().isBlank() ? "" : "  ·  в ответ «" + reply.to() + "»"), 12f);

        var context = area(reply.context(), Theme.mono.deriveFont(12f), Theme.DIM);
        var text = area(reply.text(), Theme.ui.deriveFont(Font.BOLD, 15f), Theme.TEXT);

        var texts = Ui.transparent(null);
        texts.setLayout(new BoxLayout(texts, BoxLayout.Y_AXIS));
        meta.setAlignmentX(LEFT_ALIGNMENT);
        texts.add(meta);

        if (!reply.context().isBlank()) {
            texts.add(Box.createVerticalStrut(6));
            texts.add(context);
        }

        texts.add(Box.createVerticalStrut(6));
        texts.add(text);

        var good = Ui.button("Удачно");
        var bad = Ui.button("Мимо");
        good.setForeground(score > 0 ? Theme.ACCENT : Theme.TEXT);
        bad.setForeground(score < 0 ? Theme.ERROR : Theme.TEXT);
        good.setToolTipText("Бот будет держать такой уровень");
        bad.setToolTipText("Бот так больше говорить не будет — и похожее перепишет");
        // Повторный щелчок снимает оценку
        good.addActionListener(e -> rate(reply, score > 0 ? 0 : 1));
        bad.addActionListener(e -> rate(reply, score < 0 ? 0 : -1));

        var buttons = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(good);
        buttons.add(bad);
        var right = Ui.transparent(new BorderLayout());
        right.add(buttons, BorderLayout.NORTH);

        card.add(texts, BorderLayout.CENTER);
        card.add(right, BorderLayout.EAST);
        return card;
    }

    private static JTextArea area(String value, Font font, Color color) {
        var area = new JTextArea(value);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setOpaque(false);
        area.setBorder(null);
        area.setFont(font);
        area.setForeground(color);
        area.setAlignmentX(LEFT_ALIGNMENT);
        return area;
    }

    private void rate(Reply reply, int score) {
        var before = ratings.getOrDefault(reply.id(), 0);
        ratings.put(reply.id(), score);

        try {
            writeRatings(memory().resolve("ratings.json"), ratings);
            toast.accept(score > 0 ? "Запомнил: так — хорошо." : score < 0 ? "Запомнил: так бот говорить не будет."
                    : "Оценка снята.");
        } catch (IOException e) {
            ratings.put(reply.id(), before);
            toast.accept("Оценка не сохранилась: " + e.getMessage());
        }

        render();
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(200, 200);
    }
}
