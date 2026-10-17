package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/** Behavioral contracts for the Canvas/Skia dashboard chart data plane. */
public class ModernGlucoseChartDataTest {
    @Test
    public void nativeTriplesAreValidatedSortedAndDeduplicated() {
        long early = 1_000_000L;
        long late = 1_060_000L;
        List<ModernGlucoseChartView.Reading> readings =
                ModernGlucoseChartView.parseActualReadings(new long[]{
                        late, 130, Float.floatToIntBits(1.5f),
                        early, 100, Float.floatToIntBits(-.5f),
                        late, 131, Float.floatToIntBits(Float.NaN),
                        0, 120, Float.floatToIntBits(0f),
                        early + 1, 601, Float.floatToIntBits(0f),
                        44
                });

        assertEquals(2, readings.size());
        assertEquals(early, readings.get(0).atMs);
        assertEquals(100f, readings.get(0).mgDl, 0f);
        assertEquals(-.5f, readings.get(0).rateMgDlPerMinute, 0f);
        assertEquals(late, readings.get(1).atMs);
        assertEquals(131f, readings.get(1).mgDl, 0f);
        assertEquals(0f, readings.get(1).rateMgDlPerMinute, 0f);
    }

    @Test
    public void legacyDashboardQuadruplesRemainTemporarilyReadable() {
        long early = 1_000_000L;
        long late = 1_060_000L;
        List<ModernGlucoseChartView.Reading> readings =
                ModernGlucoseChartView.parseDashboardReadings(new long[]{
                        late, Float.floatToIntBits(131.5f),
                        ModernGlucoseChartView.SOURCE_SCAN,
                        Float.floatToIntBits(1.25f),
                        early, Float.floatToIntBits(99.5f),
                        ModernGlucoseChartView.SOURCE_HISTORY
                                | ModernGlucoseChartView.SOURCE_CALIBRATED,
                        Float.floatToIntBits(Float.NaN),
                        early + 1, Float.floatToIntBits(100f), 7,
                        Float.floatToIntBits(0f),
                        44
                });

        assertEquals(2, readings.size());
        assertEquals(early, readings.get(0).atMs);
        assertEquals(99.5f, readings.get(0).mgDl, 0f);
        assertEquals(0f, readings.get(0).rateMgDlPerMinute, 0f);
        assertEquals(ModernGlucoseChartView.SOURCE_HISTORY
                        | ModernGlucoseChartView.SOURCE_CALIBRATED,
                readings.get(0).sourceKind);
        assertEquals(ModernGlucoseChartView.SERIES_UNKNOWN,
                readings.get(0).seriesId);
        assertEquals(late, readings.get(1).atMs);
        assertEquals(ModernGlucoseChartView.SOURCE_SCAN,
                readings.get(1).sourceKind);
    }

    @Test
    public void dashboardQuintuplesPreserveSeriesAndEqualTimestamps() {
        long early = 1_000_000L;
        long late = 1_060_000L;
        List<ModernGlucoseChartView.Reading> readings =
                ModernGlucoseChartView.parseDashboardReadings(new long[]{
                        late, Float.floatToIntBits(131.5f),
                        ModernGlucoseChartView.SOURCE_SCAN,
                        Float.floatToIntBits(1.25f), 7,
                        early, Float.floatToIntBits(99.5f),
                        ModernGlucoseChartView.SOURCE_HISTORY
                                | ModernGlucoseChartView.SOURCE_CALIBRATED,
                        Float.floatToIntBits(Float.NaN), 3,
                        late, Float.floatToIntBits(130f),
                        ModernGlucoseChartView.SOURCE_STREAM,
                        Float.floatToIntBits(0f), 8,
                        late + 60_000L, Float.floatToIntBits(140f),
                        ModernGlucoseChartView.SOURCE_STREAM,
                        Float.floatToIntBits(0f), 8
                });

        assertEquals(5, ModernGlucoseChartView.dashboardReadingStride(
                new long[20]));
        assertEquals(4, readings.size());
        assertEquals(3, readings.get(0).seriesId);
        assertEquals(late, readings.get(1).atMs);
        assertEquals(7, readings.get(1).seriesId);
        assertEquals(late, readings.get(2).atMs);
        assertEquals(8, readings.get(2).seriesId);
    }

    @Test
    public void eventAmountLabelSeparatesRapidAndLongInsulin() {
        assertEquals("42g · R5U · L6U · ×2",
                ModernGlucoseChartView.formatEventAmounts(
                        true, 42f, 5f, 6f, 2));
        assertEquals("0g", ModernGlucoseChartView.formatEventAmounts(
                true, 0f, 0f, 0f, 1));
        assertEquals("", ModernGlucoseChartView.formatEventAmounts(
                false, 0f, 0f, 0f, 1));
    }

    @Test
    public void actualGapUsesTheEstablishedFiveAndAHalfMinuteBoundary() {
        ModernGlucoseChartView.Reading first = reading(1_000_000L, 100f);
        assertFalse(ModernGlucoseChartView.isActualGap(first,
                reading(1_330_000L, 105f)));
        assertTrue(ModernGlucoseChartView.isActualGap(first,
                reading(1_330_001L, 105f)));
        assertTrue(ModernGlucoseChartView.isActualGap(first,
                reading(999_999L, 105f)));
    }

    @Test
    public void actualSegmentsNeverJoinDifferentSeriesOrScans() {
        ModernGlucoseChartView.Reading seriesOne = reading(
                1_000_000L, 100f, ModernGlucoseChartView.SOURCE_STREAM, 1);
        ModernGlucoseChartView.Reading sameSeries = reading(
                1_300_000L, 105f, ModernGlucoseChartView.SOURCE_STREAM, 1);
        ModernGlucoseChartView.Reading otherLayer = reading(
                1_300_000L, 105f, ModernGlucoseChartView.SOURCE_HISTORY, 1);
        ModernGlucoseChartView.Reading otherSeries = reading(
                1_300_000L, 105f, ModernGlucoseChartView.SOURCE_STREAM, 2);
        ModernGlucoseChartView.Reading scan = reading(
                1_300_000L, 105f, ModernGlucoseChartView.SOURCE_SCAN, 1);

        assertTrue(ModernGlucoseChartView.canConnectActual(
                seriesOne, sameSeries));
        assertFalse(ModernGlucoseChartView.canConnectActual(
                seriesOne, otherSeries));
        assertFalse(ModernGlucoseChartView.canConnectActual(
                seriesOne, otherLayer));
        assertFalse(ModernGlucoseChartView.canConnectActual(seriesOne, scan));
    }

    @Test
    public void oneLineSegmentSplitsAtBothClinicalThresholds() {
        ModernGlucoseChartView.Reading from = reading(1_000L, 60f);
        ModernGlucoseChartView.Reading to = reading(4_000L, 190f);
        List<ModernGlucoseChartView.LinePiece> pieces =
                ModernGlucoseChartView.splitZoneSegment(from, to, 70f, 180f);

        assertEquals(3, pieces.size());
        assertEquals(ModernGlucoseChartView.Zone.LOW, pieces.get(0).zone);
        assertEquals(ModernGlucoseChartView.Zone.TARGET, pieces.get(1).zone);
        assertEquals(ModernGlucoseChartView.Zone.HIGH, pieces.get(2).zone);
        assertEquals(70f, pieces.get(0).to.mgDl, .0001f);
        assertEquals(70f, pieces.get(1).from.mgDl, .0001f);
        assertEquals(180f, pieces.get(1).to.mgDl, .0001f);
        assertEquals(180f, pieces.get(2).from.mgDl, .0001f);
        assertEquals(from.atMs, pieces.get(0).from.atMs);
        assertEquals(to.atMs, pieces.get(2).to.atMs);
    }

    @Test
    public void descendingSegmentKeepsChronologyAndSemanticOrder() {
        List<ModernGlucoseChartView.LinePiece> pieces =
                ModernGlucoseChartView.splitZoneSegment(
                        reading(1_000L, 190f), reading(4_000L, 60f),
                        70f, 180f);

        assertEquals(3, pieces.size());
        assertEquals(ModernGlucoseChartView.Zone.HIGH, pieces.get(0).zone);
        assertEquals(ModernGlucoseChartView.Zone.TARGET, pieces.get(1).zone);
        assertEquals(ModernGlucoseChartView.Zone.LOW, pieces.get(2).zone);
        for (ModernGlucoseChartView.LinePiece piece : pieces) {
            assertTrue(piece.to.atMs > piece.from.atMs);
        }
    }

    @Test
    public void eventAnchorInterpolatesOnlyAcrossClinicallyNearbySamples() {
        List<ModernGlucoseChartView.Reading> close = Arrays.asList(
                reading(1_000_000L, 100f), reading(1_300_000L, 140f));
        assertEquals(120f, ModernGlucoseChartView.interpolatedValueAt(
                close, 1_150_000L), .0001f);

        List<ModernGlucoseChartView.Reading> gap = Arrays.asList(
                reading(1_000_000L, 100f), reading(2_800_001L, 140f));
        assertNull(ModernGlucoseChartView.interpolatedValueAt(
                gap, 1_900_000L));
        assertNull(ModernGlucoseChartView.interpolatedValueAt(
                gap, 1_600_000L));

        List<ModernGlucoseChartView.Reading> seriesBoundary = Arrays.asList(
                reading(1_000_000L, 100f,
                        ModernGlucoseChartView.SOURCE_STREAM, 1),
                reading(1_300_000L, 140f,
                        ModernGlucoseChartView.SOURCE_STREAM, 2));
        assertNull(ModernGlucoseChartView.interpolatedValueAt(
                seriesBoundary, 1_150_000L));
    }

    @Test
    public void eventClustersRequirePixelAndTimeProximity() {
        assertTrue(ModernGlucoseChartView.shouldClusterEvent(
                1_000_000L, 1_060_000L, 1_120_000L,
                10f, 15f, 20f, 10f, 20f));
        assertFalse(ModernGlucoseChartView.shouldClusterEvent(
                1_000_000L, 1_060_000L,
                1_060_000L + ModernGlucoseChartView.MAX_EVENT_CLUSTER_GAP_MS
                        + 1L,
                10f, 15f, 16f, 10f, 20f));
        assertFalse(ModernGlucoseChartView.shouldClusterEvent(
                1_000_000L, 1_060_000L, 1_120_000L,
                10f, 15f, 30.1f, 20f, 20f));
    }

    @Test
    public void nearestReadingIsDeterministicAtAnExactTie() {
        ModernGlucoseChartView.Reading first = reading(1_000L, 100f);
        ModernGlucoseChartView.Reading second = reading(2_000L, 110f);
        List<ModernGlucoseChartView.Reading> readings =
                Arrays.asList(first, second);

        assertEquals(first, ModernGlucoseChartView.nearestReading(
                readings, 1_500L));
        assertEquals(second, ModernGlucoseChartView.latestAtOrBefore(
                readings, 2_500L));
        assertNull(ModernGlucoseChartView.latestAtOrBefore(
                readings, 999L));
    }

    @Test
    public void yRangeIncludesTheCompleteValidatedForecastInterval() {
        long start = 1_000_000L;
        long end = 2_000_000L;
        List<ModernGlucoseChartView.Reading> actual = Arrays.asList(
                reading(start, 45f), reading(end, 260f));
        List<ForecastSnapshot.Point> forecast = Collections.singletonList(
                new ForecastSnapshot.Point(1_500_000L, 300f, 1f, 700f));

        ModernGlucoseChartView.YRange range =
                ModernGlucoseChartView.computeYRange(actual, forecast,
                        75.6f, 162f, start, end);

        assertTrue(range.minimumMgDl <= 45f);
        assertTrue(range.minimumMgDl <= 1f);
        assertTrue(range.maximumMgDl >= 700f);
        assertTrue(range.minimumMgDl <= 75.6f);
        assertTrue(range.maximumMgDl >= 162f);
        assertTrue(range.tickMgDl > 0f);
    }

    @Test
    public void emptyYRangeRemainsUsefulAroundTarget() {
        ModernGlucoseChartView.YRange range =
                ModernGlucoseChartView.computeYRange(
                        Collections.emptyList(), Collections.emptyList(),
                        75.6f, 162f, 0L, 1L);
        assertTrue(range.minimumMgDl < 75.6f);
        assertTrue(range.maximumMgDl > 162f);
        assertTrue(range.span() >= 72f);
    }

    @Test
    public void yTicksAreNiceInTheDisplayedUnit() {
        ModernGlucoseChartView.YRange range =
                new ModernGlucoseChartView.YRange(0f, 300f, 100f);

        assertEquals(2f, ModernGlucoseChartView.displayTickStep(
                range, 1, 8), 0f);
        assertEquals(50f, ModernGlucoseChartView.displayTickStep(
                range, 0, 8), 0f);
    }

    @Test
    public void displayTickStepStaysReadableForCompactCharts() {
        ModernGlucoseChartView.YRange range =
                new ModernGlucoseChartView.YRange(36f, 216f, 50f);

        assertEquals(2f, ModernGlucoseChartView.displayTickStep(
                range, 1, 5), 0f);
        assertEquals(50f, ModernGlucoseChartView.displayTickStep(
                range, 0, 5), 0f);
    }

    private static ModernGlucoseChartView.Reading reading(long time,
            float mgDl) {
        return new ModernGlucoseChartView.Reading(time, mgDl, 0f);
    }

    private static ModernGlucoseChartView.Reading reading(long time,
            float mgDl, int source, int series) {
        return new ModernGlucoseChartView.Reading(time, mgDl, 0f,
                source, series);
    }
}
