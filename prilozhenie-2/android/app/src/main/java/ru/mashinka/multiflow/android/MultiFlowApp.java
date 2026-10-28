package ru.mashinka.multiflow.android;

import android.app.Application;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;

// приложение целиком: до первого экрана настраивает логи и ловушку для падений
public class MultiFlowApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        AppLog.install();
        installCrashLogger();

        AppLog.i("app", "start: version " + versionText()
                + ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                + ", device " + Build.MANUFACTURER + " " + Build.MODEL
                + ", log level " + (AppLog.debugEnabled() ? "debug" : "info"));
    }

    private String versionText() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName + " (" + info.getLongVersionCode() + ")";
        } catch (PackageManager.NameNotFoundException missing) {
            AppLog.w("app", "own package info is unavailable, version unknown", missing);
            return "unknown";
        }
    }

    // любое неперехваченное исключение сначала попадает в logcat со стеком,
    // потом уходит системе: та закрывает приложение как обычно
    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            AppLog.e("crash", "uncaught exception in thread '" + thread.getName() + "', the app will close", error);
            if (previous != null) {
                previous.uncaughtException(thread, error);
            } else {
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        });
    }
}
