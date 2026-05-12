package com.pantheon.eleftheria;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class EleftheriaPrimeService extends AccessibilityService {

    private static final String TAG      = "EleftheriaPrime";
    private static final String RELAY_WS = "wss://nexus-relay-production.up.railway.app/ws";
    private static final String SECRET   = "pantheon_prime";
    private static final String VERSION  = "3.0.0";

    private static final int SCREENSHOT_MAX_W = 1080;
    private static final int SCREENSHOT_MAX_H = 2400;
    private static final int JPEG_QUALITY     = 75;

    private OkHttpClient httpClient;
    private WebSocket webSocket;
    private volatile boolean running = false;
    private final Handler reconnectHandler = new Handler(Looper.getMainLooper());

    private final AtomicReference<Bitmap> pendingScreenshot = new AtomicReference<>(null);
    private volatile CountDownLatch screenshotLatch = null;

    private static EleftheriaPrimeService instance;
    public static EleftheriaPrimeService getInstance() { return instance; }

    private int nodeCounter = 0;

    // ─── LIFECYCLE ───────────────────────────────────────────────

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        running = true;
        httpClient = new OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build();
        connectWebSocket();
        Log.i(TAG, "EleftheriaPrime v" + VERSION + " connected");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        running = false;
        instance = null;
        if (webSocket != null) webSocket.close(1000, "Service stopping");
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    // ─── WEBSOCKET ───────────────────────────────────────────────

    private void connectWebSocket() {
        if (!running) return;
        Request req = new Request.Builder()
            .url(RELAY_WS)
            .addHeader("X-Secret", SECRET)
            .build();
        webSocket = httpClient.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, okhttp3.Response r) {
                try {
                    JSONObject reg = new JSONObject();
                    reg.put("type", "register");
                    reg.put("version", VERSION);
                    ws.send(reg.toString());
                    Log.i(TAG, "WebSocket connected + registered");
                } catch (Exception e) { Log.e(TAG, "register error", e); }
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                handleCommand(ws, text);
            }

            @Override
            public void onMessage(WebSocket ws, ByteString bytes) {
                handleCommand(ws, bytes.utf8());
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, okhttp3.Response r) {
                Log.e(TAG, "WS failure: " + t.getMessage());
                scheduleReconnect();
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                Log.w(TAG, "WS closed: " + reason);
                scheduleReconnect();
            }
        });
    }

    private void scheduleReconnect() {
        if (!running) return;
        reconnectHandler.postDelayed(this::connectWebSocket, 3000);
    }

    // ─── COMMAND DISPATCH ────────────────────────────────────────

    private void handleCommand(WebSocket ws, String text) {
        try {
            JSONObject cmd = new JSONObject(text);
            String type = cmd.optString("type", "");
            if (type.equals("ack")) return;

            String action = cmd.optString("action", "");
            String id     = cmd.optString("_id", "");
            nodeCounter = 0;
            JSONObject result = dispatch(action, cmd);
            result.put("_id", id);
            ws.send(result.toString());
        } catch (Exception e) {
            Log.e(TAG, "handleCommand error", e);
        }
    }

    private JSONObject dispatch(String action, JSONObject cmd) throws Exception {
        switch (action) {
            case "ping":          return doPing();
            case "info":          return doInfo();
            case "tap":           return doTap(cmd);
            case "swipe":         return doSwipe(cmd);
            case "drag":          return doDrag(cmd);
            case "type":          return doType(cmd);
            case "back":          return doKey(GLOBAL_ACTION_BACK);
            case "home":          return doKey(GLOBAL_ACTION_HOME);
            case "recents":       return doKey(GLOBAL_ACTION_RECENTS);
            case "notifications": return doKey(GLOBAL_ACTION_NOTIFICATIONS);
            case "lock":          return doKey(GLOBAL_ACTION_LOCK_SCREEN);
            case "screen":        return doScreen(cmd);
            case "screenshot":    return doScreenshot(cmd);
            case "launch":        return doLaunch(cmd);
            case "open_url":      return doOpenUrl(cmd);
            case "click_text":    return doClickText(cmd);
            case "scroll_up":     return doScrollDir(cmd, true);
            case "scroll_down":   return doScrollDir(cmd, false);
            case "shell":         return doShell(cmd);
            case "clipboard_get": return doClipboardGet();
            case "clipboard_set": return doClipboardSet(cmd);
            case "volume_up":     return doVolume(true);
            case "volume_down":   return doVolume(false);
            case "brightness":    return doBrightness(cmd);
            default:
                JSONObject err = new JSONObject();
                err.put("ok", false);
                err.put("error", "unknown action: " + action);
                return err;
        }
    }

    // ─── ACTIONS ─────────────────────────────────────────────────

    private JSONObject doPing() throws Exception {
        JSONObject r = new JSONObject();
        r.put("ok", true);
        r.put("pong", true);
        r.put("version", VERSION);
        return r;
    }

    private JSONObject doInfo() throws Exception {
        JSONObject r = new JSONObject();
        r.put("ok", true);
        r.put("version", VERSION);
        r.put("sdk", android.os.Build.VERSION.SDK_INT);
        r.put("device", android.os.Build.MODEL);
        r.put("brand", android.os.Build.BRAND);
        return r;
    }

    private JSONObject doTap(JSONObject cmd) throws Exception {
        int x = cmd.getInt("x");
        int y = cmd.getInt("y");
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
            new GestureDescription.StrokeDescription(path, 0, 50);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        dispatchGesture(gesture, null, null);
        JSONObject r = new JSONObject();
        r.put("ok", true);
        r.put("action", "tap");
        r.put("x", x);
        r.put("y", y);
        return r;
    }

    private JSONObject doSwipe(JSONObject cmd) throws Exception {
        int x1 = cmd.getInt("x1"), y1 = cmd.getInt("y1");
        int x2 = cmd.getInt("x2"), y2 = cmd.getInt("y2");
        int dur = cmd.optInt("duration", 300);
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        GestureDescription.StrokeDescription stroke =
            new GestureDescription.StrokeDescription(path, 0, dur);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        dispatchGesture(gesture, null, null);
        JSONObject r = new JSONObject();
        r.put("ok", true);
        r.put("action", "swipe");
        return r;
    }

    private JSONObject doDrag(JSONObject cmd) throws Exception {
        int x1 = cmd.getInt("x1"), y1 = cmd.getInt("y1");
        int x2 = cmd.getInt("x2"), y2 = cmd.getInt("y2");
        int dur = cmd.optInt("duration", 1000);
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        GestureDescription.StrokeDescription stroke =
            new GestureDescription.StrokeDescription(path, 100, dur);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        dispatchGesture(gesture, null, null);
        JSONObject r = new JSONObject();
        r.put("ok", true);
        r.put("action", "drag");
        return r;
    }

    private JSONObject doType(JSONObject cmd) throws Exception {
        String text = cmd.getString("text");
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused != null) {
                android.os.Bundle args = new android.os.Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                focused.recycle();
            }
            root.recycle();
        }
        JSONObject r = new JSONObject();
        r.put("ok", true);
        r.put("action", "type");
        return r;
    }

    private JSONObject doKey(int globalAction) throws Exception {
        performGlobalAction(globalAction);
        JSONObject r = new JSONObject();
        r.put("ok", true);
        r.put("action", "key");
        return r;
    }

    private JSONObject doScreen(JSONObject cmd) throws Exception {
        boolean annotate = cmd.optBoolean("annotate", false);
        AccessibilityNodeInfo root = getRootInActiveWindow();
        JSONObject r = new JSONObject();
        if (root == null) {
            r.put("ok", false);
            r.put("error", "no active window");
            return r;
        }
        StringBuilder sb = new StringBuilder();
        List<NodeEntry> nodes = new ArrayList<>();
        collectNodes(root, sb, nodes, 0);
        r.put("ok", true);
        r.put("screen", sb.toString());
        if (annotate) {
            JSONArray arr = new JSONArray();
            for (NodeEntry n : nodes) {
                JSONObject node = new JSONObject();
                node.put("id", n.id);
                node.put("text", n.text);
                node.put("clickable", n.clickable);
                node.put("bounds", n.bounds);
                arr.put(node);
            }
            r.put("nodes", arr);
        }
        return r;
    }

    private JSONObject doScreenshot(JSONObject cmd) throws Exception {
        boolean annotate = cmd.optBoolean("annotate", false);
        JSONObject r = new JSONObject();

        if (android.os.Build.VERSION.SDK_INT < 30) {
            r.put("ok", false);
            r.put("error", "screenshot requires Android 11+ (API 30)");
            return r;
        }

        CountDownLatch latch = new CountDownLatch(1);
        screenshotLatch = latch;
        pendingScreenshot.set(null);

        takeScreenshot(android.view.Display.DEFAULT_DISPLAY,
            getMainExecutor(),
            new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult result) {
                    android.hardware.HardwareBuffer hb = result.getHardwareBuffer();
                    Bitmap bmp = Bitmap.wrapHardwareBuffer(hb, null);
                    hb.close();
                    if (bmp != null) {
                        Bitmap soft = bmp.copy(Bitmap.Config.ARGB_8888, false);
                        bmp.recycle();
                        pendingScreenshot.set(soft);
                    }
                    latch.countDown();
                }
                @Override
                public void onFailure(int errorCode) {
                    latch.countDown();
                }
            });

        boolean got = latch.await(5, TimeUnit.SECONDS);
        Bitmap bmp = pendingScreenshot.getAndSet(null);

        if (!got || bmp == null) {
            r.put("ok", false);
            r.put("error", "screenshot capture failed or timed out");
            return r;
        }

        Bitmap resized = resizeBitmap(bmp, SCREENSHOT_MAX_W, SCREENSHOT_MAX_H);
        if (resized != bmp) bmp.recycle();

        if (annotate) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                List<NodeEntry> nodes = new ArrayList<>();
                collectNodes(root, new StringBuilder(), nodes, 0);
                resized = annotateScreenshot(resized, nodes);
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        resized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos);
        String b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
        int w = resized.getWidth(), h = resized.getHeight();
        resized.recycle();

        r.put("ok", true);
        r.put("image", b64);
        r.put("width", w);
        r.put("height", h);
        r.put("encoding", "jpeg/base64");
        return r;
    }

    private Bitmap annotateScreenshot(Bitmap src, List<NodeEntry> nodes) {
        Bitmap copy = src.copy(Bitmap.Config.ARGB_8888, true);
        src.recycle();
        if (nodes.isEmpty()) return copy;

        Canvas canvas = new Canvas(copy);
        float density = copy.getWidth() / 360f;

        Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boxPaint.setColor(Color.RED);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(2f * density);
        boxPaint.setPathEffect(new DashPathEffect(
            new float[]{6f * density, 3f * density}, 0f));

        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(Color.argb(180, 255, 0, 0));
        bgPaint.setStyle(Paint.Style.FILL);

        Paint txtPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        txtPaint.setColor(Color.WHITE);
        txtPaint.setTextSize(10f * density);
        txtPaint.setTypeface(Typeface.DEFAULT_BOLD);

        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;
        float scaleX = (float) copy.getWidth() / screenW;
        float scaleY = (float) copy.getHeight() / screenH;

        for (NodeEntry node : nodes) {
            if (node.rect == null) continue;
            float l  = node.rect.left   * scaleX;
            float t  = node.rect.top    * scaleY;
            float ri = node.rect.right  * scaleX;
            float b  = node.rect.bottom * scaleY;
            if (ri <= l || b <= t) continue;
            canvas.drawRect(l, t, ri, b, boxPaint);
            String label = String.valueOf(node.id);
            float tw = txtPaint.measureText(label);
            float th = txtPaint.getTextSize();
            float pad = 2f * density;
            float ly = t - th - pad * 2;
            if (ly < 0) ly = 0;
            canvas.drawRoundRect(new RectF(l, ly, l + tw + pad * 2, ly + th + pad * 2), pad, pad, bgPaint);
            canvas.drawText(label, l + pad, ly + th + pad, txtPaint);
        }
        return copy;
    }

    private JSONObject doLaunch(JSONObject cmd) throws Exception {
        String pkg = cmd.getString("package");
        Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
        JSONObject r = new JSONObject();
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            r.put("ok", true);
        } else {
            r.put("ok", false);
            r.put("error", "package not found: " + pkg);
        }
        return r;
    }

    private JSONObject doOpenUrl(JSONObject cmd) throws Exception {
        String url = cmd.getString("url");
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        JSONObject r = new JSONObject();
        r.put("ok", true);
        r.put("url", url);
        return r;
    }

    private JSONObject doClickText(JSONObject cmd) throws Exception {
        String text = cmd.getString("text");
        AccessibilityNodeInfo root = getRootInActiveWindow();
        boolean found = findAndClick(root, text);
        JSONObject r = new JSONObject();
        r.put("ok", found);
        if (!found) r.put("error", "text not found: " + text);
        return r;
    }

    private boolean findAndClick(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        if ((t != null && t.toString().contains(text)) ||
            (d != null && d.toString().contains(text))) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            node.recycle();
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (findAndClick(node.getChild(i), text)) {
                node.recycle();
                return true;
            }
        }
        node.recycle();
        return false;
    }

    private JSONObject doScrollDir(JSONObject cmd, boolean up) throws Exception {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        JSONObject r = new JSONObject();
        if (root == null) {
            r.put("ok", false);
            r.put("error", "no window");
            return r;
        }
        boolean done = findAndScroll(root, up);
        r.put("ok", done);
        return r;
    }

    private boolean findAndScroll(AccessibilityNodeInfo node, boolean up) {
        if (node == null) return false;
        if (node.isScrollable()) {
            int action = up ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                            : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
            if (node.performAction(action)) {
                node.recycle();
                return true;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (findAndScroll(node.getChild(i), up)) {
                node.recycle();
                return true;
            }
        }
        node.recycle();
        return false;
    }

    private JSONObject doShell(JSONObject cmd) throws Exception {
        String command = cmd.getString("command");
        Process proc = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
        byte[] out = proc.getInputStream().readAllBytes();
        byte[] err = proc.getErrorStream().readAllBytes();
        int exit = proc.waitFor();
        JSONObject r = new JSONObject();
        r.put("ok", exit == 0);
        r.put("stdout", new String(out).trim());
        r.put("stderr", new String(err).trim());
        r.put("exit", exit);
        return r;
    }

    private JSONObject doClipboardGet() throws Exception {
        final String[] result = {""};
        final CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm.hasPrimaryClip() && cm.getPrimaryClip() != null) {
                    ClipData.Item item = cm.getPrimaryClip().getItemAt(0);
                    if (item != null && item.getText() != null)
                        result[0] = item.getText().toString();
                }
            } catch (Exception ignored) {}
            latch.countDown();
        });
        latch.await(2, TimeUnit.SECONDS);
        JSONObject r = new JSONObject();
        r.put("ok", true);
        r.put("text", result[0]);
        return r;
    }

    private JSONObject doClipboardSet(JSONObject cmd) throws Exception {
        String text = cmd.getString("text");
        final CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("pantheon", text));
            } catch (Exception ignored) {}
            latch.countDown();
        });
        latch.await(2, TimeUnit.SECONDS);
        JSONObject r = new JSONObject();
        r.put("ok", true);
        r.put("action", "clipboard_set");
        return r;
    }

    private JSONObject doVolume(boolean up) throws Exception {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
            up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI);
        JSONObject r = new JSONObject();
        r.put("ok", true);
        r.put("direction", up ? "up" : "down");
        return r;
    }

    private JSONObject doBrightness(JSONObject cmd) throws Exception {
        int level = cmd.optInt("level", 128);
        JSONObject r = new JSONObject();
        try {
            Settings.System.putInt(getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, level);
            r.put("ok", true);
            r.put("level", level);
        } catch (Exception e) {
            r.put("ok", false);
            r.put("error", "WRITE_SETTINGS permission required: " + e.getMessage());
        }
        return r;
    }

    // ─── HELPERS ─────────────────────────────────────────────────

    private static class NodeEntry {
        int id;
        String text;
        boolean clickable;
        String bounds;
        Rect rect;
    }

    private void collectNodes(AccessibilityNodeInfo node, StringBuilder sb,
                              List<NodeEntry> nodes, int depth) {
        if (node == null) return;
        String indent = "  ".repeat(depth);
        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        String txt = (t != null) ? t.toString() : (d != null ? d.toString() : "");

        if (!txt.isEmpty() || node.isClickable()) {
            NodeEntry entry = new NodeEntry();
            entry.id = nodeCounter++;
            entry.text = txt;
            entry.clickable = node.isClickable();
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            entry.rect = bounds;
            entry.bounds = bounds.left + "," + bounds.top + "," +
                           bounds.right + "," + bounds.bottom;
            nodes.add(entry);
            if (!txt.isEmpty()) {
                sb.append(indent).append("[").append(entry.id).append("] ")
                  .append(txt).append("\n");
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            collectNodes(node.getChild(i), sb, nodes, depth + 1);
        }
        node.recycle();
    }

    private Bitmap resizeBitmap(Bitmap src, int maxW, int maxH) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= maxW && h <= maxH) return src;
        float scale = Math.min((float) maxW / w, (float) maxH / h);
        return Bitmap.createScaledBitmap(src, (int)(w * scale), (int)(h * scale), true);
    }
}
