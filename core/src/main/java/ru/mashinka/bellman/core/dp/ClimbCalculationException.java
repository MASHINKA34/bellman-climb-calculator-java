package ru.mashinka.bellman.core.dp;

//#наследование - extends RuntimeException: не нужно писать throws по всей цепочке
//#исключения - IllegalArgumentException = плохой ввод (400), это = решения нет (422)
public class ClimbCalculationException extends RuntimeException {

    // исключения сериализуемы, без этого поля компилятор ругается
    private static final long serialVersionUID = 1L;

    public ClimbCalculationException(String message) {
        super(message);
    }
}
