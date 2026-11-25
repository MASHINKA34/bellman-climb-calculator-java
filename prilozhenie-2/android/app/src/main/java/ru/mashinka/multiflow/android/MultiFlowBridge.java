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
import ru.mashinka.multiflow.core.json.TransportJson;
import ru.mashinka.multiflow.core.network.Examples;
import ru.mashinka.multiflow.core.network.Network;
import ru.mashinka.multiflow.core.photo.PhotoTarget;
import ru.mashinka.multiflow.core.placement.HungarianSolver;
import ru.mashinka.multiflow.core.placement.PlacementExamples;
import ru.mashinka.multiflow.core.placement.PlacementProblem;
import ru.mashinka.multiflow.core.placement.PlacementSolution;
import ru.mashinka.multiflow.core.transport.TransportProblem;
import ru.mashinka.multiflow.core.transport.TransportSolution;
import ru.mashinka.multiflow.core.transport.TransportSolver;

// мост между страницей и ядром - замена сервера на телефоне
// страница зовёт fetch('api/solve'), android-bridge.js переводит это в вызов solve().
// Каждый расчёт сначала спрашивает AccessGuard: без действующего ключа ответ - отказ
//#разделение - формул тут нет, весь расчёт в модуле core
public class MultiFlowBridge {

    // имя, под которым мост виден из JavaScript
    static final String NAME = "MultiFlowBridge";

    private final GomoryHuSolver solver = new GomoryHuSolver();
    private final HungarianSolver placementSolver = new HungarianSolver();
    private final TransportSolver transportSolver = new TransportSolver();
    private final boolean night;
    private final Activity activity;
    private final PhotoScanner photoScanner;
    private final AccessGuard access;
    private final AccessListener accessListener;

    // ответ на accessCall уходит странице не сразу, а когда ответит сервер
    interface AccessListener {
        void onAnswer(String callId, AccessGuard.Answer answer);
    }

    MultiFlowBridge(boolean night, Activity activity, PhotoScanner photoScanner, AccessGuard access,
                    AccessListener accessListener) {
        this.night = night;
        this.activity = activity;
        this.photoScanner = photoScanner;
        this.access = access;
        this.accessListener = accessListener;
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

    // ------------------------------------------------------------------ доступ по ключу

    // что известно о доступе без обращения к сети: {"status", "body"} - как ответ api/access/check
    @JavascriptInterface
    public String accessState() {
        AccessGuard.Answer state = access.state();
        return "{\"status\":" + state.status + ",\"body\":" + state.body + "}";
    }

    // ввод ключа (activate, в теле {"key"}) или проверка на сервере (check, в теле {"maxAgeMs"}).
    // Отвечает не сразу: результат придёт в window.onAccessAnswer с тем же callId
    @JavascriptInterface
    public void accessCall(String callId, String operation, String requestJson) {
        if (callId == null || !callId.matches("[A-Za-z0-9-]{1,40}")) {
            AppLog.w("bridge", "access call rejected: bad call id");
            return;
        }
        try {
            JSONObject request = new JSONObject(requestJson == null ? "{}" : requestJson);
            if ("activate".equals(operation)) {
                access.activate(request.getString("key"), answer -> accessListener.onAnswer(callId, answer));
            } else {
                access.check("page", request.optLong("maxAgeMs", 0),
                        answer -> accessListener.onAnswer(callId, answer));
            }
        } catch (JSONException malformed) {
            // тело не показываем: в нём может быть ключ
            AppLog.w("bridge", "access call " + operation + " rejected: request is not valid JSON");
        }
    }

    // ------------------------------------------------------------------ вкладка «Поток»

    // встроенный пример для кнопки «Пример»
    @JavascriptInterface
    public String example() {
        long startedAt = SystemClock.elapsedRealtime();
        AccessGuard.Answer refusal = access.refuse(AccessGuard.FLOW);
        if (refusal != null) {
            return refusal.envelope();
        }
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
        AccessGuard.Answer refusal = access.refuse(AccessGuard.FLOW);
        if (refusal != null) {
            return refusal.envelope();
        }
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
        AccessGuard.Answer refusal = access.refuse(AccessGuard.PLACEMENT);
        if (refusal != null) {
            return refusal.envelope();
        }
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
        AccessGuard.Answer refusal = access.refuse(AccessGuard.PLACEMENT);
        if (refusal != null) {
            return refusal.envelope();
        }
        String runId = "unknown";
        int length = requestJson == null ? 0 : requestJson.length();
        try {
            JSONObject request = new JSONObject(requestJson == null ? "" : requestJson);
            runId = request.optString("runId", "unknown");
            int[][] production = readMatrix(request.getJSONArray("production"));
            int[][] sales = readMatrix(request.getJSONArray("sales"));
            int[] volume = readVector(request.getJSONArray("volume"), "Плановый объём", "вид");
            int[] price = readVector(request.getJSONArray("price"), "Плановая цена", "вид");
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

    // ------------------------------------------------------------------ вкладка «Транспортная»

    // 27 вариантов контрольной для списка вариантов
    @JavascriptInterface
    public String transportExample() {
        long startedAt = SystemClock.elapsedRealtime();
        AccessGuard.Answer refusal = access.refuse(AccessGuard.TRANSPORT);
        if (refusal != null) {
            return refusal.envelope();
        }
        try {
            String body = TransportJson.examples();
            AppLog.i("bridge", "transport examples served: " + body.length() + " chars in "
                    + (SystemClock.elapsedRealtime() - startedAt) + " ms");
            return envelope(200, body);
        } catch (RuntimeException unexpected) {
            AppLog.e("bridge", "transport examples failed", unexpected);
            return failure(500, "Варианты не загрузились", unexpected);
        }
    }

    // расчёт: {"runId", "costs": [[...]], "supply": [...], "demand": [...], "fictitiousLast": true}
    @JavascriptInterface
    public String solveTransport(String requestJson) {
        long startedAt = SystemClock.elapsedRealtime();
        AccessGuard.Answer refusal = access.refuse(AccessGuard.TRANSPORT);
        if (refusal != null) {
            return refusal.envelope();
        }
        String runId = "unknown";
        int length = requestJson == null ? 0 : requestJson.length();
        try {
            JSONObject request = new JSONObject(requestJson == null ? "" : requestJson);
            runId = request.optString("runId", "unknown");
            int[][] costs = readMatrix(request.getJSONArray("costs"));
            int[] supply = readVector(request.getJSONArray("supply"), "Запас", "поставщик");
            int[] demand = readVector(request.getJSONArray("demand"), "Потребность", "потребитель");
            boolean fictitiousLast = request.optBoolean("fictitiousLast", true);
            AppLog.i("bridge", "transport request run=" + runId + " suppliers=" + supply.length + " consumers="
                    + demand.length + " fictitiousLast=" + fictitiousLast + " request=" + length + " chars");

            TransportSolution solution = transportSolver.solve(TransportProblem.of(costs, supply, demand),
                    fictitiousLast, runId);
            String body = TransportJson.write(solution, runId);

            AppLog.i("bridge", "transport ok run=" + runId + " north-west=" + solution.getNorthWest().getCost()
                    + " min-cost=" + solution.getMinimumCost().getCost() + " optimal="
                    + solution.getOptimal().getCost() + " response=" + body.length() + " chars in "
                    + (SystemClock.elapsedRealtime() - startedAt) + " ms");
            return envelope(200, body);

        } catch (JSONException malformed) {
            AppLog.w("bridge", "transport rejected run=" + runId + ": request is not valid JSON ("
                    + length + " chars)", malformed);
            return failure(400, "Некорректный запрос", malformed);
        } catch (IllegalArgumentException invalid) {
            // плохие исходные данные - штатная ситуация, текст ошибки уже для человека
            AppLog.w("bridge", "transport rejected run=" + runId + ": " + invalid.getMessage());
            return failure(400, "Некорректные исходные данные", invalid);
        } catch (RuntimeException unexpected) {
            AppLog.e("bridge", "transport failed run=" + runId + " after "
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

    // {"scanId", "target": flow|production|sales|plan|transport, "source": camera|gallery}
    // отвечает сразу; распознанная таблица придёт позже в window.onPhotoScan
    @JavascriptInterface
    public String scanPhoto(String requestJson) {
        String scanId = "unknown";
        try {
            JSONObject request = new JSONObject(requestJson == null ? "" : requestJson);
            scanId = request.optString("scanId", "unknown");
            PhotoTarget photoTarget = PhotoTarget.fromId(request.getString("target"));
            String target = photoTarget.getId();
            AccessGuard.Answer refusal = access.refuse(tabOf(photoTarget));
            if (refusal != null) {
                return refusal.envelope();
            }
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

    private static String tabOf(PhotoTarget target) {
        switch (target) {
            case FLOW:
                return AccessGuard.FLOW;
            case TRANSPORT:
                return AccessGuard.TRANSPORT;
            default:
                return AccessGuard.PLACEMENT;
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

    // item - как называется строка в тексте ошибки: «вид», «поставщик»
    private static int[] readVector(JSONArray values, String title, String item) throws JSONException {
        int[] vector = new int[values.length()];
        for (int i = 0; i < values.length(); i++) {
            Object value = values.get(i);
            if (!(value instanceof Integer) && !(value instanceof Long)) {
                throw new IllegalArgumentException(title + ", " + item + " " + (i + 1) + ": нужно целое число, а пришло "
                        + value);
            }
            long number = ((Number) value).longValue();
            if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(title + ", " + item + " " + (i + 1) + ": слишком большое число "
                        + number);
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
