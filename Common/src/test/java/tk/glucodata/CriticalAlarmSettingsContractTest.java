package tk.glucodata;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.NotificationManager;
import android.content.Context;
import android.view.ContextThemeWrapper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class CriticalAlarmSettingsContractTest {
    private Context context;

    @Before
    public void setUp() {
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
                R.style.AppTheme_ClinicalDark);
    }

    @Test
    public void copyIsHonestAboutAndroidLimitsAndNeverClaimsGuarantee() {
        String intro = context.getString(R.string.critical_alarm_delivery_intro);
        assertTrue(intro.contains("maximum reliability"));
        assertTrue(intro.contains("no app can guarantee"));
        assertTrue(context.getString(
                R.string.critical_alarm_summary_configured)
                .contains("Configured"));
        assertFalse(context.getString(
                R.string.critical_alarm_summary_configured)
                .contains("Guaranteed"));
    }

    @Test
    public void settingsPageUsesReadOnlyDiagnosticsAndEveryRepairRoute()
            throws Exception {
        String page = source(Paths.get("src", "main", "java", "tk",
                "glucodata", "PredictiveAlertSettingsPage.java"));
        assertTrue(page.contains("CriticalAlarmDiagnostics.inspect(activity)"));
        assertTrue(page.contains("openNotificationSettings(activity)"));
        assertTrue(page.contains("openAlarmSoundSettings(activity)"));
        assertTrue(page.contains("openDndSettings(activity)"));
        assertTrue(page.contains("openFullScreenSettings(activity)"));
        assertTrue(page.contains("openOverlaySettings(activity)"));
        assertTrue(page.contains("openExactAlarmSettings(activity)"));
        assertTrue(page.contains("CriticalAlarmDiagnostics.showTest(activity, true)"));
        assertTrue(page.contains("FullScreenAlertSettingsPage.dndStatusResource(snapshot)"));
        assertTrue(page.contains("snapshot.dndPolicyAccess && snapshot.dndModeAllowsAlarmChannels()"));

        String diagnostics = source(Paths.get("src", "main", "java", "tk",
                "glucodata", "CriticalAlarmDiagnostics.java"));
        assertFalse(diagnostics.contains("createNotificationChannel"));
        assertFalse(diagnostics.contains("setInterruptionFilter"));
        assertFalse(diagnostics.contains("setStreamVolume"));
        assertTrue(diagnostics.contains("canUseFullScreenIntent"));
        assertTrue(diagnostics.contains("canDrawOverlays"));
        assertTrue(diagnostics.contains("ACTION_MANAGE_OVERLAY_PERMISSION"));
        assertTrue(diagnostics.contains("canScheduleExactAlarms"));
        // The notification channel is deliberately presentation-only. The
        // app-owned foreground player, not diagnostics or the notification
        // shade, must own the audible USAGE_ALARM stream.
        assertTrue(diagnostics.contains("channel.getSound() == null"));
        String audioPlayer = source(Paths.get("src", "main", "java", "tk",
                "glucodata", "CriticalAlarmAudioPlayer.java"));
        assertTrue(audioPlayer.contains(
                ".setUsage(AudioAttributes.USAGE_ALARM)"));
        assertTrue(diagnostics.contains("getCurrentInterruptionFilter"));
        assertTrue(diagnostics.contains("android.settings.ZEN_MODE_SETTINGS"));
    }

    @Test
    public void fullScreenRefreshCreatesCurrentPolicyChannelsBeforeInspecting()
            throws Exception {
        String page = source(Paths.get("src", "main", "java", "tk",
                "glucodata", "FullScreenAlertSettingsPage.java"));
        int rebuild = page.indexOf("private void rebuildReadiness()");
        int ensure = page.indexOf(
                "CriticalGlucoseAlarm.ensureChannels(activity);", rebuild);
        int inspect = page.indexOf(
                "CriticalAlarmDiagnostics.inspect(activity);", rebuild);
        assertTrue(rebuild >= 0);
        assertTrue(ensure > rebuild);
        assertTrue(inspect > ensure);
    }

    @Test
    public void dndStatusDistinguishesGrantFromBlockedOrUnknownActiveMode() {
        assertEquals(R.string.critical_alarm_dnd_needed,
                FullScreenAlertSettingsPage.dndStatusResource(snapshot(
                        NotificationManager.INTERRUPTION_FILTER_NONE, false)));
        assertEquals(R.string.critical_alarm_dnd_total_silence,
                FullScreenAlertSettingsPage.dndStatusResource(snapshot(
                        NotificationManager.INTERRUPTION_FILTER_NONE, true)));
        assertEquals(R.string.critical_alarm_dnd_mode_unknown,
                FullScreenAlertSettingsPage.dndStatusResource(snapshot(
                        NotificationManager.INTERRUPTION_FILTER_UNKNOWN, true)));
        assertEquals(R.string.critical_alarm_dnd_mode_unknown,
                FullScreenAlertSettingsPage.dndStatusResource(snapshot(99, true)));
        for (int mode : new int[]{NotificationManager.INTERRUPTION_FILTER_ALL,
                NotificationManager.INTERRUPTION_FILTER_ALARMS,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY}) {
            assertEquals(R.string.critical_alarm_dnd_ready,
                    FullScreenAlertSettingsPage.dndStatusResource(snapshot(mode, true)));
        }
    }

    @Test
    public void dndBlockCopyNamesTheCauseAndHowToFixIt() {
        String blocked = context.getString(R.string.critical_alarm_dnd_total_silence);
        assertTrue(blocked.contains("Access granted"));
        assertTrue(blocked.contains("blocks alarm sound"));
        assertTrue(blocked.contains("allow alarms"));
        String unknown = context.getString(R.string.critical_alarm_dnd_mode_unknown);
        assertTrue(unknown.contains("did not report"));
        assertTrue(unknown.contains("Check Do Not Disturb settings"));
    }

    @Test
    public void russianCopyUsesConfiguredNotGuaranteedLanguage()
            throws Exception {
        String russian = source(Paths.get("src", "main", "res", "values-ru",
                "settings_child_strings.xml"));
        assertTrue(russian.contains("Настроено для максимальной надёжности"));
        assertTrue(russian.contains("ни одно приложение не гарантирует звук"));
        assertTrue(russian.contains("Громкость будильника"));
        assertTrue(russian.contains("Полноэкранные сигналы"));
        assertTrue(russian.contains("Показ поверх других приложений"));
        assertTrue(russian.contains("режиму «Не беспокоить»"));
        assertTrue(russian.contains("режим полной тишины блокирует звук"));
        assertTrue(russian.contains("разрешите будильники"));
        assertTrue(russian.contains("Android не сообщил текущий режим"));
        assertFalse(russian.contains("Звук гарантирован"));
    }

    private static String source(Path relative) throws Exception {
        if (!Files.exists(relative)) relative = Paths.get("Common").resolve(relative);
        return new String(Files.readAllBytes(relative), StandardCharsets.UTF_8);
    }

    private static CriticalAlarmDiagnostics.Snapshot snapshot(int mode,
            boolean policyAccess) {
        CriticalAlarmDiagnostics.ChannelReadiness channels =
                new CriticalAlarmDiagnostics.ChannelReadiness(true, true, true, 2);
        return new CriticalAlarmDiagnostics.Snapshot(true, true, 5, 10,
                policyAccess, mode, true, true, true,
                channels, channels, channels, true);
    }
}
