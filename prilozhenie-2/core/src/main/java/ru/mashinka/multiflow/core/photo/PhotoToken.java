package ru.mashinka.multiflow.core.photo;

import java.util.ArrayList;
import java.util.List;

// одно слово, найденное распознаванием текста на фото: текст и прямоугольник в пикселях
// снимка. Ядро не знает, кто распознавал (на телефоне - ML Kit), ему нужны только эти поля
//#неизменяемость - поля final, объект только для чтения
public final class PhotoToken {

    // промежуток между знаками одного числа - меньше этой доли высоты цифр, между числами соседних
    // клеток - больше: на снимках печатных таблиц было до 0,45 и от 0,7
    static final double SYMBOL_GAP = 0.6;

    private final String text;
    private final int left;
    private final int top;
    private final int right;
    private final int bottom;

    public PhotoToken(String text, int left, int top, int right, int bottom) {
        this.text = text == null ? "" : text;
        this.left = Math.min(left, right);
        this.top = Math.min(top, bottom);
        this.right = Math.max(left, right);
        this.bottom = Math.max(top, bottom);
    }

    // знаки одного слова -> слова. Распознавание иногда склеивает числа соседних клеток
    // («10» и «210» -> «10210»): где промежуток между знаками шире, чем бывает внутри числа,
    // слово делится. Знаки идут слева направо, как их отдало распознавание
    public static List<PhotoToken> split(List<PhotoToken> symbols) {
        List<PhotoToken> words = new ArrayList<>();
        if (symbols.isEmpty()) {
            return words;
        }
        List<Integer> heights = new ArrayList<>();
        for (PhotoToken symbol : symbols) {
            heights.add(symbol.height());
        }
        heights.sort(Integer::compare);
        double limit = SYMBOL_GAP * heights.get(heights.size() / 2);

        PhotoToken word = symbols.get(0);
        for (int k = 1; k < symbols.size(); k++) {
            PhotoToken next = symbols.get(k);
            if (next.left - word.right > limit) {
                words.add(word);
                word = next;
            } else {
                word = new PhotoToken(word.text + next.text, Math.min(word.left, next.left),
                        Math.min(word.top, next.top), Math.max(word.right, next.right),
                        Math.max(word.bottom, next.bottom));
            }
        }
        words.add(word);
        return words;
    }

    public String getText() {
        return text;
    }

    public int getLeft() {
        return left;
    }

    public int getTop() {
        return top;
    }

    public int getRight() {
        return right;
    }

    public int getBottom() {
        return bottom;
    }

    public int width() {
        return Math.max(1, right - left);
    }

    public int height() {
        return Math.max(1, bottom - top);
    }

    public double centerX() {
        return (left + right) / 2.0;
    }

    public double centerY() {
        return (top + bottom) / 2.0;
    }

    @Override
    public String toString() {
        return "'" + text + "'@" + left + "," + top + "-" + right + "," + bottom;
    }
}
