package com.pantheon.eleftheria;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d("EleftheriaPrime", "Boot complete — EleftheriaPrime standing by.");
            // Accessibility Service restarts automatically if enabled by user
            // No manual restart needed — Android handles it
        }
    }
}
