package ru.mashinka.multiflow.web.api;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import ru.mashinka.multiflow.web.access.AccessDeniedException;
import ru.mashinka.multiflow.web.admin.AdminController;
import ru.mashinka.multiflow.web.admin.AdminDeniedException;

// ошибки расчёта в том виде, который понимает страница: {"title": "...", "detail": "..."}.
// Заголовки те же, что у моста в Android-приложении
@RestControllerAdvice(assignableTypes = {SolveController.class, ClientLogController.class, AccessController.class,
        AdminController.class})
public class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    // нет ключа, ключ отключён, вкладка не входит в ключ: причина - кодом, чтобы страница знала,
    // просить ключ заново или убрать вкладку. В журнал уже записано там, где отказали
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> denied(AccessDeniedException refusal) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("title", "Нет доступа");
        body.put("detail", refusal.getMessage());
        body.put("reason", refusal.getReason().getId());
        return ResponseEntity.status(refusal.getReason().getStatus()).cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON).body(body);
    }

    // админка: не по https, не введён или неверен пароль, слишком много попыток
    @ExceptionHandler(AdminDeniedException.class)
    public ResponseEntity<Map<String, String>> adminDenied(AdminDeniedException refusal) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("title", "Нет доступа");
        body.put("detail", refusal.getMessage());
        return ResponseEntity.status(refusal.getStatus()).cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON).body(body);
    }

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
        if (String.valueOf(operation(http)).matches("(access|admin) .*")) {
            // сбой базы или кода ключей: подробности только в журнале
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Сбой сервера", "Попробуйте ещё раз чуть позже.");
        }
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
