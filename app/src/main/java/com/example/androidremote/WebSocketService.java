package com.example.androidremote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Persistent WebSocket connection to the remote control server.
 * Replaces RemoteFirebaseMessagingService (FCM).
 *
 * Device connects at:  ws://SERVER_IP:8765/ws/device/{device_id}
 * Commands arrive as JSON: { "type":"cmd", "command":"home", ... }
 * Screenshots are sent as: { "type":"screenshot", "data":"base64..." }
 */
public class WebSocketService extends Service {

    private static final String TAG = "WebSocketService";
    public  static final String PREFS = "remote_prefs";
    public  static final String KEY_DEVICE_ID  = "device_id";
    public  static final String KEY_SERVER_URL  = "server_url";
    public  static final String DEFAULT_SERVER  = "wss://android-remote-server.onrender.com";

    private static final int RECONNECT_BASE_MS = 4_000;
    private static final int RECONNECT_MAX_MS  = 60_000;

    /** Held by RemoteAccessibilityService to call sendScreenshot(). */
    public static WebSocketService instance;

    private OkHttpClient client;
    private WebSocket    webSocket;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running = false;
    private int reconnectMs = RECONNECT_BASE_MS;

    private String deviceId;
    private String deviceName;
    private String serverUrl;

    // ── lifecycle ──────────────────────────────────────────────────────────

    public static void start(android.content.Context ctx) {
        Intent i = new Intent(ctx, WebSocketService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance    = this;
        deviceId    = getOrCreateDeviceId();
        deviceName  = Build.MANUFACTURER + " " + Build.MODEL;
        serverUrl   = getServerUrl();

        // REQUIRED on Android 8+: must call startForeground() within 5 seconds
        startForegroundNow();

        client = new OkHttpClient.Builder()
                .pingInterval(25, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)   // no read timeout for WS
                .build();

        KeepAliveService.start(this);
        Log.i(TAG, "Service created — device=" + deviceId + " server=" + serverUrl);
    }

    private void startForegroundNow() {
        final String CHANNEL_ID = "remote_control_status";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Remote Control", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_remote)
                .setContentTitle("Remote Control")
                .setContentText("WebSocket connected")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        startForeground(1002, notification);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            running = true;
            connect();
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        handler.removeCallbacksAndMessages(null);
        if (webSocket != null) {
            webSocket.close(1000, "Service stopped");
            webSocket = null;
        }
        client.dispatcher().executorService().shutdown();
        super.onDestroy();
    }

    // ── connection ─────────────────────────────────────────────────────────

    private void connect() {
        if (!running) return;
        String url = serverUrl + "/ws/device/" + deviceId;
        Log.i(TAG, "Connecting → " + url);
        Request req = new Request.Builder().url(url).build();
        webSocket = client.newWebSocket(req, new WsListener());
    }

    private void scheduleReconnect() {
        if (!running) return;
        Log.i(TAG, "Reconnect in " + reconnectMs + "ms");
        handler.postDelayed(() -> {
            connect();
            reconnectMs = Math.min(reconnectMs * 2, RECONNECT_MAX_MS);
        }, reconnectMs);
    }

    // ── called by RemoteAccessibilityService after takeScreenshot() ────────

    public void sendScreenshot(byte[] jpegBytes) {
        if (webSocket == null || jpegBytes == null) return;
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "screenshot");
            msg.put("data", Base64.encodeToString(jpegBytes, Base64.NO_WRAP));
            webSocket.send(msg.toString());
            Log.d(TAG, "Screenshot sent (" + jpegBytes.length + " bytes)");
        } catch (Exception e) {
            Log.e(TAG, "sendScreenshot error", e);
        }
    }

    // ── SharedPreferences helpers ──────────────────────────────────────────

    private String getOrCreateDeviceId() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String id = prefs.getString(KEY_DEVICE_ID, null);
        if (id == null) {
            id = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            prefs.edit().putString(KEY_DEVICE_ID, id).apply();
        }
        return id;
    }

    private String getServerUrl() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        return prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER);
    }

    // ── WebSocket listener ─────────────────────────────────────────────────

    private class WsListener extends WebSocketListener {

        @Override
        public void onOpen(WebSocket ws, Response response) {
            Log.i(TAG, "WebSocket open");
            reconnectMs = RECONNECT_BASE_MS;

            try {
                JSONObject reg = new JSONObject();
                reg.put("type",    "register");
                reg.put("name",    deviceName);
                reg.put("model",   Build.MODEL);
                reg.put("android", Build.VERSION.RELEASE);
                ws.send(reg.toString());
            } catch (Exception e) {
                Log.e(TAG, "Register error", e);
            }
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            try {
                JSONObject msg = new JSONObject(text);
                String type = msg.optString("type");

                if ("cmd".equals(type)) {
                    dispatchCommand(msg);
                } else if ("ping".equals(type)) {
                    JSONObject pong = new JSONObject();
                    pong.put("type", "pong");
                    ws.send(pong.toString());
                }
            } catch (Exception e) {
                Log.e(TAG, "Message error: " + text, e);
            }
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            Log.i(TAG, "WS closed: " + reason);
            scheduleReconnect();
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, @Nullable Response response) {
            Log.w(TAG, "WS failure: " + t.getMessage());
            scheduleReconnect();
        }
    }

    // ── command dispatch ───────────────────────────────────────────────────

    private void dispatchCommand(JSONObject msg) {
        String command = msg.optString("command");
        if (command.isEmpty()) return;
        Log.i(TAG, "Command: " + command);

        // "screenshot" handled by RemoteAccessibilityService via broadcast,
        // which then calls sendScreenshot() back on this service.
        Intent intent = new Intent(RemoteCommand.ACTION);
        intent.putExtra(RemoteCommand.EXTRA_COMMAND, command);

        // Copy all recognised extras from JSON → Intent
        String[] extras = {
            RemoteCommand.EXTRA_X,    RemoteCommand.EXTRA_Y,
            RemoteCommand.EXTRA_X1,   RemoteCommand.EXTRA_Y1,
            RemoteCommand.EXTRA_X2,   RemoteCommand.EXTRA_Y2,
            RemoteCommand.EXTRA_PACKAGE, RemoteCommand.EXTRA_NAME,
            RemoteCommand.EXTRA_LABEL,   RemoteCommand.EXTRA_MAX_SCROLLS,
            RemoteCommand.EXTRA_TEXT,    RemoteCommand.EXTRA_VALUE,
        };
        for (String key : extras) {
            if (msg.has(key)) {
                intent.putExtra(key, msg.optString(key));
            }
        }

        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }
}
