package tk.glucodata;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Non-exported notification target that validates an exact live alarm token
 * before routing to the shared graph launcher.
 *
 * <p>An Activity is deliberately used as the notification PendingIntent
 * target: Android 12+ blocks notification trampolines that start an Activity
 * from a BroadcastReceiver or Service.</p>
 */
public final class CriticalGlucoseAlarmOpenGraphActivity extends Activity {
    static final String ACTION_OPEN_GRAPH =
            "tk.glucodata.critical_alarm.OPEN_GRAPH";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = getIntent();
        if (intent != null && ACTION_OPEN_GRAPH.equals(intent.getAction())) {
            String token = intent.getStringExtra(
                    CriticalGlucoseAlarmReceiver.EXTRA_TOKEN);
            CriticalGlucoseAlarmOverlay.launchGraph(this, token);
        }
        finish();
    }
}
