package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.TimeZone;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ForecastAndroidContractTest {
    @Test
    public void liveAndHistoryUseOneImmutableTimestampIdentity()
            throws Exception {
        long at = 1_800_000_000_000L;
        ForecastReading live = ForecastReading.live("sensor", 3, at,
                120, 1.25f);
        ForecastReading history = ForecastReading.historical(at, 120,
                1.25f);

        assertEquals("cgm-1800000000000", live.readingId);
        assertEquals(live.readingId, history.readingId);
        JSONObject liveJson = live.toJson();
        assertEquals(at, liveJson.getLong("measured_at_ms"));
        assertEquals(120, liveJson.getInt("glucose_mg_dl"));
        assertEquals("sensor", liveJson.getString("sensor_id"));
        assertEquals("3", liveJson.getString("sensor_generation"));
        assertTrue(liveJson.has("quality"));
        assertEquals(live.utcOffsetMinutes,
                liveJson.getInt("utc_offset_minutes"));
        assertEquals(history.utcOffsetMinutes,
                history.toJson().getInt("utc_offset_minutes"));
        assertFalse(history.toJson().has("quality"));
    }

    @Test
    public void readingOffsetsFollowEachSampleAcrossDstWhileBatchStaysCompatible()
            throws Exception {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            ForecastReading beforeTransition = ForecastReading.historical(
                    Instant.parse("2026-03-08T06:30:00Z").toEpochMilli(),
                    118, .2f);
            ForecastReading afterTransition = ForecastReading.historical(
                    Instant.parse("2026-03-08T07:30:00Z").toEpochMilli(),
                    121, .3f);
            JSONObject body = ForecastApiClient.readingsBody(Arrays.asList(
                    beforeTransition, afterTransition), true);
            JSONArray values = body.getJSONArray("readings");

            assertEquals(-300,
                    values.getJSONObject(0).getInt("utc_offset_minutes"));
            assertEquals(-240,
                    values.getJSONObject(1).getInt("utc_offset_minutes"));
            assertEquals(-300, body.getInt("utc_offset_minutes"));
            assertTrue(body.getBoolean("backfill_complete"));

            JSONObject oldBackendBody = ForecastApiClient.readingsBody(
                    Arrays.asList(beforeTransition, afterTransition), true,
                    false);
            assertEquals(-300,
                    oldBackendBody.getInt("utc_offset_minutes"));
            assertFalse(oldBackendBody.getJSONArray("readings")
                    .getJSONObject(0).has("utc_offset_minutes"));
            assertFalse(oldBackendBody.getJSONArray("readings")
                    .getJSONObject(1).has("utc_offset_minutes"));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    public void forecastParserSortsBandsAndAllowsFreshColdStart() throws Exception {
        long now = System.currentTimeMillis();
        JSONArray points = new JSONArray()
                .put(new JSONObject().put("at_ms", now + 10 * 60_000L)
                        .put("median_mg_dl", 126).put("low_mg_dl", 150)
                        .put("high_mg_dl", 100))
                .put(new JSONObject().put("at_ms", now + 5 * 60_000L)
                        .put("median_mg_dl", 121).put("low_mg_dl", 110)
                        .put("high_mg_dl", 137));
        ForecastSnapshot forecast = ForecastSnapshot.fromJson(new JSONObject()
                .put("status", "cold_start")
                .put("generated_at_ms", now)
                .put("based_on_reading_at_ms", now)
                .put("horizon_minutes", 120)
                .put("confidence", 0.42)
                .put("points", points));

        assertEquals(2, forecast.points.size());
        assertTrue(forecast.points.get(0).atMs < forecast.points.get(1).atMs);
        assertEquals(100f, forecast.points.get(1).lowMgDl, 0f);
        assertEquals(150f, forecast.points.get(1).highMgDl, 0f);
        assertTrue(forecast.isGraphUsable(now));

        ForecastSnapshot lowConfidence = new ForecastSnapshot(
                "low_confidence", now, now, 120, "v1", .1f,
                forecast.points, forecast.activities, "uncertain");
        assertFalse(lowConfidence.isGraphUsable(now));
        assertTrue(new ForecastSnapshot("ready", now,
                now - ForecastSnapshot.MAX_GRAPH_AGE_MS, 120, "v1", .8f,
                forecast.points, forecast.activities, "")
                .isGraphUsable(now));
        assertFalse(new ForecastSnapshot("ready", now,
                now - ForecastSnapshot.MAX_GRAPH_AGE_MS - 1L, 120, "v1",
                .8f, forecast.points, forecast.activities, "")
                .isGraphUsable(now));
    }

    @Test
    public void activityParserPreservesBackendProfileAndExactAnchorPoints()
            throws Exception {
        long anchor = 1_800_000_000_000L;
        JSONArray activityPoints = new JSONArray();
        for (int index = 0; index < 25; index++) {
            activityPoints.put(new JSONObject()
                    .put("at_ms", anchor + index * 5L * 60_000L)
                    .put("minutes_from_anchor", index * 5)
                    .put("contribution_mg_dl", -index * .75)
                    .put("activity", index <= 8
                            ? index / 8.0 : (24 - index) / 16.0));
        }
        // An event-relative point without the required absolute timestamp must
        // never be guessed onto the chart.
        activityPoints.put(new JSONObject()
                .put("minutes_from_anchor", 125)
                .put("contribution_mg_dl", -4)
                .put("activity", .2));
        JSONObject rapid = new JSONObject()
                .put("event_id", "rapid-1")
                .put("kind", "rapid_insulin")
                .put("label", "NovoRapid")
                .put("start_ms", anchor - 30L * 60_000L)
                .put("peak_ms", anchor + 40L * 60_000L)
                .put("end_ms", anchor + 210L * 60_000L)
                .put("strength", .8)
                .put("confidence", .77)
                .put("amount", 4.5)
                .put("unit", "U")
                .put("profile_source", "personalized")
                .put("profile_confidence", .84)
                .put("points", activityPoints);

        ForecastSnapshot parsed = ForecastSnapshot.fromJson(new JSONObject()
                .put("status", "learning")
                .put("generated_at_ms", anchor)
                .put("based_on_reading_at_ms", anchor)
                .put("horizon_minutes", 120)
                .put("confidence", .6)
                .put("activities", new JSONArray().put(rapid)));

        assertEquals(1, parsed.activities.size());
        ForecastSnapshot.Activity factor = parsed.activities.get(0);
        assertEquals(ForecastSnapshot.Activity.KIND_RAPID, factor.kind);
        assertEquals("rapid-1", factor.eventId);
        assertEquals("NovoRapid", factor.label);
        assertNotNull(factor.amount);
        assertEquals(4.5f, factor.amount.floatValue(), 0f);
        assertEquals("U", factor.unit);
        assertEquals("personalized", factor.profileSource);
        assertNotNull(factor.profileConfidence);
        assertEquals(.84f, factor.profileConfidence.floatValue(), .0001f);
        assertEquals(25, factor.points.size());
        assertEquals(anchor, factor.points.get(0).atMs);
        assertEquals(0f, factor.points.get(0).minutesFromAnchor, 0f);
        assertEquals(anchor + 120L * 60_000L,
                factor.points.get(24).atMs);
        assertEquals(120f, factor.points.get(24).minutesFromAnchor, 0f);
    }

    @Test
    public void activityParserPreservesEffectiveActionRangesAndLegacyFallback()
            throws Exception {
        long start = 1_800_000_000_000L;
        long onset = start + 8L * 60_000L;
        long peak = start + 55L * 60_000L;
        long end = start + 210L * 60_000L;
        JSONObject ranged = new JSONObject()
                .put("event_id", "rapid-ranged")
                .put("kind", "rapid_insulin")
                .put("start_ms", start)
                .put("onset_ms", onset)
                .put("peak_ms", peak)
                .put("peak_low_ms", peak - 12L * 60_000L)
                .put("peak_high_ms", peak + 18L * 60_000L)
                .put("end_ms", end)
                .put("end_low_ms", end - 25L * 60_000L)
                .put("end_high_ms", end + 35L * 60_000L)
                .put("confidence", .72)
                .put("attribution_confidence", .64)
                .put("identifiability", "medium")
                .put("action_model", "contextual_counterfactual")
                .put("overlap_count", 2);
        ForecastSnapshot.Activity factor = parseActivity(ranged, start);

        assertEquals(Long.valueOf(onset), factor.onsetMs);
        assertEquals(Long.valueOf(peak - 12L * 60_000L), factor.peakLowMs);
        assertEquals(Long.valueOf(peak + 18L * 60_000L), factor.peakHighMs);
        assertEquals(Long.valueOf(end - 25L * 60_000L), factor.endLowMs);
        assertEquals(Long.valueOf(end + 35L * 60_000L), factor.endHighMs);
        assertEquals(.64f, factor.attributionConfidence, .0001f);
        assertEquals("medium", factor.identifiability);
        assertEquals("contextual_counterfactual", factor.actionModel);
        assertEquals(2, factor.overlapCount);
        assertTrue(factor.hasEstimatedActionWindow());

        JSONObject legacy = new JSONObject()
                .put("event_id", "legacy")
                .put("kind", "rapid_insulin")
                .put("start_ms", start)
                .put("peak_ms", peak)
                .put("end_ms", end);
        ForecastSnapshot.Activity fallback = parseActivity(legacy, start);
        assertEquals(0, fallback.overlapCount);
        assertFalse(fallback.hasEstimatedActionWindow());
        assertEquals(start, fallback.effectiveOnsetMs());
        assertEquals(peak, fallback.effectivePeakLowMs());
        assertEquals(peak, fallback.effectivePeakHighMs());
        assertEquals(end, fallback.effectiveEndLowMs());
        assertEquals(end, fallback.effectiveEndHighMs());
    }

    @Test
    public void nativeProjectionKeepsSimultaneousFactorIdentityAndRanges() {
        long start = 1_800_000_000_000L;
        ForecastSnapshot.Activity rapidA = rangedActivity("rapid-a",
                ForecastSnapshot.Activity.KIND_RAPID, "NovoRapid A", start,
                start + 8L * 60_000L, start + 45L * 60_000L,
                start + 3L * 60L * 60_000L, .81f,
                "personalized_kernel");
        ForecastSnapshot.Activity rapidB = rangedActivity("rapid-b",
                ForecastSnapshot.Activity.KIND_RAPID, "NovoRapid B", start,
                start + 10L * 60_000L, start + 58L * 60_000L,
                start + 4L * 60L * 60_000L, .72f,
                "contextual_counterfactual");
        ForecastSnapshot.Activity basal = rangedActivity("basal-a",
                ForecastSnapshot.Activity.KIND_LONG, "Tresiba", start,
                start + 2L * 60L * 60_000L,
                start + 9L * 60L * 60_000L,
                start + 42L * 60L * 60_000L, .43f, "basal_depot");
        ForecastSnapshot forecast = new ForecastSnapshot("learning", start,
                start, 120, "ranged-v1", .6f, Collections.emptyList(),
                Arrays.asList(rapidA, rapidB, basal), "experimental");

        ForecastRepository.NativeActivityProjection projection =
                ForecastRepository.nativeActivityProjection(forecast);

        assertEquals(3, projection.identityHashes.length);
        assertTrue(projection.identityHashes[0]
                != projection.identityHashes[1]);
        assertTrue(projection.identityHashes[0]
                != projection.identityHashes[2]);
        assertTrue(projection.identityHashes[1]
                != projection.identityHashes[2]);
        assertEquals(rapidA.onsetMs.longValue(), projection.onsetsMs[0]);
        assertEquals(rapidB.peakLowMs.longValue(),
                projection.peakLowsMs[1]);
        assertEquals(basal.peakHighMs.longValue(),
                projection.peakHighsMs[2]);
        assertEquals(basal.endLowMs.longValue(), projection.endLowsMs[2]);
        assertEquals(basal.endHighMs.longValue(), projection.endHighsMs[2]);
        assertEquals(.81f, projection.attributionConfidences[0], .0001f);
        assertEquals(.72f, projection.attributionConfidences[1], .0001f);
        assertEquals(.43f, projection.attributionConfidences[2], .0001f);
        assertEquals(2, projection.overlapCounts[0]);
        assertEquals(2, projection.overlapCounts[1]);
        assertEquals(2, projection.overlapCounts[2]);
    }

    @Test
    public void nativeActivityProjectionFlattensOnlyCausalNonparametricSamples() {
        long anchor = 1_800_000_000_000L;
        ArrayList<ForecastSnapshot.ActivityPoint> rapidPoints = new ArrayList<>();
        rapidPoints.add(new ForecastSnapshot.ActivityPoint(
                anchor - 5L * 60_000L, -5f, -2f, .72f));
        rapidPoints.add(new ForecastSnapshot.ActivityPoint(
                anchor, 0f, 0f, .14f));
        rapidPoints.add(new ForecastSnapshot.ActivityPoint(
                anchor + 5L * 60_000L, 5f, -8f, .91f));
        // JNI stores second-resolution graph time; a duplicate second must not
        // create a zero-width interpolation segment.
        rapidPoints.add(new ForecastSnapshot.ActivityPoint(
                anchor + 5L * 60_000L + 100L, 5f, -9f, .40f));
        rapidPoints.add(new ForecastSnapshot.ActivityPoint(
                anchor + 10L * 60_000L, 10f, -11f, .33f));
        ForecastSnapshot.Activity rapid = new ForecastSnapshot.Activity(
                "rapid-context", ForecastSnapshot.Activity.KIND_RAPID,
                "NovoRapid", anchor - 20L * 60_000L,
                anchor + 35L * 60_000L, anchor + 4L * 60L * 60_000L,
                .8f, .75f, null, 4f, "U", "personalized", .8f,
                rapidPoints);
        ForecastSnapshot.Activity longInsulin = new ForecastSnapshot.Activity(
                "long-fallback", ForecastSnapshot.Activity.KIND_LONG,
                "Tresiba", anchor - 4L * 60L * 60_000L,
                anchor + 8L * 60L * 60_000L,
                anchor + 42L * 60L * 60_000L,
                .3f, .5f, null, 12f, "U", "population_prior", .3f,
                Collections.emptyList());
        ForecastSnapshot forecast = new ForecastSnapshot("learning", anchor,
                anchor, 120, "context-v1", .6f, Collections.emptyList(),
                Arrays.asList(rapid, longInsulin), "experimental");

        ForecastRepository.NativeActivityProjection projection =
                ForecastRepository.nativeActivityProjection(forecast);

        assertEquals(2, projection.kinds.length);
        assertEquals(ForecastSnapshot.Activity.KIND_RAPID,
                projection.kinds[0]);
        assertEquals(ForecastSnapshot.Activity.KIND_LONG,
                projection.kinds[1]);
        assertEquals(3, projection.sampleCounts[0]);
        assertEquals(0, projection.sampleCounts[1]);
        assertEquals(3, projection.sampleTimesMs.length);
        assertEquals(anchor, projection.sampleTimesMs[0]);
        assertEquals(anchor + 5L * 60_000L, projection.sampleTimesMs[1]);
        assertEquals(anchor + 10L * 60_000L, projection.sampleTimesMs[2]);
        assertEquals(.14f, projection.sampleLevels[0], 0f);
        assertEquals(.91f, projection.sampleLevels[1], 0f);
        assertEquals(.33f, projection.sampleLevels[2], 0f);
    }

    @Test
    public void forecastDisplayConvertsCanonicalMgDlOnlyAtTheUiBoundary() {
        assertEquals(18d,
                ForecastDetailsPage.displayGlucoseDelta(18d, 0), 0d);
        assertEquals(1d,
                ForecastDetailsPage.displayGlucoseDelta(18d, 1), .0001d);
        assertEquals(-2d,
                ForecastDetailsPage.displayGlucoseDelta(-36d, 1), .0001d);
    }

    @Test
    public void structuredAmountIsShownOnlyInTheBadgeNotTheFactorTitle() {
        ForecastSnapshot.Activity tresiba = new ForecastSnapshot.Activity(
                "long-1", ForecastSnapshot.Activity.KIND_LONG,
                "Tresiba \u00b7 10 U", 1L, 2L, 3L, .4f, .8f, null,
                10f, "U", "personalized", .8f,
                Collections.emptyList());
        String insulinName = ForecastDetailsPage.structuredFactorName(tresiba);
        assertEquals("Tresiba", insulinName);
        assertEquals("Tresiba", ForecastDetailsPage.composeFactorLabel(
                ForecastSnapshot.Activity.KIND_LONG, "Tresiba",
                insulinName));

        ForecastSnapshot.Activity meal = new ForecastSnapshot.Activity(
                "meal-1", ForecastSnapshot.Activity.KIND_MEAL,
                "Buckwheat \u00b7 45 g carbs", 1L, 2L, 3L, .4f, .8f,
                .3f, 45f, "g", "nutrient_estimate", .7f,
                Collections.emptyList());
        String mealName = ForecastDetailsPage.structuredFactorName(meal);
        assertEquals("Buckwheat", mealName);
        assertEquals("Meal \u2014 Buckwheat",
                ForecastDetailsPage.composeFactorLabel(
                        ForecastSnapshot.Activity.KIND_MEAL, "Meal",
                        mealName));
    }

    @Test
    public void nativeTriplesDecodePackedTrendWithoutInventingQuality() {
        long timestamp = 1_800_000_000_000L;
        float trend = -1.75f;
        long[] raw = {timestamp, 112,
                Float.floatToIntBits(trend) & 0xffffffffL};
        ForecastReading reading = ForecastRepository
                .decodeNativeReadings(raw).get(0);

        assertEquals(timestamp, reading.measuredAtMs);
        assertEquals(112, reading.glucoseMgDl);
        assertEquals(trend, reading.trendMgDlMin, 0f);
        assertEquals(null, reading.quality);
    }

    @Test
    public void invalidNativeRowsCannotRejectAnEntireBackendBatch() {
        long timestamp = 1_800_000_000_000L;
        long[] raw = {
                timestamp, 12, Float.floatToIntBits(0f) & 0xffffffffL,
                timestamp + 60_000L, 118,
                Float.floatToIntBits(45f) & 0xffffffffL
        };

        java.util.ArrayList<ForecastReading> readings =
                ForecastRepository.decodeNativeReadings(raw);
        assertEquals(1, readings.size());
        assertEquals(118, readings.get(0).glucoseMgDl);
        assertEquals(null, readings.get(0).trendMgDlMin);
    }

    @Test
    public void liveSyncSelectsOnlyTheExactNativeHistorySample() {
        long wanted = 1_800_000_000_000L;
        long[] raw = {
                wanted, 119, Float.floatToIntBits(.5f) & 0xffffffffL,
                wanted + 60_000L, 121,
                Float.floatToIntBits(.75f) & 0xffffffffL
        };
        ArrayList<ForecastReading> decoded =
                ForecastRepository.decodeNativeReadings(raw);

        ForecastReading exact = ForecastRepository.exactReading(decoded,
                wanted);
        assertNotNull(exact);
        assertEquals(119, exact.glucoseMgDl);
        assertEquals(null, ForecastRepository.exactReading(decoded,
                wanted - 1L));
    }

    @Test
    public void historyLookaheadAndOverlapCutoffAreDeterministic() {
        long first = 1_800_000_000_000L;
        long[] raw = new long[1_001 * 3];
        for (int row = 0; row < 1_001; row++) {
            int index = row * 3;
            raw[index] = first + row * 60_000L;
            raw[index + 1] = 100 + row % 20;
            raw[index + 2] = Float.floatToIntBits(.25f) & 0xffffffffL;
        }

        ForecastRepository.HistoryUpload full =
                ForecastRepository.prepareHistoryUpload(raw, first - 1L,
                        Long.MAX_VALUE);
        assertEquals(1_000, full.readings.size());
        assertFalse(full.complete);
        assertEquals(first + 999L * 60_000L, full.cursorMs);

        ForecastRepository.HistoryUpload exactMultiple =
                ForecastRepository.prepareHistoryUpload(
                        Arrays.copyOf(raw, 1_000 * 3), first - 1L,
                        Long.MAX_VALUE);
        assertEquals(1_000, exactMultiple.readings.size());
        assertTrue(exactMultiple.complete);
        assertEquals(first + 999L * 60_000L,
                exactMultiple.cursorMs);

        long cutoff = first + 2L * 60_000L;
        ForecastRepository.HistoryUpload stable =
                ForecastRepository.prepareHistoryUpload(raw, first - 1L,
                        cutoff);
        assertEquals(3, stable.readings.size());
        assertTrue(stable.complete);
        assertEquals(cutoff, stable.cursorMs);

        long now = first + 10L * 60_000L;
        assertEquals(Long.MAX_VALUE,
                ForecastRepository.stableHistoryCutoff(now, 1));
        assertEquals(now - 6L * 60L * 1000L,
                ForecastRepository.stableHistoryCutoff(now, 2));
    }

    @Test
    public void serverInstanceChangeForcesReplayButMissingOldFieldDoesNot() {
        assertFalse(ForecastRepository.serverInstanceChanged("", ""));
        assertFalse(ForecastRepository.serverInstanceChanged(
                "instance-a", null));
        assertFalse(ForecastRepository.serverInstanceChanged(
                "instance-a", "instance-a"));
        assertTrue(ForecastRepository.serverInstanceChanged(
                "", "instance-a"));
        assertTrue(ForecastRepository.serverInstanceChanged(
                "instance-a", "instance-b"));
    }

    @Test
    public void liveOmitsAndHistoryDeclaresBackfillBoundary() throws Exception {
        ForecastReading reading = ForecastReading.historical(
                1_800_000_000_000L, 118, .25f);
        JSONObject live = ForecastApiClient.readingsBody(
                Collections.singletonList(reading), null);
        JSONObject partial = ForecastApiClient.readingsBody(
                Collections.singletonList(reading), false);
        JSONObject complete = ForecastApiClient.readingsBody(
                Collections.emptyList(), true);

        assertFalse(live.has("backfill_complete"));
        assertFalse(partial.getBoolean("backfill_complete"));
        assertTrue(complete.getBoolean("backfill_complete"));
        assertEquals(0, complete.getJSONArray("readings").length());
    }

    @Test
    public void statusParserToleratesNestedAndUnknownFields() throws Exception {
        ForecastModelStatus status = ForecastModelStatus.fromJson(
                new JSONObject().put("status", "learning")
                        .put("server_instance_id", "server-test-id")
                        .put("future_field", true)
                        .put("training", new JSONObject()
                                .put("state", "idle")
                                .put("sample_count", 90)
                                .put("minimum_samples", 120))
                        .put("data", new JSONObject()
                                .put("reading_count", 555)
                                .put("days_covered", 4.5)
                                .put("confirmed_meals", 7))
                        .put("accuracy", new JSONObject()
                                .put("mae_30_mg_dl", 12.25)
                                .put("mae_7d_mg_dl", 18.5)
                                .put("mae_30d_mg_dl", 20.75)));

        assertEquals("learning", status.status);
        assertEquals("server-test-id", status.serverInstanceId);
        assertEquals(90L, status.sampleCount);
        assertEquals(555L, status.readingCount);
        assertEquals(4.5, status.daysCovered, 0d);
        assertEquals(12.25, status.mae30, 0d);
        assertEquals(18.5, status.mae7d, 0d);
        assertEquals(20.75, status.mae30d, 0d);
    }

    @Test
    public void dashboardAffordanceAndFullPageAreAccessible() {
        Context context = new ContextThemeWrapper(
                RuntimeEnvironment.getApplication(),
                R.style.AppTheme_ClinicalDark);
        View dashboard = LayoutInflater.from(context).inflate(
                R.layout.modern_dashboard_chrome, null, false);
        Button affordance = dashboard.findViewById(
                R.id.modern_dashboard_forecast);
        assertNotNull(affordance);
        assertTrue(affordance.getMinimumHeight() >= dp(context, 48));

        View page = LayoutInflater.from(context).inflate(
                R.layout.modern_forecast_details, null, false);
        View close = page.findViewById(R.id.forecast_details_close);
        ImageButton refresh = page.findViewById(
                R.id.forecast_details_refresh);
        View loading = page.findViewById(R.id.forecast_details_loading);
        View refreshContainer = page.findViewById(
                R.id.forecast_details_refresh_container);
        assertNotNull(close);
        assertNotNull(refresh);
        assertNotNull(refresh.getDrawable());
        assertNotNull(loading);
        assertEquals(View.GONE, loading.getVisibility());
        assertTrue(loading.getContentDescription().length() > 0);
        assertSame(refreshContainer, refresh.getParent());
        assertSame(refreshContainer, loading.getParent());
        assertTrue(refreshContainer.getLayoutParams().height
                >= dp(context, 48));
        assertNotNull(page.findViewById(R.id.forecast_details_accuracy_7d));
        assertNotNull(page.findViewById(R.id.forecast_details_accuracy_30d));
        assertNotNull(page.findViewById(R.id.forecast_details_conditional));
        assertTrue(page.findViewById(R.id.forecast_details_factors)
                instanceof LinearLayout);
    }

    @Test
    public void factorMiniChartConsumesAbsoluteBackendPointsAndDraws() {
        Context context = new ContextThemeWrapper(
                RuntimeEnvironment.getApplication(),
                R.style.AppTheme_ClinicalDark);
        long anchor = 1_800_000_000_000L;
        ArrayList<ForecastSnapshot.ActivityPoint> points = new ArrayList<>();
        points.add(new ForecastSnapshot.ActivityPoint(anchor, 0f, 0f, 0f));
        points.add(new ForecastSnapshot.ActivityPoint(anchor + 30L * 60_000L,
                30f, -12f, 1f));
        points.add(new ForecastSnapshot.ActivityPoint(anchor + 120L * 60_000L,
                120f, -4f, .2f));
        ForecastSnapshot.Activity factor = new ForecastSnapshot.Activity(
                "rapid-1", ForecastSnapshot.Activity.KIND_RAPID,
                "NovoRapid", anchor - 20L * 60_000L,
                anchor + 30L * 60_000L, anchor + 180L * 60_000L,
                .8f, .75f, null, 3f, "U", "personalized", .8f,
                points);
        ForecastActivityMiniChart chart =
                new ForecastActivityMiniChart(context);
        chart.setFactor(factor);
        chart.setContentDescription("NovoRapid activity profile");
        int width = dp(context, 320);
        int height = dp(context, 84);
        chart.measure(View.MeasureSpec.makeMeasureSpec(width,
                        View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height,
                        View.MeasureSpec.EXACTLY));
        chart.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height,
                Bitmap.Config.ARGB_8888);
        chart.draw(new Canvas(bitmap));

        assertEquals(3, chart.sourcePointCount());
        assertEquals(width, chart.getMeasuredWidth());
        assertEquals(height, chart.getMeasuredHeight());
        assertTrue(chart.getContentDescription().length() > 0);
    }

    @Test
    public void forecastTransportUsesOnlyConfiguredBackendAndPreviewNeverUploads()
            throws Exception {
        String client = source("ForecastApiClient.java");
        String repository = source("ForecastRepository.java");
        String applic = source("Applic.java");
        String natives = source("Natives.java");
        String nativeBridge = cppSource("javacurve.cpp");

        assertTrue(client.contains("/v1/glucose/readings"));
        assertTrue(client.contains("/v1/forecast/current"));
        assertTrue(client.contains("/v1/forecast/status"));
        assertTrue(client.contains("backfill_complete"));
        assertTrue(client.contains("Boolean backfillComplete"));
        assertTrue(client.contains("unsupportedReadingUtcOffset"));
        assertTrue(client.contains("reading.toJson(includeReadingUtcOffsets)"));
        assertTrue(client.contains("Authorization\", \"Bearer \" + token"));
        assertFalse(client.toLowerCase().contains("openrouter"));
        assertTrue(repository.contains("Natives.forecastReadings"));
        assertTrue(repository.contains("Natives.setForecast"));
        assertTrue(repository.contains(
                "Natives.setForecastActivitiesRangedSampled"));
        assertTrue(natives.contains(
                "native void setForecastActivitiesRangedSampled"));
        assertTrue(nativeBridge.contains(
                "setForecastActivitiesRangedSampled)(JNIEnv*"));
        assertTrue(repository.contains("HISTORY_CURSOR_PREFIX"));
        assertTrue(repository.contains("historyCursor(generation)"));
        assertTrue(repository.contains("storeHistoryCursor(generation"));
        assertTrue(repository.contains("MessageDigest.getInstance(\"SHA-256\")"));
        assertTrue(repository.contains("remote.readingCount == 0L"));
        assertTrue(repository.contains("initialRemote.serverInstanceId"));
        assertTrue(repository.contains("SERVER_INSTANCE_PREFIX"));
        assertTrue(repository.contains("clearHistoryCursor(generation)"));
        String emptyNative = between(repository,
                "if (raw == null || raw.length < 3)", "long cutoffMs");
        assertFalse(emptyNative.contains("storeHistoryCursor"));
        assertFalse(emptyNative.contains("uploadReadings"));
        String liveSync = between(repository, "void recordLiveReading",
                "private void refresh");
        assertFalse(liveSync.contains("storeHistoryCursor"));
        assertTrue(liveSync.contains("exactNativeReading(measuredAtMs)"));
        assertFalse(liveSync.contains("ForecastReading.live"));
        assertTrue(liveSync.contains("activeSensorCount() > 1"));
        assertTrue(liveSync.contains(
                "uploadReadings(Collections.singletonList(reading))"));
        assertFalse(liveSync.contains("uploadReadings(" +
                "Collections.singletonList(reading),"));
        assertTrue(repository.contains("HISTORY_QUERY_SIZE"));
        assertTrue(repository.contains(
                "api.uploadReadings(upload.readings, upload.complete)"));
        assertTrue(repository.contains("void showDebugPreview"));
        String preview = between(repository, "void showDebugPreview",
                "void restoreGraphProjection");
        assertFalse(preview.contains("uploadReadings"));
        assertFalse(preview.contains("ForecastReading."));
        assertTrue(applic.contains("ForecastRepository.enqueueLiveReading(app"));
        assertTrue(repository.contains("LIVE_ENTRY_EXECUTOR.execute"));
    }

    private static String between(String value, String start, String end) {
        int first = value.indexOf(start);
        int last = value.indexOf(end, first);
        assertTrue(first >= 0 && last > first);
        return value.substring(first, last);
    }

    private static ForecastSnapshot.Activity parseActivity(JSONObject value,
            long anchor) throws Exception {
        ForecastSnapshot parsed = ForecastSnapshot.fromJson(new JSONObject()
                .put("status", "learning")
                .put("generated_at_ms", anchor)
                .put("based_on_reading_at_ms", anchor)
                .put("horizon_minutes", 120)
                .put("confidence", .6)
                .put("activities", new JSONArray().put(value)));
        assertEquals(1, parsed.activities.size());
        return parsed.activities.get(0);
    }

    private static ForecastSnapshot.Activity rangedActivity(String eventId,
            int kind, String label, long start, long onset, long peak,
            long end, float attribution, String actionModel) {
        return new ForecastSnapshot.Activity(eventId, kind, label, start,
                peak, end, .75f, .72f, null, 4f, "U", "personalized",
                .74f, onset, peak - 10L * 60_000L,
                peak + 15L * 60_000L, end - 20L * 60_000L,
                end + 30L * 60_000L, attribution, "medium", actionModel,
                2, Collections.emptyList());
    }

    private static String source(String name) throws Exception {
        Path path = Paths.get("src", "main", "java", "tk", "glucodata",
                name);
        if (!Files.exists(path)) path = Paths.get("Common").resolve(path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String cppSource(String name) throws Exception {
        Path path = Paths.get("src", "main", "cpp", "curve", name);
        if (!Files.exists(path)) path = Paths.get("Common").resolve(path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static int dp(Context context, int value) {
        return Math.round(value
                * context.getResources().getDisplayMetrics().density);
    }
}
