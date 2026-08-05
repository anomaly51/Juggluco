package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
    public void primaryNavigationExcludesSecondaryDestinations() {
        assertTrue(DashboardChrome.isPrimaryNavigationAction(
                R.id.modern_dashboard_overview));
        assertTrue(DashboardChrome.isPrimaryNavigationAction(
                R.id.modern_dashboard_add_intake));
        assertTrue(DashboardChrome.isPrimaryNavigationAction(
                R.id.modern_dashboard_menu));
        assertFalse(DashboardChrome.isPrimaryNavigationAction(
                R.id.modern_dashboard_records));
        assertFalse(DashboardChrome.isPrimaryNavigationAction(
                R.id.modern_dashboard_statistics));
    }

    @Test
    public void navigationRailCoversLandscapePhonesAndPortraitFoldables() {
        assertFalse(DashboardChrome.shouldUseNavigationRail(411, 891));
        assertTrue(DashboardChrome.shouldUseNavigationRail(891, 411));
        assertTrue(DashboardChrome.shouldUseNavigationRail(700, 900));
        assertFalse(DashboardChrome.shouldUseNavigationRail(599, 900));
        assertFalse(DashboardChrome.shouldUseNavigationRail(840, 340));
    }

    @Test
    public void twoPaneLayoutKeepsNarrowPortraitGraphsReadable() {
        assertTrue(DashboardChrome.shouldUseTwoPaneLayout(891, 411));
        assertFalse(DashboardChrome.shouldUseTwoPaneLayout(700, 900));
        assertTrue(DashboardChrome.shouldUseTwoPaneLayout(840, 900));
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
    public void graphGesturesRequireTheVisibleOverviewDestination() {
        int overview = R.id.modern_dashboard_overview;
        int records = R.id.modern_dashboard_records;

        assertTrue(DashboardChrome.isGraphGestureState(true, false, overview));
        assertFalse(DashboardChrome.isGraphGestureState(false, false, overview));
        assertFalse(DashboardChrome.isGraphGestureState(true, true, overview));
        assertFalse(DashboardChrome.isGraphGestureState(true, false, records));
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
