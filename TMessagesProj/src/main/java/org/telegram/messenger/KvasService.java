package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import org.telegram.tgnet.ConnectionsManager;

public class KvasService extends Service {
    private PowerManager.WakeLock wakeLock;
    private Handler keepAliveHandler;
    private static final int KEEP_ALIVE_INTERVAL_MS = 30000;
    private Handler channelPostHandler;
    private static final String CHANNEL_USERNAME = "KvasAi_api";
    private static final long MIN_POST_INTERVAL_MS = 15 * 60 * 1000L; // 15 минут
    private static final long MAX_POST_INTERVAL_MS = 60 * 60 * 1000L; // 60 минут

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
        scheduleNoonAlarm(this);
        acquireWakeLock();
        startKeepAlive();
        startChannelPosts();
        enableDefaultChats();
        Notification notif = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Квас активен")
                .setContentText("Бот работает в фоне")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
        startForeground(NOTIF_ID, notif);
        return START_STICKY;
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kvas:wakelock");
            wakeLock.acquire();
        }
    }

    private void startKeepAlive() {
        keepAliveHandler = new Handler(Looper.getMainLooper());
        keepAliveHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    for (int i = 0; i < 3; i++) {
                        ConnectionsManager.getInstance(i).resumeNetworkMaybe();
                    }
                } catch (Exception e) {}
                keepAliveHandler.postDelayed(this, KEEP_ALIVE_INTERVAL_MS);
            }
        }, KEEP_ALIVE_INTERVAL_MS);
    }

    private void enableDefaultChats() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            int account = org.telegram.messenger.UserConfig.selectedAccount;
            String[] defaultChats = {"KvasAi_api", "KvasAichat"};
            for (String username : defaultChats) {
                final String uname = username;
                MessagesController.getInstance(account).getUserNameResolver().resolve(uname, peerId -> {
                    if (peerId != null) {
                        CommandHandler.enableAiUserChat(peerId);
                        CommandHandler.addLog("\u2705 Auto-enabled /ai user for @" + uname + " (" + peerId + ")");
                    }
                });
            }
        }, 5000);
    }

    private void startChannelPosts() {
        channelPostHandler = new Handler(Looper.getMainLooper());
        scheduleNextPost();
    }

    private void scheduleNextPost() {
        long delay = MIN_POST_INTERVAL_MS + (long)(Math.random() * (MAX_POST_INTERVAL_MS - MIN_POST_INTERVAL_MS));
        channelPostHandler.postDelayed(() -> {
            postToChannel();
            scheduleNextPost();
        }, delay);
    }

    private void postToChannel() {
        int account = org.telegram.messenger.UserConfig.selectedAccount;
        MessagesController.getInstance(account).getUserNameResolver().resolve(CHANNEL_USERNAME, peerId -> {
            if (peerId == null) return;
            String[] prompts = {
                "Generate a short funny joke in Russian. Just the joke, no intro.",
                "Generate a short philosophical thought in Russian. Casual tone, 1-2 sentences.",
                "Generate a short absurd observation about life in Russian. Casual, funny.",
                "Generate a random interesting fact in Russian. Short, 1-2 sentences.",
                "Generate a short sarcastic comment about modern life in Russian."
            };
            String prompt = prompts[(int)(Math.random() * prompts.length)];
            final long finalPeerId = peerId;
            AiManager.ask(ApplicationLoader.applicationContext, finalPeerId, prompt, new AiManager.AiCallback() {
                @Override
                public void onResult(String result) {
                    AndroidUtilities.runOnUIThread(() -> {
                        SendMessagesHelper.SendMessageParams p = SendMessagesHelper.SendMessageParams.of(
                            result, finalPeerId, null, null, null, false, null, null, null, false, 0, 0, null, false);
                        SendMessagesHelper.getInstance(account).sendMessage(p);
                    });
                }
                @Override
                public void onError(String error) {
                    CommandHandler.addLog("❌ channelPost: " + error);
                }
            });
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (keepAliveHandler != null) keepAliveHandler.removeCallbacksAndMessages(null);
        if (channelPostHandler != null) channelPostHandler.removeCallbacksAndMessages(null);
    }

    private void scheduleNoonAlarm(Context ctx) {
        android.app.AlarmManager am = (android.app.AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        android.content.Intent i = new android.content.Intent(ctx, KvasAlarmReceiver.class);
        android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(ctx, 1200, i,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, 12);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
        }
        am.setRepeating(android.app.AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(),
            android.app.AlarmManager.INTERVAL_DAY, pi);
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
