package ru.mashinka.multiflow.web.api;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// ошибки расчёта в том виде, который понимает страница: {"title": "...", "detail": "..."}.
// Заголовки те же, что у моста в Android-приложении
@RestControllerAdvice(assignableTypes = {SolveController.class, ClientLogController.class})
public class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    // 400: запрос не того вида - не JSON, нет поля, строка вместо массива
    @ExceptionHandler({MalformedRequestException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, String>> malformed(Exception problem, HttpServletRequest http) {
        log.warn("{} rejected run={}: request is malformed: {}", operation(http), runId(http), problem.getMessage());
        return error(HttpStatus.BAD_REQUEST, "Некорректный запрос", problem.getMessage());
    }

    // 400: запрос в порядке, но данные не подходят - текст ошибки ядра уже для человека
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException problem, HttpServletRequest http) {
        log.warn("{} rejected run={}: {}", operation(http), runId(http), problem.getMessage());
        return error(HttpStatus.BAD_REQUEST, "Некорректные исходные данные", problem.getMessage());
    }

    // 500: сбой в коде; в журнал - целиком, со стеком
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unexpected(Exception problem, HttpServletRequest http) {
        log.error("{} failed run={} {} {}", operation(http), runId(http), http.getMethod(), http.getRequestURI(), problem);
        String detail = problem.getMessage() != null ? problem.getMessage() : problem.getClass().getSimpleName();
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Сбой расчёта", detail);
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String title, String detail) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("detail", detail);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static Object runId(HttpServletRequest http) {
        Object runId = http.getAttribute(SolveController.RUN_ATTRIBUTE);
        return runId != null ? runId : "unknown";
    }

    private static Object operation(HttpServletRequest http) {
        Object operation = http.getAttribute(SolveController.OPERATION_ATTRIBUTE);
        return operation != null ? operation : http.getRequestURI();
    }
}
