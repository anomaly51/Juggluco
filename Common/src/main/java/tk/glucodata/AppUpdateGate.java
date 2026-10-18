package tk.glucodata;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;

/** UI-only release gate. Never stops the sensor service or consumes an alarm. */
final class AppUpdateGate {
    private AlertDialog dialog;
    private Activity owner;

    static boolean requiresUpdate(boolean internet, boolean confirmed, long target, long installed) {
        return internet && confirmed && target > installed;
    }

    static boolean canPresent(boolean required, boolean alarm, boolean taskOpen) {
        return required && !alarm && !taskOpen;
    }

    static boolean validated(NetworkCapabilities capabilities) {
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
    }

    static boolean internet(Context context) {
        // Android 5 has no validated-internet API. Fail open there, rather than
        // locking a glucose app just because it is connected to an offline Wi-Fi.
        if (Build.VERSION.SDK_INT < 23) return false;
        try {
            ConnectivityManager manager = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            return manager != null && validated(manager.getNetworkCapabilities(manager.getActiveNetwork()));
        } catch (RuntimeException unavailable) { return false; }
    }

    static boolean required(Context context) {
        return !Applic.isWearable && requiresUpdate(internet(context),
                AppUpdates.prefs(context).getBoolean("target_confirmed", false),
                AppUpdates.prefs(context).getLong("required_version", 0), BuildConfig.VERSION_CODE);
    }

    void render(Activity activity, boolean show) {
        if (!show || activity != owner) dismiss();
        if (!show || activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (dialog != null && dialog.isShowing()) return;
        owner = activity;
        dialog = AppUpdatePanel.showRequired(activity, () -> {});
    }

    void dismiss() {
        if (dialog != null) dialog.dismiss();
        dialog = null;
        owner = null;
    }

    boolean showing() { return dialog != null && dialog.isShowing(); }
}
