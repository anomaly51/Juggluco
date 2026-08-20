package tk.glucodata;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Explicit, non-exported action boundary for critical alarm sessions. */
public final class CriticalGlucoseAlarmReceiver extends BroadcastReceiver {
    static final String ACTION_ACK =
            "tk.glucodata.critical_alarm.ACK";
    static final String ACTION_SNOOZE =
            "tk.glucodata.critical_alarm.SNOOZE";
    static final String ACTION_RESUME =
            "tk.glucodata.critical_alarm.RESUME";
    static final String ACTION_EXPIRE =
            "tk.glucodata.critical_alarm.EXPIRE";
    static final String EXTRA_TOKEN =
            "tk.glucodata.critical_alarm.TOKEN";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String token = intent.getStringExtra(EXTRA_TOKEN);
        if (token == null || token.isEmpty()) return;
        String action = intent.getAction();
        if (ACTION_ACK.equals(action)) {
            CriticalGlucoseAlarm.acknowledge(context, token);
        } else if (ACTION_SNOOZE.equals(action)) {
            CriticalGlucoseAlarm.snooze(context, token, 5L * 60_000L);
        } else if (ACTION_RESUME.equals(action)) {
            CriticalGlucoseAlarm.resume(context, token);
        } else if (ACTION_EXPIRE.equals(action)) {
            CriticalGlucoseAlarm.expire(context, token);
        }
    }
}
