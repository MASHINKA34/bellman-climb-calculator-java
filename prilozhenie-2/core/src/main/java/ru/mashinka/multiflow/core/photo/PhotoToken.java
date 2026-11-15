package ru.mashinka.multiflow.core.photo;

// одно слово, найденное распознаванием текста на фото: текст и прямоугольник в пикселях
// снимка. Ядро не знает, кто распознавал (на телефоне - ML Kit), ему нужны только эти поля
//#неизменяемость - поля final, объект только для чтения
public final class PhotoToken {

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
