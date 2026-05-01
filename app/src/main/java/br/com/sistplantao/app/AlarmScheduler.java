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
import java.util.Comparator;
import java.util.List;

public final class AlarmScheduler {
    private static final String PREFS = "native_reminders";
    private static final String KEY_SHIFTS = "shifts_json";
    private static final String KEY_LEAD = "lead_minutes";
    private static final String KEY_LAST_SUMMARY = "last_summary";
    private static final int BASE_REQUEST_CODE = 31000;
    private static final int MAX_ALARMS = 64;

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
        for (Reminder reminder : reminders) {
            if (reminder.triggerAt <= now && reminder.shiftAt <= now) continue;
            if (reminder.triggerAt <= now) {
                reminder = reminder.withTriggerAt(now + 5000L);
            }
            if (nextScheduled == null) nextScheduled = reminder;
            schedule(appContext, reminder, index);
            index++;
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
        try {
            JSONArray shifts = new JSONArray(shiftsJson == null ? "[]" : shiftsJson);
            for (int i = 0; i < shifts.length(); i++) {
                JSONObject shift = shifts.optJSONObject(i);
                if (shift == null) continue;
                long shiftAt = parseShiftTime(shift.optString("date"), shift.optString("time", "00:00"));
                if (shiftAt <= 0) continue;
                String local = shift.optString("local", "Plantao");
                String type = shift.optString("type", "");
                String title = "Lembrete de plantao";
                String body = local + " - " + shift.optString("date") + " " + shift.optString("time", "00:00");
                reminders.add(new Reminder(title, body, type, shiftAt, shiftAt - leadMinutes * 60000L));
            }
        } catch (Exception ignored) {
        }
        Collections.sort(reminders, Comparator.comparingLong(reminder -> reminder.triggerAt));
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

    private static void schedule(Context context, Reminder reminder, int index) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        PendingIntent intent = pendingIntent(context, index, reminder);
        PendingIntent showIntent = PendingIntent.getActivity(
                context,
                BASE_REQUEST_CODE + MAX_ALARMS + index,
                new Intent(context, LauncherActivity.class),
                pendingFlags()
        );
        AlarmManager.AlarmClockInfo alarmClockInfo = new AlarmManager.AlarmClockInfo(reminder.triggerAt, showIntent);
        alarmManager.setAlarmClock(alarmClockInfo, intent);
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
            return "Nenhum alerta futuro agendado. Confira se o horario do aviso ainda nao passou.";
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(nextScheduled.triggerAt);
        String hh = String.format("%02d", calendar.get(Calendar.HOUR_OF_DAY));
        String mm = String.format("%02d", calendar.get(Calendar.MINUTE));
        String dd = String.format("%02d", calendar.get(Calendar.DAY_OF_MONTH));
        String mo = String.format("%02d", calendar.get(Calendar.MONTH) + 1);
        String prefix = nextScheduled.triggerAt - now <= 15000L ? "Aviso imediato agendado. " : "";
        return prefix + count + " alerta(s) agendado(s). Proximo: " + dd + "/" + mo + " as " + hh + ":" + mm;
    }

    private static int pendingFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return flags;
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
