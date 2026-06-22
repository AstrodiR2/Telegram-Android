package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

public class KvasService extends Service {

    private static final String CHANNEL_ID = "kvas_bg_channel";
    private static final int NOTIF_ID = 9901;

    public static void start(Context ctx) {
        Intent intent = new Intent(ctx, KvasService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent);
        } else {
            ctx.startService(intent);
        }
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, KvasService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createChannel();
        Notification notif = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Квас активен")
                .setContentText("Бот работает в фоне")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
        startForeground(NOTIF_ID, notif);
        return START_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Квас фон", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Квас работает в фоне");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
