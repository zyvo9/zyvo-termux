package com.zyvo.code;

import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class UiUtil {

    public static int dp(Context c, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                c.getResources().getDisplayMetrics()));
    }

    public static void toast(Context c, String msg) {
        if (c == null) return;
        Toast.makeText(c.getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
    }

    public static void show(View v, boolean visible) {
        if (v != null) v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    public static String bytes(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format(Locale.US, "%.1f KB", n / 1024.0);
        if (n < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", n / (1024.0 * 1024));
        return String.format(Locale.US, "%.1f GB", n / (1024.0 * 1024 * 1024));
    }

    public static String time(long millis) {
        return new SimpleDateFormat("MMM d, HH:mm", Locale.US).format(new Date(millis));
    }

    private UiUtil() {
    }
}
