package ru.mashinka.multiflow.android;

import android.util.Log;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import ru.mashinka.multiflow.core.log.CoreLog;

// логи приложения: всё уходит в logcat под одним тегом MultiFlow
// - строки самого приложения,
// - строки ядра (java.util.logging), их перенаправляет LogcatHandler,
// - консоль страницы (console.*), её пересылает MainActivity.
// Подробный уровень включается без пересборки:
//   adb shell setprop log.tag.MultiFlow DEBUG   и перезапуск приложения
// Смотреть: adb logcat -s MultiFlow
final class AppLog {

    static final String TAG = "MultiFlow";

    private AppLog() {
    }

    static boolean debugEnabled() {
        return Log.isLoggable(TAG, Log.DEBUG);
    }

    // вызывается первым делом при старте приложения
    static void install() {
        Logger root = CoreLog.root();
        // без этого Android продублировал бы строки ядра под своим тегом
        root.setUseParentHandlers(false);
        for (Handler handler : root.getHandlers()) {
            root.removeHandler(handler);
        }
        root.addHandler(new LogcatHandler());
        CoreLog.setLevel(debugEnabled() ? Level.FINE : Level.INFO);
    }

    static void d(String component, String message) {
        if (debugEnabled()) {
            Log.d(TAG, "[" + component + "] " + message);
        }
    }

    static void i(String component, String message) {
        Log.i(TAG, "[" + component + "] " + message);
    }

    static void w(String component, String message) {
        Log.w(TAG, "[" + component + "] " + message);
    }

    static void w(String component, String message, Throwable error) {
        Log.w(TAG, "[" + component + "] " + message, error);
    }

    // стек ошибки logcat печатает целиком, с причинами (cause)
    static void e(String component, String message, Throwable error) {
        Log.e(TAG, "[" + component + "] " + message, error);
    }

    private static final class LogcatHandler extends Handler {

        @Override
        public void publish(LogRecord record) {
            if (!isLoggable(record)) {
                return;
            }
            String name = record.getLoggerName() == null ? "core" : record.getLoggerName();
            String message = "[core:" + name.substring(name.lastIndexOf('.') + 1) + "] " + record.getMessage();
            Throwable error = record.getThrown();
            int level = record.getLevel().intValue();
            if (level >= Level.SEVERE.intValue()) {
                Log.e(TAG, message, error);
            } else if (level >= Level.WARNING.intValue()) {
                Log.w(TAG, message, error);
            } else if (level >= Level.INFO.intValue()) {
                Log.i(TAG, message, error);
            } else {
                Log.d(TAG, message, error);
            }
        }

        @Override
        public void flush() {
            // logcat пишет сразу, буфера нет
        }

        @Override
        public void close() {
            // закрывать нечего
        }
    }
}
