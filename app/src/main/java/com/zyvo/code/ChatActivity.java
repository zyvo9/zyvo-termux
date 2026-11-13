package com.zyvo.code;

import com.termux.R;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

/**
 * Chat-first launcher activity for the Zyvo Code build on top of Termux.
 * The hamburger opens the ChatGPT-style session drawer; the terminal and
 * settings live in the drawer and the plus menu.
 */
public final class ChatActivity extends AppCompatActivity {

    private DrawerLayout drawer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Zyvo Code is a dark-first app: force night mode so the Zai dark tokens
        // always resolve, whatever the system theme is set to.
        int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (night != Configuration.UI_MODE_NIGHT_YES) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        }
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        Window w = getWindow();
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        drawer = findViewById(R.id.drawer_layout);

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.zyvo_chat_container, new ChatFragment())
                    .commit();
        }

        findViewById(R.id.btn_drawer_new).setOnClickListener(v -> {
            ChatFragment f = chat();
            if (f != null) f.newSession();
            closeDrawer();
        });
        findViewById(R.id.btn_drawer_terminal).setOnClickListener(v -> {
            openTerminal();
            closeDrawer();
        });
        findViewById(R.id.btn_drawer_log).setOnClickListener(v -> {
            showEngineLog();
            closeDrawer();
        });
        findViewById(R.id.btn_drawer_settings).setOnClickListener(v -> {
            openSettings();
            closeDrawer();
        });

    }

    @Override
    protected void onStart() {
        super.onStart();
        // Also restarts the foreground engine service after the user taps Exit in
        // the notification and later comes back to the app.
        startEngine();
    }

    private ChatFragment chat() {
        return (ChatFragment) getSupportFragmentManager()
                .findFragmentById(R.id.zyvo_chat_container);
    }

    public void closeDrawer() {
        if (drawer != null) drawer.closeDrawer(GravityCompat.START);
    }

    private void openTerminal() {
        try {
            startActivity(new Intent(this, Class.forName("com.termux.app.TermuxActivity")));
        } catch (Throwable t) {
            UiUtil.toast(this, "Terminal is not available");
        }
    }

    private void openSettings() {
        try {
            startActivity(new Intent(this,
                    Class.forName("com.termux.app.activities.SettingsActivity")));
        } catch (Throwable t) {
            UiUtil.toast(this, "Settings are not available");
        }
    }

    /**
     * Plain debug surface: the tail of the engine's own stderr/stdout. When a
     * turn dies silently this is the only place that says why.
     */
    private void showEngineLog() {
        String tail = EngineManager.get(this).tailLog(250);
        if (tail == null || tail.trim().isEmpty()) tail = "No engine output yet.";
        final String text = tail;

        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        TextView view = new TextView(this);
        view.setPadding(pad, pad, pad, pad);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        view.setText(text);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);

        new AlertDialog.Builder(this)
                .setTitle(R.string.engine_logs)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.copy, (d, w) -> {
                    ClipboardManager cm = (ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("engine log", text));
                        UiUtil.toast(this, getString(R.string.copied));
                    }
                })
                .show();
    }

    @Override
    public void onBackPressed() {
        if (drawer != null && drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START);
            return;
        }
        super.onBackPressed();
    }

    private void startEngine() {
        try {
            Intent i = new Intent(this, EngineService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, i);
            } else {
                startService(i);
            }
        } catch (Throwable ignored) {
            // ChatFragment shows the engine banner and lets the user retry.
        }
    }
}
