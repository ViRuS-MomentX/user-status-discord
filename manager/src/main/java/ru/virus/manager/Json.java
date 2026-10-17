package ru.virus.manager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON в обе стороны — ровно столько, сколько нужно панели.
 *
 * <p>Объект — {@link Map}, массив — {@link List}, остальное — строки, числа,
 * true/false и null. Библиотека ради этого весила бы больше всей программы.
 */
final class Json {

    private Json() {
    }

    static String write(Object value) {
        var out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value) {
        switch (value) {
            case null -> out.append("null");
            case String text -> quote(out, text);
            case Boolean flag -> out.append(flag);
            case Number number -> out.append(number);
            case Map<?, ?> map -> {
                out.append('{');
                var first = true;

                for (var entry : map.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    quote(out, String.valueOf(entry.getKey()));
                    out.append(':');
                    write(out, entry.getValue());
                }

                out.append('}');
            }
            case Iterable<?> list -> {
                out.append('[');
                var first = true;

                for (var item : list) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    write(out, item);
                }

                out.append(']');
            }
            default -> quote(out, value.toString());
        }
    }

    private static void quote(StringBuilder out, String text) {
        out.append('"');

        for (var i = 0; i < text.length(); i++) {
            var c = text.charAt(i);

            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                // Чтобы строку можно было безопасно вставить и внутрь <script>
                case '<' -> out.append("\\u003c");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }

        out.append('"');
    }

    static Object parse(String text) {
        var parser = new Parser(text);
        parser.skip();
        var value = parser.value();
        parser.skip();

        if (parser.pos != text.length()) {
            throw parser.error("лишнее после конца");
        }

        return value;
    }

    /** Объект из текста; не объект — ошибка. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(String text) {
        if (parse(text) instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }

        throw new IllegalArgumentException("ожидался объект JSON");
    }

    private static final class Parser {

        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        Object value() {
            if (pos >= text.length()) {
                throw error("текст кончился");
            }

            return switch (text.charAt(pos)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> word("true", Boolean.TRUE);
                case 'f' -> word("false", Boolean.FALSE);
                case 'n' -> word("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            var map = new LinkedHashMap<String, Object>();
            pos++;
            skip();

            if (peek() == '}') {
                pos++;
                return map;
            }

            while (true) {
                skip();
                var key = string();
                skip();
                expect(':');
                skip();
                map.put(key, value());
                skip();

                if (peek() == ',') {
                    pos++;
                    continue;
                }

                expect('}');
                return map;
            }
        }

        private List<Object> array() {
            var list = new ArrayList<Object>();
            pos++;
            skip();

            if (peek() == ']') {
                pos++;
                return list;
            }

            while (true) {
                skip();
                list.add(value());
                skip();

                if (peek() == ',') {
                    pos++;
                    continue;
                }

                expect(']');
                return list;
            }
        }

        private String string() {
            expect('"');
            var out = new StringBuilder();

            while (pos < text.length()) {
                var c = text.charAt(pos++);

                if (c == '"') {
                    return out.toString();
                }

                if (c != '\\') {
                    out.append(c);
                    continue;
                }

                if (pos >= text.length()) {
                    break;
                }

                var escaped = text.charAt(pos++);

                switch (escaped) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        if (pos + 4 > text.length()) {
                            throw error("обрыв \\u");
                        }
                        out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> out.append(escaped);
                }
            }

            throw error("строка не закрыта");
        }

        private Object number() {
            var start = pos;

            while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
                pos++;
            }

            var raw = text.substring(start, pos);

            if (raw.isEmpty()) {
                throw error("непонятный знак");
            }

            try {
                return raw.contains(".") || raw.contains("e") || raw.contains("E")
                        ? (Object) Double.parseDouble(raw)
                        : (Object) Long.parseLong(raw);
            } catch (NumberFormatException e) {
                throw error("не число: " + raw);
            }
        }

        private Object word(String word, Object value) {
            if (!text.startsWith(word, pos)) {
                throw error("ожидалось " + word);
            }

            pos += word.length();
            return value;
        }

        private char peek() {
            return pos < text.length() ? text.charAt(pos) : '\0';
        }

        private void expect(char c) {
            if (peek() != c) {
                throw error("ожидалось «" + c + "»");
            }
            pos++;
        }

        void skip() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        IllegalArgumentException error(String what) {
            return new IllegalArgumentException("JSON: " + what + " (позиция " + pos + ")");
        }
    }
}
