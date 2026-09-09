package ru.mashinka.bellman.core.dp;

/**
 * Расчёт невозможен при заданных исходных данных: например, крейсерская высота
 * лежит выше практического потолка самолёта или ограничения по скорости
 * отсекают все допустимые переходы.
 */
public class ClimbCalculationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ClimbCalculationException(String message) {
        super(message);
    }
}
