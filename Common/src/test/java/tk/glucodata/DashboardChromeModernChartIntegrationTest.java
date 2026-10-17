package tk.glucodata;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Guards the phone dashboard hand-off from the legacy GL graph to the Canvas graph. */
public class DashboardChromeModernChartIntegrationTest {
    @Test
    public void modernRendererIsLayeredAboveTheLegacyFallback() throws Exception {
        String createView = methodBody(source(), "View createView()");

        int legacyLayer = createView.indexOf("graphHost.addView(curve");
        int modernCreation = createView.indexOf(
                "modernChartView = new ModernGlucoseChartView(activity)");
        int modernLayer = createView.indexOf("graphHost.addView(modernChartView");

        assertTrue(legacyLayer >= 0);
        assertTrue(modernCreation > legacyLayer);
        assertTrue(modernLayer > modernCreation);
        assertTrue(createView.contains(
                "new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)"));
        assertTrue(createView.contains(
                "modernChartView.setInteractionEnabled(false)"));
        assertTrue(createView.contains("setModernRendererActive(true)"));
    }

    @Test
    public void repositoryListenersDoNotDoubleRefreshTheModernRenderer()
            throws Exception {
        String dashboard = source();
        String constructor = methodBody(dashboard,
                "DashboardChrome(MainActivity activity, GlucoseCurve curve)");

        assertFalse(constructor.contains("refreshModernChartData();"));
        assertFalse(constructor.contains("activity.requestRender();"));
        assertTrue(count(constructor,
                "requestLegacyGraphRenderIfActive();") >= 2);
        assertTrue(methodBody(dashboard, "void refreshData()")
                .contains("refreshModernChartData();"));
        String selectRange = methodBody(dashboard,
                "private void selectRange(int hours)");
        assertTrue(selectRange.contains("refreshData();"));
        assertTrue(selectRange.contains("requestLegacyGraphRenderIfActive();"));
        assertFalse(selectRange.contains("refreshModernChartData();"));
        assertFalse(selectRange.contains("activity.requestRender();"));
        String showNow = methodBody(dashboard, "private void showNow()");
        assertTrue(showNow.contains("refreshData();"));
        assertTrue(showNow.contains("requestLegacyGraphRenderIfActive();"));
        assertFalse(showNow.contains("refreshModernChartData();"));
        assertFalse(showNow.contains("activity.requestRender();"));
        assertTrue(methodBody(dashboard, "void refreshTheme()")
                .contains("refreshModernChartTheme();"));
        assertFalse(methodBody(dashboard, "void onForeground()")
                .contains("refreshModernChartData();"));
    }

    @Test
    public void rendererModeHasAnExplicitAccessibleLegacyFallback()
            throws Exception {
        String dashboard = source();
        String mode = methodBody(dashboard,
                "private void setModernRendererActive(boolean active)");
        String fallback = methodBody(dashboard,
                "private void activateLegacyGraphFallback(Throwable error)");
        String legacyRequest = methodBody(dashboard,
                "private void requestLegacyGraphRenderIfActive()");

        assertTrue(mode.contains("modernRendererActive = useModern;"));
        assertTrue(mode.contains("curve.setEnabled(!useModern)"));
        assertTrue(mode.contains(
                "View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS"));
        assertTrue(mode.contains(
                "modernChartView.setVisibility(useModern ? View.VISIBLE : View.GONE)"));
        assertTrue(fallback.contains("setModernRendererActive(false);"));
        assertTrue(fallback.contains("disposeModernChart();"));
        assertTrue(fallback.contains("requestLegacyGraphRenderIfActive();"));
        assertTrue(legacyRequest.contains("isLegacyGraphRendererActive()"));
        assertTrue(legacyRequest.contains("curve.requestRender();"));
    }

    @Test
    public void mainActivitySkipsOnlyTheHiddenDashboardGlRenderer()
            throws Exception {
        String request = methodBody(mainActivitySource(),
                "public void requestRender()");

        assertTrue(request.contains("dashboardChrome==null"));
        assertTrue(request.contains(
                "dashboardChrome.isLegacyGraphRendererActive()"));
        assertTrue(request.contains("curve.requestRender();"));
        assertTrue(request.contains("refreshDashboardData();"));
    }

    @Test
    public void backgroundOverlaysAndDetachReleaseGestureOwnership()
            throws Exception {
        String dashboard = source();
        String attach = methodBody(dashboard, "void attach()");
        String foreground = methodBody(dashboard, "void onForeground()");
        String background = methodBody(dashboard, "void onBackground()");
        String overlay = methodBody(dashboard,
                "private void updateOverlayVisibility()");
        String dispose = methodBody(dashboard,
                "private void disposeModernChart()");

        assertTrue(attach.contains("updateGraphInteraction();"));
        assertTrue(foreground.contains("updateGraphInteraction();"));
        assertTrue(background.indexOf("foreground = false;")
                < background.indexOf("updateGraphInteraction();"));
        assertTrue(overlay.indexOf("obscured = nowObscured;")
                < overlay.indexOf("updateGraphInteraction();"));
        int disable = dispose.indexOf("setInteractionEnabled(false)");
        int release = dispose.indexOf("modernChartView.dispose();");
        int remove = dispose.indexOf("removeView(modernChartView)");
        assertTrue(disable >= 0);
        assertTrue(release > disable);
        assertTrue(remove > release);
        assertTrue(methodBody(dashboard, "void detach()")
                .contains("disposeModernChart();"));
    }

    @Test
    public void modernRendererIsThePrimaryGestureOwner() throws Exception {
        String dashboard = source();
        String activeGraph = methodBody(dashboard,
                "private View activeDashboardGraphView()");
        String interaction = methodBody(dashboard,
                "private void updateGraphInteraction()");
        String accepts = methodBody(dashboard,
                "boolean acceptsGraphGestures()");

        assertTrue(activeGraph.indexOf("modernChartView != null")
                < activeGraph.indexOf("curve.getParent()"));
        assertTrue(interaction.contains(
                "modernChartView == activeDashboardGraphView()"));
        assertTrue(interaction.contains(
                "modernChartView.setInteractionEnabled(enabled)"));
        assertTrue(accepts.contains(
                "View activeGraph = activeDashboardGraphView();"));
        assertTrue(accepts.contains("activeGraph != curve"));
        assertFalse(DashboardChrome.isGraphInteractionState(
                true, false, false, DashboardChrome.DESTINATION_HOME));
        assertFalse(DashboardChrome.isGraphInteractionState(
                true, true, true, DashboardChrome.DESTINATION_HOME));
        assertTrue(DashboardChrome.isGraphInteractionState(
                true, true, false, DashboardChrome.DESTINATION_HOME));
        assertFalse(DashboardChrome.isGraphInteractionState(
                true, true, false, DashboardChrome.DESTINATION_MORE));
    }

    @Test
    public void modernRendererReceivesTheLiveAccessibilitySummary()
            throws Exception {
        String dashboard = source();
        String delegates = methodBody(dashboard,
                "private void installAccessibilityDelegates()");
        String summary = methodBody(dashboard,
                "private void refreshGraphAccessibilityDescription()");

        assertTrue(delegates.contains(
                "modernChartView.setAccessibilityDelegate("));
        assertTrue(delegates.contains(
                "currentGraphAccessibilityDescription("));
        assertTrue(summary.contains(
                "modernChartView.setContentSummary(description);"));
    }

    @Test
    public void modernGesturesUseTheCanvasViewportFractions()
            throws Exception {
        String chart = source("ModernGlucoseChartView.java");

        assertTrue(chart.contains("Natives.xscaleGraphFraction("));
        assertTrue(chart.contains("detector.getFocusX()"));
        assertTrue(chart.contains("- plot.left) / plot.width()"));
        assertTrue(chart.contains("Natives.translateGraphFraction("));
        assertTrue(chart.contains("distanceX / plot.width()"));
    }

    private static int count(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static String methodBody(String source, String signature) {
        int signatureStart = source.indexOf(signature);
        assertTrue("Missing method: " + signature, signatureStart >= 0);
        int bodyStart = source.indexOf('{', signatureStart);
        assertTrue("Missing method body: " + signature, bodyStart >= 0);
        int depth = 0;
        for (int index = bodyStart; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return source.substring(bodyStart + 1, index);
            }
        }
        throw new AssertionError("Unterminated method: " + signature);
    }

    private static String source() throws IOException {
        return source("DashboardChrome.java");
    }

    private static String mainActivitySource() throws IOException {
        return source("MainActivity.java");
    }

    private static String source(String fileName) throws IOException {
        Path relative = Paths.get("src", "main", "java", "tk",
                "glucodata", fileName);
        if (!Files.exists(relative)) {
            relative = Paths.get("Common").resolve(relative);
        }
        return new String(Files.readAllBytes(relative),
                StandardCharsets.UTF_8);
    }
}
