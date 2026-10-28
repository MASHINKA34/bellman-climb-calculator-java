package ru.mashinka.multiflow.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.ConsoleMessage;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

// единственный экран: WebView со страницей из папки ui
// вместо запросов к серверу страница зовёт MultiFlowBridge, поэтому работает без интернета
public class MainActivity extends Activity {

    private static final String PAGE = "file:///android_asset/index.html";

    private FrameLayout root;
    private WebView webView;
    private long createdAt;

    // последние применённые отступы под системные панели: лишний setPadding не нужен
    private int[] appliedInsets = new int[]{-1, -1, -1, -1};

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createdAt = SystemClock.elapsedRealtime();
        AppLog.i("screen", "create" + (savedInstanceState != null ? " (recreated by the system)" : ""));

        PackageInfo webViewPackage = WebView.getCurrentWebViewPackage();
        AppLog.i("screen", "WebView " + (webViewPackage != null
                ? webViewPackage.packageName + " " + webViewPackage.versionName
                : "package unknown"));

        // в отладочной сборке страницу можно открыть в chrome://inspect на компьютере
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        webView = new WebView(this);

        // Собственные отступы WebView не соблюдает, поэтому под системные панели
        // отодвигается контейнер, а не он сам.
        root = new FrameLayout(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        watchSystemBarInsets();

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);   // без него не работают ни ввод, ни рисунки
        settings.setDomStorageEnabled(true);   // localStorage: матрица переживает перезапуск
        settings.setAllowFileAccess(false);    // страница лежит в assets, файлы телефона ей не нужны
        settings.setAllowContentAccess(false);
        settings.setSupportZoom(false);
        settings.setTextZoom(100);             // таблицы не должны разъезжаться от крупного шрифта

        // чтобы страница следовала тёмной теме телефона
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            settings.setAlgorithmicDarkeningAllowed(true);
        }

        // смена темы пересоздаёт экран (uiMode нет в configChanges), поэтому тема читается здесь
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        AppLog.i("screen", "system theme " + (night ? "dark" : "light"));

        webView.setWebChromeClient(new ConsoleToLogcat());
        webView.setWebViewClient(new PageClient());
        webView.addJavascriptInterface(new MultiFlowBridge(night), MultiFlowBridge.NAME);

        AppLog.i("screen", "loading " + PAGE);
        webView.loadUrl(PAGE);
    }

    // ------------------------------------------------------------------ системные панели

    // отступы под строку состояния, панель навигации и клавиатуру. На Android 15+
    // окно рисуется под панелями, и без этого страница уезжала бы под часы;
    // на старых версиях система сама отодвигает окно, и сюда приходят нули.
    // Устаревшие getSystemWindowInset* нужны только Android 10: новых методов там ещё нет
    @SuppressWarnings("deprecation")
    private void watchSystemBarInsets() {
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout()
                        | WindowInsets.Type.ime());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }

            if (appliedInsets[0] != left || appliedInsets[1] != top
                    || appliedInsets[2] != right || appliedInsets[3] != bottom) {
                appliedInsets = new int[]{left, top, right, bottom};
                view.setPadding(left, top, right, bottom);
                AppLog.d("screen", "insets applied: left=" + left + " top=" + top
                        + " right=" + right + " bottom=" + bottom);
            }
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? WindowInsets.CONSUMED
                    : insets.consumeSystemWindowInsets();
        });
        root.requestApplyInsets();
    }

    // ------------------------------------------------------------------ страница

    // консоль страницы (console.log/warn/error) - в logcat с тем же тегом
    private static final class ConsoleToLogcat extends WebChromeClient {

        @Override
        public boolean onConsoleMessage(ConsoleMessage message) {
            String text = message.message() + " (" + message.sourceId() + ":" + message.lineNumber() + ")";
            switch (message.messageLevel()) {
                case ERROR:
                    AppLog.e("page", text, null);
                    break;
                case WARNING:
                    AppLog.w("page", text);
                    break;
                case DEBUG:
                case TIP:
                    AppLog.d("page", text);
                    break;
                default:
                    AppLog.i("page", text);
            }
            return true;
        }
    }

    private final class PageClient extends WebViewClient {

        @Override
        public void onPageFinished(WebView view, String url) {
            AppLog.i("screen", "page loaded " + url + " in " + (SystemClock.elapsedRealtime() - createdAt)
                    + " ms since screen start");
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            AppLog.e("screen", "failed to load " + request.getUrl() + " (main frame: " + request.isForMainFrame()
                    + "): code " + error.getErrorCode() + ", " + error.getDescription(), null);
        }

        // страница никуда не ведёт: переходы за пределы assets блокируются
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            boolean local = "file".equals(url.getScheme()) && url.getPath() != null
                    && url.getPath().startsWith("/android_asset/");
            if (!local) {
                AppLog.w("screen", "blocked navigation away from the app page: " + url);
            }
            return !local;
        }

        // процесс отрисовки WebView упал или был убит системой: этот WebView больше
        // не работает, поэтому экран пересоздаётся, а ввод вернётся из localStorage
        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            AppLog.e("screen", "WebView renderer is gone (crashed: " + detail.didCrash()
                    + "), recreating the screen", null);
            destroyWebView();
            recreate();
            return true;
        }
    }

    // ------------------------------------------------------------------ завершение

    @Override
    protected void onDestroy() {
        AppLog.i("screen", "destroy: finishing=" + isFinishing()
                + ", configuration change=" + isChangingConfigurations());
        destroyWebView();
        root = null;
        super.onDestroy();
    }

    private void destroyWebView() {
        if (webView == null) {
            return;
        }
        if (root != null) {
            root.removeView(webView);
        }
        webView.destroy();
        webView = null;
    }
}
