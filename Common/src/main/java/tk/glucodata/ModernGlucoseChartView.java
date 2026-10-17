package tk.glucodata;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Hardware-accelerated, dashboard-only glucose chart.
 *
 * <p>The established {@link GlucoseCurve} also owns wearable and legacy
 * screens, so this renderer intentionally has a narrow immutable-data boundary
 * and no dependency on its NanoVG drawing lifecycle. Android's Canvas is backed
 * by Skia when the window is hardware accelerated. All expensive native reads
 * happen in {@link #refreshData()}, never from {@link #onDraw(Canvas)}.</p>
 */
final class ModernGlucoseChartView extends View {
    static final long MAX_ACTUAL_GAP_MS = 330_000L;
    static final long MAX_EVENT_CLUSTER_GAP_MS = 15L * 60_000L;
    static final long MAX_EVENT_CLUSTER_SPAN_MS = 30L * 60_000L;
    static final long MAX_FORECAST_ANCHOR_AGE_MS = 15L * 60_000L;
    static final int MAX_ACTUAL_POINTS = 8_192;
    static final int SOURCE_HISTORY = 1;
    static final int SOURCE_STREAM = 2;
    static final int SOURCE_SCAN = 3;
    static final int SOURCE_CALIBRATED = 0x100;
    static final int SERIES_UNKNOWN = -1;

    enum Zone { LOW, TARGET, HIGH }

    static final class Reading {
        final long atMs;
        final float mgDl;
        final float rateMgDlPerMinute;
        final int sourceKind;
        final int seriesId;

        Reading(long atMs, float mgDl, float rateMgDlPerMinute) {
            this(atMs, mgDl, rateMgDlPerMinute, SOURCE_STREAM,
                    SERIES_UNKNOWN);
        }

        Reading(long atMs, float mgDl, float rateMgDlPerMinute,
                int sourceKind) {
            this(atMs, mgDl, rateMgDlPerMinute, sourceKind,
                    SERIES_UNKNOWN);
        }

        Reading(long atMs, float mgDl, float rateMgDlPerMinute,
                int sourceKind, int seriesId) {
            this.atMs = atMs;
            this.mgDl = mgDl;
            this.rateMgDlPerMinute = rateMgDlPerMinute;
            this.sourceKind = sourceKind;
            this.seriesId = seriesId;
        }
    }

    static final class LinePiece {
        final Reading from;
        final Reading to;
        final Zone zone;

        LinePiece(Reading from, Reading to, Zone zone) {
            this.from = from;
            this.to = to;
            this.zone = zone;
        }
    }

    static final class YRange {
        final float minimumMgDl;
        final float maximumMgDl;
        final float tickMgDl;

        YRange(float minimumMgDl, float maximumMgDl, float tickMgDl) {
            this.minimumMgDl = minimumMgDl;
            this.maximumMgDl = maximumMgDl;
            this.tickMgDl = tickMgDl;
        }

        float span() {
            return maximumMgDl - minimumMgDl;
        }
    }

    private static final class Viewport {
        final long startMs;
        final long actualEndMs;
        final long endMs;

        Viewport(long startMs, long actualEndMs, long endMs) {
            this.startMs = startMs;
            this.actualEndMs = actualEndMs;
            this.endMs = endMs;
        }

        long durationMs() {
            return Math.max(1L, endMs - startMs);
        }
    }

    private static final class EventRecord {
        final IntakeEvent event;
        final int renderKey;

        EventRecord(IntakeEvent event, int renderKey) {
            this.event = event;
            this.renderKey = renderKey;
        }
    }

    private static final class EventHit {
        final RectF bounds;
        final int[] renderKeys;

        EventHit(RectF bounds, int[] renderKeys) {
            this.bounds = bounds;
            this.renderKeys = renderKeys;
        }
    }

    private static final class EventCluster {
        final List<EventRecord> records = new ArrayList<>();
        float firstX;
        float lastX;
        long firstTimeMs;
        long lastTimeMs;

        EventCluster(EventRecord first, float x) {
            records.add(first);
            firstX = x;
            lastX = x;
            firstTimeMs = first.event.occurredAtMs;
            lastTimeMs = firstTimeMs;
        }
    }

    private final MainActivity activity;
    private final ForecastRepository forecastRepository;
    private final IntakeRepository intakeRepository;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG
            | Paint.SUBPIXEL_TEXT_FLAG);
    private final Path path = new Path();
    private final Path markerPath = new Path();
    private final float[] zoneCuts = new float[4];
    private final RectF plot = new RectF();
    private final RectF labelRect = new RectF();
    private final RectF insetRect = new RectF();
    private final ArrayList<EventHit> eventHits = new ArrayList<>();
    private final GestureDetector gestureDetector;
    private final ScaleGestureDetector scaleDetector;
    private final ForecastRepository.Listener forecastListener;
    private final IntakeRepository.Listener intakeListener;
    private final Runnable clearScrub = () -> {
        scrubReading = null;
        scrubbing = false;
        invalidate();
    };
    private final Runnable refreshFrame = () -> {
        refreshPosted = false;
        if (!this.disposed) refreshDataNow();
    };

    private List<Reading> actual = Collections.emptyList();
    private ForecastSnapshot forecast = ForecastSnapshot.empty("no_data");
    private List<EventRecord> events = Collections.emptyList();
    private Viewport viewport = defaultViewport();
    private float targetLowMgDl = 75.6f;
    private float targetHighMgDl = 162.0f;
    private int displayUnit = 1;
    private CharSequence contentSummary = "";
    private Reading scrubReading;
    private boolean scrubbing;
    private boolean interactionEnabled = true;
    private boolean scaling;
    private boolean refreshPosted;
    private boolean disposed;

    private int surfaceColor;
    private int raisedSurfaceColor;
    private int textColor;
    private int mutedTextColor;
    private int outlineColor;
    private int chromeColor;
    private int targetColor;
    private int lowColor;
    private int highColor;
    private int mealColor;
    private int rapidColor;
    private int longColor;

    ModernGlucoseChartView(MainActivity activity) {
        super(activity);
        this.activity = activity;
        forecastRepository = ForecastRepository.get(activity);
        intakeRepository = IntakeRepository.get(activity);
        ChartGestures chartGestures = new ChartGestures();
        gestureDetector = new GestureDetector(activity, chartGestures);
        gestureDetector.setOnDoubleTapListener(chartGestures);
        scaleDetector = new ScaleGestureDetector(activity,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector detector) {
                        if (!interactionEnabled || disposed) return false;
                        scaling = true;
                        scrubbing = false;
                        scrubReading = null;
                        removeCallbacks(clearScrub);
                        return true;
                    }

                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        if (!interactionEnabled || disposed) return false;
                        if (!(plot.width() > 0f)) return false;
                        try {
                            float focusFraction = (detector.getFocusX()
                                    - plot.left) / plot.width();
                            try {
                                Natives.xscaleGraphFraction(
                                        detector.getScaleFactor(),
                                        focusFraction);
                            } catch (UnsatisfiedLinkError oldNativeLibrary) {
                                // Keep install-r transitions usable until the
                                // process reloads the matching native library.
                                Natives.xscale(detector.getScaleFactor(),
                                        detector.getFocusX());
                            }
                            activity.onDashboardGraphViewportMoved();
                            refreshData();
                        } catch (UnsatisfiedLinkError ignored) {
                            return false;
                        }
                        return true;
                    }

                    @Override
                    public void onScaleEnd(ScaleGestureDetector detector) {
                        scaling = false;
                        refreshData();
                    }
                });
        forecastListener = state -> scheduleRefresh();
        intakeListener = ignored -> scheduleRefresh();

        setWillNotDraw(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setFocusable(true);
        setClickable(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setDefaultFocusHighlightEnabled(false);
        }
        refreshTheme();
        forecastRepository.addListener(forecastListener);
        intakeRepository.addListener(intakeListener);
        refreshData();
    }

    private void scheduleRefresh() {
        if (disposed) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post(this::scheduleRefresh);
            return;
        }
        if (refreshPosted) return;
        refreshPosted = true;
        postOnAnimation(refreshFrame);
    }

    void refreshData() {
        scheduleRefresh();
    }

    private void refreshDataNow() {
        if (disposed) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            scheduleRefresh();
            return;
        }

        ForecastRepository.State state = forecastRepository.snapshot();
        ForecastSnapshot nextForecast = state == null || state.forecast == null
                ? ForecastSnapshot.empty("no_data") : state.forecast;
        long now = System.currentTimeMillis();
        if (!nextForecast.isGraphUsable(now)) {
            nextForecast = ForecastSnapshot.empty(nextForecast.status);
        }

        Viewport nextViewport = readViewport(now);
        List<Reading> nextActual;
        try {
            nextActual = parseDashboardReadings(
                    Natives.dashboardChartReadings(nextViewport.startMs,
                            nextViewport.endMs, MAX_ACTUAL_POINTS));
        } catch (UnsatisfiedLinkError oldNativeLibrary) {
            try {
                nextActual = parseActualReadings(Natives.forecastReadings(
                        nextViewport.startMs, MAX_ACTUAL_POINTS));
            } catch (UnsatisfiedLinkError ignored) {
                nextActual = Collections.emptyList();
            }
        }

        int nextUnit = 1;
        float nextLow = 75.6f;
        float nextHigh = 162.0f;
        try {
            nextUnit = Natives.getunit();
            float low = Natives.targetlow();
            float high = Natives.targethigh();
            if (nextUnit == 1) {
                low *= 18.0f;
                high *= 18.0f;
            }
            if (finite(low) && finite(high) && low < high) {
                nextLow = low;
                nextHigh = high;
            }
        } catch (UnsatisfiedLinkError ignored) {
            // Defaults are the application's established 4.2–9.0 mmol/L target.
        }

        List<IntakeEvent> snapshot = intakeRepository.snapshot();
        if (snapshot == null) snapshot = Collections.emptyList();
        int[] keys = intakeRepository.assignRenderKeys(snapshot);
        ArrayList<EventRecord> nextEvents = new ArrayList<>();
        for (int index = 0; index < snapshot.size(); index++) {
            IntakeEvent event = snapshot.get(index);
            if (event == null || event.occurredAtMs < nextViewport.startMs
                    || event.occurredAtMs > nextViewport.endMs) continue;
            nextEvents.add(new EventRecord(event, keys[index]));
        }
        nextEvents.sort(Comparator.comparingLong(
                value -> value.event.occurredAtMs));

        actual = Collections.unmodifiableList(new ArrayList<>(nextActual));
        forecast = nextForecast;
        viewport = nextViewport;
        targetLowMgDl = nextLow;
        targetHighMgDl = nextHigh;
        displayUnit = nextUnit;
        events = Collections.unmodifiableList(nextEvents);
        invalidate();
    }

    void refreshTheme() {
        if (disposed) return;
        surfaceColor = color(R.color.lifeprompt_surface, 0xFF0D0602);
        raisedSurfaceColor = color(R.color.lifeprompt_surface_container,
                0xFF180B03);
        textColor = color(R.color.lifeprompt_on_surface, 0xFFFFDDA5);
        mutedTextColor = color(R.color.lifeprompt_on_surface_variant,
                0xFFE2A65A);
        outlineColor = color(R.color.lifeprompt_outline_variant, 0xFF66320B);
        chromeColor = color(R.color.lifeprompt_primary, 0xFFFFA21A);
        targetColor = color(R.color.lifeprompt_glucose_in_target, 0xFF55E39A);
        lowColor = color(R.color.lifeprompt_glucose_low, 0xFFFF675C);
        highColor = color(R.color.lifeprompt_glucose_high, 0xFFFFA63A);
        mealColor = color(R.color.lifeprompt_event_meal, 0xFFFFA63A);
        rapidColor = color(R.color.lifeprompt_event_rapid, 0xFFFFD166);
        longColor = color(R.color.lifeprompt_event_long, 0xFFD98525);
        setBackgroundColor(surfaceColor);
        invalidate();
    }

    void setInteractionEnabled(boolean enabled) {
        interactionEnabled = enabled;
        setClickable(enabled);
        setFocusable(enabled);
        if (!enabled) {
            scaling = false;
            scrubbing = false;
            scrubReading = null;
            removeCallbacks(clearScrub);
            invalidate();
        }
    }

    void dispose() {
        if (disposed) return;
        disposed = true;
        interactionEnabled = false;
        removeCallbacks(clearScrub);
        removeCallbacks(refreshFrame);
        refreshPosted = false;
        forecastRepository.removeListener(forecastListener);
        intakeRepository.removeListener(intakeListener);
        eventHits.clear();
        actual = Collections.emptyList();
        events = Collections.emptyList();
        forecast = ForecastSnapshot.empty("no_data");
    }

    void setContentSummary(CharSequence summary) {
        contentSummary = summary == null ? "" : summary;
        setContentDescription(contentSummary);
    }

    @Override
    public boolean isOpaque() {
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(surfaceColor);
        if (getWidth() <= 0 || getHeight() <= 0) return;

        final float density = getResources().getDisplayMetrics().density;
        float left = dp(getWidth() / density < 360f ? 14f
                : (getWidth() / density < 600f ? 18f : 24f));
        float right = getWidth() - dp(42f);
        float top = dp(22f);
        float bottom = getHeight() - dp(32f);
        if (right - left < dp(120f) || bottom - top < dp(120f)) return;
        plot.set(left, top, right, bottom);

        List<ForecastSnapshot.Point> future = clippedForecastPoints(
                forecast.points, Math.max(viewport.startMs,
                        System.currentTimeMillis()), viewport.endMs);
        YRange yRange = computeYRange(actual, future, targetLowMgDl,
                targetHighMgDl, viewport.startMs, viewport.endMs);

        drawPlotSurface(canvas);
        drawTargetBand(canvas, yRange);
        drawFutureField(canvas, future);
        drawGrid(canvas, yRange);
        drawActivities(canvas, forecast.activities);
        drawActual(canvas, yRange);
        drawForecast(canvas, future, yRange);
        drawIntakeEvents(canvas, yRange);
        drawNow(canvas);
        drawTargetLabels(canvas, yRange);
        drawAxes(canvas, yRange);
        drawScrub(canvas, yRange);
        drawPlotOutline(canvas);
    }

    private void drawPlotSurface(Canvas canvas) {
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(raisedSurfaceColor);
        canvas.drawRect(plot, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(.55f));
        paint.setColor(withAlpha(chromeColor, 30));
        canvas.drawLine(plot.left, plot.top, plot.right, plot.top, paint);
        canvas.drawLine(plot.left, plot.top, plot.left, plot.bottom, paint);
    }

    private void drawTargetBand(Canvas canvas, YRange range) {
        float top = y(targetHighMgDl, range);
        float bottom = y(targetLowMgDl, range);
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(targetColor, 25));
        canvas.drawRect(plot.left, top, plot.right, bottom, paint);
        float inset = Math.min(dp(8f), Math.max(0f, (bottom - top) * .18f));
        if (bottom - top > inset * 2f) {
            paint.setColor(withAlpha(targetColor, 8));
            canvas.drawRect(plot.left, top + inset, plot.right,
                    bottom - inset, paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2.8f));
        paint.setColor(withAlpha(targetColor, 18));
        canvas.drawLine(plot.left, top, plot.right, top, paint);
        canvas.drawLine(plot.left, bottom, plot.right, bottom, paint);
        paint.setStrokeWidth(dp(.8f));
        paint.setColor(withAlpha(targetColor, 132));
        canvas.drawLine(plot.left, top, plot.right, top, paint);
        canvas.drawLine(plot.left, bottom, plot.right, bottom, paint);
    }

    private void drawFutureField(Canvas canvas,
            List<ForecastSnapshot.Point> visibleFuture) {
        long now = System.currentTimeMillis();
        if (now <= viewport.startMs || now >= viewport.endMs) return;
        float nowX = x(now);
        paint.reset();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(chromeColor, 9));
        canvas.drawRect(nowX, plot.top, plot.right, plot.bottom, paint);

        if (plot.right - nowX >= dp(64f) && visibleFuture.size() >= 2) {
            paint.setTypeface(Typeface.create(Typeface.MONOSPACE,
                    Typeface.BOLD));
            paint.setTextSize(sp(10f));
            paint.setLetterSpacing(Build.VERSION.SDK_INT >= 21 ? .08f : 0f);
            paint.setColor(withAlpha(chromeColor, 190));
            canvas.drawText("FORECAST", nowX + dp(8f),
                    plot.top + dp(14f), paint);
            paint.setLetterSpacing(0f);
        }
    }

    private void drawGrid(Canvas canvas, YRange range) {
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, dp(.55f)));
        paint.setColor(withAlpha(outlineColor, 58));
        float displayStep = displayTickStep(range, displayUnit,
                preferredYIntervals());
        float minimumDisplay = toDisplayGlucose(range.minimumMgDl,
                displayUnit);
        float maximumDisplay = toDisplayGlucose(range.maximumMgDl,
                displayUnit);
        float firstDisplay = (float) Math.ceil(minimumDisplay / displayStep)
                * displayStep;
        for (int index = 0; index < 64; index++) {
            float displayValue = firstDisplay + displayStep * index;
            if (displayValue > maximumDisplay + displayStep * .01f) break;
            float lineY = y(fromDisplayGlucose(displayValue, displayUnit),
                    range);
            canvas.drawLine(plot.left, lineY, plot.right, lineY, paint);
        }

        int verticalTicks = getWidth() < dp(420f) ? 3
                : (getWidth() < dp(700f) ? 4 : 6);
        for (int index = 1; index < verticalTicks; index++) {
            float lineX = plot.left + plot.width() * index / verticalTicks;
            canvas.drawLine(lineX, plot.top, lineX, plot.bottom, paint);
        }
    }

    private void drawActual(Canvas canvas, YRange range) {
        if (actual.isEmpty()) return;
        canvas.save();
        canvas.clipRect(plot);
        Map<Long, Reading> previousBySeries = new HashMap<>();
        Map<Long, Zone> previousZoneBySeries = new HashMap<>();
        Map<Long, Reading> lastVisibleBySeries = new HashMap<>();
        Map<Long, Float> lastMarkerXBySeries = new HashMap<>();
        Set<Long> rightEdgeDrawn = new HashSet<>();
        for (Reading reading : actual) {
            if (!isScan(reading) && reading.atMs >= viewport.startMs
                    && reading.atMs <= viewport.endMs) {
                lastVisibleBySeries.put(seriesKey(reading), reading);
            }
        }
        for (Reading current : actual) {
            if (isScan(current)) {
                if (current.atMs >= viewport.startMs
                        && current.atMs <= viewport.endMs) {
                    drawActualSample(canvas, x(current.atMs),
                            y(current.mgDl, range), current,
                            zone(current.mgDl, targetLowMgDl,
                                    targetHighMgDl), false);
                }
                continue;
            }
            long seriesKey = seriesKey(current);
            Reading previous = previousBySeries.get(seriesKey);
            Zone previousZone = previousZoneBySeries.get(seriesKey);
            Zone currentZone = zone(current.mgDl, targetLowMgDl,
                    targetHighMgDl);
            if (current.atMs < viewport.startMs) {
                previousBySeries.put(seriesKey, current);
                previousZoneBySeries.put(seriesKey, currentZone);
                continue;
            }
            if (current.atMs > viewport.endMs) {
                if (rightEdgeDrawn.add(seriesKey)) {
                    drawActualSegment(canvas, range, previous, current);
                }
                continue;
            }
            drawActualSegment(canvas, range, previous, current);

            float currentX = x(current.atMs);
            boolean stateChanged = previousZone != null
                    && previousZone != currentZone;
            boolean endpoint = current == lastVisibleBySeries.get(seriesKey);
            float lastMarkerX = lastMarkerXBySeries.getOrDefault(
                    seriesKey, -Float.MAX_VALUE);
            if (lastMarkerX == -Float.MAX_VALUE || stateChanged || endpoint
                    || currentX - lastMarkerX >= dp(markerSpacingDp())) {
                drawActualSample(canvas, currentX, y(current.mgDl, range),
                        current, currentZone, false);
                lastMarkerXBySeries.put(seriesKey, currentX);
            }
            previousBySeries.put(seriesKey, current);
            previousZoneBySeries.put(seriesKey, currentZone);
        }

        Reading latest = latestAtOrBefore(actual, System.currentTimeMillis());
        if (latest != null && latest.atMs >= viewport.startMs
                && latest.atMs <= viewport.endMs) {
            drawActualSample(canvas, x(latest.atMs),
                    y(latest.mgDl, range), latest,
                    zone(latest.mgDl, targetLowMgDl, targetHighMgDl), true);
        }
        canvas.restore();
    }

    private void drawActualSegment(Canvas canvas, YRange range,
            Reading from, Reading to) {
        if (isActualGap(from, to)) return;
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(dp(5.0f));
        drawZoneColoredSegment(canvas, range, from.atMs, from.mgDl,
                to.atMs, to.mgDl, 50, false);
        paint.setStrokeWidth(dp(2.15f));
        drawZoneColoredSegment(canvas, range, from.atMs, from.mgDl,
                to.atMs, to.mgDl, 255, false);
    }

    private void drawForecast(Canvas canvas,
            List<ForecastSnapshot.Point> points, YRange range) {
        if (points.size() < 2) return;
        canvas.save();
        canvas.clipRect(plot);

        path.reset();
        for (int index = 0; index < points.size(); index++) {
            ForecastSnapshot.Point point = points.get(index);
            float px = x(point.atMs);
            float py = y(forecastHigh(point), range);
            if (index == 0) path.moveTo(px, py);
            else path.lineTo(px, py);
        }
        for (int index = points.size() - 1; index >= 0; index--) {
            ForecastSnapshot.Point point = points.get(index);
            path.lineTo(x(point.atMs), y(forecastLow(point), range));
        }
        path.close();
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.FILL);
        int near = withAlpha(chromeColor,
                forecastRibbonNearAlpha(forecast.confidence));
        int far = withAlpha(chromeColor,
                forecastRibbonFarAlpha(forecast.confidence));
        paint.setShader(new LinearGradient(x(points.get(0).atMs), 0f,
                x(points.get(points.size() - 1).atMs), 0f, near, far,
                Shader.TileMode.CLAMP));
        canvas.drawPath(path, paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(dp(.75f));
        paint.setColor(withAlpha(chromeColor,
                forecastBoundaryAlpha(forecast.confidence)));
        for (int index = 1; index < points.size(); index++) {
            ForecastSnapshot.Point left = points.get(index - 1);
            ForecastSnapshot.Point right = points.get(index);
            canvas.drawLine(x(left.atMs), y(forecastHigh(left), range),
                    x(right.atMs), y(forecastHigh(right), range), paint);
            canvas.drawLine(x(left.atMs), y(forecastLow(left), range),
                    x(right.atMs), y(forecastLow(right), range), paint);
        }

        Reading anchor = latestAtOrBefore(actual, points.get(0).atMs);
        if (anchor != null && points.get(0).atMs - anchor.atMs >= 0L
                && points.get(0).atMs - anchor.atMs
                <= MAX_FORECAST_ANCHOR_AGE_MS) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.BUTT);
            paint.setStrokeWidth(dp(.8f));
            paint.setColor(withAlpha(zoneColor(zone(anchor.mgDl,
                    targetLowMgDl, targetHighMgDl)), 72));
            drawDashedLine(canvas, x(anchor.atMs), y(anchor.mgDl, range),
                    x(points.get(0).atMs),
                    y(points.get(0).medianMgDl, range), dp(2f), dp(3f));
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.65f));
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeJoin(Paint.Join.ROUND);
        int forecastAlpha = Math.round(150f + 85f
                * clamp01(forecast.confidence));
        float dashPhase = 0f;
        for (int index = 1; index < points.size(); index++) {
            ForecastSnapshot.Point left = points.get(index - 1);
            ForecastSnapshot.Point right = points.get(index);
            dashPhase = drawZoneColoredSegment(canvas, range, left.atMs,
                    left.medianMgDl, right.atMs, right.medianMgDl,
                    forecastAlpha, true, dashPhase);
        }

        ForecastSnapshot.Point last = points.get(points.size() - 1);
        drawDiamond(canvas, x(last.atMs), y(last.medianMgDl, range),
                dp(4.2f), zoneColor(zone(last.medianMgDl, targetLowMgDl,
                        targetHighMgDl)), true);
        canvas.restore();
    }

    /** Draws a threshold-split segment without allocating in the hot path. */
    private void drawZoneColoredSegment(Canvas canvas, YRange range,
            long fromMs, float fromMgDl, long toMs, float toMgDl, int alpha,
            boolean dashed) {
        drawZoneColoredSegment(canvas, range, fromMs, fromMgDl, toMs,
                toMgDl, alpha, dashed, 0f);
    }

    /** Keeps manual dash phase continuous across samples and zone crossings. */
    private float drawZoneColoredSegment(Canvas canvas, YRange range,
            long fromMs, float fromMgDl, long toMs, float toMgDl, int alpha,
            boolean dashed, float dashPhase) {
        if (toMs <= fromMs || !finite(fromMgDl) || !finite(toMgDl)) {
            return dashPhase;
        }
        int count = 0;
        zoneCuts[count++] = 0f;
        float delta = toMgDl - fromMgDl;
        float lowCut = delta == 0f ? Float.NaN
                : (targetLowMgDl - fromMgDl) / delta;
        float highCut = delta == 0f ? Float.NaN
                : (targetHighMgDl - fromMgDl) / delta;
        if (finite(lowCut) && lowCut > 0f && lowCut < 1f) {
            zoneCuts[count++] = lowCut;
        }
        if (finite(highCut) && highCut > 0f && highCut < 1f) {
            zoneCuts[count++] = highCut;
        }
        if (count == 3 && zoneCuts[1] > zoneCuts[2]) {
            float swap = zoneCuts[1];
            zoneCuts[1] = zoneCuts[2];
            zoneCuts[2] = swap;
        }
        zoneCuts[count++] = 1f;
        long duration = toMs - fromMs;
        for (int index = 1; index < count; index++) {
            float left = zoneCuts[index - 1];
            float right = zoneCuts[index];
            if (right - left <= .000001f) continue;
            float leftValue = fromMgDl + delta * left;
            float rightValue = fromMgDl + delta * right;
            float middleValue = fromMgDl + delta * ((left + right) * .5f);
            long leftTime = fromMs + Math.round(duration * (double) left);
            long rightTime = fromMs + Math.round(duration * (double) right);
            paint.setColor(withAlpha(zoneColor(zone(middleValue,
                    targetLowMgDl, targetHighMgDl)), alpha));
            float leftX = x(leftTime);
            float leftY = y(leftValue, range);
            float rightX = x(rightTime);
            float rightY = y(rightValue, range);
            if (dashed) {
                dashPhase = drawDashedLineContinuing(canvas, leftX, leftY,
                        rightX, rightY, dp(7f), dp(5f), dashPhase, paint);
            } else {
                canvas.drawLine(leftX, leftY, rightX, rightY, paint);
            }
        }
        return dashPhase;
    }

    private void drawDashedRect(Canvas canvas, RectF rect, float dash,
            float gap) {
        drawDashedLine(canvas, rect.left, rect.top, rect.right, rect.top,
                dash, gap);
        drawDashedLine(canvas, rect.right, rect.top, rect.right, rect.bottom,
                dash, gap);
        drawDashedLine(canvas, rect.right, rect.bottom, rect.left, rect.bottom,
                dash, gap);
        drawDashedLine(canvas, rect.left, rect.bottom, rect.left, rect.top,
                dash, gap);
    }

    private static void drawDashedLine(Canvas canvas, float startX,
            float startY, float endX, float endY, float dash, float gap,
            Paint paint) {
        drawDashedLineContinuing(canvas, startX, startY, endX, endY,
                dash, gap, 0f, paint);
    }

    private static float drawDashedLineContinuing(Canvas canvas,
            float startX, float startY, float endX, float endY, float dash,
            float gap, float phase, Paint paint) {
        float deltaX = endX - startX;
        float deltaY = endY - startY;
        float length = (float) Math.hypot(deltaX, deltaY);
        if (!(length > 0f)) return phase;
        float dashLength = Math.max(.5f, dash);
        float interval = dashLength + Math.max(0f, gap);
        float normalizedPhase = nextDashPhase(0f, phase, dash, gap);
        for (float offset = -normalizedPhase; offset < length;
                offset += interval) {
            float segmentStart = Math.max(0f, offset);
            float segmentEnd = Math.min(length, offset + dashLength);
            if (segmentEnd <= segmentStart) continue;
            float startFraction = segmentStart / length;
            float endFraction = segmentEnd / length;
            canvas.drawLine(startX + deltaX * startFraction,
                    startY + deltaY * startFraction,
                    startX + deltaX * endFraction,
                    startY + deltaY * endFraction, paint);
        }
        return nextDashPhase(normalizedPhase, length, dash, gap);
    }

    static float nextDashPhase(float phase, float distance, float dash,
            float gap) {
        float interval = Math.max(.5f, dash) + Math.max(0f, gap);
        float next = (finite(phase) ? phase : 0f)
                + (finite(distance) ? Math.max(0f, distance) : 0f);
        next %= interval;
        return next < 0f ? next + interval : next;
    }

    private void drawDashedLine(Canvas canvas, float startX, float startY,
            float endX, float endY, float dash, float gap) {
        drawDashedLine(canvas, startX, startY, endX, endY, dash, gap, paint);
    }

    private void drawActivities(Canvas canvas,
            List<ForecastSnapshot.Activity> activities) {
        if (activities == null || activities.isEmpty()) return;
        canvas.save();
        canvas.clipRect(plot);
        for (ForecastSnapshot.Activity activity : activities) {
            if (activity == null || activity.endMs <= viewport.startMs
                    || activity.startMs >= viewport.endMs
                    || activity.endMs <= activity.startMs) continue;
            int color = activityColor(activity.kind);
            float confidence = clamp01(activity.profileConfidence == null
                    ? activity.confidence : activity.profileConfidence);
            float strength = visualStrength(activity.strength);
            float lane = activity.kind == ForecastSnapshot.Activity.KIND_MEAL
                    ? dp(24f) : (activity.kind
                    == ForecastSnapshot.Activity.KIND_RAPID ? dp(34f)
                    : dp(16f));
            float floor = plot.bottom - dp(8f) - lane;
            float amplitude = dp(18f + 20f * strength);

            drawActivityRange(canvas, activity.effectivePeakLowMs(),
                    activity.effectivePeakHighMs(), color, 9);
            drawActivityRange(canvas, activity.effectiveEndLowMs(),
                    activity.effectiveEndHighMs(), color, 5);

            long start = Math.max(viewport.startMs,
                    activity.effectiveOnsetMs());
            long end = Math.min(viewport.endMs, activity.effectiveEndHighMs());
            if (end <= start) continue;
            path.reset();
            final int samples = 48;
            boolean began = false;
            for (int index = 0; index <= samples; index++) {
                long time = start + (end - start) * index / samples;
                float level = activityLevel(activity, time);
                if (level <= 0f && !began) continue;
                float px = x(time);
                float py = floor - amplitude * level;
                if (!began) {
                    path.moveTo(px, py);
                    began = true;
                } else path.lineTo(px, py);
            }
            if (!began) continue;
            paint.reset();
            paint.setAntiAlias(true);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.1f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(withAlpha(color,
                    Math.round(74f + 72f * confidence)));
            canvas.drawPath(path, paint);

            long peak = Math.max(activity.effectivePeakLowMs(),
                    Math.min(activity.peakMs, activity.effectivePeakHighMs()));
            if (peak >= viewport.startMs && peak <= viewport.endMs) {
                float level = activityLevel(activity, peak);
                drawActivitySymbol(canvas, activity.kind, x(peak),
                        floor - amplitude * level, color, dp(2.7f));
            }
        }
        canvas.restore();
    }

    private void drawActivityRange(Canvas canvas, long start, long end,
            int color, int alpha) {
        long first = Math.max(viewport.startMs, Math.min(start, end));
        long last = Math.min(viewport.endMs, Math.max(start, end));
        if (last <= first) return;
        paint.reset();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(color, alpha));
        canvas.drawRect(x(first), plot.top, x(last), plot.bottom, paint);
    }

    private void drawIntakeEvents(Canvas canvas, YRange range) {
        eventHits.clear();
        if (events.isEmpty()) return;
        ArrayList<EventCluster> clusters = new ArrayList<>();
        final float joinDistance = dp(28f);
        final float maximumSpan = dp(48f);
        for (EventRecord record : events) {
            float eventX = x(record.event.occurredAtMs);
            EventCluster cluster = clusters.isEmpty() ? null
                    : clusters.get(clusters.size() - 1);
            if (cluster == null || !shouldClusterEvent(cluster.firstTimeMs,
                    cluster.lastTimeMs, record.event.occurredAtMs,
                    cluster.firstX, cluster.lastX, eventX, joinDistance,
                    maximumSpan)) {
                clusters.add(new EventCluster(record, eventX));
            } else {
                cluster.records.add(record);
                cluster.lastX = eventX;
                cluster.lastTimeMs = record.event.occurredAtMs;
            }
        }

        canvas.save();
        canvas.clipRect(plot);
        for (EventCluster cluster : clusters) {
            float centerX = (cluster.firstX + cluster.lastX) * .5f;
            long representativeTime = cluster.records.get(0).event.occurredAtMs
                    + (cluster.records.get(cluster.records.size() - 1).event
                    .occurredAtMs - cluster.records.get(0).event.occurredAtMs)
                    / 2L;
            Float anchorValue = interpolatedValueAt(actual,
                    representativeTime);
            boolean anchored = anchorValue != null;
            float anchorY = anchored ? y(anchorValue, range)
                    : plot.top + dp(22f);
            boolean placeAbove = anchored && anchorY >= plot.top + dp(58f);
            float markerY = !anchored ? plot.top + dp(22f)
                    : (placeAbove
                    ? Math.max(plot.top + dp(30f), anchorY - dp(22f))
                    : Math.min(plot.bottom - dp(30f), anchorY + dp(22f)));

            if (anchored) {
                paint.reset();
                paint.setAntiAlias(true);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(.7f));
                paint.setColor(withAlpha(mutedTextColor, 78));
                canvas.drawLine(centerX, anchorY, centerX,
                        markerY + dp(placeAbove ? 6f : -6f), paint);
            }

            boolean meal = false;
            boolean rapid = false;
            boolean longActing = false;
            boolean hasCarbs = false;
            boolean pending = false;
            float carbsGrams = 0f;
            float rapidUnits = 0f;
            float longUnits = 0f;
            Set<Integer> keys = new LinkedHashSet<>();
            for (EventRecord record : cluster.records) {
                keys.add(record.renderKey);
                meal |= record.event.hasMeal();
                if (record.event.hasCarbs()) {
                    hasCarbs = true;
                    carbsGrams += record.event.carbsGrams;
                }
                pending |= record.event.pendingSync;
                int kind = insulinKind(record.event);
                rapid |= kind == ForecastSnapshot.Activity.KIND_RAPID;
                longActing |= kind == ForecastSnapshot.Activity.KIND_LONG;
                if (kind == ForecastSnapshot.Activity.KIND_LONG) {
                    longUnits += record.event.insulinUnits;
                } else if (kind == ForecastSnapshot.Activity.KIND_RAPID) {
                    rapidUnits += record.event.insulinUnits;
                }
            }
            ArrayList<Integer> kinds = new ArrayList<>(3);
            if (meal) kinds.add(ForecastSnapshot.Activity.KIND_MEAL);
            if (rapid) kinds.add(ForecastSnapshot.Activity.KIND_RAPID);
            if (longActing) kinds.add(ForecastSnapshot.Activity.KIND_LONG);
            if (kinds.isEmpty()) kinds.add(0);
            float spacing = dp(9f);
            float startX = centerX - (kinds.size() - 1) * spacing * .5f;
            for (int index = 0; index < kinds.size(); index++) {
                int kind = kinds.get(index);
                drawActivitySymbol(canvas, kind, startX + index * spacing,
                        markerY, activityColor(kind), dp(3.4f));
            }

            String amount = formatEventAmounts(hasCarbs, carbsGrams,
                    rapidUnits, longUnits, keys.size());
            RectF amountBox = null;
            if (!amount.isEmpty()) {
                paint.setStyle(Paint.Style.FILL);
                paint.setTypeface(Typeface.create(Typeface.MONOSPACE,
                        Typeface.BOLD));
                paint.setTextSize(sp(9f));
                float boxWidth = paint.measureText(amount) + dp(10f);
                float boxHeight = dp(16f);
                float boxLeft = Math.max(plot.left + dp(2f), Math.min(
                        centerX - boxWidth * .5f,
                        plot.right - boxWidth - dp(2f)));
                float boxCenterY = markerY + dp(placeAbove ? -15f : 15f);
                amountBox = new RectF(boxLeft, boxCenterY - boxHeight * .5f,
                        boxLeft + boxWidth, boxCenterY + boxHeight * .5f);
                paint.setColor(withAlpha(raisedSurfaceColor, 238));
                canvas.drawRoundRect(amountBox, dp(3f), dp(3f), paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(.8f));
                paint.setColor(withAlpha(pending ? mutedTextColor
                        : outlineColor, pending ? 210 : 150));
                if (pending) drawDashedRect(canvas, amountBox,
                        dp(3f), dp(2f));
                else canvas.drawRoundRect(amountBox, dp(3f), dp(3f), paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(textColor);
                Paint.FontMetrics metrics = paint.getFontMetrics();
                float baseline = amountBox.centerY()
                        - (metrics.ascent + metrics.descent) * .5f;
                canvas.drawText(amount, amountBox.left + dp(5f),
                        baseline, paint);
            } else if (pending) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(.8f));
                paint.setColor(withAlpha(mutedTextColor, 210));
                RectF pendingBounds = new RectF(centerX - dp(8f),
                        markerY - dp(8f), centerX + dp(8f),
                        markerY + dp(8f));
                drawDashedRect(canvas, pendingBounds, dp(3f), dp(2f));
            }
            int[] hitKeys = new int[keys.size()];
            int index = 0;
            for (int key : keys) hitKeys[index++] = key;
            float hit = dp(48f);
            RectF hitBounds = new RectF(centerX - hit * .5f,
                    markerY - hit * .5f, centerX + hit * .5f,
                    markerY + hit * .5f);
            if (amountBox != null) hitBounds.union(amountBox);
            eventHits.add(new EventHit(hitBounds, hitKeys));
        }
        canvas.restore();
    }

    static boolean shouldClusterEvent(long firstTimeMs, long lastTimeMs,
            long nextTimeMs, float firstX, float lastX, float nextX,
            float maximumGapPx, float maximumSpanPx) {
        return nextTimeMs >= lastTimeMs
                && nextTimeMs - lastTimeMs <= MAX_EVENT_CLUSTER_GAP_MS
                && nextTimeMs - firstTimeMs <= MAX_EVENT_CLUSTER_SPAN_MS
                && nextX >= lastX && nextX - lastX <= maximumGapPx
                && nextX - firstX <= maximumSpanPx;
    }

    static String formatEventAmounts(boolean hasCarbs, float carbsGrams,
            float rapidUnits, float longUnits, int eventCount) {
        StringBuilder result = new StringBuilder(32);
        if (hasCarbs && finite(carbsGrams) && carbsGrams >= 0f) {
            result.append(compactAmount(carbsGrams)).append('g');
        }
        if (finite(rapidUnits) && rapidUnits > 0f) {
            if (result.length() > 0) result.append(" · ");
            result.append('R').append(compactAmount(rapidUnits)).append('U');
        }
        if (finite(longUnits) && longUnits > 0f) {
            if (result.length() > 0) result.append(" · ");
            result.append('L').append(compactAmount(longUnits)).append('U');
        }
        if (eventCount > 1) {
            if (result.length() > 0) result.append(" · ");
            result.append('×').append(eventCount);
        }
        return result.toString();
    }

    private static String compactAmount(float value) {
        float rounded = Math.round(value);
        if (Math.abs(value - rounded) < .05f) {
            return Integer.toString((int) rounded);
        }
        return String.format(Locale.getDefault(), "%.1f", value);
    }

    private void drawNow(Canvas canvas) {
        long now = System.currentTimeMillis();
        if (now < viewport.startMs || now > viewport.endMs) return;
        float nowX = x(now);
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(.8f));
        paint.setColor(withAlpha(chromeColor, 145));
        canvas.drawLine(nowX, plot.top, nowX, plot.bottom, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(chromeColor);
        canvas.drawRect(nowX - dp(3.5f), plot.top,
                nowX + dp(3.5f), plot.top + dp(3f), paint);

        paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        paint.setTextSize(sp(9f));
        String label = "NOW";
        float horizontal = dp(5f);
        float width = paint.measureText(label) + horizontal * 2f;
        float height = dp(15f);
        float left = nowX - width * .5f;
        left = Math.max(plot.left + dp(2f),
                Math.min(left, plot.right - width - dp(2f)));
        labelRect.set(left, plot.top - height - dp(3f), left + width,
                plot.top - dp(3f));
        paint.setColor(raisedSurfaceColor);
        canvas.drawRect(labelRect, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(.8f));
        paint.setColor(withAlpha(chromeColor, 190));
        canvas.drawRect(labelRect, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(chromeColor);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float baseline = labelRect.centerY()
                - (metrics.ascent + metrics.descent) * .5f;
        canvas.drawText(label, labelRect.left + horizontal, baseline, paint);
    }

    private void drawTargetLabels(Canvas canvas, YRange range) {
        drawTargetLabel(canvas, formatGlucose(targetHighMgDl),
                y(targetHighMgDl, range));
        drawTargetLabel(canvas, formatGlucose(targetLowMgDl),
                y(targetLowMgDl, range));
    }

    private void drawTargetLabel(Canvas canvas, String label, float lineY) {
        paint.reset();
        paint.setAntiAlias(true);
        paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        paint.setTextSize(sp(9.5f));
        float horizontal = dp(5f);
        float width = paint.measureText(label) + horizontal * 2f;
        float height = dp(16f);
        float centerY = Math.max(plot.top + height * .5f + dp(2f),
                Math.min(lineY, plot.bottom - height * .5f - dp(2f)));
        labelRect.set(plot.left + dp(5f), centerY - height * .5f,
                plot.left + dp(5f) + width, centerY + height * .5f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(raisedSurfaceColor, 238));
        canvas.drawRect(labelRect, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(.75f));
        paint.setColor(withAlpha(targetColor, 190));
        canvas.drawRect(labelRect, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(targetColor);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float baseline = labelRect.centerY()
                - (metrics.ascent + metrics.descent) * .5f;
        canvas.drawText(label, labelRect.left + horizontal, baseline, paint);
    }

    private void drawAxes(Canvas canvas, YRange range) {
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(Typeface.create(Typeface.MONOSPACE,
                Typeface.NORMAL));
        paint.setTextSize(sp(11f));
        paint.setColor(withAlpha(mutedTextColor, 205));

        float displayStep = displayTickStep(range, displayUnit,
                preferredYIntervals());
        float minimumDisplay = toDisplayGlucose(range.minimumMgDl,
                displayUnit);
        float maximumDisplay = toDisplayGlucose(range.maximumMgDl,
                displayUnit);
        float firstDisplay = (float) Math.ceil(minimumDisplay / displayStep)
                * displayStep;
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float baselineShift = -(metrics.ascent + metrics.descent) * .5f;
        for (int index = 0; index < 64; index++) {
            float displayValue = firstDisplay + displayStep * index;
            if (displayValue > maximumDisplay + displayStep * .01f) break;
            float valueMgDl = fromDisplayGlucose(displayValue, displayUnit);
            String label = formatGlucose(valueMgDl);
            canvas.drawText(label, plot.right + dp(6f),
                    y(valueMgDl, range) + baselineShift, paint);
        }

        int ticks = getWidth() < dp(420f) ? 3
                : (getWidth() < dp(700f) ? 4 : 6);
        SimpleDateFormat formatter = new SimpleDateFormat(
                android.text.format.DateFormat.is24HourFormat(getContext())
                        ? "HH:mm" : "h:mm a", Locale.getDefault());
        paint.setTextSize(sp(10.5f));
        for (int index = 0; index <= ticks; index++) {
            long time = viewport.startMs
                    + viewport.durationMs() * index / ticks;
            String label = formatter.format(new Date(time));
            float px = plot.left + plot.width() * index / ticks;
            float width = paint.measureText(label);
            if (index == 0) px = plot.left;
            else if (index == ticks) px = plot.right - width;
            else px -= width * .5f;
            canvas.drawText(label, px, plot.bottom + dp(20f), paint);
        }
    }

    private void drawScrub(Canvas canvas, YRange range) {
        Reading selected = scrubReading;
        if (selected == null) return;
        float selectedX = x(selected.atMs);
        float selectedY = y(selected.mgDl, range);
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(.8f));
        paint.setColor(withAlpha(textColor, 145));
        canvas.drawLine(selectedX, plot.top, selectedX, plot.bottom, paint);
        canvas.drawLine(plot.left, selectedY, plot.right, selectedY, paint);
        drawSample(canvas, selectedX, selectedY,
                zone(selected.mgDl, targetLowMgDl, targetHighMgDl), true);

        SimpleDateFormat formatter = new SimpleDateFormat(
                android.text.format.DateFormat.is24HourFormat(getContext())
                        ? "HH:mm" : "h:mm a", Locale.getDefault());
        String label = formatter.format(new Date(selected.atMs)) + "  "
                + formatGlucose(selected.mgDl) + " "
                + (displayUnit == 1 ? "mmol/L" : "mg/dL");
        paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        paint.setTextSize(sp(12f));
        float horizontal = dp(10f);
        float boxWidth = paint.measureText(label) + horizontal * 2f;
        float boxHeight = dp(34f);
        float left = selectedX + dp(8f);
        if (left + boxWidth > plot.right) left = selectedX - dp(8f) - boxWidth;
        left = Math.max(plot.left, Math.min(left, plot.right - boxWidth));
        float top = Math.max(plot.top + dp(6f), selectedY - boxHeight - dp(12f));
        RectF box = new RectF(left, top, left + boxWidth, top + boxHeight);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(raisedSurfaceColor);
        canvas.drawRect(box, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1f));
        paint.setColor(chromeColor);
        canvas.drawRect(box, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(textColor);
        Paint.FontMetrics fm = paint.getFontMetrics();
        float baseline = box.centerY() - (fm.ascent + fm.descent) * .5f;
        canvas.drawText(label, box.left + horizontal, baseline, paint);
    }

    private void drawPlotOutline(Canvas canvas) {
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1f));
        paint.setColor(withAlpha(outlineColor, 145));
        canvas.drawRect(plot, paint);
        insetRect.set(plot);
        insetRect.inset(dp(2f), dp(2f));
        paint.setStrokeWidth(Math.max(1f, dp(.5f)));
        paint.setColor(withAlpha(chromeColor, 34));
        canvas.drawRect(insetRect, paint);
    }

    private void drawActualSample(Canvas canvas, float x, float y,
            Reading reading, Zone zone, boolean current) {
        drawSample(canvas, x, y, zone, current);
        int baseSource = reading.sourceKind & 0xFF;
        int color = zoneColor(zone);
        float radius = dp(current ? 7.1f : 3.4f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(.7f));
        paint.setColor(withAlpha(color, current ? 210 : 145));
        if (baseSource == SOURCE_SCAN) {
            markerPath.reset();
            markerPath.moveTo(x, y - radius);
            markerPath.lineTo(x + radius, y);
            markerPath.lineTo(x, y + radius);
            markerPath.lineTo(x - radius, y);
            markerPath.close();
            canvas.drawPath(markerPath, paint);
        } else if (baseSource == SOURCE_HISTORY) {
            float offset = radius + dp(1.4f);
            canvas.drawLine(x - dp(2.5f), y + offset,
                    x + dp(2.5f), y + offset, paint);
        }
        if ((reading.sourceKind & SOURCE_CALIBRATED) != 0) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(color, current ? 235 : 175));
            canvas.drawCircle(x, y, dp(current ? 1.15f : .7f), paint);
        }
    }

    private void drawSample(Canvas canvas, float x, float y, Zone zone,
            boolean current) {
        float radius = dp(current ? 4.5f : 1.45f);
        int color = zoneColor(zone);
        if (current) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(color, 18));
            canvas.drawCircle(x, y, radius * 3.0f, paint);
            paint.setColor(withAlpha(color, 48));
            canvas.drawCircle(x, y, radius * 2.05f, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(.75f));
            paint.setColor(withAlpha(color, 125));
            canvas.drawCircle(x, y, radius * 1.65f, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(raisedSurfaceColor);
        drawZoneShape(canvas, x, y, radius + dp(current ? 1.5f : .8f),
                zone, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(current ? 2.0f : 1.0f));
        paint.setColor(color);
        drawZoneShape(canvas, x, y, radius, zone, paint);
        if (current) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            canvas.drawCircle(x, y, dp(1.15f), paint);
        }
    }

    private void drawZoneShape(Canvas canvas, float x, float y,
            float radius, Zone zone, Paint paint) {
        if (zone == Zone.TARGET) {
            canvas.drawCircle(x, y, radius, paint);
            return;
        }
        markerPath.reset();
        if (zone == Zone.HIGH) {
            markerPath.moveTo(x, y - radius);
            markerPath.lineTo(x + radius, y + radius);
            markerPath.lineTo(x - radius, y + radius);
        } else {
            markerPath.moveTo(x - radius, y - radius);
            markerPath.lineTo(x + radius, y - radius);
            markerPath.lineTo(x, y + radius);
        }
        markerPath.close();
        canvas.drawPath(markerPath, paint);
    }

    private void drawActivitySymbol(Canvas canvas, int kind, float x,
            float y, int color, float radius) {
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(surfaceColor);
        if (kind == ForecastSnapshot.Activity.KIND_MEAL) {
            canvas.drawRect(x - radius - dp(1f), y - radius - dp(1f),
                    x + radius + dp(1f), y + radius + dp(1f), paint);
        } else if (kind == ForecastSnapshot.Activity.KIND_LONG) {
            drawDiamond(canvas, x, y, radius + dp(1f), surfaceColor, false);
        } else {
            canvas.drawCircle(x, y, radius + dp(1f), paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.2f));
        paint.setColor(color);
        if (kind == ForecastSnapshot.Activity.KIND_MEAL) {
            canvas.drawRect(x - radius, y - radius,
                    x + radius, y + radius, paint);
        } else if (kind == ForecastSnapshot.Activity.KIND_LONG) {
            drawDiamond(canvas, x, y, radius, color, true);
        } else {
            canvas.drawCircle(x, y, radius, paint);
        }
    }

    private void drawDiamond(Canvas canvas, float x, float y, float radius,
            int color, boolean outline) {
        markerPath.reset();
        markerPath.moveTo(x, y - radius);
        markerPath.lineTo(x + radius, y);
        markerPath.lineTo(x, y + radius);
        markerPath.lineTo(x - radius, y);
        markerPath.close();
        paint.setColor(color);
        paint.setStyle(outline ? Paint.Style.STROKE : Paint.Style.FILL);
        if (outline) paint.setStrokeWidth(dp(1.25f));
        canvas.drawPath(markerPath, paint);
    }

    private float markerSpacingDp() {
        float hours = viewport.durationMs() / 3_600_000f;
        float fraction = Math.max(0f, Math.min(1f, (hours - 3f) / 21f));
        return 18f + fraction * 8f;
    }

    private float x(long timeMs) {
        double fraction = (double) (timeMs - viewport.startMs)
                / viewport.durationMs();
        return plot.left + (float) fraction * plot.width();
    }

    private float y(float mgDl, YRange range) {
        return plot.bottom - (mgDl - range.minimumMgDl) / range.span()
                * plot.height();
    }

    private int zoneColor(Zone zone) {
        return zone == Zone.LOW ? lowColor
                : (zone == Zone.HIGH ? highColor : targetColor);
    }

    private int activityColor(int kind) {
        return kind == ForecastSnapshot.Activity.KIND_MEAL ? mealColor
                : (kind == ForecastSnapshot.Activity.KIND_RAPID ? rapidColor
                : (kind == ForecastSnapshot.Activity.KIND_LONG ? longColor
                : mutedTextColor));
    }

    private String formatGlucose(float mgDl) {
        return displayUnit == 1
                ? String.format(Locale.getDefault(), "%.1f", mgDl / 18.0f)
                : String.format(Locale.getDefault(), "%.0f", mgDl);
    }

    private int color(int resource, int fallback) {
        try {
            return ContextCompat.getColor(getContext(), resource);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!interactionEnabled || disposed) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            try {
                if (Natives.turnoffalarm()) Notify.stopalarm();
            } catch (UnsatisfiedLinkError ignored) {
                // Keep the chart usable during an install-r native transition.
            }
        }
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_CANCEL) {
            scaling = false;
            if (scrubbing) {
                removeCallbacks(clearScrub);
                postDelayed(clearScrub, 1_500L);
            }
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(ModernGlucoseChartView.class.getName());
        info.setContentDescription(contentSummary);
        info.setScrollable(interactionEnabled);
        if (interactionEnabled) {
            info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        }
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (interactionEnabled
                && action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            return panByPixels(-Math.max(dp(48f), plot.width() * .22f));
        }
        if (interactionEnabled
                && action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
            return panByPixels(Math.max(dp(48f), plot.width() * .22f));
        }
        return super.performAccessibilityAction(action, arguments);
    }

    private boolean panByPixels(float distanceX) {
        if (!(plot.width() > 0f)) return false;
        try {
            Natives.lockGraphYRangeForPan();
            int changed;
            try {
                changed = Natives.translateGraphFraction(
                        distanceX / plot.width());
            } catch (UnsatisfiedLinkError oldNativeLibrary) {
                // Compatibility with a still-loaded pre-upgrade .so.
                changed = Natives.translate(distanceX, 0f, 0f, 0f);
            }
            if (changed != 0) {
                activity.onDashboardGraphViewportMoved();
                refreshData();
                return true;
            }
        } catch (UnsatisfiedLinkError ignored) {
            return false;
        }
        return false;
    }

    private EventHit eventHit(float x, float y) {
        for (int index = eventHits.size() - 1; index >= 0; index--) {
            EventHit hit = eventHits.get(index);
            if (hit.bounds.contains(x, y)) return hit;
        }
        return null;
    }

    private void selectScrub(float px) {
        if (actual.isEmpty() || !(plot.width() > 0f)) return;
        long time = viewport.startMs + Math.round(
                (px - plot.left) / plot.width() * viewport.durationMs());
        Reading nearest = nearestReading(actual, time);
        if (nearest == null || Math.abs(x(nearest.atMs) - px) > dp(28f)) return;
        if (scrubReading == null || scrubReading.atMs != nearest.atMs) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        scrubReading = nearest;
        scrubbing = true;
        invalidate();
    }

    private final class ChartGestures extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent event) {
            removeCallbacks(clearScrub);
            return interactionEnabled;
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent event) {
            if (!interactionEnabled || scaling) return false;
            EventHit hit = eventHit(event.getX(), event.getY());
            performClick();
            if (hit == null) {
                scrubReading = null;
                invalidate();
                return true;
            }
            if (hit.renderKeys.length == 1) {
                activity.showIntakeEvent(hit.renderKeys[0]);
            } else {
                activity.showIntakeEventCluster(hit.renderKeys);
            }
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent event) {
            if (!interactionEnabled) return false;
            try {
                Natives.settonow();
                refreshData();
                activity.refreshDashboardData();
                return true;
            } catch (UnsatisfiedLinkError ignored) {
                return false;
            }
        }

        @Override
        public void onLongPress(MotionEvent event) {
            if (!interactionEnabled || scaling || !plot.contains(
                    event.getX(), event.getY())) return;
            selectScrub(event.getX());
        }

        @Override
        public boolean onScroll(MotionEvent first, MotionEvent current,
                float distanceX, float distanceY) {
            if (!interactionEnabled || scaling || current == null) return false;
            if (scrubbing) {
                selectScrub(current.getX());
                return true;
            }
            if (Math.abs(distanceX) <= Math.abs(distanceY) * 1.25f) {
                return false;
            }
            return panByPixels(distanceX);
        }

        @Override
        public boolean onFling(MotionEvent first, MotionEvent last,
                float velocityX, float velocityY) {
            if (!interactionEnabled || scaling || scrubbing
                    || Math.abs(velocityX) < 1_200f
                    || Math.abs(velocityX) <= Math.abs(velocityY) * 1.25f) {
                return false;
            }
            try {
                Natives.lockGraphYRangeForPan();
                Natives.flingX(velocityX);
                activity.onDashboardGraphViewportMoved();
                refreshData();
                return true;
            } catch (UnsatisfiedLinkError ignored) {
                return false;
            }
        }
    }

    private Viewport readViewport(long nowMs) {
        try {
            long start = Natives.getstarttime();
            long actualEnd = Natives.getendtime();
            long fullEnd;
            try {
                fullEnd = Natives.getgraphviewendtime();
            } catch (UnsatisfiedLinkError oldNativeLibrary) {
                int hours;
                try {
                    hours = Natives.getgraphhours();
                } catch (UnsatisfiedLinkError ignored) {
                    hours = 8;
                }
                if (hours < 3 || hours > 24) hours = 8;
                fullEnd = start + hours * 3_600_000L;
            }
            if (start > 0L && actualEnd >= start && fullEnd > start) {
                return new Viewport(start, Math.min(actualEnd, fullEnd),
                        fullEnd);
            }
        } catch (UnsatisfiedLinkError ignored) {
            // The Java side can briefly run with an older .so after install -r.
        }
        return defaultViewport(nowMs);
    }

    private static Viewport defaultViewport() {
        return defaultViewport(System.currentTimeMillis());
    }

    private static Viewport defaultViewport(long nowMs) {
        long start = nowMs - 6L * 60L * 60_000L;
        return new Viewport(start, nowMs, nowMs + 2L * 60L * 60_000L);
    }

    static List<Reading> parseActualReadings(long[] raw) {
        if (raw == null || raw.length < 3) return Collections.emptyList();
        ArrayList<Reading> parsed = new ArrayList<>();
        int usable = raw.length - raw.length % 3;
        for (int index = 0; index < usable; index += 3) {
            long atMs = raw[index];
            long rawGlucose = raw[index + 1];
            if (atMs <= 0L || rawGlucose < 20L || rawGlucose > 600L) continue;
            float rate = Float.intBitsToFloat((int) raw[index + 2]);
            if (!finite(rate)) rate = 0f;
            parsed.add(new Reading(atMs, rawGlucose, rate,
                    SOURCE_STREAM, SERIES_UNKNOWN));
        }
        return immutableSortedReadings(parsed);
    }

    static List<Reading> parseDashboardReadings(long[] raw) {
        if (raw == null || raw.length < 4) return Collections.emptyList();
        int stride = dashboardReadingStride(raw);
        if (stride == 0) return Collections.emptyList();
        ArrayList<Reading> parsed = new ArrayList<>();
        int usable = raw.length - raw.length % stride;
        for (int index = 0; index < usable; index += stride) {
            long atMs = raw[index];
            float mgDl = Float.intBitsToFloat((int) raw[index + 1]);
            int sourceKind = (int) raw[index + 2];
            if (atMs <= 0L || !finite(mgDl) || mgDl < 20f || mgDl > 600f
                    || !validSourceKind(sourceKind)) {
                continue;
            }
            long rawSeriesId = stride == 5 ? raw[index + 4] : SERIES_UNKNOWN;
            if (rawSeriesId < SERIES_UNKNOWN
                    || rawSeriesId > Integer.MAX_VALUE
                    || (stride == 5 && rawSeriesId < 0L)) continue;
            float rate = Float.intBitsToFloat((int) raw[index + 3]);
            if (!finite(rate)) rate = 0f;
            parsed.add(new Reading(atMs, mgDl, rate, sourceKind,
                    (int) rawSeriesId));
        }
        return immutableSortedReadings(parsed);
    }

    static int dashboardReadingStride(long[] raw) {
        if (raw == null || raw.length < 4) return 0;
        if (raw.length >= 5 && raw.length % 5 == 0) return 5;
        if (raw.length % 4 == 0) return 4;
        int quintupleScore = validDashboardRows(raw, 5);
        int quadrupleScore = validDashboardRows(raw, 4);
        if (quintupleScore == 0 && quadrupleScore == 0) return 0;
        return quintupleScore >= quadrupleScore ? 5 : 4;
    }

    private static int validDashboardRows(long[] raw, int stride) {
        int score = 0;
        for (int index = 0; index + stride <= raw.length; index += stride) {
            long atMs = raw[index];
            float mgDl = Float.intBitsToFloat((int) raw[index + 1]);
            int sourceKind = (int) raw[index + 2];
            long seriesId = stride == 5 ? raw[index + 4] : SERIES_UNKNOWN;
            if (atMs > 0L && finite(mgDl) && mgDl >= 20f && mgDl <= 600f
                    && validSourceKind(sourceKind)
                    && seriesId >= SERIES_UNKNOWN
                    && seriesId <= Integer.MAX_VALUE
                    && (stride != 5 || seriesId >= 0L)) score++;
        }
        return score;
    }

    private static boolean validSourceKind(int sourceKind) {
        int baseSource = sourceKind & 0xFF;
        return (baseSource >= SOURCE_HISTORY && baseSource <= SOURCE_SCAN)
                && (sourceKind == baseSource
                || sourceKind == (baseSource | SOURCE_CALIBRATED));
    }

    private static List<Reading> immutableSortedReadings(
            ArrayList<Reading> parsed) {
        if (parsed.isEmpty()) return Collections.emptyList();
        parsed.sort(Comparator.comparingLong((Reading value) -> value.atMs)
                .thenComparingInt(value -> value.seriesId)
                .thenComparingInt(value -> value.sourceKind));
        ArrayList<Reading> unique = new ArrayList<>(parsed.size());
        for (Reading reading : parsed) {
            int lastIndex = unique.size() - 1;
            if (lastIndex >= 0) {
                Reading previous = unique.get(lastIndex);
                if (previous.atMs == reading.atMs
                        && previous.seriesId == reading.seriesId
                        && previous.sourceKind == reading.sourceKind) {
                    unique.set(lastIndex, reading);
                    continue;
                }
            }
            unique.add(reading);
        }
        return Collections.unmodifiableList(unique);
    }

    static boolean isActualGap(Reading from, Reading to) {
        return !canConnectActual(from, to);
    }

    static boolean canConnectActual(Reading from, Reading to) {
        return from != null && to != null && from.seriesId == to.seriesId
                && from.sourceKind == to.sourceKind
                && !isScan(from) && !isScan(to) && to.atMs > from.atMs
                && to.atMs - from.atMs <= MAX_ACTUAL_GAP_MS;
    }

    static long seriesKey(Reading reading) {
        return reading == null ? Long.MIN_VALUE
                : ((long) reading.seriesId << 32)
                ^ (reading.sourceKind & 0xFFFF_FFFFL);
    }

    static boolean isScan(Reading reading) {
        return reading != null
                && (reading.sourceKind & 0xFF) == SOURCE_SCAN;
    }

    static Zone zone(float mgDl, float targetLowMgDl,
            float targetHighMgDl) {
        return mgDl < targetLowMgDl ? Zone.LOW
                : (mgDl > targetHighMgDl ? Zone.HIGH : Zone.TARGET);
    }

    static List<LinePiece> splitZoneSegment(Reading from, Reading to,
            float targetLowMgDl, float targetHighMgDl) {
        if (!canConnectActual(from, to)
                || !finite(from.mgDl) || !finite(to.mgDl)) {
            return Collections.emptyList();
        }
        ArrayList<Float> cuts = new ArrayList<>(4);
        cuts.add(0f);
        float delta = to.mgDl - from.mgDl;
        if (delta != 0f) {
            addCut(cuts, (targetLowMgDl - from.mgDl) / delta);
            addCut(cuts, (targetHighMgDl - from.mgDl) / delta);
        }
        cuts.add(1f);
        Collections.sort(cuts);
        ArrayList<LinePiece> pieces = new ArrayList<>(cuts.size() - 1);
        for (int index = 1; index < cuts.size(); index++) {
            float left = cuts.get(index - 1);
            float right = cuts.get(index);
            if (right - left <= .000001f) continue;
            Reading start = interpolate(from, to, left);
            Reading end = interpolate(from, to, right);
            float middle = from.mgDl + delta * ((left + right) * .5f);
            pieces.add(new LinePiece(start, end,
                    zone(middle, targetLowMgDl, targetHighMgDl)));
        }
        return Collections.unmodifiableList(pieces);
    }

    private static void addCut(List<Float> cuts, float value) {
        if (finite(value) && value > 0f && value < 1f) cuts.add(value);
    }

    private static Reading interpolate(Reading from, Reading to,
            float fraction) {
        return new Reading(from.atMs + Math.round(
                (to.atMs - from.atMs) * fraction),
                from.mgDl + (to.mgDl - from.mgDl) * fraction,
                from.rateMgDlPerMinute
                        + (to.rateMgDlPerMinute - from.rateMgDlPerMinute)
                        * fraction, from.sourceKind, from.seriesId);
    }

    static Float interpolatedValueAt(List<Reading> readings, long atMs) {
        if (readings == null || readings.isEmpty()) return null;
        Map<Long, Reading> beforeBySeries = new HashMap<>();
        Set<Long> completedSeries = new HashSet<>();
        Float best = null;
        long bestSpan = Long.MAX_VALUE;
        for (Reading point : readings) {
            if (point == null) continue;
            if (point.atMs == atMs) return point.mgDl;
            if (isScan(point)) continue;
            long seriesKey = seriesKey(point);
            if (point.atMs < atMs) {
                if (!completedSeries.contains(seriesKey)) {
                    beforeBySeries.put(seriesKey, point);
                }
                continue;
            }
            if (!completedSeries.add(seriesKey)) continue;
            Reading before = beforeBySeries.get(seriesKey);
            if (!canConnectActual(before, point)) continue;
            long span = point.atMs - before.atMs;
            if (span >= bestSpan) continue;
            float fraction = (float) (atMs - before.atMs) / span;
            best = before.mgDl + (point.mgDl - before.mgDl) * fraction;
            bestSpan = span;
        }
        return best;
    }

    static YRange computeYRange(List<Reading> actual,
            List<ForecastSnapshot.Point> forecast, float targetLowMgDl,
            float targetHighMgDl, long viewStartMs, long viewEndMs) {
        float minimum = targetLowMgDl;
        float maximum = targetHighMgDl;
        if (actual != null) {
            for (Reading point : actual) {
                if (point != null && point.atMs >= viewStartMs
                        && point.atMs <= viewEndMs && finite(point.mgDl)) {
                    minimum = Math.min(minimum, point.mgDl);
                    maximum = Math.max(maximum, point.mgDl);
                }
            }
        }
        if (forecast != null) {
            for (ForecastSnapshot.Point point : forecast) {
                if (point != null && point.atMs >= viewStartMs
                        && point.atMs <= viewEndMs
                        && isRenderableForecastPoint(point)) {
                    minimum = Math.min(minimum, forecastLow(point));
                    maximum = Math.max(maximum, forecastHigh(point));
                }
            }
        }
        float rawSpan = Math.max(36f, maximum - minimum);
        float padding = Math.max(12f, rawSpan * .11f);
        minimum = Math.max(0f, minimum - padding);
        maximum += padding;
        if (maximum - minimum < 72f) {
            float center = (maximum + minimum) * .5f;
            minimum = Math.max(0f, center - 36f);
            maximum = center + 36f;
        }
        float tick = niceStep((maximum - minimum) / 4f);
        minimum = Math.max(0f, (float) Math.floor(minimum / tick) * tick);
        maximum = (float) Math.ceil(maximum / tick) * tick;
        if (!(maximum > minimum)) maximum = minimum + Math.max(18f, tick);
        return new YRange(minimum, maximum, tick);
    }

    static float niceStep(float raw) {
        if (!finite(raw) || raw <= 0f) return 18f;
        double exponent = Math.floor(Math.log10(raw));
        double magnitude = Math.pow(10d, exponent);
        double normalized = raw / magnitude;
        double nice = normalized <= 1d ? 1d
                : (normalized <= 2d ? 2d
                : (normalized <= 5d ? 5d : 10d));
        return (float) (nice * magnitude);
    }

    static float displayTickStep(YRange range, int displayUnit,
            int desiredIntervals) {
        if (range == null || !finite(range.span()) || !(range.span() > 0f)) {
            return displayUnit == 1 ? 2f : 50f;
        }
        float displaySpan = toDisplayGlucose(range.span(), displayUnit);
        return nearestNiceStep(displaySpan / Math.max(2, desiredIntervals));
    }

    private static float nearestNiceStep(float raw) {
        if (!finite(raw) || raw <= 0f) return 1f;
        double exponent = Math.floor(Math.log10(raw));
        double magnitude = Math.pow(10d, exponent);
        double normalized = raw / magnitude;
        double nice = normalized < 1.5d ? 1d
                : (normalized < 3.5d ? 2d
                : (normalized < 7.5d ? 5d : 10d));
        return (float) (nice * magnitude);
    }

    private int preferredYIntervals() {
        return Math.max(4, Math.min(9,
                Math.round(plot.height() / Math.max(1f, dp(54f)))));
    }

    private static float toDisplayGlucose(float mgDl, int displayUnit) {
        return displayUnit == 1 ? mgDl / 18f : mgDl;
    }

    private static float fromDisplayGlucose(float value, int displayUnit) {
        return displayUnit == 1 ? value * 18f : value;
    }

    static int forecastRibbonNearAlpha(float confidence) {
        return Math.round(30f + 20f * clamp01(confidence));
    }

    static int forecastRibbonFarAlpha(float confidence) {
        return Math.round(22f + 12f * clamp01(confidence));
    }

    static int forecastBoundaryAlpha(float confidence) {
        return Math.round(82f + 48f * clamp01(confidence));
    }

    static Reading nearestReading(List<Reading> readings, long atMs) {
        if (readings == null || readings.isEmpty()) return null;
        int low = 0;
        int high = readings.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            Reading point = readings.get(middle);
            if (point.atMs < atMs) low = middle + 1;
            else if (point.atMs > atMs) high = middle - 1;
            else return point;
        }
        if (high < 0) return readings.get(0);
        if (low >= readings.size()) return readings.get(readings.size() - 1);
        Reading before = readings.get(high);
        Reading after = readings.get(low);
        return atMs - before.atMs <= after.atMs - atMs ? before : after;
    }

    static Reading latestAtOrBefore(List<Reading> readings, long atMs) {
        if (readings == null || readings.isEmpty()) return null;
        int low = 0;
        int high = readings.size() - 1;
        Reading result = null;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            Reading point = readings.get(middle);
            if (point.atMs <= atMs) {
                result = point;
                low = middle + 1;
            } else high = middle - 1;
        }
        return result;
    }

    static List<ForecastSnapshot.Point> clippedForecastPoints(
            List<ForecastSnapshot.Point> source, long clipStartMs,
            long clipEndMs) {
        if (source == null || source.size() < 2
                || clipEndMs <= clipStartMs) return Collections.emptyList();
        for (ForecastSnapshot.Point point : source) {
            // Render the complete backend interval or none of it. Partially
            // dropping/clamping an invalid quantile would imply false certainty.
            if (!isRenderableForecastPoint(point)) {
                return Collections.emptyList();
            }
        }
        ArrayList<ForecastSnapshot.Point> result = new ArrayList<>();
        ForecastSnapshot.Point start = forecastPointAt(source, clipStartMs);
        if (start != null) result.add(start);
        for (ForecastSnapshot.Point point : source) {
            if (point.atMs >= clipStartMs && point.atMs <= clipEndMs
                    && (result.isEmpty()
                    || result.get(result.size() - 1).atMs != point.atMs)) {
                result.add(point);
            }
        }
        ForecastSnapshot.Point end = forecastPointAt(source, clipEndMs);
        if (end != null && (result.isEmpty()
                || result.get(result.size() - 1).atMs != end.atMs)) {
            result.add(end);
        }
        return result.size() < 2 ? Collections.emptyList()
                : Collections.unmodifiableList(result);
    }

    private static ForecastSnapshot.Point forecastPointAt(
            List<ForecastSnapshot.Point> source, long atMs) {
        ForecastSnapshot.Point previous = null;
        for (ForecastSnapshot.Point point : source) {
            if (point.atMs == atMs) return point;
            if (point.atMs > atMs) {
                if (previous == null) return null;
                float fraction = (float) (atMs - previous.atMs)
                        / (point.atMs - previous.atMs);
                return new ForecastSnapshot.Point(atMs,
                        lerp(previous.medianMgDl, point.medianMgDl, fraction),
                        lerp(previous.lowMgDl, point.lowMgDl, fraction),
                        lerp(previous.highMgDl, point.highMgDl, fraction));
            }
            previous = point;
        }
        return null;
    }

    static float activityLevel(ForecastSnapshot.Activity activity,
            long atMs) {
        if (activity == null) return 0f;
        long start = activity.effectiveOnsetMs();
        long end = activity.effectiveEndHighMs();
        if (end <= start || atMs <= start || atMs >= end) return 0f;
        if (activity.points.size() >= 2) {
            ForecastSnapshot.ActivityPoint previous = null;
            for (ForecastSnapshot.ActivityPoint point : activity.points) {
                if (point.atMs == atMs) return clamp01(point.activity);
                if (point.atMs > atMs) {
                    if (previous == null) break;
                    float fraction = (float) (atMs - previous.atMs)
                            / (point.atMs - previous.atMs);
                    return clamp01(lerp(previous.activity, point.activity,
                            fraction));
                }
                previous = point;
            }
        }
        long peak = Math.max(start + 1L,
                Math.min(end - 1L, activity.peakMs));
        if (atMs <= peak) return (float) (atMs - start) / (peak - start);
        return (float) (end - atMs) / (end - peak);
    }

    static int insulinKind(IntakeEvent event) {
        if (event == null || !event.hasInsulin()) return 0;
        String description = (event.insulinType + " " + event.insulinName)
                .toLowerCase(Locale.ROOT);
        if (description.contains("rapid") || description.contains("novo")
                || description.contains("fast")
                || description.contains("short")) {
            return ForecastSnapshot.Activity.KIND_RAPID;
        }
        if (description.contains("long") || description.contains("tresiba")
                || description.contains("basal")
                || description.contains("slow")) {
            return ForecastSnapshot.Activity.KIND_LONG;
        }
        return ForecastSnapshot.Activity.KIND_RAPID;
    }

    static boolean isRenderableForecastPoint(ForecastSnapshot.Point point) {
        return point != null && point.atMs > 0L
                && finite(point.medianMgDl) && finite(point.lowMgDl)
                && finite(point.highMgDl) && point.lowMgDl >= 0f
                && point.highMgDl <= 1_000f
                && point.medianMgDl >= point.lowMgDl
                && point.medianMgDl <= point.highMgDl;
    }

    private static float forecastLow(ForecastSnapshot.Point point) {
        return point.lowMgDl;
    }

    private static float forecastHigh(ForecastSnapshot.Point point) {
        return point.highMgDl;
    }

    private static float visualStrength(float value) {
        if (!finite(value) || value <= 0f) return 0f;
        return value <= 1f ? value : value / (value + 1f);
    }

    private static float lerp(float from, float to, float fraction) {
        return from + (to - from) * fraction;
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static float clamp01(float value) {
        return finite(value) ? Math.max(0f, Math.min(1f, value)) : 0f;
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)),
                Color.red(color), Color.green(color), Color.blue(color));
    }
}
