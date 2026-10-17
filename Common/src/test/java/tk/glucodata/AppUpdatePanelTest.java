package tk.glucodata;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Looper;
import android.widget.Button;
import android.widget.Switch;
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

    @Test public void manualInstallationRemainsAvailableWhenAutomaticIsOff() {
        assertTrue(AppUpdates.installAllowed(false, true));
        assertTrue(AppUpdates.installAllowed(true, false));
        assertFalse(AppUpdates.installAllowed(false, false));
        assertEquals(R.string.app_update_ready_manual, AppUpdatePanel.detail("ready", false, false, true));
        assertEquals(R.string.app_update_retry_manual, AppUpdatePanel.detail("retry", false, false, true));
    }

    @Test public void waitingReasonsPrioritizeAlarmsAndEditors() {
        assertEquals(R.string.app_update_wait_alarm, AppUpdatePanel.detail("ready", true, true, true));
        assertEquals(R.string.app_update_wait_home, AppUpdatePanel.detail("ready", true, false, false));
        assertEquals(R.string.app_update_confirmation_help, AppUpdatePanel.detail("confirmation", true, false, true));
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
            Switch automatic = dialog.getWindow().getDecorView().findViewWithTag("update_automatic");
            assertTrue(bytes.getText().toString().contains("25%"));
            assertFalse(action.isEnabled());
            automatic.setChecked(false);
            assertTrue(dialog.isShowing());
            assertFalse(AppUpdates.prefs(activity).getBoolean("enabled", true));
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
                assertFalse(localized.getString(AppUpdatePanel.detail(state, true, false, true)).isEmpty());
            }
            assertTrue(localized.getString(R.string.app_update_install)
                    .startsWith(language.equals("ru") ? "Установить" : "Install"));
        }
    }
}
