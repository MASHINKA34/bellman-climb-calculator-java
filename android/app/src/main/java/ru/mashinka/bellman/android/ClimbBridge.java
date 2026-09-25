package ru.mashinka.bellman.android;

import android.webkit.JavascriptInterface;

import org.json.JSONException;
import org.json.JSONObject;

import ru.mashinka.bellman.core.dp.BellmanSolver;
import ru.mashinka.bellman.core.dp.ClimbCalculationException;
import ru.mashinka.bellman.core.dp.ClimbSolution;
import ru.mashinka.bellman.core.model.Aircraft;
import ru.mashinka.bellman.core.model.ClimbTask;

// мост между страницей и ядром, замена REST-контроллера на телефоне
public class ClimbBridge {

    // имя, под которым мост виден из JavaScript
    static final String NAME = "BellmanBridge";

    // исходные данные по умолчанию — ими заполняется форма при запуске
    @JavascriptInterface
    public String defaults() {
        try {
            JSONObject body = ClimbJson.request(Aircraft.defaultAirliner(), ClimbTask.defaultTask());
            return envelope(true, 200, body);
        } catch (Exception problem) {
            return failure(500, "Не удалось подготовить исходные данные", problem);
        }
    }

    // расчёт оптимального набора крейсерской высоты
    @JavascriptInterface
    public String climb(String requestJson) {
        try {
            JSONObject request = new JSONObject(requestJson);
            Aircraft aircraft = ClimbJson.readAircraft(request.optJSONObject("aircraft"));
            ClimbTask task = ClimbJson.readTask(request.optJSONObject("task"));

            ClimbSolution solution = new BellmanSolver(aircraft, task).solve();
            return envelope(true, 200, ClimbJson.solution(solution));

        } catch (IllegalArgumentException invalid) {
            return failure(400, "Некорректные исходные данные", invalid);
        } catch (ClimbCalculationException unsolvable) {
            return failure(422, "Задача не имеет решения", unsolvable);
        } catch (Exception unexpected) {
            return failure(500, "Сбой расчёта", unexpected);
        }
    }

    // ответ в том же виде, в каком его отдаёт сервер веб-версии
    private static String envelope(boolean ok, int status, JSONObject body) throws JSONException {
        JSONObject envelope = new JSONObject();
        envelope.put("ok", ok);
        envelope.put("status", status);
        envelope.put("body", body);
        return envelope.toString();
    }

    // сообщение об ошибке. Собирается вручную, без JSONObject: сюда попадают
    // в том числе сбои самой сериализации, и падать второй раз здесь нельзя.
    private static String failure(int status, String title, Throwable cause) {
        String detail = cause.getMessage() != null
                ? cause.getMessage()
                : cause.getClass().getSimpleName();
        return "{\"ok\":false,\"status\":" + status
                + ",\"body\":{\"title\":\"" + escape(title) + "\""
                + ",\"detail\":\"" + escape(detail) + "\"}}";
    }

    private static String escape(String text) {
        StringBuilder result = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char symbol = text.charAt(i);
            switch (symbol) {
                case '"':
                    result.append("\\\"");
                    break;
                case '\\':
                    result.append("\\\\");
                    break;
                case '\n':
                    result.append("\\n");
                    break;
                case '\r':
                    result.append("\\r");
                    break;
                case '\t':
                    result.append("\\t");
                    break;
                default:
                    if (symbol < 0x20) {
                        result.append(String.format("\\u%04x", (int) symbol));
                    } else {
                        result.append(symbol);
                    }
            }
        }
        return result.toString();
    }
}
