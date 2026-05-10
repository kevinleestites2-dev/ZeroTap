package com.pantheon.zerotap;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class ZeroTapService extends AccessibilityService {

    private static final String TAG = "ZeroTap";
    private static final int PORT = 7474;
    private ServerSocket serverSocket;
    private Thread serverThread;
    private static ZeroTapService instance;

    @Override
    public void onServiceConnected() {
        instance = this;
        Log.d(TAG, "ZeroTap Accessibility Service CONNECTED — Ghost Operator ACTIVE");
        startHttpServer();
    }

    // ─── HTTP SERVER ──────────────────────────────────────────────
    private void startHttpServer() {
        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                Log.d(TAG, "ZeroTap HTTP server listening on port " + PORT);
                while (!serverSocket.isClosed()) {
                    Socket client = serverSocket.accept();
                    new Thread(() -> handleClient(client)).start();
                }
            } catch (Exception e) {
                Log.e(TAG, "Server error: " + e.getMessage());
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private void handleClient(Socket client) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            StringBuilder requestBuilder = new StringBuilder();
            String line;
            int contentLength = 0;

            // Read headers
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                requestBuilder.append(line).append("\n");
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.split(":")[1].trim());
                }
            }

            // Read body
            StringBuilder body = new StringBuilder();
            if (contentLength > 0) {
                char[] buf = new char[contentLength];
                in.read(buf, 0, contentLength);
                body.append(buf);
            }

            String request = requestBuilder.toString();
            String response = processCommand(request, body.toString());

            // Send HTTP response
            OutputStream out = client.getOutputStream();
            String httpResponse = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Content-Length: " + response.getBytes(StandardCharsets.UTF_8).length + "\r\n" +
                    "\r\n" +
                    response;
            out.write(httpResponse.getBytes(StandardCharsets.UTF_8));
            out.flush();
            client.close();
        } catch (Exception e) {
            Log.e(TAG, "Client error: " + e.getMessage());
        }
    }

    // ─── COMMAND PROCESSOR ───────────────────────────────────────
    private String processCommand(String headers, String body) {
        try {
            // Route by URL path
            String path = "/";
            for (String line : headers.split("\n")) {
                if (line.startsWith("GET") || line.startsWith("POST")) {
                    path = line.split(" ")[1];
                    break;
                }
            }

            if (path.equals("/ping")) {
                return "{\"status\":\"ok\",\"agent\":\"ZeroTap\",\"version\":\"1.0\",\"mode\":\"GhostOperator\"}";
            }

            if (path.equals("/tap") || path.startsWith("/tap")) {
                JSONObject json = new JSONObject(body);
                int x = json.getInt("x");
                int y = json.getInt("y");
                performTap(x, y);
                return "{\"status\":\"ok\",\"action\":\"tap\",\"x\":" + x + ",\"y\":" + y + "}";
            }

            if (path.equals("/swipe")) {
                JSONObject json = new JSONObject(body);
                int x1 = json.getInt("x1");
                int y1 = json.getInt("y1");
                int x2 = json.getInt("x2");
                int y2 = json.getInt("y2");
                int duration = json.optInt("duration", 300);
                performSwipe(x1, y1, x2, y2, duration);
                return "{\"status\":\"ok\",\"action\":\"swipe\"}";
            }

            if (path.equals("/type")) {
                JSONObject json = new JSONObject(body);
                String text = json.getString("text");
                typeText(text);
                return "{\"status\":\"ok\",\"action\":\"type\",\"text\":\"" + text + "\"}";
            }

            if (path.equals("/back")) {
                performGlobalAction(GLOBAL_ACTION_BACK);
                return "{\"status\":\"ok\",\"action\":\"back\"}";
            }

            if (path.equals("/home")) {
                performGlobalAction(GLOBAL_ACTION_HOME);
                return "{\"status\":\"ok\",\"action\":\"home\"}";
            }

            if (path.equals("/recents")) {
                performGlobalAction(GLOBAL_ACTION_RECENTS);
                return "{\"status\":\"ok\",\"action\":\"recents\"}";
            }

            if (path.equals("/screen")) {
                String screenText = dumpScreen();
                return "{\"status\":\"ok\",\"screen\":\"" + screenText.replace("\"", "'") + "\"}";
            }

            return "{\"status\":\"error\",\"message\":\"unknown command\"}";

        } catch (Exception e) {
            return "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }

    // ─── GESTURE ACTIONS ─────────────────────────────────────────
    private void performTap(int x, int y) {
        Path tapPath = new Path();
        tapPath.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(tapPath, 0, 50);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();
        dispatchGesture(gesture, null, null);
        Log.d(TAG, "TAP: " + x + "," + y);
    }

    private void performSwipe(int x1, int y1, int x2, int y2, int duration) {
        Path swipePath = new Path();
        swipePath.moveTo(x1, y1);
        swipePath.lineTo(x2, y2);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(swipePath, 0, duration);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();
        dispatchGesture(gesture, null, null);
        Log.d(TAG, "SWIPE: " + x1 + "," + y1 + " -> " + x2 + "," + y2);
    }

    private void typeText(String text) {
        // Find focused node and paste text
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
        for (int i = 0; i < node.getChildCount(); i++) {
            dumpNode(node.getChild(i), sb, depth + 1);
        }
    }

    // ─── LIFECYCLE ───────────────────────────────────────────────
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {
        Log.d(TAG, "ZeroTap interrupted");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception e) {}
        Log.d(TAG, "ZeroTap destroyed");
    }
}
