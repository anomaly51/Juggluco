package tk.glucodata;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

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
public class PredictiveAlertSettingsContractTest {
    private Context context;

    @Before
    public void setUp() {
        Context app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences(PredictiveAlertPreferences.PREFS_NAME,
                Context.MODE_PRIVATE).edit().clear().commit();
        context = new ContextThemeWrapper(app, R.style.AppTheme_ClinicalDark);
    }

    @Test
    public void alarmScreenEntryIsProminentAccessibleAndOffByDefault() {
        LinearLayout card = PredictiveAlertSettingsPage.entryCard(context);

        assertNotNull(card.getBackground());
        assertTrue(card.getChildCount() == 1);
        View row = card.getChildAt(0);
        assertTrue(row.getMinimumHeight()
                >= ClinicalUi.dp(context, PredictiveAlertSettingsPage.MIN_TOUCH_TARGET_DP));
        String copy = allText(card);
        assertTrue(copy.contains(context.getString(
                R.string.predictive_alert_entry_title)));
        assertTrue(copy.contains(context.getString(
                R.string.predictive_alert_entry_summary_off)));
        assertNotNull(row.getContentDescription());
        assertTrue(row.getContentDescription().toString().contains(
                context.getString(R.string.predictive_alert_state_off)));
    }

    @Test
    public void resourcesStateFixedTargetAndNonPrescriptiveSafetyBoundary() {
        assertTrue(context.getString(R.string.predictive_alert_target_mmol)
                .contains("4.2–9.0"));
        assertTrue(context.getString(R.string.predictive_alert_target_mgdl)
                .contains("76–162"));
        String safety = context.getString(R.string.predictive_alert_safety_body);
        assertTrue(safety.contains("never calculates"));
        assertTrue(safety.contains("agreed care plan"));
        assertTrue(context.getString(R.string.predictive_alert_shadow_body)
                .contains("successfully posted"));
        assertTrue(context.getString(R.string.predictive_alert_shadow_body)
                .contains("Current low, high and signal-loss alarms are never changed"));
        assertTrue(context.getString(
                R.string.predictive_alert_model_status_eligible)
                .contains("experimental"));
        assertFalse(context.getString(
                R.string.predictive_alert_model_status_eligible)
                .contains("Validated for live warnings"));
    }

    @Test
    public void phoneAlarmSettingsRoutesToDedicatedPredictivePage() throws Exception {
        String settings = source(Paths.get("src", "main", "java", "tk",
                "glucodata", "settings", "Settings.java"));
        assertTrue(settings.contains("PredictiveAlertSettingsPage.entryCard(context)"));
        assertTrue(settings.contains("PredictiveAlertSettingsPage.show(context,"));
        assertFalse(settings.contains("blocksLegacyEarlyWarnings"));

        String page = source(Paths.get("src", "main", "java", "tk",
                "glucodata", "PredictiveAlertSettingsPage.java"));
        assertTrue(page.contains("HORIZON_OPTIONS_MINUTES"));
        assertTrue(page.contains("COOLDOWN_OPTIONS_MINUTES"));
        assertTrue(page.contains("PredictiveAlertNotifier.showTest(activity,"));
        assertTrue(page.contains("PredictiveAlertNotifier.channelsEnabled(activity,"));
        assertTrue(page.contains("ForecastRepository.get(activity).addListener"));
        assertTrue(page.contains("repository.refreshNow()"));
        assertTrue(page.contains("PredictiveAlertNotifier.supportsExpiringAlerts()"));
        assertTrue(page.contains("lowToggleRow.setAlpha(enabled ? 1f : .5f)"));
        assertTrue(page.contains("-android.R.attr.state_enabled"));

        String notifier = source(Paths.get("src", "main", "java", "tk",
                "glucodata", "PredictiveAlertNotifier.java"));
        assertTrue(notifier.contains("NotificationManagerCompat.from(context)"));
        assertTrue(notifier.contains("setTimeoutAfter(timeoutMs)"));
        assertTrue(notifier.contains(
                "CriticalAlarmDiagnostics.PREDICTIVE_LOW_CHANNEL_ID"));
        assertTrue(notifier.contains(
                "CriticalAlarmDiagnostics.PREDICTIVE_HIGH_CHANNEL_ID"));
        String show = between(notifier, "static boolean show(Context context,",
                "static long notificationTimeoutMs");
        assertFalse(show.contains("notificationActive("));
        assertTrue(show.contains("usesCriticalDelivery(decision)"));
        assertTrue(show.contains("CriticalGlucoseAlarm.showPredictive("));
        assertFalse(show.contains(".setFullScreenIntent("));
        assertFalse(notifier.contains("android.media.Ringtone"));
        assertFalse(notifier.contains("setLooping("));
        String possibleBuilder = between(notifier,
                "static NotificationCompat.Builder baseBuilder(",
                "private static Notification genericPublicVersion(");
        assertTrue(possibleBuilder.contains(
                ".setVisibility(NotificationCompat.VISIBILITY_PRIVATE)"));
        assertTrue(possibleBuilder.contains(
                ".setPublicVersion(genericPublicVersion(context, channel))"));
        String publicVersion = between(notifier,
                "private static Notification genericPublicVersion(",
                "private static void cancelOrdinaryNotification(");
        assertTrue(publicVersion.contains("R.string.app_name"));
        assertTrue(publicVersion.contains(
                ".setVisibility(NotificationCompat.VISIBILITY_PUBLIC)"));
        assertFalse(publicVersion.contains("setContentText("));
        assertFalse(publicVersion.contains("setStyle("));
        assertFalse(publicVersion.contains("setContentIntent("));
        assertFalse(publicVersion.contains("addAction("));
        assertFalse(publicVersion.contains("glucose("));
        String activeCheck = between(notifier,
                "static boolean notificationActive(Context context,",
                "private static void setPossibleActiveUntil(");
        assertTrue(activeCheck.contains(
                "CriticalGlucoseAlarm.predictiveActive("));
        assertTrue(activeCheck.contains("expectedChannel.equals("));
    }

    @Test
    public void runtimeHandoffIsPerDirectionAndNeverMutatesSavedAlarmProfiles()
            throws Exception {
        String preferences = source(Paths.get("src", "main", "java", "tk",
                "glucodata", "PredictiveAlertPreferences.java"));
        assertTrue(preferences.contains(
                "void setLegacyPredictionSuppression(boolean suppressLow,"));
        assertTrue(preferences.contains(
                "Natives.setSuppressLegacyPredictionAlarms(suppressLow,"));
        assertTrue(preferences.contains("Math.max(0L, expiresAtMs)"));
        String beforeDedicatedGate = preferences.substring(0,
                preferences.indexOf("void setLegacyPredictionSuppression"));
        assertFalse(beforeDedicatedGate.contains(
                "setSuppressLegacyPredictionAlarms"));
        assertTrue(!preferences.contains("Natives.setAdvancedAlarms("));
        assertTrue(!preferences.contains("Natives.setalarms("));

        String schedules = source(Paths.get("src", "main", "java", "tk",
                "glucodata", "NumAlarm.java"));
        assertFalse(schedules.contains("PredictiveAlertPreferences"));

        String settings = source(Paths.get("src", "main", "java", "tk",
                "glucodata", "settings", "Settings.java"));
        assertFalse(settings.contains("predictiveEarlyWarnings"));
        assertFalse(settings.contains("savedPreLow"));
        assertFalse(settings.contains("savedPreHigh"));

        String jni = source(Paths.get("src", "main", "cpp", "settings",
                "javasettings.cpp"));
        assertTrue(jni.contains(
                "tlow=roundf(settings->tomgperL(tlow))"));
        assertTrue(jni.contains("jboolean suppressLow,"));
        assertTrue(jni.contains("jboolean suppressHigh"));
        assertTrue(jni.contains("jlong expiresAtMs"));
        assertTrue(jni.contains("suppressLow&&!localCalibration"));
        assertTrue(jni.contains("suppressHigh&&!localCalibration"));
        assertTrue(jni.contains("showcalibratedstream"));
        assertTrue(jni.contains("getPredictiveCalibrationActive"));

        String nativeSettings = source(Paths.get("src", "main", "cpp",
                "settings", "settings.hpp"));
        assertTrue(nativeSettings.contains(
                "std::atomic_bool suppressLegacyPreLowAlarm"));
        assertTrue(nativeSettings.contains(
                "std::atomic_bool suppressLegacyPreHighAlarm"));
        assertTrue(nativeSettings.contains(
                "suppressLegacyPredictionAlarmUntilMs"));
        String actualAlarmSection = between(nativeSettings,
                "bool availableAlarm() const", "static float preval");
        assertFalse(actualAlarmSection.contains("suppressLegacy"));
        String preHighSection = between(nativeSettings,
                "bool prehighAlarm", "bool prelowAlarm");
        String preLowSection = between(nativeSettings,
                "bool prelowAlarm", "void setranges");
        assertTrue(preHighSection.contains("suppressLegacyPreHighAlarm"));
        assertFalse(preHighSection.contains("suppressLegacyPreLowAlarm"));
        assertTrue(preLowSection.contains("suppressLegacyPreLowAlarm"));
        assertFalse(preLowSection.contains("suppressLegacyPreHighAlarm"));

        String coordinator = source(Paths.get("src", "main", "java", "tk",
                "glucodata", "PredictiveAlertCoordinator.java"));
        assertTrue(coordinator.contains("legacySuppressionFor("));
        assertTrue(coordinator.contains("localCalibrationActive()"));
        assertTrue(coordinator.contains(
                "PredictiveAlertNotifier.LOW_CHANNEL_ID"));
        assertTrue(coordinator.contains(
                "PredictiveAlertNotifier.HIGH_CHANNEL_ID"));
        assertTrue(coordinator.contains("activeHandoff(result,"));
        String runtimeHandoff = between(coordinator,
                "private LegacySuppression updateLegacySuppression(",
                "static final class LegacySuppression");
        assertTrue(runtimeHandoff.contains("runtimeDelivery.directionIfActive("));
        assertTrue(runtimeHandoff.contains("notificationActive("));
        assertFalse(runtimeHandoff.contains(
                "preferences.activeEpisodeDirection()"));
        assertTrue(coordinator.contains("activeHandoff(result,"));
        assertTrue(coordinator.contains("deliveredDirection)"));
        assertTrue(coordinator.contains(
                "shown = PredictiveAlertNotifier.show(application, decision,"));
        assertTrue(coordinator.contains("forecast);"));
        String evidenceGate = between(coordinator,
                "String direction = decision.direction",
                "boolean snoozeReplay =");
        assertTrue(evidenceGate.contains(
                "isEvidenceDowngrade(preferences.activeEpisodeDirection(),"));
        assertTrue(evidenceGate.contains(
                "preferences.activeEvidence(), direction,"));
        String successfulDelivery = between(coordinator,
                "if (shown) {", "} else {");
        assertTrue(successfulDelivery.contains(
                "PredictiveAlertNotifier.cancel(application, opposite)"));
        assertTrue(successfulDelivery.contains(
                "preferences.recordAlert(direction,"));
        assertTrue(coordinator.contains("scheduleDeliveryConfirmation("));
        String confirmation = between(coordinator,
                "private synchronized void confirmDelivery(",
                "private void clearRuntimeDeliveryProof()");
        assertTrue(confirmation.contains(
                "PredictiveAlertNotifier.notificationActive("));
        assertTrue(confirmation.contains("runtimeDelivery.record("));
        assertTrue(coordinator.contains(
                "preferences.setLegacyPredictionSuppression(false, false)"));
        String unavailable = between(coordinator,
                "synchronized void onForecastUnavailable()",
                "synchronized void onSettingsChanged()");
        assertTrue(unavailable.contains(
                "preferences.setLegacyPredictionSuppression(false, false)"));
        assertFalse(unavailable.contains("PredictiveAlertNotifier.cancelAll("));
        assertFalse(unavailable.contains("preferences.clearEpisode()"));
        String settingsChanged = between(coordinator,
                "synchronized void onSettingsChanged()",
                "static LegacySuppression legacySuppressionFor(");
        assertFalse(settingsChanged.contains(
                "replacement.anyReplacementOperational()"));
        assertTrue(settingsChanged.contains(
                "preferences.activeEpisodeDirection()"));
        String outsideTarget = between(coordinator,
                "if (current < forecast.alertAssessment.targetLowMgDl",
                "ForecastRiskEvaluator.Policy policy");
        assertTrue(outsideTarget.contains(
                "preferences.setLegacyPredictionSuppression(false, false)"));
        assertFalse(outsideTarget.contains("clearEpisodeAndNotifications()"));
        assertFalse(outsideTarget.contains("PredictiveAlertNotifier.cancel"));

        String calibration = source(Paths.get("src", "main", "java", "tk",
                "glucodata", "settings", "Calibration.java"));
        assertTrue(calibration.contains(
                "PredictiveAlertSettingsPage.onLocalCalibrationStateChanged(act)"));
        String graphSettings = source(Paths.get("src", "main", "java", "tk",
                "glucodata", "settings", "Settings.java"));
        String calibratedStream = between(graphSettings,
                "CheckDirectionBox graphCalibratedStream", "CheckDirectionBox graphHistory");
        assertTrue(calibratedStream.contains(
                "PredictiveAlertSettingsPage.onLocalCalibrationStateChanged(context)"));

        String repository = source(Paths.get("src", "main", "java", "tk",
                "glucodata", "ForecastRepository.java"));
        String graphProjection = between(repository,
                "private static void publishGraphOrClear(State value)",
                "static void clearNativeForecast()");
        assertFalse(graphProjection.contains("value.error"));
    }

    @Test
    public void russianCopyIncludesTargetSafetyAndDeliveryDiagnostics() throws Exception {
        String ru = source(Paths.get("src", "main", "res", "values-ru",
                "settings_child_strings.xml"));
        assertTrue(ru.contains("4,2–9,0 ммоль/л"));
        assertTrue(ru.contains("согласованному с врачом плану"));
        assertTrue(ru.contains("predictive_alert_permission_title"));
        assertTrue(ru.contains("predictive_alert_channels_title"));
        assertTrue(ru.contains("predictive_alert_status_os_unsupported"));
        assertTrue(ru.contains("Нижняя граница неопределённости"));
        assertTrue(ru.contains("локальная калибровка"));
        assertTrue(ru.contains("после успешной отправки"));
        assertTrue(ru.contains("для своего направления"));
        assertTrue(ru.contains("экспериментально"));
        assertFalse(ru.contains("Модель проверена для реальных оповещений"));
    }

    private static String allText(View view) {
        StringBuilder result = new StringBuilder();
        collectText(view, result);
        return result.toString();
    }

    private static void collectText(View view, StringBuilder target) {
        if (view instanceof TextView) {
            target.append(((TextView) view).getText()).append('\n');
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                collectText(group.getChildAt(index), target);
            }
        }
    }

    private static String source(Path relative) throws Exception {
        if (!Files.exists(relative)) relative = Paths.get("Common").resolve(relative);
        return new String(Files.readAllBytes(relative), StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue("Missing start marker: " + start, from >= 0);
        assertTrue("Missing end marker: " + end, to > from);
        return source.substring(from, to);
    }
}
