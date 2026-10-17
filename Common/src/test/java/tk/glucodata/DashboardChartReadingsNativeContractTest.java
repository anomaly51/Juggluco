package tk.glucodata;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Source contract for the bounded, presentation-faithful dashboard JNI feed. */
public class DashboardChartReadingsNativeContractTest {
    @Test
    public void javaAndNativeExposeFlatQuintupleContract() throws Exception {
        String natives=source("src","main","java","tk","glucodata",
                "Natives.java");
        String bridge=source("src","main","cpp","curve","javacurve.cpp");

        assertTrue(natives.contains(
                "long[] dashboardChartReadings(long startMs,long endMs,"));
        String method=between(bridge,
                "fromjava(dashboardChartReadings)",
                "fromjava(graphEventAt)");
        assertTrue(method.contains(
                "std::clamp<int>(requestedLimit,0,dashboardMaximumReadings)"));
        assertTrue(bridge.contains("dashboardMaximumReadings=20'000"));
        assertTrue(method.contains("requestedStartMs<0"));
        assertTrue(method.contains("requestedEndMs<requestedStartMs"));
        assertTrue(method.contains("!sensors||!settings"));
        assertTrue(natives.contains("chronological flat quintuples"));
        assertTrue(natives.contains("sensorSeriesId"));
        assertTrue(method.contains("selected.size()*5U"));
        assertTrue(method.contains("reading.time)*1000LL"));
        assertTrue(method.contains("std::memcpy(&glucoseBits"));
        assertTrue(method.contains("std::memcpy(&rateBits"));
        assertInOrder(method,
                "flat.push_back(static_cast<jlong>(reading.time)*1000LL)",
                "static_cast<std::uint64_t>(glucoseBits)",
                "flat.push_back(static_cast<jlong>(reading.sourceKind))",
                "static_cast<std::uint64_t>(rateBits)",
                "flat.push_back(static_cast<jlong>(reading.sensorSeriesId))");
    }

    @Test
    public void bridgeUsesEveryEnabledPresentationLayerInRenderOrder()
            throws Exception {
        String bridge=source("src","main","cpp","curve","javacurve.cpp");
        String method=between(bridge,
                "fromjava(dashboardChartReadings)",
                "fromjava(graphEventAt)");

        assertInOrder(method,
                "if(display->showhistories)",
                "if(display->showcalibratedhistories)",
                "if(display->showcalibratedstream)",
                "if(display->showstream)",
                "if(display->showcalibratedscans)",
                "if(display->showscans)",
                "std::stable_sort(readings.begin(),readings.end()",
                "unique.back()=reading");
        assertTrue(bridge.contains("dashboardHistorySource=1"));
        assertTrue(bridge.contains("dashboardStreamSource=2"));
        assertTrue(bridge.contains("dashboardScanSource=3"));
        assertTrue(bridge.contains("dashboardCalibratedSource=0x100"));
        assertTrue(method.contains("sensors->shownSensorsInPeriod"));
        assertTrue(method.contains(
                "static_cast<std::int32_t>(sensor->sensorIndex)"));
    }

    @Test
    public void bridgeRejectsFutureAndInvalidRowsAndMatchesCalibrationPrecision()
            throws Exception {
        String bridge=source("src","main","cpp","curve","javacurve.cpp");
        String helpers=between(bridge,"namespace {",
                "fromjava(dashboardChartReadings)");

        assertTrue(helpers.contains("point.t>latestActual"));
        assertTrue(helpers.contains("!point.valid(0)"));
        assertTrue(helpers.contains("!point->valid()"));
        assertTrue(helpers.contains("!sensor->hasRealHistory()"));
        assertFalse(helpers.contains("sensor->hasHistory()"));
        assertTrue(helpers.contains("CalibrateBackward<ScanData>"));
        assertTrue(helpers.contains("CalibrateBackward<Glucose>"));
        assertTrue(helpers.contains("static_cast<std::int32_t>(calibrated)"));
        assertTrue(helpers.contains("std::round(calibrated*10.0)/10.0"));
        assertTrue(helpers.contains("if(!allValues)"));
        assertTrue(helpers.contains("rawForEarlier=true"));
    }

    @Test
    public void bridgeAddsOnlyGlobalNeighbourContextAndKeepsLegacyBackfill()
            throws Exception {
        String bridge=source("src","main","cpp","curve","javacurve.cpp");
        String bounded=between(bridge,"dashboardBoundedWindow(",
                "}\n\nextern \"C\" JNIEXPORT jlongArray JNICALL fromjava(dashboardChartReadings)");

        assertTrue(bounded.contains("std::lower_bound"));
        assertTrue(bounded.contains("std::upper_bound"));
        assertTrue(bounded.contains("hasBefore?1U:0U"));
        assertTrue(bounded.contains("hasAfter?1U:0U"));
        assertTrue(bounded.contains("result.size()<limit"));

        String forecast=between(bridge,"fromjava(forecastReadings)",
                "namespace {");
        assertTrue(forecast.contains("flat.reserve(unique.size()*3U)"));
        assertTrue(forecast.contains("unique.resize(static_cast<size_t>(limit))"));
        assertFalse(forecast.contains("dashboardChartReadings"));
    }

    private static String source(String... parts) throws Exception {
        Path path=Paths.get(parts[0],java.util.Arrays.copyOfRange(
                parts,1,parts.length));
        if(!Files.exists(path)) path=Paths.get("Common").resolve(path);
        return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);
    }

    private static String between(String value,String start,String end) {
        int first=value.indexOf(start);
        int last=value.indexOf(end,first);
        assertTrue("Missing start marker: "+start,first>=0);
        assertTrue("Missing end marker: "+end,last>first);
        return value.substring(first,last);
    }

    private static void assertInOrder(String value,String... snippets) {
        int previous=-1;
        for(String snippet:snippets) {
            int current=value.indexOf(snippet,previous+1);
            assertTrue("Missing or out-of-order snippet: "+snippet,
                    current>previous);
            previous=current;
        }
    }
}
