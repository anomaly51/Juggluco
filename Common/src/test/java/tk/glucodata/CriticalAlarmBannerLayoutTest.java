package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Layout checks and synthetic render fixtures; never starts an audible alarm. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class,
        qualifiers = "en-rUS-mdpi")
public class CriticalAlarmBannerLayoutTest {
    private int previousUnit;

    @Before
    public void setMgDlForDeterministicFixtures() {
        previousUnit = Applic.unit;
        Applic.unit = 2;
    }

    @After
    public void restoreUnit() {
        Applic.unit = previousUnit;
    }

    @Test
    public void phoneKeepsSingleStopActionOutsideScrollingContent() {
        assertLayout(375, 812, 1f);
    }

    @Test
    public void alarmChromeUsesDarkTokensMonospaceAndSquareFrames() {
        Context context = context(375, 812, 1f);
        CriticalGlucoseAlarm.Session low = session(
                System.currentTimeMillis(), 63);
        CriticalAlarmSurface surface = surface(context, low, false, () -> { });
        measure(surface, 375, 812);

        assertEquals(ClinicalUi.RETRO_CORNER_RADIUS_DP,
                CriticalAlarmSurface.RETRO_CORNER_RADIUS_DP);
        assertEquals(2, CriticalAlarmSurface.RETRO_FRAME_STROKE_DP);
        assertEquals(ClinicalUi.RETRO_CORNER_RADIUS_DP,
                CriticalAlarmMiniChart.RETRO_CORNER_RADIUS_DP);
        assertEquals(2, CriticalAlarmMiniChart.RETRO_FRAME_STROKE_DP);
        assertEquals(Typeface.create("monospace", Typeface.NORMAL),
                CriticalAlarmSurface.retroTypeface(Typeface.NORMAL));
        assertEquals(Typeface.create("monospace", Typeface.BOLD),
                CriticalAlarmMiniChart.retroTypeface(Typeface.BOLD));
        assertRetroTypography(surface);

        assertTrue(surface.getBackground() instanceof ColorDrawable);
        assertEquals(ContextCompat.getColor(context,
                        R.color.lifeprompt_background),
                ((ColorDrawable) surface.getBackground()).getColor());

        View panel = surface.findViewById(CriticalAlarmSurface.PANEL_ID);
        assertNotNull(panel);
        assertTrue(panel.getBackground() instanceof GradientDrawable);
        assertEquals(CriticalAlarmSurface.RETRO_CORNER_RADIUS_DP,
                ((GradientDrawable) panel.getBackground()).getCornerRadius(),
                .001f);

        TextView title = surface.findViewById(
                CriticalAlarmSurface.ALERT_TITLE_ID);
        assertEquals(ContextCompat.getColor(context,
                        R.color.lifeprompt_glucose_low),
                title.getCurrentTextColor());
        Button stop = surface.findViewById(CriticalAlarmSurface.ACKNOWLEDGE_ID);
        assertEquals(ContextCompat.getColor(context,
                        R.color.lifeprompt_on_error),
                stop.getCurrentTextColor());
        assertTrue(stop.getBackground() instanceof RippleDrawable);
        RippleDrawable stopBackground = (RippleDrawable) stop.getBackground();
        assertTrue(stopBackground.getDrawable(0) instanceof GradientDrawable);
        assertEquals(CriticalAlarmSurface.RETRO_CORNER_RADIUS_DP,
                ((GradientDrawable) stopBackground.getDrawable(0))
                        .getCornerRadius(), .001f);

        CriticalGlucoseAlarm.Session high = session(
                System.currentTimeMillis(), 190);
        high.direction = CriticalGlucoseAlarm.DIRECTION_HIGH;
        high.alertType = CriticalAlarmSoundCatalog.AlertType.ACTUAL_HIGH;
        CriticalAlarmSurface highSurface = surface(context, high, false,
                () -> { });
        TextView highTitle = highSurface.findViewById(
                CriticalAlarmSurface.ALERT_TITLE_ID);
        assertEquals(ContextCompat.getColor(context,
                        R.color.lifeprompt_glucose_high),
                highTitle.getCurrentTextColor());
    }

    @Test
    public void landscapeKeepsStopActionFullyOnScreen() {
        assertLayout(812, 375, 1f);
    }

    @Test
    public void unfoldedTabletKeepsStopActionFullyOnScreen() {
        assertLayout(800, 700, 1f);
    }

    @Test
    public void maximumFontScaleKeepsStopActionOnEveryWindowSize() {
        assertLayout(375, 812, 2f);
        assertLayout(812, 375, 2f);
        assertLayout(800, 700, 2f);
    }

    @Test
    public void actualLowShowsAccessibleVolumeDownHintInFixedFooter() {
        CriticalAlarmSurface surface = surface(context(375, 812, 1f),
                session(System.currentTimeMillis(), 63), false, () -> { });

        TextView hint = surface.findViewById(
                CriticalAlarmSurface.VOLUME_DOWN_PROGRESS_ID);
        assertNotNull(hint);
        assertEquals(surface.getContext().getString(
                R.string.critical_alarm_volume_down_hint),
                hint.getText().toString());
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                hint.getAccessibilityLiveRegion());
        assertEquals(surface.getChildAt(1), hint.getParent());
    }

    @Test
    public void volumeDownHintIsLimitedToEligibleActualLowAlarm() {
        long now = System.currentTimeMillis();
        CriticalGlucoseAlarm.Session high = session(now, 190);
        high.direction = CriticalGlucoseAlarm.DIRECTION_HIGH;
        high.alertType = CriticalAlarmSoundCatalog.AlertType.ACTUAL_HIGH;
        assertNull(surface(context(375, 812, 1f), high, false,
                () -> { }).findViewById(
                CriticalAlarmSurface.VOLUME_DOWN_PROGRESS_ID));

        CriticalGlucoseAlarm.Session predictiveLow = session(now, 95);
        predictiveLow.source = CriticalGlucoseAlarm.SOURCE_PREDICTIVE;
        predictiveLow.alertType =
                CriticalAlarmSoundCatalog.AlertType.PREDICTIVE_LOW;
        assertNull(surface(context(375, 812, 1f), predictiveLow,
                false, () -> { }).findViewById(
                CriticalAlarmSurface.VOLUME_DOWN_PROGRESS_ID));

        CriticalGlucoseAlarm.Session lowDeliveryTest = session(now, 95);
        lowDeliveryTest.source = CriticalGlucoseAlarm.SOURCE_TEST;
        lowDeliveryTest.alertType =
                CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW;
        assertNotNull(surface(context(375, 812, 1f), lowDeliveryTest,
                false, () -> { }).findViewById(
                CriticalAlarmSurface.VOLUME_DOWN_PROGRESS_ID));
    }

    @Test
    public void volumeDownProgressIsTokenBoundAndResetsAfterInterPressPause() {
        CriticalGlucoseAlarm.Session session = session(
                System.currentTimeMillis(), 63);
        CriticalAlarmSurface surface = surface(context(375, 812, 1f),
                session, false, () -> { });
        TextView hint = surface.findViewById(
                CriticalAlarmSurface.VOLUME_DOWN_PROGRESS_ID);

        surface.setVolumeDownProgress(session.token, 2);
        assertEquals(surface.getResources().getQuantityString(
                R.plurals.critical_alarm_volume_down_remaining, 3, 3),
                hint.getText().toString());
        surface.setVolumeDownProgress("replacement-alarm", 4);
        assertEquals(surface.getResources().getQuantityString(
                R.plurals.critical_alarm_volume_down_remaining, 3, 3),
                hint.getText().toString());

        shadowOf(Looper.getMainLooper()).idleFor(
                CriticalAlarmVolumeDownGesture.INTER_PRESS_TIMEOUT_MS - 1L,
                TimeUnit.MILLISECONDS);
        assertEquals(surface.getResources().getQuantityString(
                R.plurals.critical_alarm_volume_down_remaining, 3, 3),
                hint.getText().toString());
        shadowOf(Looper.getMainLooper()).idleFor(1L, TimeUnit.MILLISECONDS);
        assertEquals(surface.getContext().getString(
                R.string.critical_alarm_volume_down_hint),
                hint.getText().toString());
    }

    @Test
    public void volumeDownProgressUsesSingularCopyForLastPress() {
        CriticalGlucoseAlarm.Session session = session(
                System.currentTimeMillis(), 63);
        CriticalAlarmSurface surface = surface(context(375, 812, 1f),
                session, false, () -> { });

        surface.setVolumeDownProgress(session.token, 4);

        assertEquals("1 press remaining", text(surface,
                CriticalAlarmSurface.VOLUME_DOWN_PROGRESS_ID));
    }

    @Test
    public void hardwareKeyHandlerGetsFirstChanceToConsumeSurfaceEvents() {
        CriticalAlarmSurface surface = surface(context(375, 812, 1f),
                session(System.currentTimeMillis(), 63), false, () -> { });
        AtomicInteger dispatches = new AtomicInteger();
        surface.setHardwareKeyHandler(event -> {
            dispatches.incrementAndGet();
            return event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN;
        });

        assertTrue(surface.dispatchKeyEvent(new KeyEvent(
                KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)));
        assertEquals(1, dispatches.get());
    }

    @Test
    public void volumeDownRockerTracksProgressAndReboundsDeterministically() {
        CriticalGlucoseAlarm.Session session = session(
                System.currentTimeMillis(), 63);
        CriticalAlarmSurface surface = surface(context(375, 812, 1f),
                session, false, () -> { });
        measure(surface, 375, 812);

        assertTrue(surface.volumeDownRockerVisibleForTest());
        assertEquals(0, surface.volumeDownRockerProgressForTest());
        surface.setVolumeDownProgress(session.token, 1);
        assertEquals(1, surface.volumeDownRockerProgressForTest());
        assertTrue(surface.volumeDownRockerAnimatingForTest());

        shadowOf(Looper.getMainLooper()).idleFor(
                CriticalAlarmSurface.ROCKER_PRESS_ANIMATION_MS + 32L,
                TimeUnit.MILLISECONDS);
        assertFalse(surface.volumeDownRockerAnimatingForTest());
        assertEquals(1, surface.volumeDownRockerProgressForTest());
    }

    @Test
    public void volumeDownRockerAdaptsFromCoverPhoneToUnfoldedWidth() {
        CriticalAlarmSurface cover = surface(context(375, 812, 1f),
                session(System.currentTimeMillis(), 63), false, () -> { });
        measure(cover, 375, 812);
        Rect coverBounds = rounded(cover.volumeDownRockerBoundsForTest());
        assertTrue(coverBounds.width() >= 38 && coverBounds.width() <= 42);
        assertTrue(coverBounds.height() >= 150 && coverBounds.height() <= 160);
        assertTrue("Cover rocker should hug the right physical edge",
                coverBounds.right >= 375);

        CriticalAlarmSurface unfolded = surface(context(800, 700, 1f),
                session(System.currentTimeMillis(), 63), false, () -> { });
        measure(unfolded, 800, 700);
        Rect unfoldedBounds = rounded(
                unfolded.volumeDownRockerBoundsForTest());
        assertTrue(unfoldedBounds.width() >= 44
                && unfoldedBounds.width() <= 48);
        assertTrue(unfoldedBounds.height() >= 170
                && unfoldedBounds.height() <= 180);
        assertTrue("Unfolded rocker needs a calm edge inset",
                unfoldedBounds.right <= 784 && unfoldedBounds.right >= 778);
    }

    @Test
    public void glucoseUsesCanonicalPayloadNotFreeFormAlarmCopy() {
        long now = System.currentTimeMillis();
        CriticalGlucoseAlarm.Session session = session(now, 63);
        session.value = "This is an old 4.7 mmol/L notification value";
        CriticalAlarmSurface surface = surface(context(375, 812, 1f),
                session, false, () -> { });

        assertEquals("63", value(surface));
        assertEquals(surface.getContext().getString(R.string.mgdL),
                text(surface, CriticalAlarmSurface.GLUCOSE_UNIT_ID));
        assertFalse(text(surface, CriticalAlarmSurface.READING_AGE_ID)
                .trim().isEmpty());
        assertFalse(containsText(surface, session.value));
    }

    @Test
    public void mmolDisplayConvertsMeasuredValueAtPresentationBoundary() {
        Applic.unit = 1;
        CriticalAlarmSurface surface = surface(context(375, 812, 1f),
                session(System.currentTimeMillis(), 54), false, () -> { });

        assertEquals("3.0", value(surface));
        assertEquals(surface.getContext().getString(R.string.mmolL),
                text(surface, CriticalAlarmSurface.GLUCOSE_UNIT_ID));
    }

    @Test
    public void predictiveBannerKeepsMeasuredGlucoseSeparateFromForecast() {
        long now = System.currentTimeMillis();
        CriticalGlucoseAlarm.Session session = session(now, 128);
        session.source = CriticalGlucoseAlarm.SOURCE_PREDICTIVE;
        session.alertType = CriticalAlarmSoundCatalog.AlertType.PREDICTIVE_LOW;
        session.title = "Predicted low glucose";
        session.value = "54 mg/dL in 30 minutes";
        CriticalAlarmSurface surface = surface(context(375, 812, 1f),
                session, false, () -> { });

        assertEquals("128", value(surface));
        assertFalse(containsText(surface, session.value));
        assertFalse(text(surface, CriticalAlarmSurface.ALERT_TITLE_ID)
                .trim().isEmpty());
    }

    @Test
    public void missingPayloadCanUseLatestCanonicalChartReading() {
        long now = System.currentTimeMillis();
        CriticalGlucoseAlarm.Session session = session(now, 63);
        session.displayPayload = CriticalDisplayPayload.EMPTY;
        session.value = "Not a glucose sample";
        CriticalAlarmSurface surface = new CriticalAlarmSurface(
                context(375, 812, 1f));
        CriticalAlarmChartData chart = CriticalAlarmChartData.from(
                Arrays.asList(reading(now - 5L * 60_000L, 118),
                        reading(now, 120)), ForecastSnapshot.empty("no_data"),
                now);

        surface.bind(session, false, chart, () -> { });

        assertEquals("120", value(surface));
        assertFalse(containsText(surface, session.value));
    }

    @Test
    public void missingSamplesNeverTurnAlarmTextIntoMeasuredGlucose() {
        long now = System.currentTimeMillis();
        CriticalGlucoseAlarm.Session session = session(now, 63);
        session.displayPayload = CriticalDisplayPayload.EMPTY;
        session.value = "54 mg/dL predicted";
        CriticalAlarmSurface surface = surface(context(375, 812, 1f),
                session, false, () -> { });

        assertFalse(value(surface).matches(".*[0-9].*"));
        assertFalse(containsText(surface, session.value));
        assertFalse(text(surface, CriticalAlarmSurface.READING_AGE_ID)
                .trim().isEmpty());
    }

    @Test
    public void lockScreenShowsRequestedCurrentValueButNotHistoryDetails() {
        long now = System.currentTimeMillis();
        CriticalGlucoseAlarm.Session session = session(now, 63);
        CriticalAlarmSurface surface = surface(context(375, 812, 1f),
                session, true, () -> { });

        assertEquals("63", value(surface));
        CriticalAlarmMiniChart chart = findChart(surface);
        assertNotNull(chart);
        assertTrue(chart.getContentDescription().toString().contains(
                surface.getContext().getString(
                        R.string.critical_alarm_chart_private_hint)));
        assertFalse(chart.getContentDescription().toString().contains("63"));
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void rendersSyntheticPhoneLandscapeFoldAndLargeFontFixtures()
            throws Exception {
        render("phone", 375, 812, 1f);
        render("landscape", 812, 375, 1f);
        render("fold", 800, 700, 1f);
        render("phone-large-font", 375, 812, 2f);
        renderProgress("phone-rocker-progress", 375, 812, 1f, 3);
        renderProgress("fold-rocker-progress", 800, 700, 1f, 3);
    }

    private static CriticalAlarmSurface assertLayout(int width, int height,
            float fontScale) {
        AtomicInteger acknowledged = new AtomicInteger();
        CriticalAlarmSurface surface = surface(context(width, height,
                        fontScale), session(System.currentTimeMillis(), 63),
                false, acknowledged::incrementAndGet);
        measure(surface, width, height);

        assertEquals(1f, surface.getResources().getDisplayMetrics().density,
                .001f);
        assertEquals(width, surface.getMeasuredWidth());
        assertEquals(height, surface.getMeasuredHeight());
        assertEquals("Only one urgent action is needed", 1,
                buttonCount(surface));
        View acknowledge = surface.findViewById(
                CriticalAlarmSurface.ACKNOWLEDGE_ID);
        assertNotNull(acknowledge);
        assertEquals(View.VISIBLE, acknowledge.getVisibility());
        assertTrue(acknowledge.isEnabled());
        assertTrue(acknowledge.isClickable());
        assertTrue("Stop must remain an accessible 56dp target",
                acknowledge.getHeight() >= 56);
        Rect bounds = boundsWithin(surface, acknowledge);
        assertTrue("Stop is clipped on the left", bounds.left >= 0);
        assertTrue("Stop is clipped on the right", bounds.right <= width);
        assertTrue("Stop is clipped above the screen", bounds.top >= 0);
        assertTrue("Stop is clipped below the screen", bounds.bottom <= height);

        // The scrollable body must end before the fixed action footer starts.
        assertEquals(2, surface.getChildCount());
        assertTrue(surface.getChildAt(0).getBottom()
                <= surface.getChildAt(1).getTop());
        assertTrue(bounds.top >= surface.getChildAt(1).getTop());
        assertTrue(bounds.bottom <= surface.getChildAt(1).getBottom());
        assertTrue(surface.findViewById(CriticalAlarmSurface.GLUCOSE_VALUE_ID)
                .getMeasuredHeight() > 0);
        assertNotNull(findChart(surface));

        assertTrue(acknowledge.performClick());
        assertEquals(1, acknowledged.get());
        return surface;
    }

    private static void render(String name, int width, int height,
            float fontScale) throws Exception {
        CriticalAlarmSurface surface = assertLayout(width, height, fontScale);
        writeFixture(name, width, height, surface);
    }

    private static void renderProgress(String name, int width, int height,
            float fontScale, int progress) throws Exception {
        CriticalGlucoseAlarm.Session session = session(
                System.currentTimeMillis(), 63);
        CriticalAlarmSurface surface = surface(context(width, height,
                fontScale), session, false, () -> { });
        surface.setVolumeDownProgress(session.token, progress);
        measure(surface, width, height);
        writeFixture(name, width, height, surface);
        shadowOf(Looper.getMainLooper()).idleFor(
                CriticalAlarmSurface.ROCKER_PRESS_ANIMATION_MS + 32L,
                TimeUnit.MILLISECONDS);
    }

    private static void writeFixture(String name, int width, int height,
            CriticalAlarmSurface surface) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(width, height,
                Bitmap.Config.ARGB_8888);
        try {
            surface.draw(new Canvas(bitmap));
            Path directory = Paths.get("build", "reports", "alarm-banner");
            Files.createDirectories(directory);
            try (OutputStream stream = Files.newOutputStream(
                    directory.resolve(name + ".png"))) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100,
                        stream));
            }
        } finally {
            bitmap.recycle();
        }
    }

    private static CriticalAlarmSurface surface(Context context,
            CriticalGlucoseAlarm.Session session, boolean locked,
            CriticalAlarmSurface.Actions actions) {
        CriticalAlarmSurface surface = new CriticalAlarmSurface(context);
        surface.bind(session, locked, CriticalAlarmChartData.from(
                session.displayPayload, System.currentTimeMillis()), actions);
        return surface;
    }

    private static CriticalGlucoseAlarm.Session session(long now,
            int currentMgDl) {
        CriticalGlucoseAlarm.Session session = new CriticalGlucoseAlarm.Session();
        session.token = "synthetic-banner-layout";
        session.source = CriticalGlucoseAlarm.SOURCE_ACTUAL;
        session.direction = CriticalGlucoseAlarm.DIRECTION_LOW;
        session.alertType = CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW;
        session.title = "Low glucose";
        session.value = "Legacy notification text";
        session.body = "Legacy notification explanation";
        session.anchorMs = now;
        session.expiresAtMs = now + 30L * 60_000L;
        session.displayPayload = CriticalDisplayPayload.fromActual(
                Arrays.asList(reading(now - 20L * 60_000L, 108),
                        reading(now - 15L * 60_000L, 98),
                        reading(now - 10L * 60_000L, 85),
                        reading(now - 5L * 60_000L, 72)),
                ForecastSnapshot.empty("no_data"), now, currentMgDl, -1.2f,
                now);
        return session;
    }

    private static ForecastReading reading(long atMs, int glucoseMgDl) {
        return new ForecastReading("", atMs, glucoseMgDl, null,
                "", "", null);
    }

    private static Context context(int widthDp, int heightDp, float fontScale) {
        Context application = RuntimeEnvironment.getApplication();
        Configuration configuration = new Configuration(
                application.getResources().getConfiguration());
        configuration.densityDpi = 160;
        configuration.screenWidthDp = widthDp;
        configuration.screenHeightDp = heightDp;
        configuration.smallestScreenWidthDp = Math.min(widthDp, heightDp);
        configuration.orientation = widthDp > heightDp
                ? Configuration.ORIENTATION_LANDSCAPE
                : Configuration.ORIENTATION_PORTRAIT;
        configuration.fontScale = fontScale;
        return new ContextThemeWrapper(application.createConfigurationContext(
                configuration), R.style.AppTheme_ClinicalDark);
    }

    private static void measure(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width,
                        View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height,
                        View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    private static Rect boundsWithin(ViewGroup root, View view) {
        Rect bounds = new Rect(0, 0, view.getWidth(), view.getHeight());
        root.offsetDescendantRectToMyCoords(view, bounds);
        return bounds;
    }

    private static Rect rounded(android.graphics.RectF bounds) {
        Rect result = new Rect();
        bounds.round(result);
        return result;
    }

    private static String value(CriticalAlarmSurface surface) {
        return text(surface, CriticalAlarmSurface.GLUCOSE_VALUE_ID);
    }

    private static String text(View root, int id) {
        TextView view = root.findViewById(id);
        assertNotNull(view);
        return view.getText().toString();
    }

    private static int buttonCount(View view) {
        int result = view instanceof Button ? 1 : 0;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                result += buttonCount(group.getChildAt(index));
            }
        }
        return result;
    }

    private static void assertRetroTypography(View view) {
        if (view instanceof TextView) {
            Typeface actual = ((TextView) view).getTypeface();
            assertNotNull(actual);
            assertEquals(CriticalAlarmSurface.retroTypeface(
                    actual.getStyle()), actual);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                assertRetroTypography(group.getChildAt(index));
            }
        }
    }

    private static boolean containsText(View view, String value) {
        if (view instanceof TextView
                && value.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                if (containsText(group.getChildAt(index), value)) return true;
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
                CriticalAlarmMiniChart result = findChart(group.getChildAt(index));
                if (result != null) return result;
            }
        }
        return null;
    }
}
