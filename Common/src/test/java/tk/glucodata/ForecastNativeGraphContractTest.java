package tk.glucodata;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Source-level guard for the typed Android/native forecast boundary. */
public class ForecastNativeGraphContractTest {
    @Test
    public void javaAndJniExposeTypedForecastContracts() throws Exception {
        String natives=javaSource("Natives.java");
        String bridge=cppSource("curve","javacurve.cpp");

        assertTrue(natives.contains(
                "setForecast(long[] timesMs,float[] medianMgDl,"));
        assertTrue(natives.contains(
                "setForecastActivities(int[] kinds,long[] startsMs,"));
        assertTrue(natives.contains(
                "setForecastActivitiesSampled(int[] kinds,"));
        assertTrue(natives.contains(
                "long[] forecastReadings(long afterMs,int limit)"));
        assertTrue(bridge.contains("fromjava(setForecast)"));
        assertTrue(bridge.contains("fromjava(setForecastActivities)"));
        assertTrue(bridge.contains(
                "fromjava(setForecastActivitiesSampled)"));
        assertTrue(bridge.contains("fromjava(forecastReadings)"));
    }

    @Test
    public void rendererUsesFutureBandDashedPathAndTypedActivities()
            throws Exception {
        String renderer=cppSource("curve","curve.cpp");
        String helper=cppSource("curve","forecastgraph.hpp");

        assertTrue(renderer.contains("drawforecastactivities(avg"));
        assertTrue(renderer.contains("forecastgraph::ActivityKind::Meal"));
        assertTrue(renderer.contains("forecastgraph::ActivityKind::RapidInsulin"));
        assertTrue(renderer.contains("hexcolor(0x55C8F2)"));
        assertTrue(renderer.contains("hexcolor(0xB69AF5)"));
        assertTrue(renderer.contains("const NVGpaint band=nvgLinearGradient"));
        assertTrue(renderer.contains("const float dashLength="));
        assertTrue(renderer.contains("forecastActualAnchor"));
        assertTrue(renderer.contains("forecastCalibrator.calibrateNow("));
        assertTrue(renderer.contains("showcalibratedscans&&!showscans"));
        assertTrue(renderer.contains("showcalibratedstream&&!showstream"));
        assertTrue(renderer.contains("points.front().time==clipStart"));
        assertTrue(renderer.contains("forecastgraph::bounded_low(point)"));
        assertTrue(renderer.contains("forecastgraph::bounded_high(point)"));
        assertTrue(helper.contains("static_assert(live_start"));
        assertTrue(helper.contains("maximum_start"));
        assertTrue(helper.contains("std::vector<ActivitySample> samples"));
        assertTrue(helper.contains(
                "inline float activity_level(const Activity &activity"));
        assertTrue(renderer.contains(
                "forecastgraph::activity_level(activity,"));
        String activityRenderer=between(renderer,
                "void JCurve::drawforecastactivities", "void JCurve::drawforecast(");
        assertTrue(activityRenderer.contains("for(const auto &sample:activity.samples)"));
        assertFalse(activityRenderer.contains("nvgFillPaint"));
    }

    @Test
    public void sampledJniStrictlyValidatesFlattenedArrayBoundaries()
            throws Exception {
        String bridge=cppSource("curve","javacurve.cpp");
        String repository=javaSource("ForecastRepository.java");

        String sampled=between(bridge,
                "fromjava(setForecastActivitiesSampled)",
                "fromjava(forecastReadings)");
        assertTrue(sampled.contains("env->GetArrayLength(sampleCounts)==count"));
        assertTrue(sampled.contains(
                "env->GetArrayLength(sampleTimesMs)==env->GetArrayLength(sampleLevels)"));
        assertTrue(sampled.contains("maximumSamplesPerActivity=256"));
        assertTrue(sampled.contains("maximumTotalSamples=65'536U"));
        assertTrue(sampled.contains("value<0||value==1"));
        assertTrue(sampled.contains("seconds<=previousTime"));
        assertTrue(sampled.contains("eventSamplesValid?std::move(samples)"));
        assertTrue(repository.contains(
                "Natives.setForecastActivitiesSampled(activities.kinds"));
        assertTrue(repository.contains(
                "Natives.setForecastActivities(activities.kinds"));
        // The legacy bridge remains callable for previews and older callers.
        assertTrue(bridge.contains("fromjava(setForecastActivities)"));
    }

    @Test
    public void backfillReadsOnlyActualPollStreamsAndPacksRates()
            throws Exception {
        String bridge=cppSource("curve","javacurve.cpp");

        assertTrue(bridge.contains("sensor->getPolldata()"));
        assertTrue(bridge.contains("poll.t>latestActual"));
        assertTrue(bridge.contains("millis<=after"));
        assertTrue(bridge.contains("unique.back().time==reading.time"));
        assertTrue(bridge.contains(
                "unique.resize(static_cast<size_t>(limit))"));
        assertTrue(bridge.contains("readings.push_back({poll.t,poll.g"));
        assertTrue(bridge.contains("std::memcpy(&rateBits,&reading.rate"));
        assertTrue(bridge.contains("reading.time)*1000LL"));
    }

    private static String javaSource(String name) throws Exception {
        Path path=Paths.get("src","main","java","tk","glucodata",name);
        if(!Files.exists(path))
            path=Paths.get("Common").resolve(path);
        return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);
    }

    private static String cppSource(String folder,String name) throws Exception {
        Path path=Paths.get("src","main","cpp",folder,name);
        if(!Files.exists(path))
            path=Paths.get("Common").resolve(path);
        return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);
    }

    private static String between(String value,String start,String end) {
        int first=value.indexOf(start);
        int last=value.indexOf(end,first);
        assertTrue(first>=0&&last>first);
        return value.substring(first,last);
    }
}
