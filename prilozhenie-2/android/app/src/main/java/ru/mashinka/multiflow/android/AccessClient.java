package ru.mashinka.multiflow.android;

import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.OsConstants;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

// запросы к серверу ключей. Соединение - только https и только с сервером, чей сертификат зашит
// в приложение (network_security_config): подменить сервер или подсмотреть ключ в чужом Wi-Fi нельзя.
// Адрес и сертификат берутся при сборке из android/server.properties, в репозитории их нет
final class AccessClient {

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 8000;
    // ответ сервера - сотни байт; больше читать незачем
    private static final int MAX_ANSWER = 64 * 1024;

    static final class Response {
        final int status;
        final JSONObject json;

        private Response(int status, JSONObject json) {
            this.status = status;
            this.json = json;
        }
    }

    private final JSONObject device;

    AccessClient(JSONObject device) {
        this.device = device;
    }

    boolean configured() {
        return !BuildConfig.SERVER_URL.isEmpty();
    }

    Response activate(String key) throws IOException, JSONException {
        return post("api/access/activate", new JSONObject().put("key", key).put("device", device));
    }

    Response check(String token) throws IOException, JSONException {
        return post("api/access/check", new JSONObject().put("token", token).put("device", device));
    }

    // тело запроса в лог не пишется: в нём ключ или токен
    private Response post(String path, JSONObject body) throws IOException, JSONException {
        if (!configured()) {
            throw new IOException("server address is not set in this build (android/server.properties)");
        }
        long startedAt = SystemClock.elapsedRealtime();
        HttpURLConnection connection = (HttpURLConnection) new URL(BuildConfig.SERVER_URL + "/" + path).openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }
            int status = connection.getResponseCode();
            String text = read(status < 400 ? connection.getInputStream() : connection.getErrorStream());
            AppLog.d("access", "POST " + path + " -> " + status + ", " + text.length() + " chars in "
                    + (SystemClock.elapsedRealtime() - startedAt) + " ms");
            // сервер за nginx: на сбое может прийти не JSON, тогда важен только код
            return new Response(status, text.startsWith("{") ? new JSONObject(text) : new JSONObject());
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) > 0) {
                out.write(buffer, 0, count);
                if (out.size() > MAX_ANSWER) {
                    throw new IOException("answer is larger than " + MAX_ANSWER + " bytes");
                }
            }
            return out.toString("UTF-8");
        }
    }

    // Android 10 после обновления с версии без доступа в интернет (1.4.2 и раньше) продолжает его
    // запрещать до перезагрузки телефона: сокет не открывается с EPERM (проверено на эмуляторе API 29)
    static boolean internetForbidden(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ErrnoException && ((ErrnoException) cause).errno == OsConstants.EPERM) {
                return true;
            }
        }
        return false;
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >> 4) & 0xf, 16)).append(Character.forDigit(value & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 обязан быть в любом Android
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }
}
