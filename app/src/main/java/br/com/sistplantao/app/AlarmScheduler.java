package br.com.sistplantao.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class AlarmScheduler {
    private static final String PREFS = "native_reminders";
    private static final String KEY_SHIFTS = "shifts_json";
    private static final String KEY_LEAD = "lead_minutes";
    private static final String KEY_LAST_SUMMARY = "last_summary";
    private static final int BASE_REQUEST_CODE = 31000;
    private static final int MAX_ALARMS = 64;
    private static final int TEST_ALARM_INDEX = 900;

    private static int lastParsedCount = 0;
    private static int lastFutureShiftCount = 0;
    private static String lastFirstShiftText = "";
    private static String lastRawPreview = "";
    private static String lastScheduleError = "";

    private AlarmScheduler() {
    }

    public static String scheduleFromJson(Context context, String shiftsJson, int leadMinutes) {
        Context appContext = context.getApplicationContext();
        int lead = Math.max(1, Math.min(10080, leadMinutes));
        savePayload(appContext, shiftsJson, lead);
        cancelAll(appContext);

        List<Reminder> reminders = parseReminders(shiftsJson, lead);
        long now = System.currentTimeMillis();
        int index = 0;
        Reminder nextScheduled = null;
        lastScheduleError = "";
        for (Reminder reminder : reminders) {
            if (reminder.triggerAt <= now) continue;
            if (nextScheduled == null) nextScheduled = reminder;
            if (schedule(appContext, reminder, index)) {
                index++;
            }
            if (index >= MAX_ALARMS) break;
        }
        String summary = buildSummary(index, nextScheduled, now);
        saveSummary(appContext, summary);
        return summary;
    }

    public static void rescheduleSaved(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        scheduleFromJson(context, prefs.getString(KEY_SHIFTS, "[]"), prefs.getInt(KEY_LEAD, 60));
    }

    public static String lastSummary(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_SUMMARY, "Nenhum alarme sincronizado ainda");
    }

    public static String scheduleTestAlarm(Context context) {
        Context appContext = context.getApplicationContext();
        cancelTestAlarm(appContext);
        long triggerAt = System.currentTimeMillis() + 15000L;
        Reminder reminder = new Reminder(
                "Teste de lembrete",
                "Alerta real agendado pelo Android para validar som e vibracao",
                "test",
                triggerAt,
                triggerAt
        );
        boolean ok = schedule(appContext, reminder, TEST_ALARM_INDEX);
        String summary = ok
                ? "Alerta real de teste agendado para 15 segundos"
                : "Falha ao agendar alerta de teste" + (lastScheduleError.isEmpty() ? "" : ": " + lastScheduleError);
        saveSummary(appContext, summary);
        return summary;
    }

    private static void cancelTestAlarm(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;
        alarmManager.cancel(pendingIntent(context, TEST_ALARM_INDEX, null));
    }

    private static void savePayload(Context context, String shiftsJson, int lead) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SHIFTS, shiftsJson == null ? "[]" : shiftsJson)
                .putInt(KEY_LEAD, lead)
                .apply();
    }

    private static void saveSummary(Context context, String summary) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_SUMMARY, summary)
                .apply();
    }

    private static List<Reminder> parseReminders(String shiftsJson, int leadMinutes) {
        List<Reminder> reminders = new ArrayList<>();
        lastParsedCount = 0;
        lastFutureShiftCount = 0;
        lastFirstShiftText = "";
        lastRawPreview = preview(shiftsJson);
        try {
            JSONArray shifts = new JSONArray(shiftsJson == null ? "[]" : shiftsJson);
            lastParsedCount = shifts.length();
            for (int i = 0; i < shifts.length(); i++) {
                JSONObject shift = shifts.optJSONObject(i);
                if (shift == null) continue;
                String date = shift.optString("date");
                String time = shift.optString("time", "00:00");
                long shiftAt = parseShiftTime(date, time);
                if (lastFirstShiftText.isEmpty()) lastFirstShiftText = date + " " + time;
                if (shiftAt <= 0) continue;
                if (shiftAt > System.currentTimeMillis()) lastFutureShiftCount++;
                String local = shift.optString("local", "Plantao");
                String type = shift.optString("type", "");
                String title = "Lembrete de plantao";
                String body = local + " - " + date + " " + time;
                reminders.add(new Reminder(title, body, type, shiftAt, shiftAt - leadMinutes * 60000L));
            }
        } catch (Exception ignored) {
            lastRawPreview = "JSON invalido: " + lastRawPreview;
        }
        Collections.sort(reminders, (left, right) -> Long.compare(left.triggerAt, right.triggerAt));
        return reminders;
    }

    private static long parseShiftTime(String date, String time) {
        try {
            String[] dateParts = date.split("-");
            String[] timeParts = time.split(":");
            Calendar calendar = Calendar.getInstance();
            calendar.set(Calendar.YEAR, Integer.parseInt(dateParts[0]));
            calendar.set(Calendar.MONTH, Integer.parseInt(dateParts[1]) - 1);
            calendar.set(Calendar.DAY_OF_MONTH, Integer.parseInt(dateParts[2]));
            calendar.set(Calendar.HOUR_OF_DAY, Integer.parseInt(timeParts[0]));
            calendar.set(Calendar.MINUTE, Integer.parseInt(timeParts[1]));
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            return calendar.getTimeInMillis();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static boolean schedule(Context context, Reminder reminder, int index) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            lastScheduleError = "AlarmManager indisponivel";
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            lastScheduleError = "Permissao de alarmes exatos desativada. Ative em Configuracoes > Alarmes e lembretes do app.";
            return false;
        }

        try {
            PendingIntent intent = pendingIntent(context, index, reminder);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAt, intent);
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, reminder.triggerAt, intent);
            }
            return true;
        } catch (Exception error) {
            String message = error.getMessage();
            lastScheduleError = message == null || message.trim().isEmpty()
                    ? error.getClass().getSimpleName()
                    : message;
            return false;
        }
    }

    private static void cancelAll(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;
        for (int i = 0; i < MAX_ALARMS; i++) {
            alarmManager.cancel(pendingIntent(context, i, null));
        }
    }

    private static PendingIntent pendingIntent(Context context, int index, Reminder reminder) {
        Intent intent = new Intent(context, ShiftReminderReceiver.class);
        if (reminder != null) {
            intent.putExtra("title", reminder.title);
            intent.putExtra("body", reminder.body);
            intent.putExtra("type", reminder.type);
        }
        return PendingIntent.getBroadcast(context, BASE_REQUEST_CODE + index, intent, pendingFlags());
    }

    private static String buildSummary(int count, Reminder nextScheduled, long now) {
        if (count <= 0 || nextScheduled == null) {
            if (!lastScheduleError.isEmpty()) {
                return "Não foi possível agendar os lembretes. Verifique as permissões de notificação e alarmes do app.";
            }
            if (lastParsedCount <= 0 || lastFutureShiftCount <= 0) {
                return "Nenhum compromisso futuro encontrado para lembrete.";
            }
            return "Nenhum alerta futuro agendado. Confira data, hora e antecedência.";
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(nextScheduled.triggerAt);
        String hh = String.format(Locale.ROOT, "%02d", calendar.get(Calendar.HOUR_OF_DAY));
        String mm = String.format(Locale.ROOT, "%02d", calendar.get(Calendar.MINUTE));
        String dd = String.format(Locale.ROOT, "%02d", calendar.get(Calendar.DAY_OF_MONTH));
        String mo = String.format(Locale.ROOT, "%02d", calendar.get(Calendar.MONTH) + 1);
        String prefix = nextScheduled.triggerAt - now <= 15000L ? "Aviso imediato agendado. " : "";
        return prefix + count + " alerta(s) agendado(s). Proximo: " + dd + "/" + mo + " as " + hh + ":" + mm;
    }

    private static int pendingFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return flags;
    }

    private static String preview(String value) {
        if (value == null) return "null";
        String clean = value.replace('\n', ' ').replace('\r', ' ').trim();
        if (clean.length() > 90) return clean.substring(0, 90);
        return clean;
    }

    private static final class Reminder {
        final String title;
        final String body;
        final String type;
        final long shiftAt;
        final long triggerAt;

        Reminder(String title, String body, String type, long shiftAt, long triggerAt) {
            this.title = title;
            this.body = body;
            this.type = type;
            this.shiftAt = shiftAt;
            this.triggerAt = triggerAt;
        }

        Reminder withTriggerAt(long newTriggerAt) {
            return new Reminder(title, body, type, shiftAt, newTriggerAt);
        }
    }
}
