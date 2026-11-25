package ru.mashinka.multiflow.android;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

// доступ по ключу: что разрешено этому телефону и как давно это подтверждал сервер.
// Ключ действует на одном устройстве, оно опознаётся по ANDROID_ID (на сервер уходит его хэш).
//
// Доступ проверяется на сервере при запуске, при возврате в приложение, раз в минуту, пока оно
// открыто, и перед расчётом. Нет связи - приложение работает ещё OFFLINE_LIMIT после последней
// удачной проверки. Время без связи считается по часам с момента загрузки телефона, поэтому
// перевод времени назад его не продлевает.
//#доступ - спрашивает MultiFlowBridge перед каждым расчётом: страница решить это не может
final class AccessGuard {

    // вкладки, как их называют сервер и страница
    static final String FLOW = "flow";
    static final String PLACEMENT = "placement";
    static final String TRANSPORT = "transport";

    private static final long OFFLINE_LIMIT_MS = TimeUnit.DAYS.toMillis(3);
    private static final long CHECK_EVERY_MS = TimeUnit.SECONDS.toMillis(60);

    private static final String PREFS = "access";

    // ответ для страницы: такой же, какой отдал бы сервер
    static final class Answer {
        final int status;
        final String body;

        private Answer(int status, String body) {
            this.status = status;
            this.body = body;
        }

        String envelope() {
            return "{\"ok\":" + (status == 200) + ",\"status\":" + status + ",\"body\":" + body + "}";
        }
    }

    interface Callback {
        // вызывается в главном потоке
        void onAnswer(Answer answer);
    }

    private final SharedPreferences prefs;
    private final AccessClient client;
    private final Context context;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    // кому сообщать, что доступ изменился (страница)
    private Callback listener;
    private boolean visible;
    private long lastAttemptAt = -CHECK_EVERY_MS;
    private boolean lastAttemptFailed;

    AccessGuard(Context context, String version) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.client = new AccessClient(deviceJson(version));
        AppLog.i("access", "start: server " + (client.configured() ? "configured" : "NOT configured in this build")
                + ", saved access " + (token() != null ? "yes, tabs " + tabs() : "no"));
    }

    // ------------------------------------------------------------------ что разрешено

    // состояние без обращения к сети: 200 - доступ есть, 401 - нужен ключ, 503 - давно нет связи
    synchronized Answer state() {
        if (token() == null) {
            String reason = prefs.getString("lostReason", "none");
            return refusal(401, reason, prefs.getString("lostDetail", "Введите ключ доступа."));
        }
        long offline = countOfflineTime();
        if (offline > OFFLINE_LIMIT_MS) {
            return refusal(503, "offline", "Больше 3 дней нет связи с сервером. Подключитесь к интернету.");
        }
        return allowed();
    }

    // разрешена ли вкладка; null - разрешена, иначе отказ
    synchronized Answer refuse(String tab) {
        Answer state = state();
        if (state.status != 200) {
            AppLog.w("access", "calculation refused: tab " + tab + ", " + state.body);
            // страница ждёт от расчёта 401 с причиной, в том числе «давно нет связи»
            return new Answer(401, state.body);
        }
        if (!tabs().contains(tab)) {
            AppLog.w("access", "calculation refused: the key has no tab " + tab + ", it has " + tabs());
            return refusal(403, "tab", "Ваш ключ не даёт доступа к этой вкладке.");
        }
        return null;
    }

    private Answer allowed() {
        try {
            return new Answer(200, new JSONObject().put("tabs", new JSONArray(tabs())).toString());
        } catch (JSONException impossible) {
            throw new IllegalStateException("cannot build access answer", impossible);
        }
    }

    private static Answer refusal(int status, String reason, String detail) {
        try {
            return new Answer(status, new JSONObject().put("title", "Нет доступа").put("detail", detail)
                    .put("reason", reason).toString());
        } catch (JSONException impossible) {
            throw new IllegalStateException("cannot build access refusal", impossible);
        }
    }

    private String token() {
        return prefs.getString("token", null);
    }

    private List<String> tabs() {
        String saved = prefs.getString("tabs", "");
        return saved.isEmpty() ? new ArrayList<>() : Arrays.asList(saved.split(","));
    }

    // ------------------------------------------------------------------ время без связи

    // прибавляет прошедшее с прошлого раза время к времени без связи и возвращает его.
    // Пока телефон не перезагружали, время идёт по elapsedRealtime - его не перевести;
    // после перезагрузки остаются только обычные часы, и если они ушли назад - срок считается вышедшим
    private long countOfflineTime() {
        long wall = System.currentTimeMillis();
        long elapsed = SystemClock.elapsedRealtime();
        int boot = bootCount();
        long spent = prefs.getLong("offlineSpent", 0);
        long passed;
        if (boot == prefs.getInt("anchorBoot", -1) && elapsed >= prefs.getLong("anchorElapsed", 0)) {
            passed = elapsed - prefs.getLong("anchorElapsed", 0);
        } else {
            passed = wall - prefs.getLong("anchorWall", 0);
            if (passed < 0) {
                AppLog.w("access", "the clock went back by " + (-passed / 1000) + " s after a reboot, "
                        + "offline time is considered over");
                passed = OFFLINE_LIMIT_MS + 1;
            }
        }
        spent += passed;
        prefs.edit().putLong("offlineSpent", spent).putLong("anchorWall", wall)
                .putLong("anchorElapsed", elapsed).putInt("anchorBoot", boot).apply();
        return spent;
    }

    private void confirmOnline(SharedPreferences.Editor editor) {
        editor.putLong("offlineSpent", 0).putLong("anchorWall", System.currentTimeMillis())
                .putLong("anchorElapsed", SystemClock.elapsedRealtime()).putInt("anchorBoot", bootCount());
    }

    private int bootCount() {
        return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 0);
    }

    // ------------------------------------------------------------------ сервер

    // ввод ключа
    void activate(String key, Callback callback) {
        network.execute(() -> {
            Answer answer;
            try {
                AccessClient.Response response = client.activate(key);
                answer = onActivated(response);
            } catch (IOException | JSONException failure) {
                AppLog.w("access", "key was not checked: no connection to the server", failure);
                answer = refusal(503, "network", AccessClient.internetForbidden(failure)
                        ? "Телефон ещё не разрешил приложению интернет. Перезагрузите телефон и введите ключ снова."
                        : "Нет связи с сервером. Для ввода ключа нужен интернет.");
            } catch (RuntimeException unexpected) {
                AppLog.e("access", "key check failed", unexpected);
                answer = refusal(500, "failure", "Сбой при проверке ключа: " + unexpected.getMessage());
            }
            Answer result = answer;
            main.post(() -> callback.onAnswer(result));
        });
    }

    private synchronized Answer onActivated(AccessClient.Response response) throws JSONException {
        if (response.status != 200) {
            AppLog.w("access", "key refused by the server: status " + response.status + ", reason "
                    + response.json.optString("reason", "-"));
            return refusal(response.status, response.json.optString("reason", "failure"),
                    response.json.optString("detail", "Ключ не принят, код " + response.status + "."));
        }
        String tabs = join(response.json.getJSONArray("tabs"));
        SharedPreferences.Editor editor = prefs.edit().putString("token", response.json.getString("token"))
                .putString("tabs", tabs).remove("lostReason").remove("lostDetail");
        confirmOnline(editor);
        editor.apply();
        lastAttemptAt = SystemClock.elapsedRealtime();
        lastAttemptFailed = false;
        AppLog.i("access", "key accepted, tabs " + tabs);
        return allowed();
    }

    // проверка на сервере. Свежий ответ (не старше maxAgeMs) и недавняя неудача повторно не спрашиваются,
    // чтобы расчёт без сети не ждал каждый раз
    void check(String why, long maxAgeMs, Callback callback) {
        network.execute(() -> {
            if (needsRequest(maxAgeMs)) {
                request(why);
            }
            Answer answer = state();
            main.post(() -> callback.onAnswer(answer));
        });
    }

    private synchronized boolean needsRequest(long maxAgeMs) {
        if (token() == null) {
            return false;
        }
        // 0 - спросить обязательно: запуск, возврат в приложение, кнопка «Повторить»
        long age = SystemClock.elapsedRealtime() - lastAttemptAt;
        return maxAgeMs == 0 || age >= (lastAttemptFailed ? Math.max(maxAgeMs, CHECK_EVERY_MS / 2) : maxAgeMs);
    }

    private void request(String why) {
        String token;
        synchronized (this) {
            token = token();
        }
        boolean failed = true;
        try {
            AccessClient.Response response = client.check(token);
            failed = onChecked(response, why);
        } catch (IOException | JSONException failure) {
            AppLog.w("access", "access was not checked (" + why + "): no connection to the server, offline "
                    + offlineMinutes() + " min of " + TimeUnit.MILLISECONDS.toMinutes(OFFLINE_LIMIT_MS) + ": " + failure);
        } catch (RuntimeException unexpected) {
            AppLog.e("access", "access check failed (" + why + ")", unexpected);
        }
        synchronized (this) {
            lastAttemptAt = SystemClock.elapsedRealtime();
            lastAttemptFailed = failed;
        }
    }

    // возвращает true, если ответ ничего не решил
    private synchronized boolean onChecked(AccessClient.Response response, String why) throws JSONException {
        if (response.status == 200) {
            String tabs = join(response.json.getJSONArray("tabs"));
            if (!tabs.equals(prefs.getString("tabs", ""))) {
                AppLog.i("access", "tabs changed by the server: " + prefs.getString("tabs", "") + " -> " + tabs);
            }
            SharedPreferences.Editor editor = prefs.edit().putString("tabs", tabs);
            confirmOnline(editor);
            editor.apply();
            AppLog.d("access", "access confirmed (" + why + "), tabs " + tabs);
            return false;
        }
        String reason = response.json.optString("reason", "");
        if (response.status == 401 && !reason.isEmpty()) {
            prefs.edit().remove("token").remove("tabs").putString("lostReason", reason)
                    .putString("lostDetail", response.json.optString("detail", "Введите ключ доступа.")).apply();
            AppLog.w("access", "access lost (" + why + "): " + reason + ", the key will be asked again");
            return false;
        }
        // сервер перегружен или сломан: это не отказ, время без связи продолжает идти
        AppLog.w("access", "access check is inconclusive (" + why + "): status " + response.status);
        return true;
    }

    private synchronized long offlineMinutes() {
        return TimeUnit.MILLISECONDS.toMinutes(countOfflineTime());
    }

    // ------------------------------------------------------------------ проверка раз в минуту

    private final Runnable timer = new Runnable() {
        @Override
        public void run() {
            if (visible) {
                check("timer", CHECK_EVERY_MS / 2, AccessGuard.this::tell);
                main.postDelayed(this, CHECK_EVERY_MS);
            }
        }
    };

    // приложение на экране: сразу проверка и дальше раз в минуту
    void start(Callback changed) {
        listener = changed;
        visible = true;
        main.removeCallbacks(timer);
        check("app shown", 0, this::tell);
        main.postDelayed(timer, CHECK_EVERY_MS);
    }

    void stop() {
        visible = false;
        listener = null;
        main.removeCallbacks(timer);
    }

    private void tell(Answer answer) {
        if (listener != null) {
            listener.onAnswer(answer);
        }
    }

    // ------------------------------------------------------------------ устройство

    private JSONObject deviceJson(String version) {
        try {
            return new JSONObject()
                    .put("id", deviceId())
                    .put("name", deviceName())
                    .put("os", "Android " + Build.VERSION.RELEASE)
                    .put("appVersion", version);
        } catch (JSONException impossible) {
            throw new IllegalStateException("cannot describe the device", impossible);
        }
    }

    private static String deviceName() {
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        if (model.toLowerCase().startsWith(maker.toLowerCase())) {
            return model;
        }
        return maker.isEmpty() ? model : maker.substring(0, 1).toUpperCase() + maker.substring(1) + " " + model;
    }

    // ANDROID_ID свой у каждой пары «телефон - подпись приложения» и переживает переустановку.
    // На сервер уходит только его хэш
    @SuppressLint("HardwareIds")
    private String deviceId() {
        String id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (id == null || id.isEmpty()) {
            id = prefs.getString("fallbackId", null);
            if (id == null) {
                id = UUID.randomUUID().toString();
                prefs.edit().putString("fallbackId", id).apply();
                AppLog.w("access", "ANDROID_ID is empty, a random id is used: it will change after reinstall");
            }
        }
        return AccessClient.sha256("multiflow:" + id);
    }

    private static String join(JSONArray items) throws JSONException {
        StringBuilder text = new StringBuilder();
        for (int k = 0; k < items.length(); k++) {
            text.append(k == 0 ? "" : ",").append(items.getString(k));
        }
        return text.toString();
    }
}
