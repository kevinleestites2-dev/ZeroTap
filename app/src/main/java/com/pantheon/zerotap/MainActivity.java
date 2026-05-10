package com.pantheon.zerotap;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView tv = new TextView(this);
        tv.setText(
            "ZeroTap — Ghost Operator\n\n" +
            "HTTP Server: localhost:7474\n\n" +
            "Endpoints:\n" +
            "  GET  /ping\n" +
            "  POST /tap    {x, y}\n" +
            "  POST /swipe  {x1,y1,x2,y2}\n" +
            "  POST /type   {text}\n" +
            "  GET  /back\n" +
            "  GET  /home\n" +
            "  GET  /screen\n\n" +
            "Enable Accessibility Service to activate."
        );
        tv.setPadding(40, 80, 40, 40);
        tv.setTextSize(16);

        setContentView(tv);

        // Open accessibility settings so user can enable it
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        startActivity(intent);
    }
}
