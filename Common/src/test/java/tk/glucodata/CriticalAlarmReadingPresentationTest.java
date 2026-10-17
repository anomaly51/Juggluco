package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Presentation-only regressions: these tests never post or sound an alarm. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class CriticalAlarmReadingPresentationTest {
    private Application application;
    private int previousUnit;

    @Before
    public void setUp() {
        application = RuntimeEnvironment.getApplication();
        previousUnit = Applic.unit;
        Applic.unit = 1;
    }

    @After
    public void tearDown() {
        Applic.unit = previousUnit;
    }

    @Test
    public void staleMeasuredGlucoseKeepsItsValueButShowsAgeAndNoTrend() {
        long now = System.currentTimeMillis();
        CriticalGlucoseAlarm.Session session = session(
                CriticalGlucoseAlarm.SOURCE_ACTUAL,
                payload(now - 12L * 60_000L, 108, -2f, now));

        CriticalAlarmSurface surface = bind(session, now);

        assertEquals("6.0", text(surface, CriticalAlarmSurface.GLUCOSE_VALUE_ID));
        assertEquals(application.getString(
                        R.string.critical_alarm_banner_measured_stale, 12L),
                text(surface, CriticalAlarmSurface.READING_AGE_ID));
        assertNull(surface.findViewById(CriticalAlarmSurface.TREND_ID));
    }

    @Test
    public void missingTrendIsNotRenderedAsAStableArrow() {
        long now = System.currentTimeMillis();
        CriticalGlucoseAlarm.Session session = session(
                CriticalGlucoseAlarm.SOURCE_ACTUAL, payload(now, 108, null, now));

        CriticalAlarmSurface surface = bind(session, now);

        assertEquals("6.0", text(surface, CriticalAlarmSurface.GLUCOSE_VALUE_ID));
        assertNull(surface.findViewById(CriticalAlarmSurface.TREND_ID));
        assertEquals(application.getString(
                        R.string.critical_alarm_banner_measured_now),
                text(surface, CriticalAlarmSurface.READING_AGE_ID));
    }

    @Test
    public void oneMeasuredPointWithExplicitTrendShowsItsDirection() {
        long now = System.currentTimeMillis();
        CriticalGlucoseAlarm.Session session = session(
                CriticalGlucoseAlarm.SOURCE_ACTUAL, payload(now, 108, -2f, now));

        CriticalAlarmSurface surface = bind(session, now);

        TextView trend = surface.findViewById(CriticalAlarmSurface.TREND_ID);
        assertNotNull(trend);
        assertEquals("↓", trend.getText().toString());
        assertEquals(application.getString(
                        R.string.critical_alarm_trend_falling_fast),
                trend.getContentDescription().toString());
    }

    @Test
    public void longHistoryGapDoesNotInventACurrentTrend() {
        long now = System.currentTimeMillis();
        CriticalDisplayPayload payload = CriticalDisplayPayload.fromActual(
                Arrays.asList(new ForecastReading("", now - 20L * 60_000L,
                                180, null, "", "", null),
                        new ForecastReading("", now, 108, null, "", "", null)),
                ForecastSnapshot.empty("no_data"), now, 108, null, now);

        CriticalAlarmSurface surface = bind(session(
                CriticalGlucoseAlarm.SOURCE_ACTUAL, payload), now);

        assertEquals("6.0", text(surface, CriticalAlarmSurface.GLUCOSE_VALUE_ID));
        assertNull(surface.findViewById(CriticalAlarmSurface.TREND_ID));
    }

    @Test
    public void futureTimestampBeyondClockSkewIsNotAMeasuredCurrentValue() {
        long now = System.currentTimeMillis();
        CriticalDisplayPayload payload = payload(
                now + 2L * 60_000L, 108, -2f, now);
        CriticalAlarmChartData chart = CriticalAlarmChartData.from(payload, now);
        assertNull(CriticalAlarmSurface.Reading.from(payload, chart, now));

        CriticalAlarmSurface surface = bind(session(
                CriticalGlucoseAlarm.SOURCE_ACTUAL, payload), now);

        assertEquals("—", text(surface, CriticalAlarmSurface.GLUCOSE_VALUE_ID));
        assertEquals(application.getString(
                        R.string.critical_alarm_banner_no_reading),
                text(surface, CriticalAlarmSurface.READING_AGE_ID));
        assertNull(surface.findViewById(CriticalAlarmSurface.TREND_ID));
    }

    @Test
    public void deliveryTestShowsRealMeasuredGlucoseAndRetainsTestLabel() {
        long now = System.currentTimeMillis();
        CriticalGlucoseAlarm.Session session = session(
                CriticalGlucoseAlarm.SOURCE_TEST, payload(now, 108, null, now));
        session.value = "TEST";

        CriticalAlarmSurface surface = bind(session, now);

        assertEquals("6.0", text(surface, CriticalAlarmSurface.GLUCOSE_VALUE_ID));
        assertEquals(application.getString(R.string.critical_alarm_banner_test),
                text(surface, CriticalAlarmSurface.ALERT_TITLE_ID));
    }

    @Test
    public void signalLossLabelsTheLastMeasurementInsteadOfClaimingItIsCurrent() {
        long now = System.currentTimeMillis();
        CriticalGlucoseAlarm.Session session = session(
                CriticalGlucoseAlarm.SOURCE_SIGNAL_LOSS,
                payload(now - 17L * 60_000L, 108, 2f, now));
        session.direction = CriticalGlucoseAlarm.DIRECTION_SIGNAL;

        CriticalAlarmSurface surface = bind(session, now);

        assertEquals("6.0", text(surface, CriticalAlarmSurface.GLUCOSE_VALUE_ID));
        assertEquals(application.getString(R.string.critical_alarm_banner_signal),
                text(surface, CriticalAlarmSurface.ALERT_TITLE_ID));
        assertEquals(application.getString(
                        R.string.critical_alarm_banner_measured_stale, 17L),
                text(surface, CriticalAlarmSurface.READING_AGE_ID));
        assertNull(surface.findViewById(CriticalAlarmSurface.TREND_ID));
    }

    @Test
    public void ageRefreshHidesExpiredTrendWithoutReplacingOrDefocusingStop() {
        long now = System.currentTimeMillis();
        AtomicLong wallClock = new AtomicLong(now);
        CriticalGlucoseAlarm.Session session = session(
                CriticalGlucoseAlarm.SOURCE_ACTUAL,
                payload(now - 290_000L, 108, -2f, now));
        AtomicInteger acknowledged = new AtomicInteger();
        ActivityController<Activity> controller = Robolectric
                .buildActivity(Activity.class).setup().visible();
        try {
            Activity activity = controller.get();
            CriticalAlarmSurface surface = new CriticalAlarmSurface(activity,
                    wallClock::get);
            activity.setContentView(surface);
            surface.bind(session, false,
                    CriticalAlarmChartData.from(session.displayPayload, now),
                    acknowledged::incrementAndGet);
            assertTrue(surface.isAttachedToWindow());
            TextView age = surface.findViewById(CriticalAlarmSurface.READING_AGE_ID);
            View stop = surface.findViewById(CriticalAlarmSurface.ACKNOWLEDGE_ID);
            View trend = surface.findViewById(CriticalAlarmSurface.TREND_ID);
            assertNotNull(trend);
            stop.setFocusableInTouchMode(true);
            assertTrue(stop.requestFocus());

            // Looper idling advances Android uptime, not java.lang.System's
            // wall clock. Advance the injected measurement clock separately.
            wallClock.addAndGet(30_000L);
            shadowOf(Looper.getMainLooper()).idleFor(30L, TimeUnit.SECONDS);

            assertSame(age, surface.findViewById(CriticalAlarmSurface.READING_AGE_ID));
            assertSame(stop, surface.findViewById(CriticalAlarmSurface.ACKNOWLEDGE_ID));
            assertTrue(stop.hasFocus());
            assertEquals(View.GONE, trend.getVisibility());
            assertEquals(application.getString(
                    R.string.critical_alarm_banner_measured_stale, 5L),
                    age.getText().toString());
            stop.performClick();
            assertEquals(1, acknowledged.get());
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test
    public void detachedSurfaceDoesNotKeepRefreshingItsReadingLabel() {
        long now = System.currentTimeMillis();
        AtomicLong wallClock = new AtomicLong(now);
        CriticalGlucoseAlarm.Session session = session(
                CriticalGlucoseAlarm.SOURCE_ACTUAL, payload(now, 108, null, now));
        ActivityController<Activity> controller = Robolectric
                .buildActivity(Activity.class).setup().visible();
        try {
            Activity activity = controller.get();
            CriticalAlarmSurface surface = new CriticalAlarmSurface(activity,
                    wallClock::get);
            activity.setContentView(surface);
            surface.bind(session, false,
                    CriticalAlarmChartData.from(session.displayPayload, now), () -> { });
            TextView age = surface.findViewById(CriticalAlarmSurface.READING_AGE_ID);
            String before = age.getText().toString();
            activity.setContentView(new View(activity));
            assertFalse(surface.isAttachedToWindow());

            wallClock.addAndGet(90_000L);
            shadowOf(Looper.getMainLooper()).idleFor(90L, TimeUnit.SECONDS);

            assertEquals(before, age.getText().toString());
        } finally {
            controller.pause().stop().destroy();
        }
    }

    private CriticalAlarmSurface bind(CriticalGlucoseAlarm.Session session, long now) {
        CriticalAlarmSurface surface = new CriticalAlarmSurface(application);
        surface.bind(session, false,
                CriticalAlarmChartData.from(session.displayPayload, now), () -> { });
        return surface;
    }

    private static CriticalGlucoseAlarm.Session session(String source,
            CriticalDisplayPayload payload) {
        CriticalGlucoseAlarm.Session result = new CriticalGlucoseAlarm.Session();
        result.token = "reading-presentation-only";
        result.source = source;
        result.direction = CriticalGlucoseAlarm.DIRECTION_LOW;
        result.title = "Legacy title";
        result.body = "Legacy detail";
        result.value = "Legacy value must not override measured glucose";
        result.displayPayload = payload;
        return result;
    }

    private static CriticalDisplayPayload payload(long atMs, int mgDl,
            Float trend, long now) {
        return CriticalDisplayPayload.fromActual(Collections.emptyList(),
                ForecastSnapshot.empty("no_data"), atMs, mgDl, trend, now);
    }

    private static String text(CriticalAlarmSurface surface, int id) {
        TextView value = surface.findViewById(id);
        assertNotNull(value);
        return value.getText().toString();
    }
}
