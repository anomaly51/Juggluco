package tk.glucodata;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Not exported. System replacement/boot broadcasts and a session-bound PendingIntent only. */
public final class AppUpdateReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || Applic.isWearable) return;
        if (AppUpdates.RESULT.equals(intent.getAction())) {
            AppUpdates.result(context, intent);
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())
                || Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            AppUpdates.schedule(context);
            if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
                try {
                    AppUpdates.reconcile(context);
                    Applic.possiblybluetooth(context);
                    NumAlarm.handlealarm((Applic) context.getApplicationContext());
                } catch (Throwable error) {
                    AppUpdates.notice(context, R.string.app_update_reopen,
                            new Intent(context, MainActivity.class));
                }
            }
        }
    }
}

