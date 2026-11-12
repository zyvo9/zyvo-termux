package com.zyvo.code;

import com.termux.R;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

/**
 * Chat-first launcher activity for the Zyvo Code build on top of Termux.
 * The real terminal is one tap away from the plus menu / mode chip.
 */
public final class ChatActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        Window w = getWindow();
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.zyvo_chat_container, new ChatFragment())
                    .commit();
        }

        startEngine();
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
