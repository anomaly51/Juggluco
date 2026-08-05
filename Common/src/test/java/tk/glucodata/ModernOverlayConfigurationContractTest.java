package tk.glucodata;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.core.view.ViewCompat;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

/** Regression coverage for handled configuration changes on modern overlays. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ModernOverlayConfigurationContractTest {
    @Before
    @After
    public void clearBackHandlers() {
        MainActivity.clearonback();
    }

    @Test
    public void configurationDrainIsBoundedEvenWhenBusyHandlerRequeues() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger lowerHandlerCalls = new AtomicInteger();
        Runnable[] callback = new Runnable[1];
        callback[0] = () -> {
            calls.incrementAndGet();
            assertTrue(MainActivity.isConfigurationBackDrain());
            // Models the old busy-delete behavior that previously made the
            // unbounded while(doonback()) loop hang forever.
            MainActivity.setonback(callback[0]);
        };
        MainActivity.setonback(lowerHandlerCalls::incrementAndGet);
        MainActivity.setonback(callback[0]);

        assertEquals(2, MainActivity.drainBackStackForConfiguration());
        assertEquals(1, calls.get());
        assertEquals(1, lowerHandlerCalls.get());
        assertEquals(0, MainActivity.onbacknr());
        assertFalse(MainActivity.isConfigurationBackDrain());
    }

    @Test
    public void handledRotationKeepsModernViewsAndNeverReopensRequests()
            throws Exception {
        String main = source("MainActivity.java");
        String sheet = source("IntakeEventDetailsSheet.java");
        String forecast = source("ForecastDetailsPage.java");
        String config = between(main, "public void onConfigurationChanged",
                "public void requestRender");

        assertTrue(config.contains("keepEventDetails"));
        assertTrue(config.contains("keepEventCluster"));
        assertTrue(config.contains("keepForecast"));
        assertTrue(config.contains(
                "keepIntake||keepEventDetails||keepEventCluster||keepForecast"));
        assertTrue(config.contains(
                "intakeEventDetailsSheet.onConfigurationChanged()"));
        assertTrue(config.contains(
                "intakeEventClusterSheet.onConfigurationChanged()"));
        assertTrue(config.contains(
                "forecastDetailsPage.onConfigurationChanged()"));
        assertFalse(config.contains("new IntakeEventDetailsSheet"));
        assertFalse(config.contains("ForecastDetailsPage.show"));
        assertFalse(config.contains("deleteEvent"));
        assertFalse(config.contains("refreshNow"));

        String sheetRelayout = between(sheet,
                "void onConfigurationChanged()", "void destroy()");
        assertTrue(sheetRelayout.contains("requestLayout()"));
        assertTrue(sheetRelayout.contains("updateSheetBounds()"));
        assertTrue(sheetRelayout.contains("requestApplyInsets(root)"));
        assertFalse(sheetRelayout.contains("deleteEvent"));
        assertFalse(sheetRelayout.contains("deleteConfirmed"));

        String forecastRelayout = between(forecast,
                "void onConfigurationChanged()", "void destroy()");
        assertTrue(forecastRelayout.contains("requestLayout()"));
        assertTrue(forecastRelayout.contains("requestApplyInsets(root)"));
        assertFalse(forecastRelayout.contains("refreshNow"));

        String back = between(sheet, "private void handleSystemBack()",
                "private void updateSheetBounds()");
        assertTrue(back.indexOf("isConfigurationBackDrain()")
                < back.indexOf("if (busy)"));
        assertTrue(back.contains("dismiss(false)"));
    }

    @Test
    public void confirmationStateSurvivesPortraitLandscapeRemeasure() {
        Context context = themedContext(1f);
        View root = LayoutInflater.from(context).inflate(
                R.layout.modern_intake_event_details, null, false);
        View confirmation = root.findViewById(
                R.id.intake_event_delete_confirmation);
        View delete = root.findViewById(R.id.intake_event_details_delete);
        confirmation.setVisibility(VISIBLE);
        delete.setVisibility(GONE);

        measure(root, dp(context, 360), dp(context, 760));
        measure(root, dp(context, 760), dp(context, 360));

        assertEquals(VISIBLE, confirmation.getVisibility());
        assertEquals(GONE, delete.getVisibility());
    }

    @Test
    public void largeFontHeadersWrapInsteadOfClipping() {
        Context context = themedContext(2f);
        View forecast = LayoutInflater.from(context).inflate(
                R.layout.modern_forecast_details, null, false);
        View forecastHeader = forecast.findViewById(
                R.id.forecast_details_header);
        measure(forecast, dp(context, 360), dp(context, 800));
        assertEquals(WRAP_CONTENT, forecastHeader.getLayoutParams().height);
        assertTrue(forecastHeader.getMinimumHeight() >= dp(context, 68));
        assertTrue(forecastHeader.getMeasuredHeight()
                >= forecastHeader.getMinimumHeight());
        assertTrue(forecast.findViewById(R.id.forecast_details_title)
                .getMeasuredHeight() > 0);

        View marker = LayoutInflater.from(context).inflate(
                R.layout.modern_intake_event_details, null, false);
        View markerHeader = marker.findViewById(
                R.id.intake_event_details_header);
        measure(marker, dp(context, 360), dp(context, 800));
        assertEquals(WRAP_CONTENT, markerHeader.getLayoutParams().height);
        assertTrue(markerHeader.getMinimumHeight() >= dp(context, 64));
        assertTrue(markerHeader.getMeasuredHeight()
                >= markerHeader.getMinimumHeight());
        assertTrue(marker.findViewById(R.id.intake_event_details_title)
                .getMeasuredHeight() > 0);
    }

    @Test
    public void refreshAnnouncementsEmitOnlyStateTransitions() {
        Context context = themedContext(1f);
        View page = LayoutInflater.from(context).inflate(
                R.layout.modern_forecast_details, null, false);
        TextView status = page.findViewById(R.id.forecast_details_status);
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                ViewCompat.getAccessibilityLiveRegion(status));
        assertTrue(ForecastDetailsPage.updateLiveStatus(status,
                "Refreshing forecast"));
        assertFalse(ForecastDetailsPage.updateLiveStatus(status,
                "Refreshing forecast"));
        assertTrue(ForecastDetailsPage.updateLiveStatus(status,
                "Forecast ready"));
        assertFalse(ForecastDetailsPage.updateLiveStatus(status,
                "Forecast ready"));
        assertTrue(ForecastDetailsPage.updateLiveStatus(status,
                "Refreshing forecast"));
        assertTrue(ForecastDetailsPage.updateLiveStatus(status,
                "Forecast unavailable"));
    }

    private static Context themedContext(float fontScale) {
        Context application = RuntimeEnvironment.getApplication();
        Configuration configuration = new Configuration(
                application.getResources().getConfiguration());
        configuration.fontScale = fontScale;
        Context configured = application.createConfigurationContext(
                configuration);
        return new ContextThemeWrapper(configured,
                R.style.AppTheme_ClinicalDark);
    }

    private static void measure(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width,
                        View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height,
                        View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    private static String between(String value, String start, String end) {
        int first = value.indexOf(start);
        int last = value.indexOf(end, first);
        assertTrue(first >= 0 && last > first);
        return value.substring(first, last);
    }

    private static String source(String name) throws Exception {
        Path path = Paths.get("src", "main", "java", "tk", "glucodata",
                name);
        if (!Files.exists(path)) path = Paths.get("Common").resolve(path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static int dp(Context context, int value) {
        return Math.round(value
                * context.getResources().getDisplayMetrics().density);
    }
}
