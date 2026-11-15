package ru.mashinka.multiflow.android;

import android.app.Activity;
import android.os.SystemClock;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import ru.mashinka.multiflow.core.gomoryhu.GomoryHuSolver;
import ru.mashinka.multiflow.core.gomoryhu.MultiFlowSolution;
import ru.mashinka.multiflow.core.json.PlacementJson;
import ru.mashinka.multiflow.core.json.SolutionJson;
import ru.mashinka.multiflow.core.network.Examples;
import ru.mashinka.multiflow.core.network.Network;
import ru.mashinka.multiflow.core.photo.PhotoTarget;
import ru.mashinka.multiflow.core.placement.HungarianSolver;
import ru.mashinka.multiflow.core.placement.PlacementExamples;
import ru.mashinka.multiflow.core.placement.PlacementProblem;
import ru.mashinka.multiflow.core.placement.PlacementSolution;

// мост между страницей и ядром - замена сервера на телефоне
// страница зовёт fetch('api/solve'), android-bridge.js переводит это в вызов solve()
//#разделение - формул тут нет, весь расчёт в модуле core
public class MultiFlowBridge {

    // имя, под которым мост виден из JavaScript
    static final String NAME = "MultiFlowBridge";

    private final GomoryHuSolver solver = new GomoryHuSolver();
    private final HungarianSolver placementSolver = new HungarianSolver();
    private final boolean night;
    private final Activity activity;
    private final PhotoScanner photoScanner;

    MultiFlowBridge(boolean night, Activity activity, PhotoScanner photoScanner) {
        this.night = night;
        this.activity = activity;
        this.photoScanner = photoScanner;
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

    // ------------------------------------------------------------------ вкладка «Размещение»

    // встроенный пример из пособия МЭСИ для кнопки «Пример»
    @JavascriptInterface
    public String placementExample() {
        long startedAt = SystemClock.elapsedRealtime();
        try {
            String body = PlacementJson.example(PlacementExamples.production(), PlacementExamples.sales(),
                    PlacementExamples.volume(), PlacementExamples.price());
            AppLog.i("bridge", "placement example served: " + body.length() + " chars in "
                    + (SystemClock.elapsedRealtime() - startedAt) + " ms");
            return envelope(200, body);
        } catch (RuntimeException unexpected) {
            AppLog.e("bridge", "placement example failed", unexpected);
            return failure(500, "Пример не загрузился", unexpected);
        }
    }

    // расчёт: {"runId", "production": [[...]], "sales": [[...]], "volume": [...], "price": [...]}
    @JavascriptInterface
    public String solvePlacement(String requestJson) {
        long startedAt = SystemClock.elapsedRealtime();
        String runId = "unknown";
        int length = requestJson == null ? 0 : requestJson.length();
        try {
            JSONObject request = new JSONObject(requestJson == null ? "" : requestJson);
            runId = request.optString("runId", "unknown");
            int[][] production = readMatrix(request.getJSONArray("production"));
            int[][] sales = readMatrix(request.getJSONArray("sales"));
            int[] volume = readVector(request.getJSONArray("volume"), "Плановый объём");
            int[] price = readVector(request.getJSONArray("price"), "Плановая цена");
            AppLog.i("bridge", "placement request run=" + runId + " products=" + production.length
                    + " plants=" + (production.length > 0 ? production[0].length : 0)
                    + " request=" + length + " chars");

            PlacementSolution solution = placementSolver.solve(
                    PlacementProblem.of(production, sales, volume, price), runId);
            String body = PlacementJson.write(solution, runId);

            AppLog.i("bridge", "placement ok run=" + runId + " iterations=" + solution.getIterations().size()
                    + " total=" + solution.getTotalProfit() + " rub response=" + body.length() + " chars in "
                    + (SystemClock.elapsedRealtime() - startedAt) + " ms");
            return envelope(200, body);

        } catch (JSONException malformed) {
            AppLog.w("bridge", "placement rejected run=" + runId + ": request is not valid JSON ("
                    + length + " chars)", malformed);
            return failure(400, "Некорректный запрос", malformed);
        } catch (IllegalArgumentException invalid) {
            // плохие исходные данные - штатная ситуация, текст ошибки уже для человека
            AppLog.w("bridge", "placement rejected run=" + runId + ": " + invalid.getMessage());
            return failure(400, "Некорректные исходные данные", invalid);
        } catch (RuntimeException unexpected) {
            AppLog.e("bridge", "placement failed run=" + runId + " after "
                    + (SystemClock.elapsedRealtime() - startedAt) + " ms", unexpected);
            return failure(500, "Сбой расчёта", unexpected);
        }
    }

    // ------------------------------------------------------------------ таблица по фото

    // страница показывает кнопки «По фото» только в приложении: на сайте распознавать нечем
    @JavascriptInterface
    public String photoSupported() {
        return "yes";
    }

    // {"scanId", "target": flow|production|sales|plan, "source": camera|gallery}
    // отвечает сразу; распознанная таблица придёт позже в window.onPhotoScan
    @JavascriptInterface
    public String scanPhoto(String requestJson) {
        String scanId = "unknown";
        try {
            JSONObject request = new JSONObject(requestJson == null ? "" : requestJson);
            scanId = request.optString("scanId", "unknown");
            String target = PhotoTarget.fromId(request.getString("target")).getId();
            String source = request.getString("source");
            if (!"camera".equals(source) && !"gallery".equals(source)) {
                throw new IllegalArgumentException("Неизвестный источник фото: " + source);
            }
            AppLog.i("bridge", "photo request scan=" + scanId + " target=" + target + " source=" + source);
            String id = scanId;
            activity.runOnUiThread(() -> photoScanner.start(id, target, source));
            return envelope(202, "{\"scanId\":\"" + escape(scanId) + "\"}");
        } catch (JSONException malformed) {
            AppLog.w("bridge", "photo request rejected scan=" + scanId + ": not valid JSON", malformed);
            return failure(400, "Некорректный запрос", malformed);
        } catch (IllegalArgumentException invalid) {
            AppLog.w("bridge", "photo request rejected scan=" + scanId + ": " + invalid.getMessage());
            return failure(400, "Некорректный запрос", invalid);
        } catch (RuntimeException unexpected) {
            AppLog.e("bridge", "photo request failed scan=" + scanId, unexpected);
            return failure(500, "Сбой при запуске съёмки", unexpected);
        }
    }

    // ------------------------------------------------------------------ разбор запроса

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

    private static int[] readVector(JSONArray values, String title) throws JSONException {
        int[] vector = new int[values.length()];
        for (int i = 0; i < values.length(); i++) {
            Object value = values.get(i);
            if (!(value instanceof Integer) && !(value instanceof Long)) {
                throw new IllegalArgumentException(title + ", вид " + (i + 1) + ": нужно целое число, а пришло "
                        + value);
            }
            long number = ((Number) value).longValue();
            if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(title + ", вид " + (i + 1) + ": слишком большое число " + number);
            }
            vector[i] = (int) number;
        }
        return vector;
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
