package br.com.sistplantao.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ShiftReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String title = intent == null ? null : intent.getStringExtra("title");
        String body = intent == null ? null : intent.getStringExtra("body");

        Context appContext = context.getApplicationContext();
        if (!AlarmSoundService.start(appContext, title, body)) {
            NotificationHelper.show(appContext, title, body);
        }
    }
}
