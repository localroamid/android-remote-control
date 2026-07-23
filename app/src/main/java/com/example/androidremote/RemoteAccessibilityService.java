package com.example.androidremote;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

public class RemoteAccessibilityService extends AccessibilityService {
    private static final String TAG = "RemoteAccessibility";
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver commandReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handleCommand(intent);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        LocalBroadcastManager.getInstance(this).registerReceiver(
                commandReceiver, new IntentFilter(RemoteCommand.ACTION));
        KeepAliveService.start(this);
        WebSocketService.start(this);   // <── start WS on accessibility enable
        Log.i(TAG, "Accessibility service connected");
    }

    @Override
    public void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(commandReceiver);
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}

    // ── command dispatch ───────────────────────────────────────────────────

    private void handleCommand(Intent intent) {
        String command = intent.getStringExtra(RemoteCommand.EXTRA_COMMAND);
        if (command == null) return;

        try {
            switch (command) {
                case "tap":
                    tap(getInt(intent, RemoteCommand.EXTRA_X),
                        getInt(intent, RemoteCommand.EXTRA_Y));
                    break;
                case "swipe":
                    swipe(getInt(intent, RemoteCommand.EXTRA_X1),
                          getInt(intent, RemoteCommand.EXTRA_Y1),
                          getInt(intent, RemoteCommand.EXTRA_X2),
                          getInt(intent, RemoteCommand.EXTRA_Y2));
                    break;
                case "app":
                    openApp(intent.getStringExtra(RemoteCommand.EXTRA_PACKAGE),
                            intent.getStringExtra(RemoteCommand.EXTRA_NAME));
                    break;
                case "type":
                    typeText(intent.getStringExtra(RemoteCommand.EXTRA_TEXT));
                    break;
                case "click_label":
                    clickFirstMatchingLabel(intent.getStringExtra(RemoteCommand.EXTRA_LABEL));
                    break;
                case "find_label_scroll":
                    findLabelWithScroll(
                        intent.getStringExtra(RemoteCommand.EXTRA_LABEL),
                        getIntOrDefault(intent, RemoteCommand.EXTRA_MAX_SCROLLS, 6));
                    break;
                case "long_press":
                    longPress(getInt(intent, RemoteCommand.EXTRA_X),
                              getInt(intent, RemoteCommand.EXTRA_Y));
                    break;
                case "long_click_label":
                    longClickLabel(intent.getStringExtra(RemoteCommand.EXTRA_LABEL));
                    break;
                case "home":
                    performGlobalAction(GLOBAL_ACTION_HOME);
                    break;
                case "back":
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    break;
                case "screenshot":
                    takeScreenshotAndSend();
                    break;
                case "lock":
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
                    }
                    break;
                case "volume":
                    setVolume(getInt(intent, RemoteCommand.EXTRA_VALUE));
                    break;
                default:
                    Log.w(TAG, "Unknown command: " + command);
            }
        } catch (RuntimeException ex) {
            Log.e(TAG, "Command failed: " + command, ex);
        }
    }

    // ── screenshot → WebSocket ─────────────────────────────────────────────

    private void takeScreenshotAndSend() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+: capture bitmap and forward to WebSocketService
            Executor executor = handler::post;
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, executor,
                new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult result) {
                        try {
                            Bitmap bmp = Bitmap.wrapHardwareBuffer(
                                    result.getHardwareBuffer(), result.getColorSpace());
                            if (bmp == null) return;
                            Bitmap soft = bmp.copy(Bitmap.Config.ARGB_8888, false);
                            bmp.recycle();
                            result.getHardwareBuffer().close();

                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            soft.compress(Bitmap.CompressFormat.JPEG, 65, baos);
                            soft.recycle();

                            if (WebSocketService.instance != null) {
                                WebSocketService.instance.sendScreenshot(baos.toByteArray());
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Screenshot encode error", e);
                        }
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        Log.w(TAG, "takeScreenshot failed: " + errorCode);
                        // Fallback: save to gallery
                        performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT);
                    }
                });
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // API 28-29: saves to gallery only (WS cannot receive image)
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT);
        } else {
            Log.w(TAG, "Screenshot not supported on API " + Build.VERSION.SDK_INT);
        }
    }

    // ── gestures ───────────────────────────────────────────────────────────

    private void tap(int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 80);
        dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
    }

    private void longPress(int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 800); // 800ms = long press
        dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
    }

    private void swipe(int x1, int y1, int x2, int y2) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 450);
        dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
    }

    // ── app launcher ───────────────────────────────────────────────────────

    private void openApp(String packageName, String name) {
        if (!openAppByPackage(packageName)) openAppByName(name);
    }

    private boolean openAppByPackage(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) return false;
        Intent i = getPackageManager().getLaunchIntentForPackage(packageName);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        return true;
    }

    private void openAppByName(String name) {
        if (name == null || name.trim().isEmpty()) return;
        PackageManager pm = getPackageManager();
        String query = name.toLowerCase(Locale.US);
        for (ApplicationInfo app : pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
            CharSequence label = pm.getApplicationLabel(app);
            if (label != null && label.toString().toLowerCase(Locale.US).contains(query)) {
                Intent i = pm.getLaunchIntentForPackage(app.packageName);
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    return;
                }
            }
        }
    }

    // ── text input ─────────────────────────────────────────────────────────

    private void typeText(String text) {
        if (text == null) return;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo focused = root == null ? null
                : root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused != null) {
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            focused.recycle();
        }
    }

    // ── label search ───────────────────────────────────────────────────────

    private void longClickLabel(String labels) {
        if (labels == null || labels.trim().isEmpty()) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        String[] opts = labels.toLowerCase(Locale.US).split("\\|");
        AccessibilityNodeInfo match = findNodeByLabel(root, opts);
        if (match != null) {
            AccessibilityNodeInfo clickable = findClickableNode(match);
            if (clickable != null) {
                clickable.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
                clickable.recycle();
            }
            match.recycle();
        }
        root.recycle();
    }

    private void clickFirstMatchingLabel(String labels) {
        if (labels == null || labels.trim().isEmpty()) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        String[] opts = labels.toLowerCase(Locale.US).split("\\|");
        AccessibilityNodeInfo match = findNodeByLabel(root, opts);
        if (match != null) {
            AccessibilityNodeInfo clickable = findClickableNode(match);
            if (clickable != null) {
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                clickable.recycle();
            }
            match.recycle();
        }
        root.recycle();
    }

    private void findLabelWithScroll(String labels, int maxScrolls) {
        if (labels == null || labels.trim().isEmpty()) return;
        findLabelWithScroll(labels, Math.max(0, maxScrolls), 0);
    }

    private void findLabelWithScroll(String labels, int maxScrolls, int attempt) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            String[] opts = labels.toLowerCase(Locale.US).split("\\|");
            AccessibilityNodeInfo match = findNodeByLabel(root, opts);
            root.recycle();
            if (match != null) { match.recycle(); return; }
        }
        if (attempt >= maxScrolls) return;
        swipe(540, 900, 540, 300);
        handler.postDelayed(() -> findLabelWithScroll(labels, maxScrolls, attempt + 1), 900L);
    }

    private AccessibilityNodeInfo findNodeByLabel(AccessibilityNodeInfo node, String[] labels) {
        if (node == null) return null;
        if (node.isVisibleToUser() && matchesLabel(node, labels))
            return AccessibilityNodeInfo.obtain(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo m = findNodeByLabel(child, labels);
            child.recycle();
            if (m != null) return m;
        }
        return null;
    }

    private boolean matchesLabel(AccessibilityNodeInfo node, String[] labels) {
        String t = node.getText() == null ? "" : node.getText().toString();
        String d = node.getContentDescription() == null ? "" : node.getContentDescription().toString();
        String hay = (t + " " + d).toLowerCase(Locale.US);
        for (String l : labels) { if (!l.trim().isEmpty() && hay.contains(l.trim())) return true; }
        return false;
    }

    private AccessibilityNodeInfo findClickableNode(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = AccessibilityNodeInfo.obtain(node);
        while (cur != null) {
            if (cur.isClickable() && cur.isEnabled()) return cur;
            AccessibilityNodeInfo parent = cur.getParent();
            cur.recycle(); cur = parent;
        }
        return null;
    }

    // ── volume ─────────────────────────────────────────────────────────────

    private void setVolume(int percent) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        am.setStreamVolume(AudioManager.STREAM_MUSIC,
                Math.max(0, Math.min(max, Math.round(max * (percent / 100f)))), 0);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private int getInt(Intent i, String key) {
        String v = i.getStringExtra(key);
        if (v == null) throw new IllegalArgumentException("Missing " + key);
        return Integer.parseInt(v);
    }

    private int getIntOrDefault(Intent i, String key, int def) {
        String v = i.getStringExtra(key);
        return v == null ? def : Integer.parseInt(v);
    }
}
