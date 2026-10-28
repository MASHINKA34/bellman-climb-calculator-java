package ru.mashinka.multiflow.core.json;

import java.util.Locale;

// маленький писатель JSON без внешних библиотек: ядру нельзя тянуть зависимости,
// иначе оно не подключится к Android-проекту как есть
// запятые между элементами ставит сам, поэтому вызывающему коду остаётся только порядок
final class JsonWriter {

    private static final int MAX_DEPTH = 32;

    private final StringBuilder out = new StringBuilder(8192);
    // first[d] - на глубине d ещё не было ни одного элемента, запятая не нужна
    private final boolean[] first = new boolean[MAX_DEPTH];
    private int depth;
    private boolean afterName;

    JsonWriter beginObject() {
        separate();
        out.append('{');
        push();
        return this;
    }

    JsonWriter endObject() {
        depth--;
        out.append('}');
        return this;
    }

    JsonWriter beginArray() {
        separate();
        out.append('[');
        push();
        return this;
    }

    JsonWriter endArray() {
        depth--;
        out.append(']');
        return this;
    }

    JsonWriter name(String name) {
        separate();
        quote(name);
        out.append(':');
        afterName = true;
        return this;
    }

    JsonWriter value(long number) {
        separate();
        out.append(number);
        return this;
    }

    JsonWriter value(double number) {
        separate();
        out.append(String.format(Locale.ROOT, "%.3f", number));
        return this;
    }

    JsonWriter value(boolean flag) {
        separate();
        out.append(flag);
        return this;
    }

    JsonWriter value(String text) {
        separate();
        if (text == null) {
            out.append("null");
        } else {
            quote(text);
        }
        return this;
    }

    // вершины для страницы: внутри ядра они с нуля, пользователь видит их с единицы
    JsonWriter vertices(int[] zeroBased) {
        beginArray();
        for (int vertex : zeroBased) {
            value(vertex + 1);
        }
        return endArray();
    }

    JsonWriter matrix(int[][] rows) {
        beginArray();
        for (int[] row : rows) {
            beginArray();
            for (int cell : row) {
                value(cell);
            }
            endArray();
        }
        return endArray();
    }

    @Override
    public String toString() {
        return out.toString();
    }

    private void push() {
        if (depth + 1 >= MAX_DEPTH) {
            throw new IllegalStateException("JSON nesting deeper than " + MAX_DEPTH);
        }
        depth++;
        first[depth] = true;
    }

    private void separate() {
        if (afterName) {
            afterName = false;
            return;
        }
        if (depth > 0) {
            if (first[depth]) {
                first[depth] = false;
            } else {
                out.append(',');
            }
        }
    }

    private void quote(String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char symbol = text.charAt(i);
            switch (symbol) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    // управляющие символы и разделители строк JavaScript - только кодом
                    if (symbol < 0x20 || symbol == ' ' || symbol == ' ') {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) symbol));
                    } else {
                        out.append(symbol);
                    }
            }
        }
        out.append('"');
    }
}
