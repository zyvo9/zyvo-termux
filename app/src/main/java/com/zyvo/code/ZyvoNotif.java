package com.zyvo.code;

import com.termux.R;
import com.termux.app.TermuxService;
import com.termux.shared.termux.TermuxConstants;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

/**
 * The one and only notification of the app.
 *
 * The terminal service and the engine service both run as foreground services.
 * They share this notification id and channel, so the shade shows a single
 * "Zyvo Code" entry (with Awake / Exit actions) instead of a separate
 * "Termux" one for the terminal service.
 */
public final class ZyvoNotif {

    public static final String CHANNEL_ID = "zyvo_engine";
    public static final int ID = 4101;

    private static volatile String text = "Terminal running";
    private static volatile boolean wakeHeld = false;

    private ZyvoNotif() {
    }

    /** Create (or update) the low-importance channel used by both services. */
    public static void ensureChannel(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        // Remove the old separate "Termux" notification left behind by earlier builds.
        nm.cancel(TermuxConstants.TERMUX_APP_NOTIFICATION_ID);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Zyvo Code",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Keeps the zyvo agent and terminal running");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    /** Set the status line shown in the notification. */
    public static void setText(Context ctx, String t) {
        text = t;
        refresh(ctx);
    }

    /** Remember whether the terminal wake lock is held (drives the Awake action label). */
    public static void setWakeHeld(Context ctx, boolean held) {
        wakeHeld = held;
        refresh(ctx);
    }

    public static Notification build(Context ctx) {
        Intent open = new Intent(ctx, ChatActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent wake = new Intent(ctx, TermuxService.class);
        wake.setAction(wakeHeld
                ? TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_WAKE_UNLOCK
                : TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_WAKE_LOCK);
        PendingIntent wakeIntent = PendingIntent.getService(ctx, 1, wake,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent exit = new Intent(ctx, TermuxService.class);
        exit.setAction(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_STOP_SERVICE);
        PendingIntent exitIntent = PendingIntent.getService(ctx, 2, exit,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_terminal)
                .setContentTitle("Zyvo Code")
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(R.drawable.ic_terminal, wakeHeld ? "Release" : "Awake", wakeIntent)
                .addAction(R.drawable.ic_terminal, "Exit", exitIntent)
                .build();
    }

    /** Re-post the notification (safe to call from any service in the app). */
    public static void refresh(Context ctx) {
        ensureChannel(ctx);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(ID, build(ctx));
    }
}
