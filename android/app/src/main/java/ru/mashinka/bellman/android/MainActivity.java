package ru.mashinka.bellman.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;

// единственный экран: WebView с той же страницей, что и на сайте
// вместо запросов к серверу страница зовёт ClimbBridge, поэтому работает без интернета
public class MainActivity extends Activity {

    private FrameLayout root;
    private WebView webView;

    // последние применённые отступы под системные панели, чтобы не дёргать разметку зря
    private int[] appliedInsets = new int[]{-1, -1, -1, -1};

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);

        // Собственные отступы WebView не соблюдает, поэтому под системные панели
        // отодвигается контейнер, а не он сам.
        root = new FrameLayout(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);   // без него не работают ни форма, ни графики
        settings.setDomStorageEnabled(true);   // localStorage: форма помнит введённые данные
        settings.setAllowFileAccess(true);
        settings.setSupportZoom(false);
        settings.setTextZoom(100);             // расчётные таблицы не должны разъезжаться

        // WebView по умолчанию всегда сообщает светлую тему. Разрешаем следовать
        // системной: стили страницы умеют тёмную тему сами, и WebView отдаст
        // им предпочтение вместо принудительного затемнения.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            settings.setAlgorithmicDarkeningAllowed(true);
        }

        watchSystemBarInsets();

        webView.addJavascriptInterface(new ClimbBridge(), ClimbBridge.NAME);
        webView.loadUrl("file:///android_asset/index.html");
    }

    // android 15 кладёт приложение под системные панели, без отступов заголовок
    // уезжает под часы. берём getRootWindowInsets на каждой перекомпоновке:
    // до слушателя инсеты могут не дойти, а корневые доступны всегда
    private void watchSystemBarInsets() {
        root.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        applySystemBarInsets();
                    }
                });
    }

    private void applySystemBarInsets() {
        if (root == null) {
            return;
        }
        WindowInsets insets = root.getRootWindowInsets();
        if (insets == null) {
            return;
        }

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

        // Без этой проверки setPadding вызвал бы новую перекомпоновку, а та — снова
        // этот же метод, и разметка зациклилась бы.
        if (appliedInsets[0] == left && appliedInsets[1] == top
                && appliedInsets[2] == right && appliedInsets[3] == bottom) {
            return;
        }
        appliedInsets = new int[]{left, top, right, bottom};
        root.setPadding(left, top, right, bottom);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        root = null;
        super.onDestroy();
    }
}
