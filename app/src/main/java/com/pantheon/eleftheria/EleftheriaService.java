package com.pantheon.eleftheria;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class EleftheriaService extends AccessibilityService {

    private static final String TAG = "EleftheriaPrime";
    private static final int LOCAL_PORT = 7474;
    private static final String RELAY_URL = "https://nexus-relay-production.up.railway.app";
    private static final String RELAY_SECRET = "pantheon_prime";
    private static final int POLL_INTERVAL_MS = 3000;

    private ServerSocket serverSocket;
    private Thread serverThread;
    private Thread relayPollThread;
    private static EleftheriaService instance;
    private volatile boolean running = true;

    @Override
    public void onServiceConnected() {
        instance = this;
        running = true;
        Log.d(TAG, "EleftheriaPrime CONNECTED — Freedom is ACTIVE. Ghost Operator: ONLINE.");
        startLocalHttpServer();
        startRelayPoller();
    }

    // ─── LOCAL HTTP SERVER (for LAN / direct use) ─────────────────
    private void startLocalHttpServer() {
        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(LOCAL_PORT);
                Log.d(TAG, "Local HTTP server on port " + LOCAL_PORT);
                while (!serverSocket.isClosed() && running) {
                    Socket client = serverSocket.accept();
                    new Thread(() -> handleLocalClient(client)).start();
                }
            } catch (Exception e) {
                Log.e(TAG, "Server error: " + e.getMessage());
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }

    // ─── NEXUS RELAY POLLER (no Termux, no tunnel) ───────────────
    private void startRelayPoller() {
        relayPollThread = new Thread(() -> {
            Log.d(TAG, "Relay poller started → " + RELAY_URL);
            while (running) {
                try {
                    // Poll for pending command
                    URL url = new URL(RELAY_URL + "/poll");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("X-Secret", RELAY_SECRET);
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);

                    int code = conn.getResponseCode();
                    if (code == 200) {
                        BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream()));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) sb.append(line);
                        reader.close();

                        String body = sb.toString().trim();
                        if (!body.isEmpty() && !body.equals("{}") && !body.equals("null")) {
                            Log.d(TAG, "Command received: " + body);
                            String commandId = null;
                            try {
                                JSONObject cmdObj = new JSONObject(body);
                                commandId = cmdObj.optString("_id", null);
                            } catch (Exception ignored) {}
                            String result = executeCommand(body);
                            postResult(commandId, result);
                        }
                    }
                    conn.disconnect();
                } catch (Exception e) {
                    Log.e(TAG, "Relay poll error: " + e.getMessage());
                }

                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        });
        relayPollThread.setDaemon(true);
        relayPollThread.start();
    }

    private void postResult(String id, String result) {
        try {
            URL url = new URL(RELAY_URL + "/result");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-Secret", RELAY_SECRET);
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);

            String idPart = (id != null) ? ",\"_id\":" + JSONObject.quote(id) : "";
            String payload = "{\"result\":" + JSONObject.quote(result) + idPart + "}";
            OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream());
            writer.write(payload);
            writer.flush();
            writer.close();

            conn.getResponseCode();
            conn.disconnect();
        } catch (Exception e) {
            Log.e(TAG, "Post result error: " + e.getMessage());
        }
    }

    // ─── LOCAL CLIENT HANDLER ─────────────────────────────────────
    private void handleLocalClient(Socket client) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            StringBuilder requestBuilder = new StringBuilder();
            String line;
            int contentLength = 0;

            while ((line = in.readLine()) != null && !line.isEmpty()) {
                requestBuilder.append(line).append("\n");
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.split(":")[1].trim());
                }
            }

            StringBuilder body = new StringBuilder();
            if (contentLength > 0) {
                char[] buf = new char[contentLength];
                in.read(buf, 0, contentLength);
                body.append(buf);
            }

            String response = executeCommand(body.toString());

            OutputStream out = client.getOutputStream();
            String httpResponse = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Content-Length: " + response.getBytes(StandardCharsets.UTF_8).length + "\r\n" +
                    "\r\n" + response;
            out.write(httpResponse.getBytes(StandardCharsets.UTF_8));
            out.flush();
            client.close();
        } catch (Exception e) {
            Log.e(TAG, "Client error: " + e.getMessage());
        }
    }

    // ─── COMMAND EXECUTOR ─────────────────────────────────────────
    private String executeCommand(String body) {
        try {
            if (body == null || body.isEmpty()) return "{\"status\":\"empty\"}";
            JSONObject cmd = new JSONObject(body);
            String action = cmd.optString("action", "");

            switch (action) {
                case "ping":
                    return "{\"status\":\"ok\",\"agent\":\"EleftheriaPrime\",\"version\":\"1.0.0\"}";

                case "tap": {
                    float x = (float) cmd.getDouble("x");
                    float y = (float) cmd.getDouble("y");
                    performTap(x, y);
                    return "{\"status\":\"ok\",\"action\":\"tap\",\"x\":" + x + ",\"y\":" + y + "}";
                }

                case "swipe": {
                    float x1 = (float) cmd.getDouble("x1");
                    float y1 = (float) cmd.getDouble("y1");
                    float x2 = (float) cmd.getDouble("x2");
                    float y2 = (float) cmd.getDouble("y2");
                    int duration = cmd.optInt("duration", 300);
                    performSwipe(x1, y1, x2, y2, duration);
                    return "{\"status\":\"ok\",\"action\":\"swipe\"}";
                }

                case "type": {
                    String text = cmd.getString("text");
                    typeText(text);
                    return "{\"status\":\"ok\",\"action\":\"type\",\"text\":" + JSONObject.quote(text) + "}";
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

                case "screen":
                    return "{\"status\":\"ok\",\"screen\":" + JSONObject.quote(dumpScreen()) + "}";

                case "launch": {
                    String pkg = cmd.getString("package");
                    launchApp(pkg);
                    return "{\"status\":\"ok\",\"action\":\"launch\",\"package\":" + JSONObject.quote(pkg) + "}";
                }

                case "click_text": {
                    String target = cmd.getString("text");
                    boolean found = clickNodeWithText(target);
                    return "{\"status\":\"" + (found ? "ok" : "not_found") + "\",\"action\":\"click_text\",\"text\":" + JSONObject.quote(target) + "}";
                }

                default:
                    return "{\"status\":\"unknown_action\",\"action\":" + JSONObject.quote(action) + "}";
            }
        } catch (Exception e) {
            return "{\"status\":\"error\",\"message\":" + JSONObject.quote(e.getMessage()) + "}";
        }
    }

    // ─── ACTIONS ──────────────────────────────────────────────────
    private void performTap(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
            new GestureDescription.StrokeDescription(path, 0, 50);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        dispatchGesture(gesture, null, null);
    }

    private void performSwipe(float x1, float y1, float x2, float y2, int duration) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        GestureDescription.StrokeDescription stroke =
            new GestureDescription.StrokeDescription(path, 0, duration);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        dispatchGesture(gesture, null, null);
    }

    private void typeText(String text) {
        AccessibilityNodeInfo focus = findFocusedNode();
        if (focus != null) {
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            focus.recycle();
        }
    }

    private void launchApp(String packageName) {
        try {
            android.content.Intent intent = getPackageManager()
                .getLaunchIntentForPackage(packageName);
            if (intent != null) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                getApplicationContext().startActivity(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Launch error: " + e.getMessage());
        }
    }

    private boolean clickNodeWithText(String text) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            return findAndClick(root, text);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean findAndClick(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        CharSequence nodeText = node.getText();
        CharSequence nodeDesc = node.getContentDescription();
        if ((nodeText != null && nodeText.toString().equalsIgnoreCase(text)) ||
            (nodeDesc != null && nodeDesc.toString().equalsIgnoreCase(text))) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            node.recycle();
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (findAndClick(node.getChild(i), text)) return true;
        }
        return false;
    }

    private AccessibilityNodeInfo findFocusedNode() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
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
        String indent = new String(new char[depth * 2]).replace('\0', ' ');
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        String cls = node.getClassName() != null ? node.getClassName().toString() : "";
        if (text != null && text.length() > 0)
            sb.append(indent).append("[TEXT] ").append(text).append("\n");
        if (desc != null && desc.length() > 0)
            sb.append(indent).append("[DESC] ").append(desc).append("\n");
        for (int i = 0; i < node.getChildCount(); i++) {
            dumpNode(node.getChild(i), sb, depth + 1);
        }
    }

    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getApplicationContext().startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "openUrl error: " + e.getMessage());
        }
    }

    // ─── LIFECYCLE ────────────────────────────────────────────────
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {
        Log.d(TAG, "EleftheriaPrime interrupted");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception e) {}
        Log.d(TAG, "EleftheriaPrime destroyed");
    }
}

