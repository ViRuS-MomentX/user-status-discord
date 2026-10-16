package ru.virus.voicebridge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Сводка манеры участника в цифрах.
 *
 * <p>Модели по умолчанию тянет писать длинно, с заглавной и с точкой в конце.
 * Образцов она слушается, но не до конца, а прямо названные цифры — «обычно
 * 25 знаков, с маленькой буквы, без точки» — держат её куда крепче.
 */
final class ParodyStyle {

    /** Меньше — цифры случайны и только собьют. */
    private static final int ENOUGH = 10;

    /** Скобки-смайлы: «)))», «((». */
    private static final Pattern BRACKETS = Pattern.compile("[)(]{2,}\\s*$");

    private ParodyStyle() {
    }

    /**
     * @return несколько строк для подсказки; пустая строка, если образцов мало
     */
    static String describe(List<String> samples) {
        if (samples.size() < ENOUGH) {
            return "";
        }

        var lengths = new ArrayList<Integer>();
        var lowercase = 0;
        var startsWithLetter = 0;
        var endsWithDot = 0;
        var shouting = 0;
        var brackets = 0;
        var emojis = new HashMap<String, Integer>();
        var withEmoji = 0;

        for (var sample : samples) {
            var text = sample.trim();
            lengths.add(text.length());

            var first = text.codePointAt(0);

            if (Character.isLetter(first)) {
                startsWithLetter++;

                if (Character.isLowerCase(first)) {
                    lowercase++;
                }
            }

            if (text.endsWith(".") && !text.endsWith("..")) {
                endsWithDot++;
            }

            var letters = text.codePoints().filter(Character::isLetter).count();
            var upper = text.codePoints().filter(Character::isUpperCase).count();

            if (letters >= 4 && upper * 10 >= letters * 8) {
                shouting++;
            }

            if (BRACKETS.matcher(text).find()) {
                brackets++;
            }

            var found = false;

            for (var emoji : emojis(text)) {
                emojis.merge(emoji, 1, Integer::sum);
                found = true;
            }

            if (found) {
                withEmoji++;
            }
        }

        lengths.sort(Integer::compare);
        var median = lengths.get(lengths.size() / 2);
        var total = samples.size();
        var lines = new ArrayList<String>();

        lines.add("Обычная длина его сообщения — около " + median + " знаков"
                + (median <= 40 ? ": пишет коротко." : "."));

        if (startsWithLetter > 0) {
            var share = percent(lowercase, startsWithLetter);

            if (share >= 70) {
                lines.add("Почти всегда начинает со строчной буквы (" + share + "%).");
            } else if (share <= 20) {
                lines.add("Начинает с заглавной буквы.");
            }
        }

        var dots = percent(endsWithDot, total);
        lines.add(dots <= 15
                ? "Точку в конце почти не ставит (" + dots + "%)."
                : "Точку в конце ставит в " + dots + "% сообщений.");

        if (percent(shouting, total) >= 15) {
            lines.add("Часто пишет КАПСОМ (" + percent(shouting, total) + "%).");
        }

        if (percent(brackets, total) >= 10) {
            lines.add("Скобки-смайлы вроде «)))» — в " + percent(brackets, total) + "% сообщений.");
        }

        var emojiShare = percent(withEmoji, total);

        if (emojiShare >= 10) {
            lines.add("Эмодзи — в " + emojiShare + "% сообщений, чаще всего: "
                    + String.join(" ", top(emojis, 5)) + ".");
        } else {
            lines.add("Эмодзи почти не использует.");
        }

        return String.join("\n", lines);
    }

    private static int percent(int part, int whole) {
        return whole == 0 ? 0 : Math.round(part * 100f / whole);
    }

    /** Эмодзи в тексте — по символам из картинных блоков Юникода. */
    private static List<String> emojis(String text) {
        var found = new ArrayList<String>();

        text.codePoints().forEach(point -> {
            var block = Character.UnicodeBlock.of(point);

            if (block == Character.UnicodeBlock.EMOTICONS
                    || block == Character.UnicodeBlock.MISCELLANEOUS_SYMBOLS_AND_PICTOGRAPHS
                    || block == Character.UnicodeBlock.SUPPLEMENTAL_SYMBOLS_AND_PICTOGRAPHS
                    || block == Character.UnicodeBlock.TRANSPORT_AND_MAP_SYMBOLS
                    || block == Character.UnicodeBlock.SYMBOLS_AND_PICTOGRAPHS_EXTENDED_A
                    || block == Character.UnicodeBlock.DINGBATS
                    || block == Character.UnicodeBlock.MISCELLANEOUS_SYMBOLS) {
                found.add(Character.toString(point));
            }
        });

        return found;
    }

    private static List<String> top(Map<String, Integer> counts, int limit) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }
}
