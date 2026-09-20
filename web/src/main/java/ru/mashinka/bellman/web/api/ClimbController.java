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

// REST-интерфейс калькулятора. Вся логика лежит в модуле core —
// здесь только приём запроса, вызов решателя и обработка ошибок.
@RestController
@RequestMapping(value = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
public class ClimbController {

    // исходные данные по умолчанию — ими заполняется форма при открытии страницы
    @GetMapping("/defaults")
    public ClimbRequest defaults() {
        return new ClimbRequest();
    }

    // расчёт оптимального набора крейсерской высоты
    @PostMapping(value = "/climb", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ClimbResponse climb(@RequestBody(required = false) ClimbRequest request) {
        ClimbRequest actual = request != null ? request : new ClimbRequest();
        BellmanSolver solver = new BellmanSolver(actual.getAircraft(), actual.getTask());
        return ClimbResponse.from(solver.solve());
    }

    // 400: виноват тот, кто прислал запрос. крейсерская ниже начальной, масса
    // отрицательная - чинится правкой формы. повторять такой запрос бессмысленно
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleInvalidInput(IllegalArgumentException exception) {
        return new ApiError("Некорректные исходные данные", exception.getMessage());
    }

    // 422: запрос правильный, сервер его понял - но ответа не существует.
    // самолёт не вытягивает на заданную высоту. это не ошибка ввода,
    // поэтому 400 тут был бы враньём, а 500 сваливал бы вину на сервер
    @ExceptionHandler(ClimbCalculationException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ApiError handleUnsolvable(ClimbCalculationException exception) {
        return new ApiError("Задача не имеет решения", exception.getMessage());
    }

    // сообщение об ошибке в формате, понятном интерфейсу
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
