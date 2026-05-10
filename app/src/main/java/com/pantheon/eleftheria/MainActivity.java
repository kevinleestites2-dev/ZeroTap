package com.pantheon.eleftheria;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.Color;
import android.view.Gravity;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.parseColor("#0a0a0a"));
        layout.setPadding(60, 100, 60, 60);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("⚡ EleftheriaPrime");
        title.setTextColor(Color.parseColor("#FFD700"));
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 20);

        TextView subtitle = new TextView(this);
        subtitle.setText("Freedom. No Termux. No Limits.");
        subtitle.setTextColor(Color.parseColor("#9B59B6"));
        subtitle.setTextSize(14);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 0, 0, 60);

        TextView status = new TextView(this);
        status.setText(
            "STATUS\n\n" +
            "Local Server:  port 7474\n" +
            "Relay:         Nexus Relay (Railway)\n" +
            "Poll Interval: 3 seconds\n\n" +
            "COMMANDS\n\n" +
            "  ping          — check status\n" +
            "  tap           — tap x,y\n" +
            "  swipe         — swipe gesture\n" +
            "  type          — type text\n" +
            "  back          — back button\n" +
            "  home          — home button\n" +
            "  recents       — recent apps\n" +
            "  screen        — dump screen text\n" +
            "  launch        — open app by package\n" +
            "  click_text    — tap element by text\n\n" +
            "Enable Accessibility Service to activate\nGhost Operator mode."
        );
        status.setTextColor(Color.parseColor("#E0E0E0"));
        status.setTextSize(13);
        status.setLineSpacing(6, 1);

        layout.addView(title);
        layout.addView(subtitle);
        layout.addView(status);
        setContentView(layout);

        // Auto-open accessibility settings
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        startActivity(intent);
    }
}
