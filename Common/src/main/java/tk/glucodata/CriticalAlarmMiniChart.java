package tk.glucodata;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import java.util.List;
import java.util.Locale;

/** Read-only recent + forecast glucose chart for the critical alarm surface. */
final class CriticalAlarmMiniChart extends View {
    private static final int HISTORY_COLOR = 0xFFE7EFEB;
    private static final int TARGET_COLOR = 0xFF4CC38A;
    private static final int GRID_COLOR = 0xFF3A4340;
    private static final int MUTED_COLOR = 0xFFA0AAA5;
    private static final long MIN_FUTURE_WINDOW_MS = 15L * 60_000L;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private CriticalAlarmChartData data;
    private int accent = 0xFFE65B65;
    private boolean hideExactValue;

    CriticalAlarmMiniChart(Context context) {
        super(context);
        initialize();
    }

    CriticalAlarmMiniChart(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }

    private void initialize() {
        setMinimumHeight(dp(164));
        setBackgroundColor(Color.TRANSPARENT);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    void bind(CriticalAlarmChartData value, int color, boolean hidden) {
        data = value;
        accent = color;
        hideExactValue = hidden;
        if (!hasRenderableData()) {
            setContentDescription(getContext().getString(
                    R.string.critical_alarm_chart_empty));
        } else {
            setContentDescription(accessibilityDescription());
        }
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float fontScale = getResources().getConfiguration().fontScale;
        int desiredHeight = dp(174 + Math.round(
                Math.max(0f, Math.min(1f, fontScale - 1f)) * 34f));
        int height = resolveSize(desiredHeight, heightMeasureSpec);
        int width = resolveSize(dp(280), widthMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!hasRenderableData()) {
            drawEmpty(canvas);
            return;
        }

        float left = dp(12);
        float right = getWidth() - dp(12);
        float top = Math.max(dp(29), sp(10) + dp(14));
        float bottom = getHeight() - Math.max(dp(27), sp(9) + dp(15));
        if (right <= left || bottom <= top) return;

        long minTime = data.nowMs - data.historyMinutes * 60_000L;
        long maxTime = data.nowMs;
        if (data.hasForecast()) {
            maxTime = Math.max(data.nowMs + MIN_FUTURE_WINDOW_MS,
                    data.forecast.get(data.forecast.size() - 1).atMs);
        }

        float minValue = data.targetLowMgDl;
        float maxValue = data.targetHighMgDl;
        minValue = includeMinimum(minValue, data.history, false);
        maxValue = includeMaximum(maxValue, data.history, false);
        minValue = includeMinimum(minValue, data.forecast, true);
        maxValue = includeMaximum(maxValue, data.forecast, true);
        float padding = Math.max(18f, (maxValue - minValue) * .17f);
        minValue = Math.max(20f, minValue - padding);
        maxValue = Math.min(600f, maxValue + padding);
        if (maxValue - minValue < 40f) {
            float center = (maxValue + minValue) * .5f;
            minValue = Math.max(20f, center - 20f);
            maxValue = Math.min(600f, center + 20f);
        }

        drawLegend(canvas, left, right);
        drawGrid(canvas, left, right, top, bottom);

        float targetTop = y(data.targetHighMgDl, minValue, maxValue,
                top, bottom);
        float targetBottom = y(data.targetLowMgDl, minValue, maxValue,
                top, bottom);
        paint.reset();
        paint.setAntiAlias(true);
        paint.setColor(withAlpha(TARGET_COLOR, 31));
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(new RectF(left, targetTop, right, targetBottom),
                dp(5), dp(5), paint);
        paint.setColor(withAlpha(TARGET_COLOR, 82));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        canvas.drawLine(left, targetTop, right, targetTop, paint);
        canvas.drawLine(left, targetBottom, right, targetBottom, paint);

        float nowX = x(data.nowMs, minTime, maxTime, left, right);
        paint.setColor(withAlpha(Color.WHITE, 80));
        paint.setStrokeWidth(dp(1));
        paint.setPathEffect(new DashPathEffect(
                new float[]{dp(3), dp(4)}, 0f));
        canvas.drawLine(nowX, top, nowX, bottom, paint);
        paint.setPathEffect(null);

        if (data.hasForecast()) {
            drawForecastBand(canvas, data.forecast, minTime, maxTime,
                    minValue, maxValue, left, right, top, bottom);
        }
        drawLine(canvas, data.history, HISTORY_COLOR, false,
                minTime, maxTime, minValue, maxValue,
                left, right, top, bottom);
        if (data.hasForecast()) {
            drawLine(canvas, data.forecast, accent, true,
                    minTime, maxTime, minValue, maxValue,
                    left, right, top, bottom);
        }
        drawLatestPoint(canvas, minTime, maxTime, minValue, maxValue,
                left, right, top, bottom);
        drawAxisLabels(canvas, left, right, nowX, bottom,
                data.hasForecast());
    }

    private void drawLegend(Canvas canvas, float left, float right) {
        paint.reset();
        paint.setAntiAlias(true);
        paint.setTextSize(sp(10));
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        float y = Math.max(dp(15), sp(10) + dp(3));
        float cursor = left;
        cursor = legendItem(canvas, cursor, y, HISTORY_COLOR,
                getContext().getString(R.string.critical_alarm_chart_recent));
        if (data.hasForecast() && cursor < right - dp(90)) {
            cursor = legendItem(canvas, cursor + dp(10), y, accent,
                    getContext().getString(R.string.critical_alarm_chart_forecast));
        }
        if (cursor < right - dp(72)) {
            legendItem(canvas, cursor + dp(10), y, TARGET_COLOR,
                    getContext().getString(R.string.critical_alarm_chart_target));
        }
    }

    private float legendItem(Canvas canvas, float x, float baseline,
            int color, String label) {
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(x + dp(3), baseline - dp(3), dp(3), paint);
        paint.setColor(MUTED_COLOR);
        canvas.drawText(label, x + dp(10), baseline, paint);
        return x + dp(10) + paint.measureText(label);
    }

    private void drawGrid(Canvas canvas, float left, float right,
            float top, float bottom) {
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(withAlpha(GRID_COLOR, 175));
        paint.setStrokeWidth(dp(1));
        for (int index = 0; index < 4; index++) {
            float y = top + (bottom - top) * index / 3f;
            canvas.drawLine(left, y, right, y, paint);
        }
        for (int index = 0; index < 4; index++) {
            float x = left + (right - left) * index / 3f;
            canvas.drawLine(x, top, x, bottom, paint);
        }
        paint.setColor(withAlpha(Color.WHITE, 42));
        canvas.drawRoundRect(new RectF(left, top, right, bottom),
                dp(4), dp(4), paint);
    }

    private void drawForecastBand(Canvas canvas,
            List<CriticalAlarmChartData.Point> values,
            long minTime, long maxTime, float minValue, float maxValue,
            float left, float right, float top, float bottom) {
        if (values.size() < 2) return;
        path.reset();
        for (int index = 0; index < values.size(); index++) {
            CriticalAlarmChartData.Point point = values.get(index);
            float x = x(point.atMs, minTime, maxTime, left, right);
            float y = y(point.highMgDl, minValue, maxValue, top, bottom);
            if (index == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        for (int index = values.size() - 1; index >= 0; index--) {
            CriticalAlarmChartData.Point point = values.get(index);
            path.lineTo(x(point.atMs, minTime, maxTime, left, right),
                    y(point.lowMgDl, minValue, maxValue, top, bottom));
        }
        path.close();
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(accent, 38));
        canvas.drawPath(path, paint);
    }

    private void drawLine(Canvas canvas,
            List<CriticalAlarmChartData.Point> values, int color,
            boolean dashed, long minTime, long maxTime,
            float minValue, float maxValue, float left, float right,
            float top, float bottom) {
        if (values.isEmpty()) return;
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(dashed ? 2.6f : 3.2f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
        if (dashed) paint.setPathEffect(new DashPathEffect(
                new float[]{dp(7), dp(4)}, 0f));
        path.reset();
        long previous = Long.MIN_VALUE;
        boolean started = false;
        for (CriticalAlarmChartData.Point point : values) {
            if (point.atMs < minTime || point.atMs > maxTime) continue;
            float x = x(point.atMs, minTime, maxTime, left, right);
            float y = y(point.glucoseMgDl, minValue, maxValue, top, bottom);
            if (!started || (!dashed && previous != Long.MIN_VALUE
                    && point.atMs - previous > 16L * 60_000L)) {
                path.moveTo(x, y);
                started = true;
            } else {
                path.lineTo(x, y);
            }
            previous = point.atMs;
        }
        if (started) canvas.drawPath(path, paint);
        paint.setPathEffect(null);
    }

    private void drawLatestPoint(Canvas canvas, long minTime, long maxTime,
            float minValue, float maxValue, float left, float right,
            float top, float bottom) {
        if (data.history.isEmpty()) return;
        CriticalAlarmChartData.Point point =
                data.history.get(data.history.size() - 1);
        if (point.atMs < minTime || point.atMs > maxTime) return;
        float x = x(point.atMs, minTime, maxTime, left, right);
        float y = y(point.glucoseMgDl, minValue, maxValue, top, bottom);
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF0B0E0F);
        canvas.drawCircle(x, y, dp(6.5f), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(withAlpha(Color.WHITE, 170));
        canvas.drawCircle(x, y, dp(5f), paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(accent);
        canvas.drawCircle(x, y, dp(3.3f), paint);
    }

    private void drawAxisLabels(Canvas canvas, float left, float right,
            float nowX, float bottom, boolean forecast) {
        paint.reset();
        paint.setAntiAlias(true);
        paint.setTextSize(sp(9));
        paint.setColor(MUTED_COLOR);
        String past = getContext().getString(
                R.string.critical_alarm_chart_past_axis,
                data.historyMinutes);
        String now = getContext().getString(
                R.string.critical_alarm_chart_now_axis);
        float baseline = bottom + Math.max(dp(18), sp(9) + dp(6));
        canvas.drawText(past, left, baseline, paint);
        canvas.drawText(now, clamp(nowX - paint.measureText(now) * .5f,
                left, right - paint.measureText(now)), baseline, paint);
        if (forecast) {
            String future = getContext().getString(
                    R.string.critical_alarm_chart_future_axis,
                    data.forecastMinutes);
            canvas.drawText(future, right - paint.measureText(future),
                    baseline, paint);
        }
    }

    private void drawEmpty(Canvas canvas) {
        drawPlaceholder(canvas,
                getContext().getString(R.string.critical_alarm_chart_empty));
    }

    private void drawPlaceholder(Canvas canvas, String label) {
        float left = dp(12);
        float right = getWidth() - dp(12);
        float top = Math.max(dp(28), sp(10) + dp(13));
        float bottom = getHeight() - Math.max(dp(27), sp(9) + dp(15));
        if (right > left && bottom > top) {
            drawGrid(canvas, left, right, top, bottom);
        }
        paint.reset();
        paint.setAntiAlias(true);
        float centerY = getHeight() * .46f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(MUTED_COLOR);
        paint.setTextSize(sp(12));
        float maximumWidth = Math.max(dp(80), getWidth() - dp(32));
        if (paint.measureText(label) > maximumWidth) {
            paint.setTextSize(Math.max(sp(9), paint.getTextSize()
                    * maximumWidth / paint.measureText(label)));
        }
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(label, getWidth() * .5f,
                centerY + dp(4), paint);
    }

    private String accessibilityDescription() {
        String base = data.hasForecast()
                ? getContext().getString(
                        R.string.critical_alarm_chart_description_forecast,
                        data.forecastMinutes)
                : getContext().getString(
                        R.string.critical_alarm_chart_description_recent);
        if (hideExactValue) {
            return base + ". " + getContext().getString(
                    R.string.critical_alarm_chart_private_hint);
        }
        CriticalAlarmChartData.Point latest = latestVisibleHistoryPoint();
        if (latest == null) return base;
        Locale locale = Applic.usedlocale == null
                ? Locale.getDefault() : Applic.usedlocale;
        String glucose = Applic.unit == 1
                ? String.format(locale, "%.1f %s",
                        latest.glucoseMgDl / Applic.mgdLmult,
                        getContext().getString(R.string.mmolL))
                : String.format(locale, "%.0f %s", latest.glucoseMgDl,
                        getContext().getString(R.string.mgdL));
        String trend = getContext().getString(trendLabel(data.trend()));
        return base + ". " + getContext().getString(
                R.string.critical_alarm_chart_accessibility_latest,
                glucose, trend);
    }

    private static int trendLabel(int trend) {
        if (trend >= 2) return R.string.critical_alarm_trend_rising_fast;
        if (trend == 1) return R.string.critical_alarm_trend_rising;
        if (trend <= -2) return R.string.critical_alarm_trend_falling_fast;
        if (trend == -1) return R.string.critical_alarm_trend_falling;
        return R.string.critical_alarm_trend_stable;
    }

    private boolean hasRenderableData() {
        return data != null && (data.hasForecast()
                || latestVisibleHistoryPoint() != null);
    }

    private CriticalAlarmChartData.Point latestVisibleHistoryPoint() {
        if (data == null || data.history.isEmpty()) return null;
        long minimum = data.nowMs - data.historyMinutes * 60_000L;
        for (int index = data.history.size() - 1; index >= 0; index--) {
            CriticalAlarmChartData.Point point = data.history.get(index);
            if (point.atMs >= minimum && point.atMs <= data.nowMs) {
                return point;
            }
        }
        return null;
    }

    private static float includeMinimum(float current,
            List<CriticalAlarmChartData.Point> values, boolean interval) {
        for (CriticalAlarmChartData.Point point : values) {
            current = Math.min(current,
                    interval ? point.lowMgDl : point.glucoseMgDl);
        }
        return current;
    }

    private static float includeMaximum(float current,
            List<CriticalAlarmChartData.Point> values, boolean interval) {
        for (CriticalAlarmChartData.Point point : values) {
            current = Math.max(current,
                    interval ? point.highMgDl : point.glucoseMgDl);
        }
        return current;
    }

    private static float x(long atMs, long minTime, long maxTime,
            float left, float right) {
        if (maxTime <= minTime) return left;
        float ratio = (float) ((double) (atMs - minTime)
                / (double) (maxTime - minTime));
        return left + clamp(ratio, 0f, 1f) * (right - left);
    }

    private static float y(float value, float minValue, float maxValue,
            float top, float bottom) {
        if (maxValue <= minValue) return bottom;
        float ratio = (value - minValue) / (maxValue - minValue);
        return bottom - clamp(ratio, 0f, 1f) * (bottom - top);
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private float sp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                getResources().getDisplayMetrics());
    }
}
