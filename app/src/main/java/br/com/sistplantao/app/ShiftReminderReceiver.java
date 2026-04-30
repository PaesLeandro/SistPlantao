package br.com.sistplantao.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ShiftReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            AlarmScheduler.rescheduleSaved(context);
            return;
        }
        String title = intent.getStringExtra("title");
        String body = intent.getStringExtra("body");
        NotificationHelper.show(context, title, body);
    }
}
