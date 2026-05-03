package br.com.sistplantao.app;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;

public class AlarmSoundService extends Service {
    private static final int FOREGROUND_ID = 43001;
    private static final long MAX_RING_MS = 20000L;

    private MediaPlayer mediaPlayer;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public static void start(Context context, String title, String body) {
        Intent intent = new Intent(context, AlarmSoundService.class)
                .putExtra("title", title)
                .putExtra("body", body);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String title = intent == null ? null : intent.getStringExtra("title");
        String body = intent == null ? null : intent.getStringExtra("body");
        startForeground(FOREGROUND_ID, buildForegroundNotification(title, body));
        playAlarmSound();
        NotificationHelper.show(this, title, body);
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::stopSelf, MAX_RING_MS);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopSound();
        super.onDestroy();
    }

    private Notification buildForegroundNotification(String title, String body) {
        NotificationHelper.ensureChannel(this);
        return new NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(title == null || title.isEmpty() ? "Lembrete de plantao" : title)
                .setContentText(body == null ? "Alarme tocando" : body)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(false)
                .build();
    }

    private void playAlarmSound() {
        stopSound();
        Uri alertUri = Settings.System.DEFAULT_ALARM_ALERT_URI != null
                ? Settings.System.DEFAULT_ALARM_ALERT_URI
                : Settings.System.DEFAULT_NOTIFICATION_URI;
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(getApplicationContext(), alertUri);
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mediaPlayer.setLooping(true);
            mediaPlayer.setVolume(1.0f, 1.0f);
            mediaPlayer.setOnPreparedListener(MediaPlayer::start);
            mediaPlayer.prepareAsync();
        } catch (Exception ignored) {
            stopSound();
        }
    }

    private void stopSound() {
        if (mediaPlayer == null) return;
        try {
            if (mediaPlayer.isPlaying()) mediaPlayer.stop();
        } catch (Exception ignored) {
        }
        try {
            mediaPlayer.release();
        } catch (Exception ignored) {
        }
        mediaPlayer = null;
    }
}
