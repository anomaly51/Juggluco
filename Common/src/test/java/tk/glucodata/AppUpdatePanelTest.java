package tk.glucodata;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import java.time.Duration;
import java.util.Locale;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.Shadows;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class AppUpdatePanelTest {
    Context context;
    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        AppUpdates.prefs(context).edit().clear().commit();
    }

    @Test public void downloadIsNotSuccessAndProgressIsBounded() {
        assertEquals(0, AppUpdatePanel.percent(7, 0));
        assertEquals(0, AppUpdatePanel.percent(-2, 100));
        assertEquals(25, AppUpdatePanel.percent(25, 100));
        assertEquals(100, AppUpdatePanel.percent(120, 100));
        AppUpdates.prefs(context).edit().putString("state", "ready").commit();
        assertTrue(AppUpdatePanel.banner(context).contains(context.getString(R.string.app_update_ready_title)));
        assertFalse(AppUpdates.prefs(context).getBoolean("update_unseen", false));
    }

    @Test public void onlyActualVersionIncreaseProducesSuccessReceipt() {
        AppUpdates.recordInstalledVersion(context, 904);
        assertFalse(AppUpdates.prefs(context).getBoolean("update_unseen", false));
        AppUpdates.recordInstalledVersion(context, 904);
        assertFalse(AppUpdates.prefs(context).getBoolean("update_unseen", false));
        AppUpdates.recordInstalledVersion(context, 905);
        assertTrue(AppUpdates.prefs(context).getBoolean("update_unseen", false));
        assertEquals(905, AppUpdates.prefs(context).getLong("updated_version", 0));
        AppUpdates.prefs(context).edit().putBoolean("update_unseen", false).commit();
        AppUpdates.recordInstalledVersion(context, 905);
        assertFalse(AppUpdates.prefs(context).getBoolean("update_unseen", false));
    }

    @Test public void migrationRecognizesOnlyCompletedLegacySession() {
        AppUpdates.prefs(context).edit().putInt("session", 4).putLong("target", 905).commit();
        AppUpdates.recordInstalledVersion(context, 904);
        assertFalse(AppUpdates.prefs(context).getBoolean("update_unseen", false));
        AppUpdates.prefs(context).edit().remove("observed_version").commit();
        AppUpdates.recordInstalledVersion(context, 905);
        assertTrue(AppUpdates.prefs(context).getBoolean("update_unseen", false));
    }

    @Test public void readyAndRetryAlwaysDescribeAutomaticUpdates() {
        assertEquals(R.string.app_update_ready_auto, AppUpdatePanel.detail("ready", false, true));
        assertEquals(R.string.app_update_retry, AppUpdatePanel.detail("retry", false, true));
    }

    @Test public void reconciliationRemovesLegacyOptOutWithoutResettingOtherPreferences() throws Exception {
        for (boolean previouslyEnabled : new boolean[]{false, true}) {
            AppUpdates.prefs(context).edit().putBoolean("enabled", previouslyEnabled)
                    .putLong("retry_after", 12345).putString("state", "check_failed").commit();
            AppUpdates.reconcile(context);
            AppUpdates.reconcile(context); // Migration is idempotent.
            assertFalse(AppUpdates.prefs(context).contains("enabled"));
            assertEquals(12345, AppUpdates.prefs(context).getLong("retry_after", 0));
            assertEquals("check_failed", AppUpdates.prefs(context).getString("state", ""));
        }
    }

    @Test public void serverTimeoutNeverClaimsAnInstalledUpdateFailed() {
        assertEquals("check_failed", AppUpdates.failureState("checking"));
        assertEquals("retry", AppUpdates.failureState("downloading"));
        assertEquals(R.string.app_update_check_failed_title, AppUpdatePanel.headline("check_failed"));
        assertEquals(R.string.app_update_check_failed_help,
                AppUpdatePanel.detail("check_failed", false, true));
        AppUpdates.prefs(context).edit().putString("state", "check_failed")
                .putBoolean("update_unseen", true).putLong("target", BuildConfig.VERSION_CODE).commit();
        assertTrue(AppUpdatePanel.banner(context).startsWith(
                context.getString(R.string.app_update_success, BuildConfig.DISPLAY_VERSION)));
    }

    @Test public void sessionCleanupDoesNotEraseSuccessNotification() {
        android.app.NotificationManager manager = (android.app.NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        AppUpdates.notice(context, R.string.app_update_reopen,
                new android.content.Intent(context, MainActivity.class));
        AppUpdates.cancelNotice(context);
        assertNotNull(Shadows.shadowOf(manager).getNotification(AppUpdates.NOTICE + 1));
        AppUpdates.acknowledgeReceipt(context);
        assertNull(Shadows.shadowOf(manager).getNotification(AppUpdates.NOTICE + 1));
    }

    @Test public void waitingReasonsPrioritizeAlarmsAndEditors() {
        assertEquals(R.string.app_update_wait_alarm, AppUpdatePanel.detail("ready", true, true));
        assertEquals(R.string.app_update_wait_home, AppUpdatePanel.detail("ready", false, false));
        assertEquals(R.string.app_update_confirmation_help, AppUpdatePanel.detail("confirmation", false, true));
        assertTrue(AppUpdatePanel.working("verifying"));
        assertTrue(AppUpdatePanel.working("preparing"));
        assertFalse(AppUpdatePanel.working("confirmation"));
    }

    @Test public void livePanelUpdatesInPlaceAndCleansUpOnDismiss() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            activity.setTheme(R.style.AppTheme_ClinicalDark);
            AppUpdates.prefs(activity).edit().putString("state", "downloading").commit();
            AppUpdates.downloadedBytes = 1048576;
            AppUpdates.totalBytes = 4194304;
            boolean[] dismissed = {false};
            AlertDialog dialog = AppUpdatePanel.show(activity, () -> dismissed[0] = true);
            TextView bytes = dialog.getWindow().getDecorView().findViewWithTag("update_bytes");
            Button action = dialog.getWindow().getDecorView().findViewWithTag("update_action");
            assertNull(dialog.getWindow().getDecorView().findViewWithTag("update_automatic"));
            assertNull(dialog.getWindow().getDecorView().findViewWithTag("update_auto_help"));
            assertTrue(bytes.getText().toString().contains("25%"));
            assertFalse(action.isEnabled());
            AppUpdates.prefs(activity).edit().putString("state", "current").commit();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
            assertTrue(dialog.isShowing());
            assertTrue(action.isEnabled());
            TextView status = dialog.getWindow().getDecorView().findViewWithTag("update_status");
            assertEquals(activity.getString(R.string.app_update_current_title), status.getText().toString());
            dialog.dismiss();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertTrue(dismissed[0]);
            assertFalse(dialog.isShowing());
        }
    }

    @Test public void bothLanguagesHaveActionableLabels() {
        for (String language : new String[]{"en", "ru"}) {
            Configuration configuration = new Configuration(context.getResources().getConfiguration());
            configuration.setLocale(new Locale(language));
            Context localized = context.createConfigurationContext(configuration);
            for (String state : new String[]{"", "checking", "downloading", "verifying", "ready",
                    "permission", "preparing", "confirmation", "installing", "retry", "current"}) {
                assertFalse(localized.getString(AppUpdatePanel.headline(state)).isEmpty());
                assertFalse(localized.getString(AppUpdatePanel.detail(state, false, true)).isEmpty());
            }
            assertTrue(localized.getString(R.string.app_update_install)
                    .startsWith(language.equals("ru") ? "Установить" : "Install"));
        }
    }
}
