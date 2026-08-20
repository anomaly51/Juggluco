package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class CriticalAlarmVisualTest {

    @Test
    public void chartSnapshotIsBoundedSortedDeduplicatedAndUsesFreshForecast() {
        long now = 1_800_000_000_000L;
        ForecastReading old = reading(now - 91L * 60_000L, 140);
        ForecastReading first = reading(now - 10L * 60_000L, 110);
        ForecastReading duplicate = reading(now - 10L * 60_000L, 112);
        ForecastReading latest = reading(now - 5L * 60_000L, 124);
        ForecastReading futureClockSkew = reading(now + 2L * 60_000L, 130);

        ForecastSnapshot forecast = new ForecastSnapshot("ready", now, now,
                124f, 120, "visual-test", .8f,
                Arrays.asList(
                        new ForecastSnapshot.Point(now, 124f, 118f, 130f),
                        new ForecastSnapshot.Point(now + 60L * 60_000L,
                                88f, 70f, 106f),
                        new ForecastSnapshot.Point(now + 120L * 60_000L,
                                68f, 54f, 86f)),
                Collections.emptyList(), "",
                ForecastSnapshot.AlertAssessment.unavailable());

        CriticalAlarmChartData data = CriticalAlarmChartData.from(
                Arrays.asList(latest, old, first, duplicate,
                        futureClockSkew), forecast, now);

        assertEquals(2, data.history.size());
        assertEquals(now - 10L * 60_000L, data.history.get(0).atMs);
        assertEquals(112f, data.history.get(0).glucoseMgDl, .001f);
        assertEquals(3, data.forecast.size());
        assertTrue(data.hasForecast());
        assertEquals(120, data.forecastMinutes);
        assertEquals(2, data.trend());
    }

    @Test
    public void staleForecastNeverAppearsInCriticalTimeline() {
        long now = 1_800_000_000_000L;
        ForecastSnapshot stale = new ForecastSnapshot("ready",
                now - 30L * 60_000L, now - 30L * 60_000L,
                100f, 120, "stale", .9f,
                Arrays.asList(
                        new ForecastSnapshot.Point(now - 30L * 60_000L,
                                100f, 90f, 110f),
                        new ForecastSnapshot.Point(now + 30L * 60_000L,
                                70f, 55f, 85f)),
                Collections.emptyList(), "",
                ForecastSnapshot.AlertAssessment.unavailable());
        CriticalAlarmChartData data = CriticalAlarmChartData.from(
                Collections.singletonList(reading(now, 100)), stale, now);
        assertFalse(data.hasForecast());
        assertTrue(data.forecast.isEmpty());
    }

    @Test
    public void persistedAlarmPayloadMapsWithoutNativeOrNetworkWork() {
        long now = 1_800_000_000_000L;
        CriticalDisplayPayload payload = CriticalDisplayPayload.fromActual(
                Arrays.asList(
                        reading(now - 15L * 60_000L, 92),
                        reading(now - 5L * 60_000L, 75)),
                ForecastSnapshot.empty("no_data"), now, 64f, -2.1f, now);
        CriticalAlarmChartData data = CriticalAlarmChartData.from(payload,
                now);

        assertEquals(45, data.historyMinutes);
        assertEquals(3, data.history.size());
        assertEquals(64f, data.history.get(2).glucoseMgDl, .001f);
        assertEquals(-2, data.trend());
        assertFalse(data.hasForecast());
    }

    @Test
    public void reusableSurfaceRedactsLockedMedicalDetailsAndWiresActions() {
        Application application = RuntimeEnvironment.getApplication();
        CriticalGlucoseAlarm.Session session = new CriticalGlucoseAlarm.Session();
        session.token = "visual-session";
        session.source = CriticalGlucoseAlarm.SOURCE_ACTUAL;
        session.direction = CriticalGlucoseAlarm.DIRECTION_LOW;
        session.title = "Current glucose is low";
        session.body = "Sensitive medical detail";
        session.value = "SECRET 3.1 mmol/L";

        CriticalAlarmSurface surface = new CriticalAlarmSurface(application);
        AtomicInteger action = new AtomicInteger();
        CriticalAlarmSurface.Actions actions = new CriticalAlarmSurface.Actions() {
            @Override public void acknowledge() { action.set(1); }
            @Override public void snooze() { action.set(2); }
            @Override public void openGraph() { action.set(3); }
        };
        surface.bind(session, true,
                CriticalAlarmChartData.empty(System.currentTimeMillis()),
                actions);

        assertEquals(2, surface.getChildCount());
        assertFalse(containsText(surface, "SECRET 3.1 mmol/L"));
        assertFalse(containsText(surface, "Sensitive medical detail"));
        CriticalAlarmMiniChart lockedChart = findChart(surface);
        assertNotNull(lockedChart);
        assertEquals(application.getString(R.string.critical_alarm_chart_locked),
                lockedChart.getContentDescription().toString());

        surface.findViewById(CriticalAlarmSurface.ACKNOWLEDGE_ID)
                .performClick();
        assertEquals(1, action.get());
        surface.findViewById(CriticalAlarmSurface.SNOOZE_ID).performClick();
        assertEquals(2, action.get());
        surface.findViewById(CriticalAlarmSurface.OPEN_GRAPH_ID).performClick();
        assertEquals(3, action.get());

        surface.bind(session, false,
                CriticalAlarmChartData.empty(System.currentTimeMillis()),
                actions);
        assertTrue(containsText(surface, "SECRET 3.1 mmol/L"));
        assertTrue(containsText(surface, "Sensitive medical detail"));
    }

    @Test
    public void surfaceSchedulesExactTokenRefreshAtForecastExpiry()
            throws Exception {
        String surface = new String(Files.readAllBytes(Paths.get("src",
                "main", "java", "tk", "glucodata",
                "CriticalAlarmSurface.java")), StandardCharsets.UTF_8);
        assertTrue(surface.contains("ForecastSnapshot.MAX_GRAPH_AGE_MS"));
        assertTrue(surface.contains(
                "CriticalGlucoseAlarm.session(getContext(), token)"));
        assertTrue(surface.contains("currentLockedState(locked)"));
        assertTrue(surface.contains("post(forecastFreshnessRefresh)"));
        assertTrue(surface.contains("if (!isAttachedToWindow()) return"));
        assertTrue(surface.contains("onDetachedFromWindow()"));
        assertTrue(surface.contains("CriticalAlarmChartData.from("));

        String activity = new String(Files.readAllBytes(Paths.get("src",
                "main", "java", "tk", "glucodata",
                "CriticalGlucoseAlarmActivity.java")),
                StandardCharsets.UTF_8);
        assertTrue(activity.contains(
                "WindowManager.LayoutParams.FLAG_SECURE"));
    }

    private static ForecastReading reading(long atMs, int glucose) {
        return new ForecastReading("", atMs, glucose, null,
                "", "", null);
    }

    private static boolean containsText(View view, String expected) {
        if (view instanceof TextView
                && expected.contentEquals(((TextView) view).getText())) {
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                if (containsText(group.getChildAt(index), expected)) return true;
            }
        }
        return false;
    }

    private static CriticalAlarmMiniChart findChart(View view) {
        if (view instanceof CriticalAlarmMiniChart) {
            return (CriticalAlarmMiniChart) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                CriticalAlarmMiniChart found = findChart(
                        group.getChildAt(index));
                if (found != null) return found;
            }
        }
        return null;
    }
}
