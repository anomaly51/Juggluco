package tk.glucodata;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Contract guard for graph gestures expressed in viewport fractions. */
public class NormalizedGraphViewportNativeContractTest {
    @Test
    public void javaJniAndCurveExposeNormalizedGestureApis() throws Exception {
        String natives=javaSource("Natives.java");
        String header=cppSource("curve","JCurve.hpp");
        String bridge=cppSource("curve","javacurve.cpp");

        assertTrue(natives.contains(
                "native int translateGraphFraction(float deltaFraction)"));
        assertTrue(natives.contains(
                "native void xscaleGraphFraction(float scale,"));
        assertTrue(header.contains(
                "int translateGraphFraction(float deltaFraction);"));
        assertTrue(header.contains(
                "void xscaleGraphFraction(float scale,float focusFraction);"));
        assertTrue(bridge.contains("fromjava(translateGraphFraction)"));
        assertTrue(bridge.contains(
                "appcurve.translateGraphFraction(deltaFraction)"));
        assertTrue(bridge.contains("fromjava(xscaleGraphFraction)"));
        assertTrue(bridge.contains(
                "appcurve.xscaleGraphFraction(scale,focusFraction)"));
    }

    @Test
    public void normalizedPanPreservesLegacyTimeMeaningWithoutGlGeometry()
            throws Exception {
        String source=cppSource("curve","appcurve.cpp");
        String pan=between(source,"int JCurve::translateGraphFraction(",
                "void JCurve::xscaleGraphFraction(");

        assertTrue(pan.contains("std::isfinite(deltaFraction)"));
        assertTrue(pan.contains("deltaFraction==0.0f"));
        assertTrue(pan.contains("1.2L*static_cast<long double>(deltaFraction)"));
        assertTrue(pan.contains("static_cast<long double>(duration)"));
        assertTrue(pan.contains("boundNormalizedGraphStart(*this)"));
        assertFalse(pan.contains("dleft"));
        assertFalse(pan.contains("dwidth"));
    }

    @Test
    public void normalizedZoomClampsFocusAndKeepsLegacyDurationRules()
            throws Exception {
        String source=cppSource("curve","appcurve.cpp");
        String helpers=between(source,
                "constexpr int normalizedGraphMinimumDuration",
                "int JCurve::translateGraphFraction(");
        String zoom=between(source,"void JCurve::xscaleGraphFraction(",
                "void JCurve::xscaleGesture(");

        assertTrue(helpers.contains("10*60"));
        assertTrue(helpers.contains("30*24*60*60"));
        assertTrue(helpers.contains("std::pow(static_cast<double>(scale),5.0)"));
        assertTrue(helpers.contains("focus<0.0?0.0:(focus>1.0?1.0:focus)"));
        assertTrue(helpers.contains("now-0.91L*static_cast<long double>(duration)"));
        assertTrue(helpers.contains("forecastgraph::maximum_start"));
        assertTrue(helpers.contains("static_assert(clampNormalizedGraphFocus"));
        assertTrue(zoom.contains("unlockGraphYRange()"));
        assertTrue(zoom.contains("scale<=0.0f"));
        assertTrue(zoom.contains("!std::isfinite(focusFraction)"));
        assertTrue(zoom.contains(
                "clampNormalizedGraphFocus(focusFraction)"));
        assertTrue(zoom.contains("normalizedGraphScaledDuration(duration,scale)"));
        assertTrue(zoom.contains("normalizedGraphMaximumStart(duration)"));
        assertFalse(helpers.contains("dleft"));
        assertFalse(helpers.contains("dwidth"));
        assertFalse(zoom.contains("dleft"));
        assertFalse(zoom.contains("dwidth"));
    }

    @Test
    public void legacyPixelGestureEntrypointsRemainAvailable() throws Exception {
        String source=cppSource("curve","appcurve.cpp");
        assertTrue(source.contains(
                "int JCurve::translate(float dx,float dy,float yold,float y)"));
        assertTrue(source.contains(
                "void JCurve::xscaleGesture(float scalex,float midx)"));
    }

    private static String javaSource(String name) throws Exception {
        Path path=Paths.get("src","main","java","tk","glucodata",name);
        if(!Files.exists(path)) path=Paths.get("Common").resolve(path);
        return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);
    }

    private static String cppSource(String folder,String name) throws Exception {
        Path path=Paths.get("src","main","cpp",folder,name);
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
}
