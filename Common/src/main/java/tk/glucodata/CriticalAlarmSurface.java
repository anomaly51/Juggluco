package tk.glucodata;

import android.animation.ValueAnimator;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.widget.TextViewCompat;

import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * Reusable visual/control surface shared by the full-screen Activity and any
 * app-owned foreground alarm window.
 */
final class CriticalAlarmSurface extends LinearLayout {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    static final int RETRO_CORNER_RADIUS_DP = ClinicalUi.RETRO_CORNER_RADIUS_DP;
    static final int RETRO_FRAME_STROKE_DP = 2;
    static final long ROCKER_PRESS_ANIMATION_MS = 180L;
    private static final long ROCKER_FRAME_MS = 16L;
    private static final long NO_ANIMATION_TIME = Long.MIN_VALUE;

    static final int ACKNOWLEDGE_ID = View.generateViewId();
    static final int GLUCOSE_VALUE_ID = View.generateViewId();
    static final int GLUCOSE_UNIT_ID = View.generateViewId();
    static final int READING_AGE_ID = View.generateViewId();
    static final int ALERT_TITLE_ID = View.generateViewId();
    static final int TREND_ID = View.generateViewId();
    static final int VOLUME_DOWN_PROGRESS_ID = View.generateViewId();
    static final int PANEL_ID = View.generateViewId();

    private Runnable forecastFreshnessRefresh;
    private Runnable readingAgeRefresh;
    private Runnable volumeDownProgressReset;
    private Runnable volumeDownRockerFrame;
    private TextView volumeDownProgress;
    private String boundToken;
    private int acceptedVolumeDownPresses;
    private long volumeDownRockerAnimationStartedAtMs = NO_ANIMATION_TIME;
    private float volumeDownRockerPressFraction;
    private HardwareKeyHandler hardwareKeyHandler;
    private final Paint volumeDownRockerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF volumeDownRockerBounds = new RectF();
    private final LongSupplier wallClock;
    private int rockerSurface;
    private int rockerSurfaceHigh;
    private int rockerOutline;
    private int rockerOnSurface;
    private int rockerAccent;

    interface Actions {
        void acknowledge();
    }

    interface HardwareKeyHandler {
        boolean dispatch(KeyEvent event);
    }

    CriticalAlarmSurface(Context context) {
        this(context, System::currentTimeMillis);
    }

    CriticalAlarmSurface(Context context, LongSupplier wallClock) {
        super(context);
        this.wallClock = wallClock;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setFitsSystemWindows(false);
        setClipToPadding(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            setOnApplyWindowInsetsListener((view, insets) -> {
                applySafeInsets(insets);
                return insets;
            });
        }
    }

    void bind(CriticalGlucoseAlarm.Session session, boolean locked,
            CriticalAlarmChartData chartData, Actions actions) {
        cancelForecastFreshnessRefresh();
        cancelReadingAgeRefresh();
        String nextToken = session == null ? null : session.token;
        boolean volumeDownStopEligible = session != null
                && session.volumeDownStopEligible();
        if (!sameToken(boundToken, nextToken) || !volumeDownStopEligible) {
            cancelVolumeDownProgressReset();
            cancelVolumeDownRockerAnimation();
            acceptedVolumeDownPresses = 0;
        }
        boundToken = nextToken;
        volumeDownProgress = null;
        removeAllViews();
        if (session == null) return;

        int background = color(R.color.lifeprompt_background);
        int surface = color(R.color.lifeprompt_surface);
        int onSurface = color(R.color.lifeprompt_on_surface);
        int onSurfaceVariant = color(R.color.lifeprompt_on_surface_variant);
        int outline = color(R.color.lifeprompt_outline_variant);
        int accent = color(session.low() || session.signalLoss()
                ? R.color.lifeprompt_glucose_low
                : R.color.lifeprompt_glucose_high);
        int acknowledgeFill = color(R.color.lifeprompt_error);
        int acknowledgeText = color(R.color.lifeprompt_on_error);
        rockerSurface = color(R.color.lifeprompt_surface_container);
        rockerSurfaceHigh = color(R.color.lifeprompt_surface_container_highest);
        rockerOutline = color(R.color.lifeprompt_outline);
        rockerOnSurface = onSurface;
        rockerAccent = accent;
        boolean compactLandscape = getResources().getConfiguration().screenWidthDp >= 600
                && getResources().getConfiguration().screenHeightDp < 500;
        setBackgroundColor(background);

        ScrollView scroll = new ScrollView(getContext());
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(dp(16), dp(16), dp(16), dp(16));
        scroll.addView(content, new ScrollView.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        // Keep the urgent surface visually calm and unmistakably bounded.
        // The outline carries severity; large saturated background areas do
        // not compete with the glucose value, chart, or acknowledgement.
        LinearLayout panel = new LinearLayout(getContext());
        panel.setId(PANEL_ID);
        panel.setOrientation(compactLandscape ? HORIZONTAL : VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(16), dp(compactLandscape ? 16 : 24), dp(16), dp(16));
        panel.setBackground(shape(surface, RETRO_CORNER_RADIUS_DP,
                ClinicalUi.blend(accent, outline, .55f),
                RETRO_FRAME_STROKE_DP));
        content.addView(panel, matchWrap(0));

        LinearLayout details = new LinearLayout(getContext());
        details.setOrientation(VERTICAL);
        details.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.addView(details, compactLandscape
                ? new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                : matchWrap(0));

        TextView direction = text(alertTitle(session), 24, accent, true);
        direction.setId(ALERT_TITLE_ID);
        direction.setGravity(Gravity.CENTER);
        direction.setAccessibilityLiveRegion(
                View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
        details.addView(direction, matchWrap(0));
        if (CriticalGlucoseAlarm.SOURCE_PREDICTIVE.equals(session.source)) {
            TextView experimental = text(getString(
                    R.string.critical_alarm_banner_experimental),
                    13, onSurfaceVariant, false);
            experimental.setGravity(Gravity.CENTER);
            details.addView(experimental, matchWrap(5));
        }

        // The alarm's legacy value may be a FORECAST crossing, or the word
        // TEST. Only a timestamped measured sample can populate this number.
        Reading reading = Reading.from(session.displayPayload, chartData,
                wallClock.getAsLong());
        String unit = getString(Applic.unit == 1 ? R.string.mmolL : R.string.mgdL);
        LinearLayout valueRow = new LinearLayout(getContext());
        valueRow.setGravity(Gravity.CENTER);
        valueRow.setOrientation(HORIZONTAL);
        details.addView(valueRow, matchWrap(16));

        TextView value = text(reading == null ? "—" : reading.formatted(),
                76, accent, true);
        value.setId(GLUCOSE_VALUE_ID);
        value.setGravity(Gravity.CENTER);
        value.setMaxLines(1);
        value.setMaxWidth(dp(compactLandscape ? 160 : 200));
        value.setIncludeFontPadding(false);
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(value,
                40, 76, 1, TypedValue.COMPLEX_UNIT_SP);
        valueRow.addView(value, new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT));

        TextView trend = null;
        if (reading != null && reading.hasTrend(chartData,
                wallClock.getAsLong())) {
            trend = trendView(chartData.trend(), accent);
            trend.setId(TREND_ID);
            LayoutParams trendParams = new LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            valueRow.addView(trend, trendParams);
        }

        TextView units = text(unit, 16, onSurfaceVariant, false);
        units.setId(GLUCOSE_UNIT_ID);
        units.setGravity(Gravity.CENTER);
        details.addView(units, matchWrap(4));
        TextView age = text(readingAge(reading, session.signalLoss()),
                14, onSurfaceVariant, false);
        age.setId(READING_AGE_ID);
        age.setGravity(Gravity.CENTER);
        details.addView(age, matchWrap(10));

        CriticalAlarmMiniChart chart = new CriticalAlarmMiniChart(getContext());
        chart.bind(chartData, accent, locked);
        LayoutParams chartParams = compactLandscape
                ? new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                : matchWrap(18);
        if (compactLandscape) chartParams.leftMargin = dp(12);
        panel.addView(chart, chartParams);

        LinearLayout footer = new LinearLayout(getContext());
        footer.setOrientation(VERTICAL);
        footer.setPadding(dp(16), dp(10), dp(16), dp(16));
        footer.setBackgroundColor(background);
        addView(footer, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        if (volumeDownStopEligible) {
            volumeDownProgress = text(volumeDownInstruction(), 14,
                    onSurfaceVariant, false);
            volumeDownProgress.setId(VOLUME_DOWN_PROGRESS_ID);
            volumeDownProgress.setGravity(Gravity.CENTER);
            volumeDownProgress.setAccessibilityLiveRegion(
                    View.ACCESSIBILITY_LIVE_REGION_POLITE);
            volumeDownProgress.setPadding(0, 0, 0, dp(8));
            updateVolumeDownProgressText();
            footer.addView(volumeDownProgress, matchWrap(0));
        }

        Button acknowledge = button(getString(
                R.string.critical_alarm_ack_button), acknowledgeFill,
                acknowledgeText, true);
        acknowledge.setId(ACKNOWLEDGE_ID);
        acknowledge.setOnClickListener(view -> {
            if (actions != null) actions.acknowledge();
        });
        acknowledge.setMinHeight(dp(56));
        acknowledge.setMinimumHeight(dp(56));
        footer.addView(acknowledge, matchWrap(0));

        scheduleReadingAgeRefresh(reading, session.signalLoss(), age, trend);
        scheduleForecastFreshnessRefresh(session, locked, chartData,
                actions);
    }

    void setHardwareKeyHandler(HardwareKeyHandler handler) {
        hardwareKeyHandler = handler;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        HardwareKeyHandler handler = hardwareKeyHandler;
        if (handler != null && handler.dispatch(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    void setVolumeDownProgress(String token, int count) {
        if (!sameToken(boundToken, token) || volumeDownProgress == null) return;
        int accepted = Math.max(0,
                Math.min(CriticalAlarmVolumeDownGesture.REQUIRED_PRESSES,
                        count));
        if (accepted > acceptedVolumeDownPresses) {
            startVolumeDownRockerAnimation();
        }
        acceptedVolumeDownPresses = accepted;
        updateVolumeDownProgressText();
        cancelVolumeDownProgressReset();
        if (acceptedVolumeDownPresses <= 0) return;
        final String scheduledToken = token;
        volumeDownProgressReset = new Runnable() {
            @Override public void run() {
                if (volumeDownProgressReset != this) return;
                volumeDownProgressReset = null;
                if (!sameToken(boundToken, scheduledToken)
                        || volumeDownProgress == null) return;
                acceptedVolumeDownPresses = 0;
                updateVolumeDownProgressText();
            }
        };
        MAIN.postDelayed(volumeDownProgressReset,
                CriticalAlarmVolumeDownGesture.INTER_PRESS_TIMEOUT_MS);
    }

    private void updateVolumeDownProgressText() {
        TextView progress = volumeDownProgress;
        if (progress == null) return;
        if (acceptedVolumeDownPresses <= 0) {
            progress.setText(volumeDownInstruction());
            invalidate();
            return;
        }
        int remaining = Math.max(0,
                CriticalAlarmVolumeDownGesture.REQUIRED_PRESSES
                        - acceptedVolumeDownPresses);
        progress.setText(getResources().getQuantityString(
                R.plurals.critical_alarm_volume_down_remaining,
                remaining, remaining));
        invalidate();
    }

    private String volumeDownInstruction() {
        return getString(R.string.critical_alarm_volume_down_hint);
    }

    private static boolean sameToken(String first, String second) {
        return first == null ? second == null : first.equals(second);
    }

    private void cancelVolumeDownProgressReset() {
        if (volumeDownProgressReset == null) return;
        MAIN.removeCallbacks(volumeDownProgressReset);
        volumeDownProgressReset = null;
    }

    private void startVolumeDownRockerAnimation() {
        cancelVolumeDownRockerAnimation();
        if (!systemAnimationsEnabled()) {
            invalidate();
            return;
        }
        volumeDownRockerAnimationStartedAtMs = SystemClock.uptimeMillis();
        // Make the response visible on the first frame even when draw happens
        // before the next Choreographer pulse.
        volumeDownRockerPressFraction = .18f;
        volumeDownRockerFrame = new Runnable() {
            @Override public void run() {
                if (volumeDownRockerFrame != this) return;
                long elapsed = Math.max(0L, SystemClock.uptimeMillis()
                        - volumeDownRockerAnimationStartedAtMs);
                if (elapsed >= ROCKER_PRESS_ANIMATION_MS) {
                    volumeDownRockerFrame = null;
                    volumeDownRockerAnimationStartedAtMs = NO_ANIMATION_TIME;
                    volumeDownRockerPressFraction = 0f;
                    invalidate();
                    return;
                }
                volumeDownRockerPressFraction = rockerPressFraction(elapsed);
                invalidate();
                MAIN.postDelayed(this, ROCKER_FRAME_MS);
            }
        };
        invalidate();
        MAIN.postDelayed(volumeDownRockerFrame, ROCKER_FRAME_MS);
    }

    private void cancelVolumeDownRockerAnimation() {
        if (volumeDownRockerFrame != null) {
            MAIN.removeCallbacks(volumeDownRockerFrame);
            volumeDownRockerFrame = null;
        }
        volumeDownRockerAnimationStartedAtMs = NO_ANIMATION_TIME;
        volumeDownRockerPressFraction = 0f;
    }

    private static float rockerPressFraction(long elapsedMs) {
        final float pressEnd = 58f;
        if (elapsedMs <= pressEnd) {
            float t = Math.min(1f, elapsedMs / pressEnd);
            return 1f - (1f - t) * (1f - t) * (1f - t);
        }
        float t = Math.min(1f, (elapsedMs - pressEnd)
                / (ROCKER_PRESS_ANIMATION_MS - pressEnd));
        float inverse = 1f - t;
        return inverse * inverse * inverse;
    }

    private static boolean systemAnimationsEnabled() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || ValueAnimator.areAnimatorsEnabled();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (volumeDownProgress == null || getWidth() <= 0
                || getHeight() <= 0) return;
        drawVolumeDownRocker(canvas);
    }

    private void drawVolumeDownRocker(Canvas canvas) {
        updateVolumeDownRockerBounds();
        RectF bounds = volumeDownRockerBounds;
        if (bounds.isEmpty()) return;

        float press = volumeDownRockerPressFraction;
        float centerX = bounds.centerX();
        float centerY = bounds.centerY();
        int save = canvas.save();
        canvas.translate(dp(3) * press, 0f);
        canvas.scale(1f, 1f - .025f * press, centerX, centerY);

        Paint paint = volumeDownRockerPaint;
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(bounds.left, bounds.top,
                bounds.right, bounds.bottom,
                new int[]{ClinicalUi.blend(rockerOnSurface,
                                rockerSurfaceHigh, .12f),
                        rockerSurfaceHigh, rockerSurface},
                new float[]{0f, .42f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(bounds, dp(RETRO_CORNER_RADIUS_DP),
                dp(RETRO_CORNER_RADIUS_DP), paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(RETRO_FRAME_STROKE_DP));
        paint.setColor(rockerOutline);
        RectF outline = new RectF(bounds);
        outline.inset(dp(1), dp(1));
        canvas.drawRoundRect(outline, dp(RETRO_CORNER_RADIUS_DP),
                dp(RETRO_CORNER_RADIUS_DP), paint);

        // A restrained edge reflection gives depth without using an expensive
        // real-time blur over the glucose chart.
        paint.setStrokeWidth(Math.max(1f, dp(1)));
        paint.setColor(withAlpha(rockerOnSurface, 48));
        float reflectionX = bounds.left + bounds.width() * .27f;
        canvas.drawLine(reflectionX, bounds.top + bounds.width() * .55f,
                reflectionX, bounds.bottom - bounds.width() * .55f, paint);

        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(3));
        paint.setColor(rockerOnSurface);
        float minusY = bounds.top + bounds.width() * .8f;
        float minusHalf = bounds.width() * .18f;
        canvas.drawLine(centerX - minusHalf, minusY,
                centerX + minusHalf, minusY, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);

        float markerStartY = bounds.top + bounds.width() * 1.55f;
        float markerGap = (bounds.bottom - bounds.width() * .72f
                - markerStartY)
                / (CriticalAlarmVolumeDownGesture.REQUIRED_PRESSES - 1f);
        for (int index = 0;
                index < CriticalAlarmVolumeDownGesture.REQUIRED_PRESSES;
                index++) {
            float markerY = markerStartY + markerGap * index;
            if (index < acceptedVolumeDownPresses) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(withAlpha(rockerAccent, 64));
                canvas.drawCircle(centerX, markerY, dp(6), paint);
                paint.setColor(rockerAccent);
                canvas.drawCircle(centerX, markerY, dp(3), paint);
            } else {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, dp(1)));
                paint.setColor(rockerOutline);
                canvas.drawCircle(centerX, markerY, dp(3), paint);
            }
        }
        canvas.restoreToCount(save);
    }

    private void updateVolumeDownRockerBounds() {
        boolean expanded = getResources().getConfiguration().screenWidthDp
                >= 600;
        boolean compactLandscape = expanded
                && getResources().getConfiguration().screenHeightDp < 500;
        float width = dp(expanded ? 46 : 40);
        float desiredHeight = dp(compactLandscape ? 132
                : expanded ? 176 : 156);
        float topLimit = getPaddingTop() + dp(12);
        float bottomLimit = getHeight() - getPaddingBottom() - dp(12);
        if (getChildCount() >= 2) {
            bottomLimit = Math.min(bottomLimit,
                    getChildAt(1).getTop() - dp(12));
        }
        float availableHeight = Math.max(0f, bottomLimit - topLimit);
        float height = Math.min(desiredHeight, availableHeight);
        if (height < dp(96)) {
            volumeDownRockerBounds.setEmpty();
            return;
        }

        float right = expanded
                ? getWidth() - getPaddingRight() - dp(18)
                : getWidth() - getPaddingRight() + dp(5);
        float centerY = topLimit + availableHeight * .43f;
        float top = Math.max(topLimit,
                Math.min(centerY - height / 2f, bottomLimit - height));
        volumeDownRockerBounds.set(right - width, top, right, top + height);
    }

    boolean volumeDownRockerVisibleForTest() {
        return volumeDownProgress != null;
    }

    int volumeDownRockerProgressForTest() {
        return acceptedVolumeDownPresses;
    }

    boolean volumeDownRockerAnimatingForTest() {
        return volumeDownRockerFrame != null;
    }

    RectF volumeDownRockerBoundsForTest() {
        updateVolumeDownRockerBounds();
        return new RectF(volumeDownRockerBounds);
    }

    private String alertTitle(CriticalGlucoseAlarm.Session session) {
        if (session.test()) return getString(R.string.critical_alarm_banner_test);
        if (session.signalLoss()) return getString(R.string.critical_alarm_banner_signal);
        if (!session.actual()) return getString(session.low()
                ? R.string.critical_alarm_banner_predictive_low
                : R.string.critical_alarm_banner_predictive_high);
        if (session.priority >= CriticalAlarmEpisodePolicy.PRIORITY_ACTUAL_SEVERE) {
            return getString(session.low() ? R.string.critical_alarm_actual_very_low_title
                    : R.string.critical_alarm_actual_very_high_title);
        }
        return getString(session.low() ? R.string.critical_alarm_banner_low
                : R.string.critical_alarm_banner_high);
    }

    private String readingAge(Reading reading, boolean signalLoss) {
        if (reading == null) return getString(R.string.critical_alarm_banner_no_reading);
        long ageMs = Math.max(0L, wallClock.getAsLong() - reading.atMs);
        if (ageMs < 60_000L && !signalLoss) {
            return getString(R.string.critical_alarm_banner_measured_now);
        }
        return getString(signalLoss || ageMs >= Reading.FRESH_MS
                        ? R.string.critical_alarm_banner_measured_stale
                        : R.string.critical_alarm_banner_measured_minutes,
                ageMs / 60_000L);
    }

    private void scheduleReadingAgeRefresh(Reading reading, boolean signalLoss,
            TextView age, TextView trend) {
        if (reading == null) return;
        readingAgeRefresh = new Runnable() {
            @Override public void run() {
                if (readingAgeRefresh != this || !isAttachedToWindow()) return;
                age.setText(readingAge(reading, signalLoss));
                if (trend != null && wallClock.getAsLong() - reading.atMs
                        >= Reading.FRESH_MS) trend.setVisibility(GONE);
                // Refresh only the small age label, keeping Stop's focus and
                // scroll position stable while this alert remains on screen.
                postDelayed(this, 30_000L);
            }
        };
        postDelayed(readingAgeRefresh, 30_000L);
    }

    private void cancelReadingAgeRefresh() {
        if (readingAgeRefresh == null) return;
        removeCallbacks(readingAgeRefresh);
        readingAgeRefresh = null;
    }

    static final class Reading {
        static final long FRESH_MS = 5L * 60_000L;
        final float mgDl;
        final long atMs;

        Reading(float mgDl, long atMs) { this.mgDl = mgDl; this.atMs = atMs; }

        static Reading from(CriticalDisplayPayload payload,
                CriticalAlarmChartData chart, long nowMs) {
            Reading result = null;
            if (payload != null && valid(payload.currentMgDl, payload.readingAtMs, nowMs)) {
                result = new Reading(payload.currentMgDl, payload.readingAtMs);
            }
            if (chart != null) for (CriticalAlarmChartData.Point point : chart.history) {
                if (valid(point.glucoseMgDl, point.atMs, nowMs)
                        && (result == null || point.atMs > result.atMs)) {
                    result = new Reading(point.glucoseMgDl, point.atMs);
                }
            }
            return result;
        }

        private static boolean valid(Float value, long time, long nowMs) {
            return value != null && !Float.isNaN(value) && !Float.isInfinite(value)
                    && value >= 20f && value <= 600f && time > 0L
                    && time - nowMs <= 60_000L;
        }

        String formatted() {
            Locale locale = Applic.usedlocale == null ? Locale.getDefault() : Applic.usedlocale;
            return Applic.unit == 1 ? String.format(locale, "%.1f", mgDl / Applic.mgdLmult)
                    : String.format(locale, "%.0f", mgDl);
        }

        boolean hasTrend(CriticalAlarmChartData chart, long nowMs) {
            if (chart == null || nowMs - atMs >= FRESH_MS) return false;
            if (chart.history.isEmpty()
                    || chart.history.get(chart.history.size() - 1).atMs != atMs) return false;
            if (chart.trendMgDlMin != null) return true;
            if (chart.history.size() < 2) return false;
            long gap = atMs - chart.history.get(chart.history.size() - 2).atMs;
            return gap >= 30_000L && gap <= 10L * 60_000L;
        }
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int available = Math.max(0, MeasureSpec.getSize(widthMeasureSpec)
                - getPaddingLeft() - getPaddingRight());
        if (available > 0) for (int index = 0; index < getChildCount(); index++) {
            getChildAt(index).getLayoutParams().width = Math.min(available, dp(600));
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    /**
     * Rebind exactly when a displayed forecast stops being fresh. Sensor or
     * backend silence must not leave a future curve looking current for the
     * remainder of a long-running actual alarm.
     */
    private void scheduleForecastFreshnessRefresh(
            CriticalGlucoseAlarm.Session session, boolean locked,
            CriticalAlarmChartData chartData, Actions actions) {
        if (session == null || session.token == null
                || session.token.isEmpty() || chartData == null
                || !chartData.hasForecast()
                || session.displayPayload == null
                || session.displayPayload.readingAtMs <= 0L) return;
        long readingAtMs = session.displayPayload.readingAtMs;
        if (readingAtMs > Long.MAX_VALUE
                - ForecastSnapshot.MAX_GRAPH_AGE_MS - 1L) return;
        long refreshAtMs = readingAtMs
                + ForecastSnapshot.MAX_GRAPH_AGE_MS + 1L;
        long delayMs = refreshAtMs - wallClock.getAsLong();
        String token = session.token;
        forecastFreshnessRefresh = () -> {
            forecastFreshnessRefresh = null;
            if (!isAttachedToWindow()) return;
            long nowMs = wallClock.getAsLong();
            CriticalGlucoseAlarm.Session live =
                    CriticalGlucoseAlarm.session(getContext(), token);
            if (live == null || live.snoozeUntilMs > nowMs) return;
            bind(live, currentLockedState(locked),
                    CriticalAlarmChartData.from(live.displayPayload, nowMs),
                    actions);
        };
        if (delayMs <= 0L) {
            // A bind may straddle the freshness boundary. Re-evaluate on the
            // next main-loop turn so stale forecast data cannot remain drawn,
            // without recursively rebinding on this stack.
            post(forecastFreshnessRefresh);
        } else {
            postDelayed(forecastFreshnessRefresh, delayMs);
        }
    }

    private boolean currentLockedState(boolean fallback) {
        try {
            KeyguardManager keyguard = (KeyguardManager) getContext()
                    .getSystemService(Context.KEYGUARD_SERVICE);
            return keyguard == null ? fallback : keyguard.isKeyguardLocked();
        } catch (RuntimeException unavailable) {
            return fallback;
        }
    }

    private void cancelForecastFreshnessRefresh() {
        if (forecastFreshnessRefresh == null) return;
        removeCallbacks(forecastFreshnessRefresh);
        forecastFreshnessRefresh = null;
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelForecastFreshnessRefresh();
        cancelReadingAgeRefresh();
        cancelVolumeDownProgressReset();
        cancelVolumeDownRockerAnimation();
        super.onDetachedFromWindow();
    }

    private TextView trendView(int trend, int accent) {
        String glyph;
        int label;
        if (trend >= 2) {
            glyph = "↑";
            label = R.string.critical_alarm_trend_rising_fast;
        } else if (trend == 1) {
            glyph = "↗";
            label = R.string.critical_alarm_trend_rising;
        } else if (trend <= -2) {
            glyph = "↓";
            label = R.string.critical_alarm_trend_falling_fast;
        } else if (trend == -1) {
            glyph = "↘";
            label = R.string.critical_alarm_trend_falling;
        } else {
            glyph = "→";
            label = R.string.critical_alarm_trend_stable;
        }
        TextView result = text(glyph, 32, accent, true);
        result.setGravity(Gravity.CENTER);
        result.setPadding(dp(8), dp(3), dp(8), dp(3));
        result.setContentDescription(getString(label));
        return result;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(value == null ? "" : value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setLineSpacing(0f, 1.08f);
        view.setTypeface(retroTypeface(
                bold ? Typeface.BOLD : Typeface.NORMAL));
        return view;
    }

    private Button button(String value, int background, int foreground,
            boolean primary) {
        Button button = new Button(getContext());
        button.setText(value);
        button.setTextSize(primary ? 16 : 15);
        button.setTypeface(retroTypeface(
                primary ? Typeface.BOLD : Typeface.NORMAL));
        button.setTextColor(foreground);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), dp(9), dp(12), dp(9));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(
                        ClinicalUi.blend(foreground, background, .18f)),
                shape(background, RETRO_CORNER_RADIUS_DP,
                        Color.TRANSPARENT, 0),
                shape(foreground, RETRO_CORNER_RADIUS_DP,
                        Color.TRANSPARENT, 0)));
        button.setFilterTouchesWhenObscured(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            button.setDefaultFocusHighlightEnabled(true);
        }
        return button;
    }

    private GradientDrawable shape(int fill, int radiusDp, int stroke,
            int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) drawable.setStroke(dp(strokeDp), stroke);
        return drawable;
    }

    static Typeface retroTypeface(int style) {
        return ClinicalUi.retroTypeface(style);
    }

    private LayoutParams wrapCenter(int topMargin) {
        LayoutParams params = new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.topMargin = dp(topMargin);
        return params;
    }

    private LayoutParams matchWrap(int topMargin) {
        LayoutParams params = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topMargin);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void applySafeInsets(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Api30.apply(this, insets);
        } else {
            setPadding(insets.getSystemWindowInsetLeft(),
                    insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),
                    insets.getSystemWindowInsetBottom());
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.R)
    private static final class Api30 {
        static void apply(View view, WindowInsets insets) {
            android.graphics.Insets safe = insets.getInsets(
                    WindowInsets.Type.systemBars()
                            | WindowInsets.Type.displayCutout());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
        }
    }

    private String getString(int resource) {
        return getContext().getString(resource);
    }

    private String getString(int resource, Object... arguments) {
        return getContext().getString(resource, arguments);
    }

    private int color(int resource) {
        return ContextCompat.getColor(getContext(), resource);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }
}
