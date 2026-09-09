package ru.mashinka.bellman.web.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import ru.mashinka.bellman.core.dp.BellmanSolver;
import ru.mashinka.bellman.core.dp.ClimbCalculationException;

/**
 * REST-интерфейс калькулятора. Вся логика лежит в модуле core —
 * здесь только приём запроса, вызов решателя и обработка ошибок.
 */
@RestController
@RequestMapping(value = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
public class ClimbController {

    /** Исходные данные по умолчанию — ими заполняется форма при открытии страницы. */
    @GetMapping("/defaults")
    public ClimbRequest defaults() {
        return new ClimbRequest();
    }

    /** Расчёт оптимального набора крейсерской высоты. */
    @PostMapping(value = "/climb", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ClimbResponse climb(@RequestBody(required = false) ClimbRequest request) {
        ClimbRequest actual = request != null ? request : new ClimbRequest();
        BellmanSolver solver = new BellmanSolver(actual.getAircraft(), actual.getTask());
        return ClimbResponse.from(solver.solve());
    }

    /** Исходные данные противоречивы — виновата форма, а не расчёт. */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleInvalidInput(IllegalArgumentException exception) {
        return new ApiError("Некорректные исходные данные", exception.getMessage());
    }

    /** Данные корректны, но решения не существует. */
    @ExceptionHandler(ClimbCalculationException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ApiError handleUnsolvable(ClimbCalculationException exception) {
        return new ApiError("Задача не имеет решения", exception.getMessage());
    }

    /** Сообщение об ошибке в формате, понятном интерфейсу. */
    public static class ApiError {

        private final String title;
        private final String detail;

        ApiError(String title, String detail) {
            this.title = title;
            this.detail = detail;
        }

        public String getTitle() {
            return title;
        }

        public String getDetail() {
            return detail;
        }
    }
}
