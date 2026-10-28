package ru.mashinka.multiflow.android;

import android.os.SystemClock;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import ru.mashinka.multiflow.core.gomoryhu.GomoryHuSolver;
import ru.mashinka.multiflow.core.gomoryhu.MultiFlowSolution;
import ru.mashinka.multiflow.core.json.SolutionJson;
import ru.mashinka.multiflow.core.network.Examples;
import ru.mashinka.multiflow.core.network.Network;

// мост между страницей и ядром - замена сервера на телефоне
// страница зовёт fetch('api/solve'), android-bridge.js переводит это в вызов solve()
//#разделение - формул тут нет, весь расчёт в модуле core
public class MultiFlowBridge {

    // имя, под которым мост виден из JavaScript
    static final String NAME = "MultiFlowBridge";

    private final GomoryHuSolver solver = new GomoryHuSolver();
    private final boolean night;

    MultiFlowBridge(boolean night) {
        this.night = night;
    }

    // уровень логов для страницы: тот же, что у приложения
    @JavascriptInterface
    public String logLevel() {
        return AppLog.debugEnabled() ? "debug" : "info";
    }

    // тема страницы - та же, что у системы. WebView Android 10-12 сам её странице
    // не сообщает (prefers-color-scheme там всегда светлый), поэтому сообщает приложение
    @JavascriptInterface
    public String theme() {
        return night ? "dark" : "light";
    }

    // встроенный пример для кнопки «Пример»
    @JavascriptInterface
    public String example() {
        long startedAt = SystemClock.elapsedRealtime();
        try {
            String body = SolutionJson.example(Examples.defaultNetwork());
            AppLog.i("bridge", "example served: " + body.length() + " chars in "
                    + (SystemClock.elapsedRealtime() - startedAt) + " ms");
            return envelope(200, body);
        } catch (RuntimeException unexpected) {
            AppLog.e("bridge", "example failed", unexpected);
            return failure(500, "Пример не загрузился", unexpected);
        }
    }

    // расчёт: {"runId": "...", "capacities": [[...], ...]} -> решение в JSON
    @JavascriptInterface
    public String solve(String requestJson) {
        long startedAt = SystemClock.elapsedRealtime();
        String runId = "unknown";
        int length = requestJson == null ? 0 : requestJson.length();
        try {
            JSONObject request = new JSONObject(requestJson == null ? "" : requestJson);
            runId = request.optString("runId", "unknown");
            int[][] capacities = readMatrix(request.getJSONArray("capacities"));
            AppLog.i("bridge", "solve request run=" + runId + " size=" + capacities.length
                    + " request=" + length + " chars");

            MultiFlowSolution solution = solver.solve(Network.of(capacities), runId);
            String body = SolutionJson.write(solution, runId);

            AppLog.i("bridge", "solve ok run=" + runId + " steps=" + solution.getSteps().size()
                    + " response=" + body.length() + " chars in "
                    + (SystemClock.elapsedRealtime() - startedAt) + " ms");
            return envelope(200, body);

        } catch (JSONException malformed) {
            AppLog.w("bridge", "solve rejected run=" + runId + ": request is not valid JSON ("
                    + length + " chars)", malformed);
            return failure(400, "Некорректный запрос", malformed);
        } catch (IllegalArgumentException invalid) {
            // плохие исходные данные - штатная ситуация, текст ошибки уже для человека
            AppLog.w("bridge", "solve rejected run=" + runId + ": " + invalid.getMessage());
            return failure(400, "Некорректные исходные данные", invalid);
        } catch (RuntimeException unexpected) {
            AppLog.e("bridge", "solve failed run=" + runId + " after "
                    + (SystemClock.elapsedRealtime() - startedAt) + " ms", unexpected);
            return failure(500, "Сбой расчёта", unexpected);
        }
    }

    // каждая клетка обязана быть целым числом: дробное org.json молча обрезал бы
    private static int[][] readMatrix(JSONArray rows) throws JSONException {
        int[][] matrix = new int[rows.length()][];
        for (int i = 0; i < rows.length(); i++) {
            JSONArray row = rows.getJSONArray(i);
            matrix[i] = new int[row.length()];
            for (int j = 0; j < row.length(); j++) {
                Object value = row.get(j);
                if (!(value instanceof Integer) && !(value instanceof Long)) {
                    throw new IllegalArgumentException("Клетка (" + (i + 1) + "," + (j + 1)
                            + "): нужно целое число, а пришло " + value);
                }
                long number = ((Number) value).longValue();
                if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("Клетка (" + (i + 1) + "," + (j + 1)
                            + "): слишком большое число " + number);
                }
                matrix[i][j] = (int) number;
            }
        }
        return matrix;
    }

    // ответ в том же виде, в каком его отдал бы сервер сайта
    private static String envelope(int status, String body) {
        return "{\"ok\":true,\"status\":" + status + ",\"body\":" + body + "}";
    }

    // сообщение об ошибке собирается вручную, без JSONObject: сюда попадают
    // в том числе сбои самой сериализации, и падать второй раз здесь нельзя
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
