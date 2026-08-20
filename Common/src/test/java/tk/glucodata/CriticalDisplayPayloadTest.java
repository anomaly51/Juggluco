package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.app.NotificationManager;
import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNotificationManager;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class CriticalDisplayPayloadTest {
    private static final String CRITICAL_PREFS =
            "critical_glucose_alarm_v1";

    private Application application;
    private NotificationManager notifications;

    @Before
    public void setUp() throws Exception {
        application = RuntimeEnvironment.getApplication();
        shadowOf(application).grantPermissions(
                Manifest.permission.POST_NOTIFICATIONS);
        notifications = (NotificationManager) application.getSystemService(
                Context.NOTIFICATION_SERVICE);
        assertNotNull(notifications);
        ShadowNotificationManager shadow = shadowOf(notifications);
        shadow.setNotificationsEnabled(true);
        shadow.setNotificationPolicyAccessGranted(true);
        clearController();
        notifications.cancelAll();
        CriticalGlucoseAlarm.ensureChannels(application);
    }

    @After
    public void tearDown() throws Exception {
        clearController();
        notifications.cancelAll();
    }

    @Test
    public void actualCaptureUsesExactNativeAnchorAndBoundsBothSeries() {
        long anchor = System.currentTimeMillis();
        ArrayList<ForecastReading> recent = readings(anchor, 60, 80);
        // The later duplicate is the deterministic native winner.
        recent.add(ForecastReading.historical(anchor, 82, -1f));
        ForecastSnapshot forecast = forecast(anchor, anchor, 82f, 21);

        CriticalDisplayPayload payload = CriticalDisplayPayload.fromActual(
                recent, forecast, anchor, 73f, -1f, anchor);

        assertFalse(payload.isEmpty());
        assertEquals(Float.valueOf(82f), payload.currentMgDl);
        assertEquals(anchor, payload.readingAtMs);
        assertTrue(payload.history.size()
                <= CriticalDisplayPayload.MAX_HISTORY_POINTS);
        assertTrue(payload.forecast.size()
                <= CriticalDisplayPayload.MAX_FORECAST_POINTS);
        assertEquals(anchor, payload.history.get(
                payload.history.size() - 1).atMs);
        assertEquals(82f, payload.history.get(
                payload.history.size() - 1).glucoseMgDl, 0f);
        assertEquals(anchor, payload.forecast.get(0).atMs);
        assertEquals(anchor + 120L * 60_000L, payload.forecast.get(
                payload.forecast.size() - 1).atMs);
        assertTrue(payload.hasForecast(anchor));
    }

    @Test
    public void actualCaptureRejectsForecastForAnotherReading() {
        long anchor = System.currentTimeMillis();
        ForecastSnapshot previous = forecast(anchor,
                anchor - 2L * 60_000L, 82f, 21);

        CriticalDisplayPayload payload = CriticalDisplayPayload.fromActual(
                readings(anchor, 20, 82), previous, anchor, 82f, null,
                anchor);

        assertFalse(payload.history.isEmpty());
        assertTrue(payload.forecast.isEmpty());
        assertFalse(payload.hasForecast(anchor));
    }

    @Test
    public void actualTimestampAcceptsRealEpochSecondsAndMillis() {
        long nowMs = 1_787_200_123_456L;
        long epochSeconds = 1_787_200_123L;

        assertEquals(1_787_200_123_000L,
                CriticalDisplayPayload.readingTimeMillis(epochSeconds, nowMs));
        assertEquals(nowMs, CriticalDisplayPayload.readingTimeMillis(
                nowMs, nowMs + 1L));
        assertEquals(nowMs, CriticalDisplayPayload.readingTimeMillis(
                0L, nowMs));
        assertEquals(Long.MAX_VALUE,
                CriticalDisplayPayload.readingTimeMillis(
                        Long.MAX_VALUE, nowMs));
    }

    @Test
    public void predictiveCapturePreservesCrossingAndJsonRoundTrip() {
        long anchor = System.currentTimeMillis();
        ForecastSnapshot forecast = forecast(anchor, anchor, 110f, 21);
        ForecastRiskEvaluator.Decision decision = decision(forecast, anchor);
        assertTrue(decision.shouldNotify());

        CriticalDisplayPayload payload = CriticalDisplayPayload.fromPredictive(
                readings(anchor, 60, 108), forecast, decision, anchor);
        String encoded = payload.toJsonString();
        CriticalDisplayPayload decoded =
                CriticalDisplayPayload.fromJsonString(encoded);

        assertFalse(encoded.isEmpty());
        assertFalse(decoded.isEmpty());
        assertEquals(decision.crossingAtMs, decoded.crossingAtMs);
        assertEquals(Float.valueOf(decision.currentMgDl), decoded.currentMgDl);
        assertEquals(payload.history.size(), decoded.history.size());
        assertEquals(payload.forecast.size(), decoded.forecast.size());
        assertEquals(payload.forecast.get(0).atMs,
                decoded.forecast.get(0).atMs);
        assertEquals(payload.forecast.get(payload.forecast.size() - 1).atMs,
                decoded.forecast.get(decoded.forecast.size() - 1).atMs);
        assertEquals(payload.targetLowMgDl, decoded.targetLowMgDl, 0f);
        assertEquals(payload.targetHighMgDl, decoded.targetHighMgDl, 0f);
        assertTrue(decoded.hasForecast(anchor));
        assertFalse(decoded.hasForecast(
                anchor + ForecastSnapshot.MAX_GRAPH_AGE_MS + 1L));
        try {
            decoded.history.clear();
            fail("history must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    @Test
    public void corruptUnknownAndOversizedJsonFailSoftToEmpty() {
        assertTrue(CriticalDisplayPayload.fromJsonString("not-json").isEmpty());
        assertTrue(CriticalDisplayPayload.fromJsonString(
                "{\"v\":99}").isEmpty());
        assertTrue(CriticalDisplayPayload.fromJsonString(
                String.join("", Collections.nCopies(40_000, "x"))).isEmpty());
        assertTrue(CriticalDisplayPayload.fromJsonString(
                "{\"v\":1,\"c\":1,\"r\":1,\"g\":\"NaN\","
                        + "\"tl\":75.6,\"th\":162,\"h\":[],\"f\":[]}")
                .isEmpty());
    }

    @Test
    public void sessionPayloadSurvivesColdRestore() throws Exception {
        long anchor = System.currentTimeMillis();
        CriticalDisplayPayload payload = CriticalDisplayPayload.fromActual(
                readings(anchor, 8, 62), ForecastSnapshot.empty("no_data"),
                anchor, 62f, -2f, anchor);

        assertTrue(CriticalGlucoseAlarm.showActual(application, 0,
                62f, "Measured low", true, payload));
        String token = savedToken();
        setActive(null);

        CriticalGlucoseAlarm.Session restored =
                CriticalGlucoseAlarm.session(application, token);
        assertNotNull(restored);
        assertFalse(restored.displayPayload.isEmpty());
        assertEquals(anchor, restored.displayPayload.readingAtMs);
        assertEquals(Float.valueOf(62f), restored.displayPayload.currentMgDl);
    }

    @Test
    public void actualSessionTimingIsIndependentFromDisplayReading() {
        long readingAtMs = System.currentTimeMillis() - 24L * 60L * 60_000L;
        CriticalDisplayPayload payload = CriticalDisplayPayload.immediateActual(
                readingAtMs, 61, -1f, System.currentTimeMillis());
        long beforeShowMs = System.currentTimeMillis();

        assertTrue(CriticalGlucoseAlarm.showActual(application, 0,
                61f, "Measured low", true, payload));
        long afterShowMs = System.currentTimeMillis();
        CriticalGlucoseAlarm.Session session =
                CriticalGlucoseAlarm.currentSession(application);

        assertNotNull(session);
        assertTrue(session.anchorMs >= beforeShowMs);
        assertTrue(session.anchorMs <= afterShowMs);
        assertTrue(session.expiresAtMs > afterShowMs);
        assertEquals(readingAtMs, session.displayPayload.readingAtMs);
    }

    @Test
    public void corruptPayloadNeverInvalidatesLiveAlarm() throws Exception {
        long anchor = System.currentTimeMillis();
        CriticalDisplayPayload payload = CriticalDisplayPayload.fromActual(
                readings(anchor, 8, 61), ForecastSnapshot.empty("no_data"),
                anchor, 61f, null, anchor);
        assertTrue(CriticalGlucoseAlarm.showActual(application, 0,
                61f, "Measured low", true, payload));
        String token = savedToken();
        application.getSharedPreferences(CRITICAL_PREFS, Context.MODE_PRIVATE)
                .edit().putString("display_payload", "{corrupt").commit();
        setActive(null);

        CriticalGlucoseAlarm.Session restored =
                CriticalGlucoseAlarm.session(application, token);
        assertNotNull(restored);
        assertTrue(restored.displayPayload.isEmpty());
        assertTrue(notifications.getActiveNotifications().length > 0);
    }

    @Test
    public void lowerSeverityActualRefreshesPayloadWithoutPriorityDowngrade()
            throws Exception {
        long first = System.currentTimeMillis() - 60_000L;
        CriticalDisplayPayload severe = CriticalDisplayPayload.fromActual(
                readings(first, 5, 48), ForecastSnapshot.empty("no_data"),
                first, 48f, -2f, first);
        assertTrue(CriticalGlucoseAlarm.showActual(application, 5,
                48f, "Very low", true, severe));
        String token = savedToken();

        long second = first + 60_000L;
        CriticalDisplayPayload newer = CriticalDisplayPayload.fromActual(
                readings(second, 5, 64), ForecastSnapshot.empty("no_data"),
                second, 64f, 1f, second);
        assertTrue(CriticalGlucoseAlarm.showActual(application, 0,
                64f, "Still low", false, newer));

        assertEquals(token, savedToken());
        CriticalGlucoseAlarm.Session session =
                CriticalGlucoseAlarm.session(application, token);
        assertNotNull(session);
        assertEquals(CriticalAlarmEpisodePolicy.PRIORITY_ACTUAL_SEVERE,
                session.priority);
        assertEquals(second, session.displayPayload.readingAtMs);
        assertEquals(Float.valueOf(64f), session.displayPayload.currentMgDl);
    }

    private ForecastRiskEvaluator.Decision decision(
            ForecastSnapshot forecast, long nowMs) {
        return ForecastRiskEvaluator.evaluate(forecast,
                new ForecastRiskEvaluator.Policy(true, true, true,
                        60, 60,
                        ForecastRiskEvaluator.SENSITIVITY_BALANCED), nowMs);
    }

    private ForecastSnapshot forecast(long generatedAtMs, long anchorMs,
            float currentMgDl, int crossingMinutes) {
        ArrayList<ForecastSnapshot.Point> points = new ArrayList<>();
        for (int minute = 0; minute <= 120; minute += 3) {
            float median = currentMgDl - minute * .2f;
            points.add(new ForecastSnapshot.Point(
                    anchorMs + minute * 60_000L, median,
                    median - 8f, median + 8f));
        }
        ForecastSnapshot.ThresholdCrossing likely =
                new ForecastSnapshot.ThresholdCrossing("low", "likely",
                        anchorMs + crossingMinutes * 60_000L,
                        crossingMinutes, 72f, 68f);
        ForecastSnapshot.AlertAssessment assessment =
                new ForecastSnapshot.AlertAssessment("eligible", true,
                        ForecastSnapshot.AlertAssessment
                                .DEFAULT_TARGET_LOW_MG_DL,
                        ForecastSnapshot.AlertAssessment
                                .DEFAULT_TARGET_HIGH_MG_DL,
                        4.2f, 9.0f, Collections.emptyList(), likely, null);
        return new ForecastSnapshot("ready", generatedAtMs, anchorMs,
                currentMgDl, 120, "test", .82f, points,
                Collections.emptyList(), "", assessment);
    }

    private ArrayList<ForecastReading> readings(long anchorMs, int count,
            int latestMgDl) {
        ArrayList<ForecastReading> result = new ArrayList<>();
        for (int index = count - 1; index >= 0; index--) {
            long atMs = anchorMs - index * 60_000L;
            int glucose = Math.max(20, latestMgDl + index / 3);
            result.add(ForecastReading.historical(atMs, glucose, -1f));
        }
        return result;
    }

    private String savedToken() {
        return application.getSharedPreferences(
                CRITICAL_PREFS, Context.MODE_PRIVATE)
                .getString("token", "");
    }

    private void setActive(Object value) throws Exception {
        Field field = CriticalGlucoseAlarm.class.getDeclaredField("active");
        field.setAccessible(true);
        field.set(null, value);
    }

    private void clearController() throws Exception {
        CriticalGlucoseAlarm.cancelPredictive(application, true);
        CriticalGlucoseAlarm.cancelPredictive(application, false);
        CriticalGlucoseAlarm.resolveActual(application);
        application.getSharedPreferences(CRITICAL_PREFS,
                Context.MODE_PRIVATE).edit().clear().commit();
        setActive(null);
    }
}
