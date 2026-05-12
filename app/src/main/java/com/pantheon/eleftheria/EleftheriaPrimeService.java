package com.pantheon.eleftheria;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class EleftheriaPrimeService extends AccessibilityService {

    private static final String TAG      = "EleftheriaPrime";
    private static final String RELAY_WS = "wss://nexus-relay-production.up.railway.app/ws";
    private static final String SECRET   = "pantheon_prime";
    private static final String VERSION  = "2.0.0";

    private OkHttpClient httpClient;
    private WebSocket webSocket;
    private volatile boolean running = false;
    private final Handler reconnectHandler = new Handler(Looper.getMainLooper());

    private static EleftheriaPrimeService instance;
    public static EleftheriaPrimeService getInstance() { return instance; }

    // ─── LIFECYCLE ───────────────────────────────────────────────

    @Override
    public void onServiceConnected() {
        instance = this;
        running = true;
        Log.d(TAG, "EleftheriaPrime v" + VERSION + " — Ghost Operator ACTIVE");
        httpClient = new OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build();
        connectWebSocket();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() { Log.d(TAG, "Interrupted"); }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
        if (webSocket != null) webSocket.cancel();
        Log.d(TAG, "Destroyed");
    }

    // ─── WEBSOCKET ───────────────────────────────────────────────

    private void connectWebSocket() {
        if (!running) return;
        Log.d(TAG, "Connecting WS: " + RELAY_WS);
        Request req = new Request.Builder()
            .url(RELAY_WS)
            .header("X-Secret", SECRET)
            .header("X-Agent", "EleftheriaPrime")
            .header("X-Version", VERSION)
            .build();

        webSocket = httpClient.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                Log.d(TAG, "WS OPEN");
                ws.send("{\"type\":\"register\",\"agent\":\"EleftheriaPrime\",\"version\":\"" + VERSION + "\"}");
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                new Thread(() -> {
                    try {
                        JSONObject cmd = new JSONObject(text);
                        String id = cmd.optString("_id", "");
                        String result = processCommand(cmd);
                        JSONObject res = new JSONObject(result);
                        if (!id.isEmpty()) res.put("_id", id);
                        ws.send(res.toString());
                    } catch (Exception e) {
                        Log.e(TAG, "Msg error: " + e.getMessage());
                    }
                }).start();
            }

            @Override public void onMessage(WebSocket ws, ByteString bytes) {}

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                Log.e(TAG, "WS FAIL: " + t.getMessage());
                scheduleReconnect(5000);
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                Log.d(TAG, "WS CLOSED: " + reason);
                scheduleReconnect(3000);
            }
        });
    }

    private void scheduleReconnect(long delayMs) {
        if (!running) return;
        reconnectHandler.postDelayed(() -> { if (running) connectWebSocket(); }, delayMs);
    }

    // ─── COMMAND PROCESSOR ───────────────────────────────────────

    private String processCommand(JSONObject cmd) {
        try {
            String action = cmd.optString("action", cmd.optString("type", ""));
            switch (action) {

                case "ping":
                    return "{\"status\":\"ok\",\"agent\":\"EleftheriaPrime\",\"version\":\"" + VERSION + "\",\"transport\":\"websocket\"}";

                case "tap": {
                    int x = cmd.getInt("x"), y = cmd.getInt("y");
                    performTap(x, y);
                    return "{\"status\":\"ok\",\"action\":\"tap\",\"x\":" + x + ",\"y\":" + y + "}";
                }

                case "swipe": {
                    int x1 = cmd.getInt("x1"), y1 = cmd.getInt("y1");
                    int x2 = cmd.getInt("x2"), y2 = cmd.getInt("y2");
                    int dur = cmd.optInt("duration", 300);
                    performSwipe(x1, y1, x2, y2, dur);
                    return "{\"status\":\"ok\",\"action\":\"swipe\"}";
                }

                case "type": {
                    typeText(cmd.getString("text"));
                    return "{\"status\":\"ok\",\"action\":\"type\"}";
                }

                case "back":
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    return "{\"status\":\"ok\",\"action\":\"back\"}";

                case "home":
                    performGlobalAction(GLOBAL_ACTION_HOME);
                    return "{\"status\":\"ok\",\"action\":\"home\"}";

                case "recents":
                    performGlobalAction(GLOBAL_ACTION_RECENTS);
                    return "{\"status\":\"ok\",\"action\":\"recents\"}";

                case "screen": {
                    String screen = dumpScreen();
                    return "{\"status\":\"ok\",\"screen\":\"" + screen.replace("\"","'").replace("\n","\\n") + "\"}";
                }

                case "open_url": {
                    String urlStr = cmd.getString("url");
                    openUrl(urlStr);
                    return "{\"status\":\"ok\",\"action\":\"open_url\",\"url\":\"" + urlStr + "\"}";
                }

                case "launch": {
                    String pkg = cmd.getString("package");
                    launchApp(pkg);
                    return "{\"status\":\"ok\",\"action\":\"launch\",\"package\":\"" + pkg + "\"}";
                }

                case "click_text": {
                    String text = cmd.getString("text");
                    boolean found = clickNodeByText(text);
                    return "{\"status\":\"" + (found ? "ok" : "not_found") + "\",\"action\":\"click_text\",\"text\":\"" + text + "\"}";
                }

                case "scroll_down":
                    performSwipe(540, 1400, 540, 400, 400);
                    return "{\"status\":\"ok\",\"action\":\"scroll_down\"}";

                case "scroll_up":
                    performSwipe(540, 400, 540, 1400, 400);
                    return "{\"status\":\"ok\",\"action\":\"scroll_up\"}";

                case "shell": {
                    String shellCmd = cmd.optString("cmd", "echo no_cmd");
                    try {
                        Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", shellCmd});
                        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line).append("\\n");
                        p.waitFor();
                        return "{\"status\":\"ok\",\"output\":\"" + sb.toString().trim().replace("\"","'") + "\"}";
                    } catch (Exception e) {
                        return "{\"status\":\"error\",\"output\":\"" + e.getMessage() + "\"}";
                    }
                }

                case "info":
                    return "{\"status\":\"ok\",\"version\":\"" + VERSION + "\",\"transport\":\"websocket\"}";

                default:
                    return "{\"status\":\"unknown_action\",\"action\":\"" + action + "\"}";
            }
        } catch (Exception e) {
            return "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }

    // ─── ACTIONS ─────────────────────────────────────────────────

    private void openUrl(String urlStr) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(urlStr));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getApplicationContext().startActivity(intent);
            } catch (Exception e) { Log.e(TAG, "open_url: " + e.getMessage()); }
        });
    }

    private void launchApp(String packageName) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                }
            } catch (Exception e) { Log.e(TAG, "launch: " + e.getMessage()); }
        });
    }

    private boolean clickNodeByText(String text) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            java.util.List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
            if (nodes != null && !nodes.isEmpty()) {
                nodes.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                root.recycle();
                return true;
            }
            root.recycle();
        } catch (Exception e) { Log.e(TAG, "click_text: " + e.getMessage()); }
        return false;
    }

    private void performTap(int x, int y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription s = new GestureDescription.StrokeDescription(p, 0, 50);
        dispatchGesture(new GestureDescription.Builder().addStroke(s).build(), null, null);
    }

    private void performSwipe(int x1, int y1, int x2, int y2, int duration) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        GestureDescription.StrokeDescription s = new GestureDescription.StrokeDescription(p, 0, duration);
        dispatchGesture(new GestureDescription.Builder().addStroke(s).build(), null, null);
    }

    private void typeText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused != null) {
            android.os.Bundle args = new android.os.Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            focused.recycle();
        }
        root.recycle();
    }

    private String dumpScreen() {
        StringBuilder sb = new StringBuilder();
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                dumpNode(root, sb, 0);
                root.recycle();
            }
        } catch (Exception e) {
            sb.append("error: ").append(e.getMessage());
        }
        return sb.toString();
    }

    private void dumpNode(AccessibilityNodeInfo node, StringBuilder sb, int depth) {
        if (node == null) return;
        String indent = "  ".repeat(depth);
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        if (text != null && text.length() > 0) sb.append(indent).append("[T] ").append(text).append("\n");
        if (desc != null && desc.length() > 0) sb.append(indent).append("[D] ").append(desc).append("\n");
        for (int i = 0; i < node.getChildCount(); i++) dumpNode(node.getChild(i), sb, depth + 1);
    }
}
