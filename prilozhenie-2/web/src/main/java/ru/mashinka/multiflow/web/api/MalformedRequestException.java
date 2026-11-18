package ru.mashinka.multiflow.web.api;

// запрос не того вида: не JSON, нет нужного поля, вместо массива строка.
// Это ответ 400 «Некорректный запрос» - так же, как в мосте Android-приложения
public class MalformedRequestException extends RuntimeException {

    public MalformedRequestException(String message) {
        super(message);
    }

    public MalformedRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
