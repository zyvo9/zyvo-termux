package com.zyvo.code;

import com.termux.R;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * Keeps the zyvo engine process alive and supervised.
 *
 * Android kills plain background processes freely, and the engine is the whole
 * app — so it runs inside a dataSync foreground service with a wake lock and a
 * 30 s health watchdog that restarts it if it dies.
 */
public final class EngineService extends Service implements EngineManager.Listener {

    private static final String TAG = "ZyvoEngineService";
    public static final String CHANNEL_ID = "zyvo_engine";
    public static final int NOTIF_ID = 4101;

    private EngineManager engine;
    private PowerManager.WakeLock wakeLock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            try {
                if (!engine.isReady()) engine.ensureStarted();
            } catch (Throwable t) {
                Log.w(TAG, "watchdog", t);
            }
            handler.postDelayed(this, 30_000L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        engine = EngineManager.get(this);
        createChannel();
        startForegroundCompat(buildNotification("Starting engine…"));
        engine.addListener(this);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zyvo:engine");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
        engine.ensureStarted();
        handler.postDelayed(watchdog, 20_000L);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        engine.ensureStarted();
        return START_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Zyvo engine",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Keeps the zyvo agent running");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, ChatActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_terminal)
                .setContentTitle("Zyvo Code")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }

    @Override
    public void onEngineState(EngineManager.State state, String detail) {
        String text;
        switch (state) {
            case READY:
                text = "Agent ready — online";
                break;
            case STARTING:
                text = "Starting… " + (detail == null ? "" : detail);
                break;
            case ERROR:
                text = "Engine stopped: " + (detail == null ? "unknown" : detail);
                break;
            default:
                text = "Engine stopped";
                break;
        }
        updateNotification(text);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(watchdog);
        if (engine != null) engine.removeListener(this);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
