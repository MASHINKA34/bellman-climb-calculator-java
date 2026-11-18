package ru.mashinka.multiflow.web.api;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ru.mashinka.multiflow.core.gomoryhu.GomoryHuSolver;
import ru.mashinka.multiflow.core.gomoryhu.MultiFlowSolution;
import ru.mashinka.multiflow.core.json.PlacementJson;
import ru.mashinka.multiflow.core.json.SolutionJson;
import ru.mashinka.multiflow.core.network.Examples;
import ru.mashinka.multiflow.core.network.Network;
import ru.mashinka.multiflow.core.placement.HungarianSolver;
import ru.mashinka.multiflow.core.placement.PlacementExamples;
import ru.mashinka.multiflow.core.placement.PlacementProblem;
import ru.mashinka.multiflow.core.placement.PlacementSolution;
import ru.mashinka.multiflow.web.StartupCheck;

// те же адреса, что подменяет android-bridge.js в приложении: api/example, api/solve,
// api/placement/example, api/placement/solve. Ответы - тот же JSON из ядра (SolutionJson, PlacementJson),
// поэтому app.js и placement.js на сайте и в APK одни и те же
//#разделение - формул тут нет, весь расчёт в модуле core
@RestController
@RequestMapping(value = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
public class SolveController {

    // по этим атрибутам обработчик ошибок пишет, какой расчёт упал (ApiErrorHandler)
    static final String RUN_ATTRIBUTE = "multiflow.runId";
    static final String OPERATION_ATTRIBUTE = "multiflow.operation";

    private static final Logger log = LoggerFactory.getLogger(SolveController.class);

    // решатели без состояния: один экземпляр на все запросы
    private final GomoryHuSolver solver = new GomoryHuSolver();
    private final HungarianSolver placementSolver = new HungarianSolver();
    private final RequestReader reader;
    private final ObjectProvider<BuildProperties> build;

    public SolveController(ObjectMapper mapper, ObjectProvider<BuildProperties> build) {
        this.reader = new RequestReader(mapper);
        this.build = build;
    }

    // жив ли сайт и какая версия выложена - для скрипта выкладки и проверки руками
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("version", StartupCheck.version(build));
        return body;
    }

    // встроенный пример для кнопки «Пример» вкладки «Поток»
    @GetMapping("/example")
    public String example(HttpServletRequest http) {
        http.setAttribute(OPERATION_ATTRIBUTE, "example");
        long startedAt = System.nanoTime();
        String body = SolutionJson.example(Examples.defaultNetwork());
        log.info("example served: {} chars in {} ms", body.length(), millis(startedAt));
        return body;
    }

    // расчёт: {"runId": "...", "capacities": [[...], ...]} -> решение в JSON
    @PostMapping("/solve")
    public String solve(@RequestBody(required = false) String requestBody, HttpServletRequest http) {
        http.setAttribute(OPERATION_ATTRIBUTE, "solve");
        long startedAt = System.nanoTime();
        JsonNode request = reader.parse(requestBody);
        String runId = RequestReader.runId(request);
        http.setAttribute(RUN_ATTRIBUTE, runId);

        int[][] capacities = RequestReader.matrix(request, "capacities");
        log.info("solve request run={} size={} request={} chars", runId, capacities.length, requestBody.length());

        MultiFlowSolution solution = solver.solve(Network.of(capacities), runId);
        String body = SolutionJson.write(solution, runId);
        log.info("solve ok run={} steps={} response={} chars in {} ms", runId, solution.getSteps().size(),
                body.length(), millis(startedAt));
        return body;
    }

    // встроенный пример из пособия МЭСИ для кнопки «Пример» вкладки «Размещение»
    @GetMapping("/placement/example")
    public String placementExample(HttpServletRequest http) {
        http.setAttribute(OPERATION_ATTRIBUTE, "placement example");
        long startedAt = System.nanoTime();
        String body = PlacementJson.example(PlacementExamples.production(), PlacementExamples.sales(),
                PlacementExamples.volume(), PlacementExamples.price());
        log.info("placement example served: {} chars in {} ms", body.length(), millis(startedAt));
        return body;
    }

    // расчёт: {"runId", "production": [[...]], "sales": [[...]], "volume": [...], "price": [...]}
    @PostMapping("/placement/solve")
    public String solvePlacement(@RequestBody(required = false) String requestBody, HttpServletRequest http) {
        http.setAttribute(OPERATION_ATTRIBUTE, "placement");
        long startedAt = System.nanoTime();
        JsonNode request = reader.parse(requestBody);
        String runId = RequestReader.runId(request);
        http.setAttribute(RUN_ATTRIBUTE, runId);

        int[][] production = RequestReader.matrix(request, "production");
        int[][] sales = RequestReader.matrix(request, "sales");
        int[] volume = RequestReader.vector(request, "volume", "Плановый объём");
        int[] price = RequestReader.vector(request, "price", "Плановая цена");
        log.info("placement request run={} products={} plants={} request={} chars", runId, production.length,
                production.length > 0 ? production[0].length : 0, requestBody.length());

        PlacementSolution solution = placementSolver.solve(PlacementProblem.of(production, sales, volume, price), runId);
        String body = PlacementJson.write(solution, runId);
        log.info("placement ok run={} iterations={} total={} rub response={} chars in {} ms", runId,
                solution.getIterations().size(), solution.getTotalProfit(), body.length(), millis(startedAt));
        return body;
    }

    private static long millis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
