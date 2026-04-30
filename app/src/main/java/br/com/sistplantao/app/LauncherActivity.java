package br.com.sistplantao.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class LauncherActivity extends Activity {
    private static final String APP_URL = "https://sistplantao.vercel.app/";
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestNotificationPermission();
        NotificationHelper.ensureChannel(this);
        setupWebView();
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private void setupWebView() {
        webView = new WebView(this);
        setContentView(webView);

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setDatabaseEnabled(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setUseWideViewPort(true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new ReminderBridge(), "AndroidReminder");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectReminderSync();
            }
        });
        webView.loadUrl(APP_URL);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 42);
        }
    }

    private void injectReminderSync() {
        String script = "(function(){"
                + "if(window.__androidReminderSyncInstalled)return;"
                + "window.__androidReminderSyncInstalled=true;"
                + "var timer=null;"
                + "function sync(){"
                + "try{"
                + "var shifts=localStorage.getItem('plantao_pro_v36')||'[]';"
                + "var lead=localStorage.getItem('notifyLeadMinutes')||'60';"
                + "if(window.AndroidReminder&&AndroidReminder.sync){AndroidReminder.sync(shifts,lead);}"
                + "}catch(e){}"
                + "}"
                + "function queue(){clearTimeout(timer);timer=setTimeout(sync,700);}"
                + "var oldSet=localStorage.setItem;"
                + "localStorage.setItem=function(k,v){oldSet.apply(this,arguments);if(k==='plantao_pro_v36'||k==='notifyLeadMinutes')queue();};"
                + "document.addEventListener('click',queue,true);"
                + "document.addEventListener('change',queue,true);"
                + "window.addEventListener('focus',queue);"
                + "sync();"
                + "})();";
        webView.evaluateJavascript(script, null);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    private class ReminderBridge {
        @JavascriptInterface
        public void sync(String shiftsJson, String leadMinutes) {
            int lead = 60;
            try {
                lead = Integer.parseInt(leadMinutes);
            } catch (NumberFormatException ignored) {
            }
            AlarmScheduler.scheduleFromJson(LauncherActivity.this, shiftsJson, lead);
        }

        @JavascriptInterface
        public void test() {
            NotificationHelper.show(
                    LauncherActivity.this,
                    "Teste de lembrete",
                    "Som e vibracao das notificacoes do SistPlantao"
            );
        }
    }
}
