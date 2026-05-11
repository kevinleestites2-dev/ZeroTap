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
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class EleftheriaPrimeService extends AccessibilityService {

    private static final String TAG     = "EleftheriaPrime";
    private static final int    PORT    = 7474;
    private static final String RELAY   = "https://nexus-relay-production.up.railway.app";
    private static final String SECRET  = "pantheon_prime";
    private static final int    POLL_MS = 2000;
    private static final String VERSION = "1.2";

    private ServerSocket serverSocket;
    private Thread       serverThread;
    private Thread       relayThread;
    private volatile boolean relayRunning = false;

    private static EleftheriaPrimeService instance;

    // ─── LIFECYCLE ───────────────────────────────────────────────

    @Override
    public void onServiceConnected() {
        instance = this;
        Log.d(TAG, "EleftheriaPrime v" + VERSION + " — Ghost Operator ACTIVE");
        startHttpServer();
        startRelayPoller();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {
        Log.d(TAG, "EleftheriaPrime interrupted");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        relayRunning = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        Log.d(TAG, "EleftheriaPrime destroyed");
    }

    // ─── LOCAL HTTP SERVER (port 7474) ───────────────────────────

    private void startHttpServer() {
        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                Log.d(TAG, "Local HTTP server on port " + PORT);
                while (!serverSocket.isClosed()) {
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

    private void handleLocalClient(Socket client) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            String line;
            int contentLength = 0;
            StringBuilder headers = new StringBuilder();
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                headers.append(line).append("\n");
                if (line.toLowerCase().startsWith("content-length:"))
                    contentLength = Integer.parseInt(line.split(":")[1].trim());
            }
            StringBuilder body = new StringBuilder();
            if (contentLength > 0) {
                char[] buf = new char[contentLength];
                in.read(buf, 0, contentLength);
                body.append(buf);
            }
            String response = processCommand(headers.toString(), body.toString());
            OutputStream out = client.getOutputStream();
            String http = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                    "Access-Control-Allow-Origin: *\r\nContent-Length: " +
                    response.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + response;
            out.write(http.getBytes(StandardCharsets.UTF_8));
            out.flush();
            client.close();
        } catch (Exception e) {
            Log.e(TAG, "Client error: " + e.getMessage());
        }
    }

    // ─── RAILWAY RELAY POLLER (NO TERMUX NEEDED) ─────────────────

    private void startRelayPoller() {
        relayRunning = true;
        relayThread = new Thread(() -> {
            Log.d(TAG, "Relay poller started -> " + RELAY);
            int errors = 0;
            while (relayRunning) {
                try {
                    JSONObject cmd = relayGet("/poll");
                    if (cmd == null || cmd.optString("status").equals("empty") || !cmd.has("_id")) {
                        Thread.sleep(POLL_MS);
                        errors = 0;
                        continue;
                    }
                    errors = 0;
                    String id = cmd.getString("_id");
                    Log.d(TAG, "Relay cmd [" + id + "]: " + cmd.toString().substring(0, Math.min(80, cmd.toString().length())));
                    String result = processRelayCommand(cmd);
                    JSONObject resultObj = new JSONObject(result);
                    resultObj.put("_id", id);
                    relayPost("/result", resultObj.toString());
                    Log.d(TAG, "Result posted for [" + id + "]");
                } catch (InterruptedException ie) {
                    break;
                } catch (Exception e) {
                    errors++;
                    long wait = Math.min(30000L, (long)(Math.pow(2, errors)) * 1000L);
                    Log.e(TAG, "Relay error: " + e.getMessage() + " retry in " + wait + "ms");
                    try { Thread.sleep(wait); } catch (InterruptedException ie) { break; }
                }
            }
            Log.d(TAG, "Relay poller stopped");
        });
        relayThread.setDaemon(true);
        relayThread.start();
    }

    private JSONObject relayGet(String path) throws Exception {
        URL url = new URL(RELAY + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("X-Secret", SECRET);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        if (conn.getResponseCode() != 200) return null;
        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        conn.disconnect();
        return new JSONObject(sb.toString());
    }

    private void relayPost(String path, String json) throws Exception {
        URL url = new URL(RELAY + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("X-Secret", SECRET);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
        conn.getInputStream().close();
        conn.disconnect();
    }

    // ─── UNIFIED COMMAND PROCESSOR ───────────────────────────────

    private String processRelayCommand(JSONObject cmd) {
        try {
            String action = cmd.optString("action", cmd.optString("type", ""));
            switch (action) {
                case "ping":
                    return "{\"status\":\"ok\",\"output\":\"pong\",\"agent\":\"EleftheriaPrime\",\"version\":\"" + VERSION + "\"}";

                case "tap": {
                    int x = cmd.getInt("x");
                    int y = cmd.getInt("y");
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
                    String text = cmd.getString("text");
                    typeText(text);
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
                    return "{\"status\":\"ok\",\"screen\":\"" + screen.replace("\"", "'") + "\"}";
                }

                case "open_url": {
                    String urlStr = cmd.getString("url");
                    openUrl(urlStr);
                    return "{\"status\":\"ok\",\"action\":\"open_url\",\"url\":\"" + urlStr + "\"}";
                }

                case "shell": {
                    String shellCmd = cmd.optString("cmd", "echo no_cmd");
                    try {
                        Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", shellCmd});
                        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line).append("\\n");
                        p.waitFor();
                        return "{\"status\":\"ok\",\"output\":\"" + sb.toString().trim().replace("\"", "'") + "\"}";
                    } catch (Exception e) {
                        return "{\"status\":\"error\",\"output\":\"" + e.getMessage() + "\"}";
                    }
                }

                case "info":
                    return "{\"status\":\"ok\",\"output\":\"EleftheriaPrime v" + VERSION + " | Ghost Operator ACTIVE | Termux-free\"}";

                default:
                    return "{\"status\":\"unknown_action\",\"action\":\"" + action + "\"}";
            }
        } catch (Exception e) {
            return "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }

    private String processCommand(String headers, String body) {
        try {
            String path = "/";
            for (String line : headers.split("\n")) {
                if (line.startsWith("GET") || line.startsWith("POST")) {
                    path = line.split(" ")[1];
                    break;
                }
            }
            JSONObject cmd = new JSONObject();
            if (!body.isEmpty()) {
                JSONObject bodyJson = new JSONObject(body);
                java.util.Iterator<String> keys = bodyJson.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    cmd.put(k, bodyJson.get(k));
                }
            }
            String action = path.replaceFirst("/", "").split("\\?")[0];
            cmd.put("action", action);
            return processRelayCommand(cmd);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }

    // ─── OPEN URL ────────────────────────────────────────────────

    private void openUrl(String urlStr) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(urlStr));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getApplicationContext().startActivity(intent);
                Log.d(TAG, "Opened URL: " + urlStr);
            } catch (Exception e) {
                Log.e(TAG, "open_url error: " + e.getMessage());
            }
        });
    }

    // ─── GESTURES ────────────────────────────────────────────────

    private void performTap(int x, int y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription s = new GestureDescription.StrokeDescription(p, 0, 50);
        dispatchGesture(new GestureDescription.Builder().addStroke(s).build(), null, null);
        Log.d(TAG, "TAP: " + x + "," + y);
    }

    private void performSwipe(int x1, int y1, int x2, int y2, int duration) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        GestureDescription.StrokeDescription s = new GestureDescription.StrokeDescription(p, 0, duration);
        dispatchGesture(new GestureDescription.Builder().addStroke(s).build(), null, null);
        Log.d(TAG, "SWIPE: " + x1 + "," + y1 + " -> " + x2 + "," + y2);
    }

    private void typeText(String text) {
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
        Log.d(TAG, "TYPE: " + text);
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
        if (text != null && text.length() > 0) sb.append(indent).append("[TEXT] ").append(text).append("\n");
        if (desc != null && desc.length() > 0) sb.append(indent).append("[DESC] ").append(desc).append("\n");
        for (int i = 0; i < node.getChildCount(); i++) dumpNode(node.getChild(i), sb, depth + 1);
    }
}
