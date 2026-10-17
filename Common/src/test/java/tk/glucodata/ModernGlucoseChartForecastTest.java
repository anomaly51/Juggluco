package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/** Forecast and activity semantics must not be altered by visual migration. */
public class ModernGlucoseChartForecastTest {
    @Test
    public void forecastClipInterpolatesExactNowAndRightBoundaries() {
        List<ForecastSnapshot.Point> source = Arrays.asList(
                point(1_000L, 100f, 90f, 110f),
                point(2_000L, 120f, 100f, 140f),
                point(3_000L, 140f, 105f, 180f));

        List<ForecastSnapshot.Point> clipped =
                ModernGlucoseChartView.clippedForecastPoints(
                        source, 1_500L, 2_500L);

        assertEquals(3, clipped.size());
        assertEquals(1_500L, clipped.get(0).atMs);
        assertEquals(110f, clipped.get(0).medianMgDl, .0001f);
        assertEquals(2_000L, clipped.get(1).atMs);
        assertEquals(2_500L, clipped.get(2).atMs);
        assertEquals(130f, clipped.get(2).medianMgDl, .0001f);
    }

    @Test
    public void forecastClipNeverInventsDataOutsideBackendHorizon() {
        List<ForecastSnapshot.Point> source = Arrays.asList(
                point(1_000L, 100f, 90f, 110f),
                point(2_000L, 120f, 100f, 140f));

        assertTrue(ModernGlucoseChartView.clippedForecastPoints(
                source, 2_100L, 3_000L).isEmpty());
        assertTrue(ModernGlucoseChartView.clippedForecastPoints(
                source, 0L, 900L).isEmpty());
        assertTrue(ModernGlucoseChartView.clippedForecastPoints(
                Collections.singletonList(source.get(0)), 1_000L,
                2_000L).isEmpty());
    }

    @Test
    public void forecastKeepsFullValidatedConfidenceInterval() {
        List<ForecastSnapshot.Point> source = Arrays.asList(
                point(1_000L, 100f, 1f, 700f),
                point(2_000L, 120f, 2f, 800f));

        List<ForecastSnapshot.Point> clipped =
                ModernGlucoseChartView.clippedForecastPoints(
                        source, 1_000L, 2_000L);

        assertEquals(1f, clipped.get(0).lowMgDl, 0f);
        assertEquals(700f, clipped.get(0).highMgDl, 0f);
        assertEquals(800f, clipped.get(1).highMgDl, 0f);
    }

    @Test
    public void invalidForecastIntervalIsRejectedInsteadOfClamped() {
        List<ForecastSnapshot.Point> invalid = Arrays.asList(
                point(1_000L, 100f, 90f, 110f),
                point(2_000L, 120f, 130f, 140f));

        assertTrue(ModernGlucoseChartView.clippedForecastPoints(
                invalid, 1_000L, 2_000L).isEmpty());
    }

    @Test
    public void triangularActivityIsCausalAndPeaksAtBackendPeak() {
        ForecastSnapshot.Activity activity = new ForecastSnapshot.Activity(
                "rapid", ForecastSnapshot.Activity.KIND_RAPID, "NovoRapid",
                1_000L, 2_000L, 4_000L, 1f, .8f, null);

        assertEquals(0f, ModernGlucoseChartView.activityLevel(
                activity, 999L), 0f);
        assertEquals(0f, ModernGlucoseChartView.activityLevel(
                activity, 1_000L), 0f);
        assertEquals(.5f, ModernGlucoseChartView.activityLevel(
                activity, 1_500L), .0001f);
        assertEquals(1f, ModernGlucoseChartView.activityLevel(
                activity, 2_000L), .0001f);
        assertEquals(.5f, ModernGlucoseChartView.activityLevel(
                activity, 3_000L), .0001f);
        assertEquals(0f, ModernGlucoseChartView.activityLevel(
                activity, 4_000L), 0f);
    }

    @Test
    public void sampledActivityUsesBackendCurveInsideItsWindow() {
        List<ForecastSnapshot.ActivityPoint> points = Arrays.asList(
                new ForecastSnapshot.ActivityPoint(1_000L, 0f, 0f, 0f),
                new ForecastSnapshot.ActivityPoint(2_000L, 0f, 0f, .8f),
                new ForecastSnapshot.ActivityPoint(3_000L, 0f, 0f, .2f));
        ForecastSnapshot.Activity activity = new ForecastSnapshot.Activity(
                "meal", ForecastSnapshot.Activity.KIND_MEAL, "Meal",
                1_000L, 2_000L, 4_000L, 1f, .8f, null,
                null, "", "", null, points);

        assertEquals(.4f, ModernGlucoseChartView.activityLevel(
                activity, 1_500L), .0001f);
        assertEquals(.5f, ModernGlucoseChartView.activityLevel(
                activity, 2_500L), .0001f);
        assertEquals(0f, ModernGlucoseChartView.activityLevel(
                activity, 4_000L), 0f);
    }

    @Test
    public void zoneBoundariesBelongToTarget() {
        assertEquals(ModernGlucoseChartView.Zone.LOW,
                ModernGlucoseChartView.zone(69f, 70f, 180f));
        assertEquals(ModernGlucoseChartView.Zone.TARGET,
                ModernGlucoseChartView.zone(70f, 70f, 180f));
        assertEquals(ModernGlucoseChartView.Zone.TARGET,
                ModernGlucoseChartView.zone(180f, 70f, 180f));
        assertEquals(ModernGlucoseChartView.Zone.HIGH,
                ModernGlucoseChartView.zone(181f, 70f, 180f));
    }

    @Test
    public void confidenceRibbonAndBoundariesRemainVisible() {
        int nearLow = ModernGlucoseChartView.forecastRibbonNearAlpha(0f);
        int nearHigh = ModernGlucoseChartView.forecastRibbonNearAlpha(1f);
        int farLow = ModernGlucoseChartView.forecastRibbonFarAlpha(0f);
        int farHigh = ModernGlucoseChartView.forecastRibbonFarAlpha(1f);
        int boundaryLow = ModernGlucoseChartView.forecastBoundaryAlpha(0f);
        int boundaryHigh = ModernGlucoseChartView.forecastBoundaryAlpha(1f);

        assertTrue(nearLow >= 30);
        assertTrue(farLow >= 20);
        assertTrue(boundaryLow >= 80);
        assertTrue(nearHigh > nearLow);
        assertTrue(farHigh > farLow);
        assertTrue(boundaryHigh > boundaryLow);
        assertEquals(nearLow,
                ModernGlucoseChartView.forecastRibbonNearAlpha(-1f));
        assertEquals(boundaryHigh,
                ModernGlucoseChartView.forecastBoundaryAlpha(2f));
    }

    @Test
    public void manualForecastDashPhaseContinuesAcrossShortSamples() {
        float phase = ModernGlucoseChartView.nextDashPhase(
                0f, 5f, 7f, 5f);
        assertEquals(5f, phase, 0f);
        phase = ModernGlucoseChartView.nextDashPhase(
                phase, 4f, 7f, 5f);
        assertEquals(9f, phase, 0f);
        phase = ModernGlucoseChartView.nextDashPhase(
                phase, 4f, 7f, 5f);
        assertEquals(1f, phase, 0f);
    }

    private static ForecastSnapshot.Point point(long time, float median,
            float low, float high) {
        return new ForecastSnapshot.Point(time, median, low, high);
    }
}
