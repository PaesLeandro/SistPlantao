package br.com.sistplantao.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class LauncherActivity extends Activity {
    private static final String APP_URL = "https://sistplantao.vercel.app/?apk=18";
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
        webView.clearCache(true);
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
                + "function shiftsPayload(){"
                + "try{if(window.App&&Array.isArray(window.App.shifts)&&window.App.shifts.length){return JSON.stringify(window.App.shifts);}}catch(e){}"
                + "try{return localStorage.getItem('plantao_pro_v36')||'[]';}catch(e){return '[]';}"
                + "}"
                + "function sync(){"
                + "try{"
                + "var shifts=shiftsPayload();"
                + "var lead=localStorage.getItem('notifyLeadMinutes')||'60';"
                + "if(window.AndroidReminder&&AndroidReminder.sync){AndroidReminder.sync(shifts,lead);}"
                + "}catch(e){}"
                + "}"
                + "function queue(){clearTimeout(timer);timer=setTimeout(sync,700);}"
                + "var oldSet=localStorage.setItem;"
                + "localStorage.setItem=function(k,v){oldSet.apply(this,arguments);if(k==='plantao_pro_v36'||k==='notifyLeadMinutes')queue();};"
                + "function hookApp(){"
                + "try{"
                + "if(!window.App||window.App.__androidSaveHooked)return false;"
                + "var oldSave=window.App.saveShifts;"
                + "window.App.saveShifts=function(){var r=oldSave.apply(this,arguments);setTimeout(sync,50);setTimeout(sync,500);return r;};"
                + "window.App.__androidSaveHooked=true;"
                + "sync();"
                + "return true;"
                + "}catch(e){return false;}"
                + "}"
                + "hookApp();"
                + "var hookTimer=setInterval(function(){if(hookApp())clearInterval(hookTimer);},500);"
                + "window.__androidReminderSyncNow=function(){sync();return true;};"
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
        public String sync(String shiftsJson, String leadMinutes) {
            int lead = 60;
            try {
                lead = Integer.parseInt(leadMinutes);
            } catch (NumberFormatException ignored) {
            }
            String normalized = shiftsJson == null ? "" : shiftsJson.trim();
            if (normalized.isEmpty() || "[]".equals(normalized)) {
                syncFromPageState(lead);
                return "Buscando compromissos salvos na tela...";
            }
            return AlarmScheduler.scheduleFromJson(LauncherActivity.this, shiftsJson, lead);
        }

        @JavascriptInterface
        public String syncSimple(String simpleShiftsJson, String leadMinutes) {
            int lead = 60;
            try {
                lead = Integer.parseInt(leadMinutes);
            } catch (NumberFormatException ignored) {
            }
            String normalized = simpleShiftsJson == null ? "" : simpleShiftsJson.trim();
            if (normalized.isEmpty() || "[]".equals(normalized)) {
                syncFromPageState(lead);
                return "Buscando compromissos salvos na tela...";
            }
            return AlarmScheduler.scheduleFromJson(LauncherActivity.this, simpleShiftsJson, lead);
        }

        @JavascriptInterface
        public void test() {
            NotificationHelper.show(
                    LauncherActivity.this,
                    "Teste de lembrete",
                    "Som e vibracao das notificacoes do SistPlantao"
            );
        }

        @JavascriptInterface
        public String status() {
            return AlarmScheduler.lastSummary(LauncherActivity.this);
        }

        @JavascriptInterface
        public void openSettings() {
            Intent intent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                        .putExtra(Settings.EXTRA_CHANNEL_ID, NotificationHelper.CHANNEL_ID);
            } else {
                intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:" + getPackageName()));
            }
            startActivity(intent);
        }
    }

    private void syncFromPageState(int fallbackLead) {
        if (webView == null) return;
        webView.post(() -> webView.evaluateJavascript(
                "(function(){"
                        + "var shifts='[]';"
                        + "try{if(window.App&&Array.isArray(window.App.shifts)&&window.App.shifts.length){shifts=JSON.stringify(window.App.shifts);}else{shifts=localStorage.getItem('plantao_pro_v36')||'[]';}}catch(e){}"
                        + "var lead='" + fallbackLead + "';"
                        + "try{lead=localStorage.getItem('notifyLeadMinutes')||lead;}catch(e){}"
                        + "return JSON.stringify({shifts:shifts,lead:lead});"
                        + "})()",
                value -> {
                    try {
                        String json = value;
                        if (json == null || "null".equals(json)) return;
                        if (json.startsWith("\"") && json.endsWith("\"")) {
                            json = json.substring(1, json.length() - 1)
                                    .replace("\\\"", "\"")
                                    .replace("\\\\", "\\");
                        }
                        org.json.JSONObject payload = new org.json.JSONObject(json);
                        String shifts = payload.optString("shifts", "[]");
                        int lead = payload.optInt("lead", fallbackLead);
                        AlarmScheduler.scheduleFromJson(LauncherActivity.this, shifts, lead);
                    } catch (Exception ignored) {
                    }
                }
        ));
    }
}
