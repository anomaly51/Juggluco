package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.res.Configuration;

import org.junit.Test;

public class DashboardChromePresentationTest {
    @Test
    public void freshnessUsesTheExistingApplicationTimeoutBoundary() {
        assertFalse(DashboardChrome.isReadingStale(
                Notify.glucosetimeoutSEC - 1L));
        assertTrue(DashboardChrome.isReadingStale(
                Notify.glucosetimeoutSEC));
    }

    @Test
    public void cadenceUsesTheArithmeticMeanOfRecentValidGaps() {
        assertEquals(60_500L,
                DashboardChrome.calculateAverageReadingIntervalMs(
                        new long[]{1_000_000L, 1_060_000L, 1_121_000L},
                        330_000L));
    }

    @Test
    public void cadenceIgnoresDuplicatesAndSignalOutages() {
        assertEquals(60_000L,
                DashboardChrome.calculateAverageReadingIntervalMs(
                        new long[]{1_000L, 61_000L, 61_000L,
                                500_000L, 560_000L}, 330_000L));
        assertEquals(-1L,
                DashboardChrome.calculateAverageReadingIntervalMs(
                        new long[]{1_000L}, 330_000L));
        assertEquals(-1L,
                DashboardChrome.calculateAverageReadingIntervalMs(
                        null, 330_000L));
    }

    @Test
    public void compactClockStaysStableAtMinuteAndHourBoundaries() {
        assertEquals("1:00", DashboardChrome.formatClockDuration(60L));
        assertEquals("2:07", DashboardChrome.formatClockDuration(127L));
        assertEquals("1:04:12",
                DashboardChrome.formatClockDuration(3_852L));
    }

    @Test
    public void readingRangeClassificationUsesConfiguredTargets() {
        assertEquals(DashboardChrome.RANGE_LOW,
                DashboardChrome.classifyReadingRange("69", 70.0f, 180.0f));
        assertEquals(DashboardChrome.RANGE_IN_TARGET,
                DashboardChrome.classifyReadingRange("70", 70.0f, 180.0f));
        assertEquals(DashboardChrome.RANGE_IN_TARGET,
                DashboardChrome.classifyReadingRange("118", 70.0f, 180.0f));
        assertEquals(DashboardChrome.RANGE_HIGH,
                DashboardChrome.classifyReadingRange("181", 70.0f, 180.0f));
        assertEquals(DashboardChrome.RANGE_IN_TARGET,
                DashboardChrome.classifyReadingRange("6,4", 3.9f, 10.0f));
        assertEquals(DashboardChrome.RANGE_LOW,
                DashboardChrome.classifyReadingRange("LO", 70.0f, 180.0f));
        assertEquals(DashboardChrome.RANGE_HIGH,
                DashboardChrome.classifyReadingRange("HI", 70.0f, 180.0f));
        assertEquals(DashboardChrome.RANGE_UNKNOWN,
                DashboardChrome.classifyReadingRange("—", 70.0f, 180.0f));
    }

    @Test
    public void targetFormattingKeepsClinicalPrecisionPerUnit() {
        assertEquals("3.9", DashboardChrome.formatTargetValue(3.91f, 1)
                .replace(',', '.'));
        assertEquals("70", DashboardChrome.formatTargetValue(70.2f, 2));
    }

    @Test
    public void primaryNavigationExposesOnlyAddAndMore() {
        assertTrue(DashboardChrome.isPrimaryNavigationAction(
                R.id.modern_dashboard_add_intake));
        assertTrue(DashboardChrome.isPrimaryNavigationAction(
                R.id.modern_dashboard_menu));
        assertFalse(DashboardChrome.isPrimaryNavigationAction(
                android.R.id.content));
    }

    @Test
    public void navigationRailCoversLandscapePhonesAndPortraitFoldables() {
        assertFalse(DashboardChrome.shouldUseNavigationRail(411, 891));
        assertTrue(DashboardChrome.shouldUseNavigationRail(891, 411));
        assertTrue(DashboardChrome.shouldUseNavigationRail(700, 900));
        assertFalse(DashboardChrome.shouldUseNavigationRail(599, 900));
        assertFalse(DashboardChrome.shouldUseNavigationRail(840, 340));
        assertEquals(68, DashboardChrome.NAVIGATION_RAIL_ITEM_HEIGHT_DP);
        assertTrue(DashboardChrome.NAVIGATION_RAIL_ITEM_HEIGHT_DP * 2 + 12
                <= 360);
        assertTrue(DashboardChrome.compactNavigationWeight(
                R.id.modern_dashboard_add_intake)
                > DashboardChrome.compactNavigationWeight(
                R.id.modern_dashboard_menu));
    }

    @Test
    public void largePhoneTextStacksMetadataInsteadOfClippingTheReading() {
        assertFalse(DashboardChrome.usesStackedHeader(1.0f, 360));
        assertTrue(DashboardChrome.usesStackedHeader(1.5f, 360));
        assertTrue(DashboardChrome.usesStackedHeader(2.0f, 411));
        assertFalse(DashboardChrome.usesStackedHeader(2.0f, 700));
    }

    @Test
    public void foldAndTabletGuttersCapContentWithoutCrampingPhones() {
        assertEquals(16, DashboardChrome.chartContentGutterDp(
                411, false));
        assertEquals(16, DashboardChrome.chartContentGutterDp(
                700, true));
        assertEquals(24, DashboardChrome.chartContentGutterDp(
                1200, true));
        assertEquals(104, DashboardChrome.chartContentGutterDp(
                1600, true));
    }

    @Test
    public void retroDashboardKeepsDarkSystemBarsInBothSystemModes() {
        Configuration light = new Configuration();
        light.uiMode = Configuration.UI_MODE_NIGHT_NO;
        assertFalse(MainActivity.usesLightDashboardPalette(light));

        Configuration dark = new Configuration();
        dark.uiMode = Configuration.UI_MODE_NIGHT_YES;
        assertFalse(MainActivity.usesLightDashboardPalette(dark));
    }

    @Test
    public void graphAccessibilitySummaryUsesSingleSentenceBoundaries() {
        StringBuilder description = new StringBuilder();
        DashboardChrome.appendAccessibilitySentence(description,
                "Current glucose 6.6 mmol/L.");
        DashboardChrome.appendAccessibilitySentence(description,
                "Target 4.2–9.0 mmol/L");
        DashboardChrome.appendAccessibilitySentence(description, "");
        assertEquals("Current glucose 6.6 mmol/L. Target 4.2–9.0 mmol/L.",
                description.toString());
    }

    @Test
    public void nativeTrendNamesMapToStableUnicodeArrows() {
        assertEquals("\u21C8", DashboardChrome.trendArrowForName("DoubleUp"));
        assertEquals("\u2191", DashboardChrome.trendArrowForName("SingleUp"));
        assertEquals("\u2197", DashboardChrome.trendArrowForName("FortyFiveUp"));
        assertEquals("\u2192", DashboardChrome.trendArrowForName("Flat"));
        assertEquals("\u2198", DashboardChrome.trendArrowForName("FortyFiveDown"));
        assertEquals("\u2193", DashboardChrome.trendArrowForName("SingleDown"));
        assertEquals("\u21CA", DashboardChrome.trendArrowForName("DoubleDown"));
        assertEquals("", DashboardChrome.trendArrowForName(""));
        assertEquals("", DashboardChrome.trendArrowForName(null));
    }

    @Test
    public void graphGesturesRequireTheInternalHomeDestination() {
        int home = DashboardChrome.DESTINATION_HOME;
        int more = DashboardChrome.DESTINATION_MORE;

        assertTrue(DashboardChrome.isGraphGestureState(true, false, home));
        assertFalse(DashboardChrome.isGraphGestureState(false, false, home));
        assertFalse(DashboardChrome.isGraphGestureState(true, true, home));
        assertFalse(DashboardChrome.isGraphGestureState(true, false, more));
    }

    @Test
    public void doubleTapNowExcludesEveryGraphEdge() {
        assertTrue(GlucoseCurve.isCentralDashboardGraphPoint(
                200.0f, 120.0f, 400.0f, 240.0f, 48.0f));
        assertFalse(GlucoseCurve.isCentralDashboardGraphPoint(
                30.0f, 120.0f, 400.0f, 240.0f, 48.0f));
        assertFalse(GlucoseCurve.isCentralDashboardGraphPoint(
                370.0f, 120.0f, 400.0f, 240.0f, 48.0f));
        assertFalse(GlucoseCurve.isCentralDashboardGraphPoint(
                200.0f, 20.0f, 400.0f, 240.0f, 48.0f));
        assertFalse(GlucoseCurve.isCentralDashboardGraphPoint(
                200.0f, 220.0f, 400.0f, 240.0f, 48.0f));
    }
}
