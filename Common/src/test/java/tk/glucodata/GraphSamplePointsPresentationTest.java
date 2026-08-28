package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Protects the native modern-graph sample marker presentation contract. */
public class GraphSamplePointsPresentationTest {
    @Test
    public void markerGeometryGetsQuieterAsTheVisibleRangeGrows() {
        assertEquals(2.45f,sampleRadius(1.0f,3,false),0.0001f);
        assertEquals(1.75f,sampleRadius(1.0f,24,false),0.0001f);
        assertEquals(2.70f,sampleRadius(1.0f,3,true),0.0001f);
        assertEquals(2.00f,sampleRadius(1.0f,24,true),0.0001f);
        assertEquals(5.50f,minimumSpacing(1.0f,3),0.0001f);
        assertEquals(7.00f,minimumSpacing(1.0f,24),0.0001f);
    }

    @Test
    public void decimationKeepsMeaningfulSamplesAndLeavesCurrentEmphasisAlone() {
        assertFalse(shouldDraw(12f,10f,7f,true,false,false,false,false));
        assertTrue(shouldDraw(12f,10f,7f,true,false,false,true,false));
        assertTrue(shouldDraw(12f,10f,7f,true,false,true,false,false));
        assertFalse(shouldDraw(12f,10f,7f,true,false,true,false,true));
    }

    @Test
    public void allFiveGlucosePathsUseTheSameNativeMarkerPrimitive() throws Exception {
        String source=cpp("curve.cpp");
        String header=cpp("graphpoints.hpp");
        assertTrue(source.contains("#include \"graphpoints.hpp\""));
        assertTrue(source.contains("drawModernGraphSample"));
        String scans=between(source,"JCurve::showScan","JCurve::showlineScan");
        String stream=between(source,"JCurve::showlineScan","pair<int32_t,int32_t> histPositions");
        String calibratedHistory=between(source,"JCurve::calihistcurve","JCurve::histcurve");
        String history=between(source,"JCurve::histcurve","extern uint32_t getnumlasttime");
        String preview=between(source,"constexpr int previewcount=57",
                "CURVELOGAR(\"before showhistories\")");
        for(String path:new String[]{scans,stream,calibratedHistory,history,preview}) {
            assertTrue(path.contains("drawModernGraphSample"));
            assertTrue(path.contains("graphpoints::sample_radius"));
            assertTrue(path.contains("graphpoints::minimum_spacing"));
        }
        assertTrue(count(source,"drawModernGraphSample(")>=6);
        assertTrue(count(source,"graphpoints::sample_radius")>=5);
        assertTrue(count(source,"graphpoints::minimum_spacing")>=5);
        assertTrue(header.contains("constexpr RangeState range_state"));
        assertTrue(header.contains("static_assert(sample_radius"));
        assertTrue(header.contains("static_assert(minimum_spacing"));
    }

    @Test
    public void everyRealSampleStillParticipatesInHitTesting() throws Exception {
        String source=cpp("curve.cpp");
        String stream=between(source,"void JCurve::showlineScan","pair<int32_t,int32_t> histPositions");
        assertTrue(stream.contains("glucosepointinfo(avg,tim,glu,posx,posy)"));
        assertTrue(stream.contains("Visual density is adaptive"));
        assertTrue(stream.indexOf("glucosepointinfo(avg,tim,glu,posx,posy)")<
                stream.indexOf("Visual density is adaptive"));
        assertTrue(stream.contains("it==lastvalid,statechanged,true"));
        assertTrue(stream.contains("drawModernGraphCurrentSample"));
        assertTrue(source.contains("nvgCircle(avg,x,y,pointRadius*2.15f)"));
    }

    @Test
    public void historyMarkersPreserveLineGapsCalibrationAndHitTesting() throws Exception {
        String source=cpp("curve.cpp");
        String calibrated=between(source,"JCurve::calihistcurve","JCurve::histcurve");
        String history=between(source,"JCurve::histcurve","extern uint32_t getnumlasttime");
        for(String path:new String[]{calibrated,history}) {
            assertTrue(path.contains("glucosepointinfo"));
            assertTrue(path.contains("nvgStroke(avg)"));
            assertTrue(path.contains("if(!histglu->valid())"));
            assertTrue(path.contains("flushsegment"));
            assertTrue(path.contains("statechanged,false"));
        }
        assertTrue(calibrated.contains("CalibrateBackward<Glucose> markercalibration"));
        assertTrue(calibrated.contains("markercalibration.backvalue"));
        assertTrue(history.contains("histglu->getsputnik()"));
    }

    @Test
    public void rangeColorsAndScanStreamSwitchesKeepTheirExistingSemantics() throws Exception {
        String palette=cpp("curve.hpp");
        String source=cpp("curve.cpp");
        assertTrue(palette.contains("modernGraphGlucose=hexcolor(0x4ECB83)"));
        assertTrue(palette.contains("modernGraphHigh=hexcolor(0xF2A93B)"));
        assertTrue(palette.contains("modernGraphLow=hexcolor(0xF06B65)"));
        assertTrue(source.contains("if(showstream)"));
        assertTrue(source.contains("showlineScan(avg,pollranges[i].first,pollranges[i].second"));
        assertTrue(source.contains("if(showscans)"));
        assertTrue(source.contains("showScan(avg,scanranges[i].first,scanranges[i].second"));
        assertTrue(source.contains("if(showhistories)"));
        assertTrue(source.contains("histcurve(avg,sensors->getSensorData(index)"));
        assertTrue(source.contains("if(showcalibratedhistories)"));
        assertTrue(source.contains("calihistcurve(avg,sensors->getSensorData(index)"));
    }

    @Test
    public void rangeAndForecastMarkersHaveNonColorShapes() throws Exception {
        String source=cpp("curve.cpp");
        String header=cpp("graphpoints.hpp");
        assertTrue(header.contains("enum class MarkerShape"));
        assertTrue(header.contains("MarkerShape::down_triangle"));
        assertTrue(header.contains("MarkerShape::circle"));
        assertTrue(header.contains("MarkerShape::up_triangle"));
        assertTrue(source.contains("graphpoints::marker_shape(state)"));
        assertTrue(source.contains("drawModernGraphCurrentSample"));
        assertTrue(source.contains("A hollow diamond is a non-colour cue"));
        assertTrue(source.contains("endpointRadius*1.85f"));
    }

    @Test
    public void nativePlotGuttersAdaptToPhoneFoldAndTabletWidths() throws Exception {
        String layout=cpp("graphlayout.hpp");
        Path appCurvePath=Paths.get("src","main","cpp","curve","appcurve.cpp");
        if(!Files.exists(appCurvePath))
            appCurvePath=Paths.get("Common").resolve(appCurvePath);
        String appCurve=new String(Files.readAllBytes(appCurvePath),
                StandardCharsets.UTF_8);
        assertTrue(layout.contains("logicalWidthDp<360.0f?14.0f"));
        assertTrue(layout.contains("logicalWidthDp<600.0f?18.0f"));
        assertTrue(layout.contains("logicalWidthDp<1200.0f?24.0f:28.0f"));
        assertTrue(appCurve.contains("#include \"graphlayout.hpp\""));
        assertTrue(appCurve.contains("graphlayout::horizontal_inset_px"));
        assertFalse(appCurve.contains("modernui?density*20.0f"));
    }

    private static float densityScale(int hours) {
        return Math.max(0f,Math.min(1f,(hours-3f)/21f));
    }

    private static float sampleRadius(float density,int hours,boolean discrete) {
        float safeDensity=Math.max(density,0.1f);
        float radiusDp=2.45f-densityScale(hours)*0.70f+(discrete?0.25f:0f);
        return Math.max(1f,safeDensity*radiusDp);
    }

    private static float minimumSpacing(float density,int hours) {
        return Math.max(density,0.1f)*(5.50f+densityScale(hours)*1.50f);
    }

    private static boolean shouldDraw(float x,float lastX,float spacing,
                                      boolean hasDrawn,boolean first,boolean last,
                                      boolean stateChanged,boolean lastHasEmphasis) {
        if(last&&lastHasEmphasis) return false;
        return first||last||stateChanged||!hasDrawn||Math.abs(x-lastX)>=spacing;
    }

    private static String between(String source,String start,String end) {
        int from=source.indexOf(start);
        return source.substring(from,source.indexOf(end,from));
    }

    private static int count(String source,String token) {
        int result=0,offset=0;
        while((offset=source.indexOf(token,offset))>=0) {
            result++;
            offset+=token.length();
        }
        return result;
    }

    private static String cpp(String name) throws IOException {
        Path path=Paths.get("src","main","cpp","curve",name);
        if(!Files.exists(path)) path=Paths.get("Common").resolve(path);
        return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);
    }
}
