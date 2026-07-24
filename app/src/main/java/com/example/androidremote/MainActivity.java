package com.example.androidremote;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import android.os.PowerManager;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

public class MainActivity extends AppCompatActivity {

    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(
                    this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }

        // Auto-request battery optimization exemption if not already granted
        // This is the #1 cause of background service being killed after ~10 min
        requestBatteryOptimizationExemption();

        // ── load saved server URL ──────────────────────────────────────────
        SharedPreferences prefs = getSharedPreferences(WebSocketService.PREFS, MODE_PRIVATE);
        String savedUrl = prefs.getString(
                WebSocketService.KEY_SERVER_URL, WebSocketService.DEFAULT_SERVER);
        String deviceId = prefs.getString(WebSocketService.KEY_DEVICE_ID, "—");

        // ── title ──────────────────────────────────────────────────────────
        TextView title = new TextView(this);
        title.setText("📱 Android Remote Control v2");
        title.setTextSize(18);
        title.setPadding(32, 40, 32, 8);

        // ── device ID display ──────────────────────────────────────────────
        TextView idView = new TextView(this);
        idView.setText("Device ID: " + deviceId + "\nModel: " + Build.MANUFACTURER + " " + Build.MODEL);
        idView.setPadding(32, 8, 32, 16);
        idView.setTextSize(13);

        // ── server URL input ───────────────────────────────────────────────
        TextView urlLabel = new TextView(this);
        urlLabel.setText("Server URL (ws://IP:8765):");
        urlLabel.setPadding(32, 8, 32, 0);

        EditText urlInput = new EditText(this);
        urlInput.setText(savedUrl);
        urlInput.setPadding(32, 8, 32, 8);
        urlInput.setHint("ws://192.168.1.100:8765");

        Button saveUrl = new Button(this);
        saveUrl.setText("💾 Save & Reconnect");
        saveUrl.setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            prefs.edit().putString(WebSocketService.KEY_SERVER_URL, url).apply();
            // Restart WebSocket service with new URL
            stopService(new Intent(this, WebSocketService.class));
            WebSocketService.start(this);
            statusView.setText("⏳ Connecting to " + url + "...");
        });

        // ── status indicator ───────────────────────────────────────────────
        statusView = new TextView(this);
        statusView.setText("⏳ Starting...");
        statusView.setPadding(32, 8, 32, 8);

        // ── accessibility button ───────────────────────────────────────────
        Button accessibility = new Button(this);
        accessibility.setText("⚙️ Open Accessibility Settings");
        accessibility.setOnClickListener(view ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        // ── battery optimization button ────────────────────────────────────
        Button battery = new Button(this);
        battery.setText("🔋 Disable Battery Optimization");
        battery.setOnClickListener(view -> {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });

        // ── instructions ───────────────────────────────────────────────────
        TextView instructions = new TextView(this);
        instructions.setText(
            "\nSetup:\n" +
            "1. Set server URL above and tap Save\n" +
            "2. Open Accessibility Settings\n" +
            "3. Enable 'Android Remote Control'\n" +
            "4. Disable battery optimization\n\n" +
            "The app will connect automatically and stay connected in background."
        );
        instructions.setPadding(32, 8, 32, 32);
        instructions.setTextSize(13);

        // ── layout ─────────────────────────────────────────────────────────
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(title);
        layout.addView(idView);
        layout.addView(urlLabel);
        layout.addView(urlInput);
        layout.addView(saveUrl);
        layout.addView(statusView);
        layout.addView(accessibility);
        layout.addView(battery);
        layout.addView(instructions);
        setContentView(layout);

        // Start service if accessibility is already enabled
        KeepAliveService.start(this);
        WebSocketService.start(this);
    }

    private void requestBatteryOptimizationExemption() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Update status from WebSocketService instance
        if (WebSocketService.instance != null) {
            statusView.setText("✅ WebSocket service running");
        } else {
            statusView.setText("⚠️  Enable Accessibility to start connection");
        }
    }
}
