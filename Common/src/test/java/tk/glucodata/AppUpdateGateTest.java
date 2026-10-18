package tk.glucodata;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.app.job.JobScheduler;
import android.content.Context;
import android.net.NetworkCapabilities;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.time.Duration;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class AppUpdateGateTest {
    Context context;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        AppUpdates.prefs(context).edit().clear().commit();
    }

    @Test public void onlyConfirmedNewerReleaseWithInternetRequiresAnUpdate() {
        assertTrue(AppUpdateGate.requiresUpdate(true, true, 913, 912));
        assertFalse(AppUpdateGate.requiresUpdate(false, true, 913, 912));
        assertFalse(AppUpdateGate.requiresUpdate(true, false, 913, 912));
        assertFalse(AppUpdateGate.requiresUpdate(true, true, 912, 912));
        assertFalse(AppUpdateGate.requiresUpdate(true, true, 911, 912));
        assertFalse(AppUpdateGate.requiresUpdate(true, false, 0, 912));
    }

    @Test public void wifiAloneAndCaptivePortalsDoNotLockTheApp() {
        assertFalse(AppUpdateGate.validated(null));
        NetworkCapabilities wifi = new NetworkCapabilities();
        Shadows.shadowOf(wifi).addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        Shadows.shadowOf(wifi).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        assertFalse(AppUpdateGate.validated(wifi));
        Shadows.shadowOf(wifi).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        assertTrue(AppUpdateGate.validated(wifi));
        Shadows.shadowOf(wifi).addCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
        assertFalse(AppUpdateGate.validated(wifi));
        Shadows.shadowOf(wifi).removeCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
        Shadows.shadowOf(wifi).removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        assertFalse(AppUpdateGate.validated(wifi));
    }

    @Test public void alarmsAndUnfinishedTasksAlwaysTakePriority() {
        assertTrue(AppUpdateGate.canPresent(true, false, false));
        assertFalse(AppUpdateGate.canPresent(true, true, false));
        assertFalse(AppUpdateGate.canPresent(true, false, true));
        assertFalse(AppUpdateGate.canPresent(false, false, false));
    }

    @Test public void releaseWithdrawalClearsCachedGateAndDoesNotTouchHistory() throws Exception {
        AppUpdates.prefs(context).edit().putLong("observed_version", BuildConfig.VERSION_CODE).commit();
        AppUpdates.rememberRelease(context, AppUpdateReleaseTest.select(
                AppUpdateReleaseTest.feed(AppUpdateReleaseTest.build())));
        assertTrue(AppUpdates.prefs(context).getBoolean("target_confirmed", false));
        assertTrue(AppUpdates.prefs(context).getLong("required_version", 0) > 0);
        // A network failure changes only status, not confirmed release metadata.
        AppUpdates.state(context, "check_failed");
        assertTrue(AppUpdates.prefs(context).getBoolean("target_confirmed", false));
        AppUpdates.rememberRelease(context, null);
        assertFalse(AppUpdates.prefs(context).contains("required_version"));
        assertFalse(AppUpdates.prefs(context).contains("target_confirmed"));
        assertFalse(AppUpdates.prefs(context).contains("target"));
        assertEquals(BuildConfig.VERSION_CODE, AppUpdates.prefs(context).getLong("observed_version", 0));
    }

    @Test public void feedRefreshCannotRewriteAnExistingInstallerSessionTarget() throws Exception {
        AppUpdates.prefs(context).edit().putInt("session", 7).putLong("target", 901)
                .putString("target_name", "original").commit();
        AppUpdates.rememberRelease(context, AppUpdateReleaseTest.select(
                AppUpdateReleaseTest.feed(AppUpdateReleaseTest.build())));
        assertEquals(901, AppUpdates.prefs(context).getLong("target", 0));
        assertEquals("original", AppUpdates.prefs(context).getString("target_name", ""));
        AppUpdates.rememberRelease(context, null);
        assertEquals(901, AppUpdates.prefs(context).getLong("target", 0));
        assertEquals(7, AppUpdates.prefs(context).getInt("session", -1));
    }

    @Test public void legacyOptOutCannotSuppressBackgroundJobs() {
        AppUpdates.prefs(context).edit().putBoolean("enabled", false).commit();
        AppUpdates.schedule(context);
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        assertNotNull(scheduler.getPendingJob(AppUpdates.JOB));
        assertNotNull(scheduler.getPendingJob(AppUpdates.CONNECTED_JOB));
    }

    @Test public void requiredPanelHasNoDismissOrDisablePathButStillShowsProgress() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            activity.setTheme(R.style.AppTheme_ClinicalDark);
            AppUpdates.prefs(activity).edit().putString("state", "downloading").commit();
            AppUpdates.downloadedBytes = 25;
            AppUpdates.totalBytes = 100;
            AlertDialog dialog = AppUpdatePanel.showRequired(activity, () -> {});
            View root = dialog.getWindow().getDecorView();
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, dialog.getWindow().getAttributes().width);
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, dialog.getWindow().getAttributes().height);
            assertEquals(View.GONE, root.findViewWithTag("update_close").getVisibility());
            assertNull(root.findViewWithTag("update_automatic"));
            assertNull(root.findViewWithTag("update_auto_help"));
            assertFalse(root.findViewWithTag("update_action").isEnabled());
            assertTrue(((TextView) root.findViewWithTag("update_bytes")).getText().toString().contains("25%"));
            dialog.onBackPressed();
            assertTrue(dialog.isShowing());
            MotionEvent outside = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, -100, -100, 0);
            dialog.onTouchEvent(outside);
            outside.recycle();
            assertTrue(dialog.isShowing());
            AppUpdates.prefs(activity).edit().putString("state", "available").commit();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
            assertTrue(root.findViewWithTag("update_action").isEnabled());
            assertEquals(activity.getString(R.string.app_update_required_action),
                    ((TextView) root.findViewWithTag("update_action")).getText().toString());
            dialog.dismiss();
        }
    }

    @Test public void offlineAlarmAndActivityPauseDismissGateWithoutClearingKnownRelease() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            activity.setTheme(R.style.AppTheme_ClinicalDark);
            AppUpdates.prefs(activity).edit().putString("state", "available")
                    .putBoolean("target_confirmed", true).putLong("required_version", 9999).commit();
            AppUpdateGate gate = new AppUpdateGate();
            gate.render(activity, true);
            assertTrue(gate.showing());
            gate.render(activity, false);
            assertFalse(gate.showing());
            assertEquals(9999, AppUpdates.prefs(activity).getLong("required_version", 0));
            gate.render(activity, true);
            assertTrue(gate.showing());
            gate.render(null, false);
            assertFalse(gate.showing());
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
            assertFalse(gate.showing());
        }
    }

    @Test public void requiredCopyNeverSaysCloseOrReportsSuccessBeforeInstallation() {
        assertEquals(R.string.app_update_required_retry, AppUpdatePanel.requiredDetail("check_failed"));
        assertEquals(R.string.app_update_required_download, AppUpdatePanel.requiredDetail("downloading"));
        assertEquals(R.string.app_update_required_auto, AppUpdatePanel.requiredDetail("ready"));
        assertEquals(R.string.app_update_confirmation_help, AppUpdatePanel.requiredDetail("confirmation"));
    }
}
