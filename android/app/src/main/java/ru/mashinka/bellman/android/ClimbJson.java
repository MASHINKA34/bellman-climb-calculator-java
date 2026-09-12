package ru.mashinka.bellman.android;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

import ru.mashinka.bellman.core.dp.ClimbSolution;
import ru.mashinka.bellman.core.dp.TrajectoryPoint;
import ru.mashinka.bellman.core.model.Aircraft;
import ru.mashinka.bellman.core.model.ClimbTask;
import ru.mashinka.bellman.core.model.Criterion;

/**
 * Перевод между JSON страницы и объектами ядра.
 *
 * <p>На сервере этим занимается Jackson, здесь — встроенный в Android
 * {@code org.json}: так у приложения не появляется ни одной внешней
 * зависимости. Имена полей в точности повторяют ответ веб-версии
 * ({@code ClimbResponse}), иначе страница их не поймёт.
 */
final class ClimbJson {

    /** Предел числа узлов, при котором таблица функции Беллмана ещё передаётся странице. */
    private static final int MAX_MATRIX_NODES = 6000;

    private ClimbJson() {
    }

    // ------------------------------------------------------------ чтение запроса

    /** Данные самолёта из JSON; отсутствующие поля остаются значениями по умолчанию. */
    static Aircraft readAircraft(JSONObject json) {
        Aircraft aircraft = Aircraft.defaultAirliner();
        if (json == null) {
            return aircraft;
        }
        aircraft.setName(json.optString("name", aircraft.getName()));
        aircraft.setMass(json.optDouble("mass", aircraft.getMass()));
        aircraft.setWingArea(json.optDouble("wingArea", aircraft.getWingArea()));
        aircraft.setCx0(json.optDouble("cx0", aircraft.getCx0()));
        aircraft.setInducedDragFactor(
                json.optDouble("inducedDragFactor", aircraft.getInducedDragFactor()));
        aircraft.setCyMax(json.optDouble("cyMax", aircraft.getCyMax()));
        aircraft.setStallMargin(json.optDouble("stallMargin", aircraft.getStallMargin()));
        aircraft.setThrustSeaLevel(json.optDouble("thrustSeaLevel", aircraft.getThrustSeaLevel()));
        aircraft.setThrustAltitudeExponent(
                json.optDouble("thrustAltitudeExponent", aircraft.getThrustAltitudeExponent()));
        aircraft.setThrustMachFactor(
                json.optDouble("thrustMachFactor", aircraft.getThrustMachFactor()));
        aircraft.setSfcSeaLevel(json.optDouble("sfcSeaLevel", aircraft.getSfcSeaLevel()));
        aircraft.setSfcMachFactor(json.optDouble("sfcMachFactor", aircraft.getSfcMachFactor()));
        aircraft.setMaxMach(json.optDouble("maxMach", aircraft.getMaxMach()));
        aircraft.setMaxIndicatedSpeed(
                json.optDouble("maxIndicatedSpeed", aircraft.getMaxIndicatedSpeed()));
        return aircraft;
    }

    /** Условия задачи из JSON. */
    static ClimbTask readTask(JSONObject json) {
        ClimbTask task = ClimbTask.defaultTask();
        if (json == null) {
            return task;
        }
        task.setStartAltitude(json.optDouble("startAltitude", task.getStartAltitude()));
        task.setCruiseAltitude(json.optDouble("cruiseAltitude", task.getCruiseAltitude()));
        task.setAltitudeStep(json.optDouble("altitudeStep", task.getAltitudeStep()));
        task.setStartSpeed(json.optDouble("startSpeed", task.getStartSpeed()));
        task.setSpeedMin(json.optDouble("speedMin", task.getSpeedMin()));
        task.setSpeedMax(json.optDouble("speedMax", task.getSpeedMax()));
        task.setSpeedStep(json.optDouble("speedStep", task.getSpeedStep()));
        task.setMaxSpeedChangePerStep(
                json.optDouble("maxSpeedChangePerStep", task.getMaxSpeedChangePerStep()));
        task.setMinRateOfClimb(json.optDouble("minRateOfClimb", task.getMinRateOfClimb()));
        task.setCostIndexKgPerMinute(
                json.optDouble("costIndexKgPerMinute", task.getCostIndexKgPerMinute()));
        task.setCruiseMachTolerance(
                json.optDouble("cruiseMachTolerance", task.getCruiseMachTolerance()));

        String criterion = json.optString("criterion", task.getCriterion().name());
        try {
            task.setCriterion(Criterion.valueOf(criterion));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("Неизвестный критерий оптимальности: " + criterion);
        }

        // null означает «крейсерскую скорость выбирает алгоритм», и это не то же
        // самое, что отсутствие поля — поэтому разбирается отдельно.
        if (json.has("targetCruiseMach")) {
            task.setTargetCruiseMach(json.isNull("targetCruiseMach")
                    ? null
                    : Double.valueOf(json.optDouble("targetCruiseMach")));
        }
        return task;
    }

    // ------------------------------------------------------------ запись ответа

    /** Исходные данные в том виде, в каком их отдаёт {@code GET /api/defaults}. */
    static JSONObject request(Aircraft aircraft, ClimbTask task) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("aircraft", aircraft(aircraft));
        json.put("task", task(task));
        return json;
    }

    private static JSONObject aircraft(Aircraft aircraft) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("name", aircraft.getName());
        json.put("mass", aircraft.getMass());
        json.put("wingArea", aircraft.getWingArea());
        json.put("cx0", aircraft.getCx0());
        json.put("inducedDragFactor", aircraft.getInducedDragFactor());
        json.put("cyMax", aircraft.getCyMax());
        json.put("stallMargin", aircraft.getStallMargin());
        json.put("thrustSeaLevel", aircraft.getThrustSeaLevel());
        json.put("thrustAltitudeExponent", aircraft.getThrustAltitudeExponent());
        json.put("thrustMachFactor", aircraft.getThrustMachFactor());
        json.put("sfcSeaLevel", aircraft.getSfcSeaLevel());
        json.put("sfcMachFactor", aircraft.getSfcMachFactor());
        json.put("maxMach", aircraft.getMaxMach());
        json.put("maxIndicatedSpeed", aircraft.getMaxIndicatedSpeed());
        return json;
    }

    private static JSONObject task(ClimbTask task) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("startAltitude", task.getStartAltitude());
        json.put("cruiseAltitude", task.getCruiseAltitude());
        json.put("altitudeStep", task.getAltitudeStep());
        json.put("startSpeed", task.getStartSpeed());
        json.put("speedMin", task.getSpeedMin());
        json.put("speedMax", task.getSpeedMax());
        json.put("speedStep", task.getSpeedStep());
        json.put("maxSpeedChangePerStep", task.getMaxSpeedChangePerStep());
        json.put("minRateOfClimb", task.getMinRateOfClimb());
        json.put("criterion", task.getCriterion().name());
        json.put("costIndexKgPerMinute", task.getCostIndexKgPerMinute());
        json.put("cruiseMachTolerance", task.getCruiseMachTolerance());
        json.put("targetCruiseMach", task.getTargetCruiseMach() == null
                ? JSONObject.NULL
                : task.getTargetCruiseMach());
        return json;
    }

    /** Результат расчёта в том виде, в каком его отдаёт {@code POST /api/climb}. */
    static JSONObject solution(ClimbSolution solution) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("criterion", solution.getCriterion().name());
        json.put("criterionTitle", solution.getCriterion().getTitle());
        json.put("criterionUnit", solution.getCriterion().getUnit());
        json.put("optimalValue", solution.getOptimalValue());
        json.put("totalTime", solution.getTotalTime());
        json.put("totalFuel", solution.getTotalFuel());
        json.put("totalDistance", solution.getTotalDistance());
        json.put("trajectory", trajectory(solution.getTrajectory()));
        json.put("altitudes", numbers(solution.getAltitudes()));
        json.put("speeds", numbers(solution.getSpeeds()));
        json.put("feasibleNodes", solution.getFeasibleNodes());
        json.put("evaluatedTransitions", solution.getEvaluatedTransitions());
        json.put("solveTimeMillis", solution.getSolveTimeMillis());

        JSONArray notes = new JSONArray();
        for (String note : solution.getNotes()) {
            notes.put(note);
        }

        int nodes = solution.getAltitudes().length * solution.getSpeeds().length;
        if (nodes <= MAX_MATRIX_NODES) {
            json.put("valueFunction", valueFunction(solution.getValueFunction()));
            json.put("feasible", feasible(solution.getFeasible()));
        } else {
            notes.put(String.format(Locale.US,
                    "Число узлов сетки - %d, поэтому поузловые таблицы не передаются: "
                            + "ответ весил бы сотни килобайт, а браузер телефона не справился бы "
                            + "с их отрисовкой. Сама оптимальная траектория рассчитана полностью. "
                            + "Увеличьте шаг по высоте или по скорости, чтобы увидеть таблицу "
                            + "функции Беллмана.", nodes));
        }
        json.put("notes", notes);
        return json;
    }

    private static JSONArray trajectory(List<TrajectoryPoint> points) throws JSONException {
        JSONArray array = new JSONArray();
        for (TrajectoryPoint point : points) {
            JSONObject json = new JSONObject();
            json.put("step", point.getStep());
            json.put("altitude", point.getAltitude());
            json.put("speed", point.getSpeed());
            json.put("mach", point.getMach());
            json.put("indicatedSpeed", point.getIndicatedSpeed());
            json.put("energyAltitude", point.getEnergyAltitude());
            json.put("liftCoefficient", point.getLiftCoefficient());
            json.put("thrust", point.getThrust());
            json.put("drag", point.getDrag());
            json.put("excessThrust", point.getExcessThrust());
            json.put("fuelFlow", point.getFuelFlow());
            json.put("energyRate", point.getEnergyRate());
            json.put("rateOfClimb", point.getRateOfClimb());
            json.put("segmentTime", point.getSegmentTime());
            json.put("segmentFuel", point.getSegmentFuel());
            json.put("segmentDistance", point.getSegmentDistance());
            json.put("time", point.getTime());
            json.put("fuel", point.getFuel());
            json.put("distance", point.getDistance());
            array.put(json);
        }
        return array;
    }

    private static JSONArray numbers(double[] values) throws JSONException {
        JSONArray array = new JSONArray();
        for (double value : values) {
            array.put(value);
        }
        return array;
    }

    /**
     * Функция Беллмана. Бесконечность нельзя записать в корректный JSON,
     * поэтому недостижимые состояния становятся null — это и есть прочерки
     * в таблице на странице.
     */
    private static JSONArray valueFunction(double[][] matrix) throws JSONException {
        JSONArray rows = new JSONArray();
        for (double[] row : matrix) {
            JSONArray cells = new JSONArray();
            for (double value : row) {
                if (Double.isFinite(value)) {
                    cells.put(value);
                } else {
                    cells.put(JSONObject.NULL);
                }
            }
            rows.put(cells);
        }
        return rows;
    }

    private static JSONArray feasible(boolean[][] matrix) {
        JSONArray rows = new JSONArray();
        for (boolean[] row : matrix) {
            JSONArray cells = new JSONArray();
            for (boolean value : row) {
                cells.put(value);
            }
            rows.put(cells);
        }
        return rows;
    }
}
