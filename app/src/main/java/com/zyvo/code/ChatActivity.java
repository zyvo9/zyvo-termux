package com.zyvo.code;

import com.termux.R;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;
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
