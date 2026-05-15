package br.com.sistplantao.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlarmManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.provider.MediaStore;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Base64;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONObject;

public class LauncherActivity extends Activity {
    private static final String APP_URL = "file:///android_asset/index.html";
    private static final String UI_PREFS = "ui_preferences";
    private static final String KEY_THEME = "theme";
    private static final String DATA_PREFS = "web_data_backup";
    private static final String KEY_SHIFTS_JSON = "shifts_json";
    private static final int DARK_BG = Color.rgb(3, 7, 13);
    private static final int LIGHT_BG = Color.rgb(243, 244, 246);
    private WebView webView;
    private final JSONArray pendingNativeShifts = new JSONArray();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyStoredWindowTheme();
        requestNotificationPermission();
        NotificationHelper.ensureChannel(this);
        setupWebView();
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private void setupWebView() {
        webView = new WebView(this);
        webView.setBackgroundColor(windowBackgroundForStoredTheme());
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

    private void applyStoredWindowTheme() {
        boolean light = isLightThemeStored();
        int background = light ? LIGHT_BG : DARK_BG;
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        getWindow().getDecorView().setBackgroundColor(background);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int flags = getWindow().getDecorView().getSystemUiVisibility();
            if (light) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    private boolean isLightThemeStored() {
        return "light".equals(getSharedPreferences(UI_PREFS, MODE_PRIVATE).getString(KEY_THEME, "dark"));
    }

    private int windowBackgroundForStoredTheme() {
        return isLightThemeStored() ? LIGHT_BG : DARK_BG;
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
                + "function calendarPayload(){"
                + "try{"
                + "var view=(window.Calendar&&Calendar.view)?new Date(Calendar.view):new Date();"
                + "var y=view.getFullYear();"
                + "var m=String(view.getMonth()+1).padStart(2,'0');"
                + "var out=[];"
                + "document.querySelectorAll('#cal-grid .cal-cell').forEach(function(cell){"
                + "var dayEl=cell.querySelector('.day-num');"
                + "if(!dayEl)return;"
                + "var d=parseInt((dayEl.textContent||'').trim(),10);"
                + "if(!d)return;"
                + "var date=y+'-'+m+'-'+String(d).padStart(2,'0');"
                + "cell.querySelectorAll('.shift-chip').forEach(function(chip){"
                + "var info=chip.querySelector('.shift-info');"
                + "var infoText=info?(info.textContent||'').trim():'';"
                + "var timeMatch=infoText.match(/\\b\\d{1,2}:\\d{2}\\b/);"
                + "var aria=chip.getAttribute('aria-label')||'';"
                + "var local=aria.replace(/^(Excluir compromisso|Editar ou remover compromisso):\\s*/i,'').trim()||chip.dataset.local||'Compromisso';"
                + "out.push({date:date,time:timeMatch?timeMatch[0]:'00:00',local:local,type:'avulso'});"
                + "});"
                + "});"
                + "return out.length?JSON.stringify(out):'[]';"
                + "}catch(e){return '[]';}"
                + "}"
                + "function shiftsPayload(){"
                + "try{if(window.App&&Array.isArray(window.App.shifts)&&window.App.shifts.length){return JSON.stringify(window.App.shifts);}}catch(e){}"
                + "try{var stored=localStorage.getItem('plantao_pro_v36')||'[]';if(stored&&stored!=='[]')return stored;}catch(e){}"
                + "return calendarPayload();"
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
                + "window.App.saveShifts=function(){var r=oldSave.apply(this,arguments);queue();return r;};"
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
            try {
                int lead = parseLead(leadMinutes);
                String normalized = shiftsJson == null ? "" : shiftsJson.trim();
                if (normalized.isEmpty() || "[]".equals(normalized)) {
                    syncFromPageState(lead);
                    return "Buscando compromissos salvos na tela...";
                }
                return AlarmScheduler.scheduleFromJson(LauncherActivity.this, shiftsJson, lead);
            } catch (Exception error) {
                return "Falha nativa ao sincronizar: " + safeMessage(error);
            }
        }

        @JavascriptInterface
        public String syncSimple(String simpleShiftsJson, String leadMinutes) {
            try {
                int lead = parseLead(leadMinutes);
                String normalized = simpleShiftsJson == null ? "" : simpleShiftsJson.trim();
                if (normalized.isEmpty() || "[]".equals(normalized)) {
                    syncFromPageState(lead);
                    return "Buscando compromissos salvos na tela...";
                }
                return AlarmScheduler.scheduleFromJson(LauncherActivity.this, simpleShiftsJson, lead);
            } catch (Exception error) {
                return "Falha nativa ao sincronizar: " + safeMessage(error);
            }
        }

        @JavascriptInterface
        public String beginSync() {
            synchronized (pendingNativeShifts) {
                while (pendingNativeShifts.length() > 0) {
                    pendingNativeShifts.remove(0);
                }
            }
            return "Sincronizacao nativa iniciada";
        }

        @JavascriptInterface
        public String addShift(String date, String time, String local, String type) {
            try {
                JSONObject shift = new JSONObject();
                shift.put("date", date == null ? "" : date);
                shift.put("time", time == null || time.trim().isEmpty() ? "00:00" : time);
                shift.put("local", local == null || local.trim().isEmpty() ? "Compromisso" : local);
                shift.put("type", type == null || type.trim().isEmpty() ? "avulso" : type);
                synchronized (pendingNativeShifts) {
                    pendingNativeShifts.put(shift);
                    return "Recebido " + pendingNativeShifts.length();
                }
            } catch (Exception error) {
                return "Falha ao receber plantao: " + safeMessage(error);
            }
        }

        @JavascriptInterface
        public String finishSync(String leadMinutes) {
            try {
                int lead = parseLead(leadMinutes);
                String payload;
                synchronized (pendingNativeShifts) {
                    payload = pendingNativeShifts.toString();
                }
                if ("[]".equals(payload)) {
                    syncFromPageState(lead);
                    return "Nenhum plantao enviado ao Android. Tentando ler a tela...";
                }
                return AlarmScheduler.scheduleFromJson(LauncherActivity.this, payload, lead);
            } catch (Exception error) {
                return "Falha nativa ao finalizar: " + safeMessage(error);
            }
        }

        @JavascriptInterface
        public void test() {
            AlarmSoundService.start(
                    LauncherActivity.this,
                    "Teste de lembrete",
                    "Som e vibracao das notificacoes do SistPlantao"
            );
        }

        @JavascriptInterface
        public String testAlarm() {
            return AlarmScheduler.scheduleTestAlarm(LauncherActivity.this);
        }

        @JavascriptInterface
        public String status() {
            return AlarmScheduler.lastSummary(LauncherActivity.this);
        }

        @JavascriptInterface
        public String savedShifts() {
            return getSharedPreferences(DATA_PREFS, MODE_PRIVATE)
                    .getString(KEY_SHIFTS_JSON, "[]");
        }

        @JavascriptInterface
        public String backupShifts(String shiftsJson) {
            try {
                String normalized = shiftsJson == null || shiftsJson.trim().isEmpty()
                        ? "[]"
                        : shiftsJson.trim();
                new JSONArray(normalized);
                getSharedPreferences(DATA_PREFS, MODE_PRIVATE)
                        .edit()
                        .putString(KEY_SHIFTS_JSON, normalized)
                        .apply();
                return "Backup nativo atualizado";
            } catch (Exception error) {
                return "Backup nativo ignorado: " + safeMessage(error);
            }
        }

        @JavascriptInterface
        public void setTheme(String theme) {
            String normalized = "light".equals(theme) ? "light" : "dark";
            getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_THEME, normalized)
                    .apply();
            runOnUiThread(() -> {
                applyStoredWindowTheme();
                if (webView != null) webView.setBackgroundColor(windowBackgroundForStoredTheme());
            });
        }

        @JavascriptInterface
        public boolean canScheduleExactAlarms() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
            AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
            return alarmManager != null && alarmManager.canScheduleExactAlarms();
        }

        @JavascriptInterface
        public void openExactAlarmSettings() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
            Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
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

        @JavascriptInterface
        public String savePdf(String fileName, String base64Pdf) {
            try {
                if (base64Pdf == null || base64Pdf.trim().isEmpty()) {
                    return "Arquivo PDF vazio";
                }
                String safeName = sanitizeFileName(fileName == null || fileName.trim().isEmpty()
                        ? "plantao.pdf"
                        : fileName.trim());
                if (!safeName.toLowerCase(Locale.ROOT).endsWith(".pdf")) safeName += ".pdf";
                byte[] bytes;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    bytes = Base64.getDecoder().decode(base64Pdf);
                } else {
                    bytes = android.util.Base64.decode(base64Pdf, android.util.Base64.DEFAULT);
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentResolver resolver = getContentResolver();
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, safeName);
                    values.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
                    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SistPlantao");
                    values.put(MediaStore.Downloads.IS_PENDING, 1);
                    Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) return "Não foi possível criar o arquivo em Downloads";
                    try (OutputStream out = resolver.openOutputStream(uri)) {
                        if (out == null) return "Não foi possível abrir o arquivo para escrita";
                        out.write(bytes);
                        out.flush();
                    }
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Downloads.IS_PENDING, 0);
                    resolver.update(uri, done, null, null);
                    return "PDF salvo em Downloads/SistPlantao/" + safeName;
                }

                File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "SistPlantao");
                if (!dir.exists() && !dir.mkdirs()) return "Não foi possível criar a pasta de PDFs";
                File file = new File(dir, safeName);
                try (OutputStream out = new FileOutputStream(file)) {
                    out.write(bytes);
                    out.flush();
                }
                return "PDF salvo em " + file.getAbsolutePath();
            } catch (Exception error) {
                return "Falha ao salvar PDF: " + safeMessage(error);
            }
        }

        private int parseLead(String leadMinutes) {
            try {
                return Integer.parseInt(leadMinutes);
            } catch (NumberFormatException ignored) {
                return 60;
            }
        }

        private String safeMessage(Exception error) {
            String message = error.getMessage();
            if (message == null || message.trim().isEmpty()) return error.getClass().getSimpleName();
            return message.length() > 120 ? message.substring(0, 120) : message;
        }

        private String sanitizeFileName(String name) {
            return name.replaceAll("[\\\\/:*?\"<>|]", "-");
        }
    }

    private void syncFromPageState(int fallbackLead) {
        if (webView == null) return;
        webView.post(() -> webView.evaluateJavascript(
                "(function(){"
                        + "function calendarPayload(){"
                        + "try{"
                        + "var view=(window.Calendar&&Calendar.view)?new Date(Calendar.view):new Date();"
                        + "var y=view.getFullYear();"
                        + "var m=String(view.getMonth()+1).padStart(2,'0');"
                        + "var out=[];"
                        + "document.querySelectorAll('#cal-grid .cal-cell').forEach(function(cell){"
                        + "var dayEl=cell.querySelector('.day-num');"
                        + "if(!dayEl)return;"
                        + "var d=parseInt((dayEl.textContent||'').trim(),10);"
                        + "if(!d)return;"
                        + "var date=y+'-'+m+'-'+String(d).padStart(2,'0');"
                        + "cell.querySelectorAll('.shift-chip').forEach(function(chip){"
                        + "var info=chip.querySelector('.shift-info');"
                        + "var infoText=info?(info.textContent||'').trim():'';"
                        + "var timeMatch=infoText.match(/\\b\\d{1,2}:\\d{2}\\b/);"
                        + "var aria=chip.getAttribute('aria-label')||'';"
                        + "var local=aria.replace(/^(Excluir compromisso|Editar ou remover compromisso):\\s*/i,'').trim()||chip.dataset.local||'Compromisso';"
                        + "out.push({date:date,time:timeMatch?timeMatch[0]:'00:00',local:local,type:'avulso'});"
                        + "});"
                        + "});"
                        + "return out.length?JSON.stringify(out):'[]';"
                        + "}catch(e){return '[]';}"
                        + "}"
                        + "var shifts='[]';"
                        + "try{if(window.App&&Array.isArray(window.App.shifts)&&window.App.shifts.length){shifts=JSON.stringify(window.App.shifts);}else{var stored=localStorage.getItem('plantao_pro_v36')||'[]';shifts=(stored&&stored!=='[]')?stored:calendarPayload();}}catch(e){shifts=calendarPayload();}"
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
