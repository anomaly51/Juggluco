package tk.glucodata;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Read-only, phone-first forecast chart used by the forecast explanation page.
 * The target band, uncertainty ribbon and dashed median deliberately use more
 * than colour alone, so the chart remains understandable in monochrome and to
 * users with colour-vision differences.
 */
public final class ForecastTrajectoryView extends View {
    static final int ZONE_LOW = -1;
    static final int ZONE_TARGET = 0;
    static final int ZONE_HIGH = 1;
    private static final float TARGET_LOW_MG_DL =
            ForecastSnapshot.AlertAssessment.DEFAULT_TARGET_LOW_MG_DL;
    private static final float TARGET_HIGH_MG_DL =
            ForecastSnapshot.AlertAssessment.DEFAULT_TARGET_HIGH_MG_DL;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path ribbon = new Path();
    private final Path median = new Path();
    private List<ForecastSnapshot.Point> points = Collections.emptyList();
    private int displayUnit;

    public ForecastTrajectoryView(Context context) {
        this(context, null);
    }

    public ForecastTrajectoryView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setFocusable(false);
        setMinimumHeight(ClinicalUi.dp(context, 184));
    }

    void setForecast(ForecastSnapshot forecast, int unit) {
        points = forecast == null ? Collections.emptyList() : forecast.points;
        displayUnit = unit;
        invalidate();
    }

    int sourcePointCount() {
        return points.size();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(resolveSize(getSuggestedMinimumWidth(),
                        widthMeasureSpec),
                resolveSize(ClinicalUi.dp(getContext(), 196),
                        heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (points.size() < 2 || getWidth() <= 0 || getHeight() <= 0) return;

        float left = ClinicalUi.dp(getContext(), 8);
        float right = getWidth() - ClinicalUi.dp(getContext(), 8);
        float top = ClinicalUi.dp(getContext(), 12);
        float bottom = getHeight() - ClinicalUi.dp(getContext(), 28);
        float width = Math.max(1f, right - left);
        float height = Math.max(1f, bottom - top);

        long start = points.get(0).atMs;
        long end = points.get(points.size() - 1).atMs;
        long duration = Math.max(1L, end - start);
        float minimum = TARGET_LOW_MG_DL - 20f;
        float maximum = TARGET_HIGH_MG_DL + 20f;
        for (ForecastSnapshot.Point point : points) {
            minimum = Math.min(minimum, point.lowMgDl - 8f);
            maximum = Math.max(maximum, point.highMgDl + 8f);
        }
        float valueSpan = Math.max(40f, maximum - minimum);

        int target = ContextCompat.getColor(getContext(),
                R.color.lifeprompt_glucose_in_target);
        int outline = ContextCompat.getColor(getContext(),
                R.color.lifeprompt_outline_variant);
        int secondary = ContextCompat.getColor(getContext(),
                R.color.lifeprompt_on_surface_variant);

        float targetTop = y(TARGET_HIGH_MG_DL, minimum, valueSpan,
                top, height);
        float targetBottom = y(TARGET_LOW_MG_DL, minimum, valueSpan,
                top, height);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(28, Color.red(target), Color.green(target),
                Color.blue(target)));
        canvas.drawRoundRect(left, targetTop, right, targetBottom,
                ClinicalUi.dp(getContext(), 8), ClinicalUi.dp(getContext(), 8),
                paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(ClinicalUi.dp(getContext(), 1));
        paint.setPathEffect(new DashPathEffect(new float[]{
                ClinicalUi.dp(getContext(), 3), ClinicalUi.dp(getContext(), 4)
        }, 0f));
        paint.setColor(Color.argb(105, Color.red(target), Color.green(target),
                Color.blue(target)));
        canvas.drawLine(left, targetTop, right, targetTop, paint);
        canvas.drawLine(left, targetBottom, right, targetBottom, paint);
        paint.setPathEffect(null);

        // A restrained grid helps read direction without competing with data.
        paint.setColor(Color.argb(78, Color.red(outline), Color.green(outline),
                Color.blue(outline)));
        for (int index = 0; index <= 2; index++) {
            float gridY = top + height * index / 2f;
            canvas.drawLine(left, gridY, right, gridY, paint);
        }

        ribbon.reset();
        for (int index = 0; index < points.size(); index++) {
            ForecastSnapshot.Point point = points.get(index);
            float x = x(point.atMs, start, duration, left, width);
            float pointY = y(point.highMgDl, minimum, valueSpan, top, height);
            if (index == 0) ribbon.moveTo(x, pointY);
            else ribbon.lineTo(x, pointY);
        }
        for (int index = points.size() - 1; index >= 0; index--) {
            ForecastSnapshot.Point point = points.get(index);
            ribbon.lineTo(x(point.atMs, start, duration, left, width),
                    y(point.lowMgDl, minimum, valueSpan, top, height));
        }
        ribbon.close();
        int ribbonColor = ContextCompat.getColor(getContext(),
                R.color.lifeprompt_primary);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(36, Color.red(ribbonColor),
                Color.green(ribbonColor), Color.blue(ribbonColor)));
        canvas.drawPath(ribbon, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(ClinicalUi.dp(getContext(), 3));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        drawSegmentedMedian(canvas, start, duration, left, width, minimum,
                valueSpan, top, height);
        paint.setPathEffect(null);

        ForecastSnapshot.Point first = points.get(0);
        ForecastSnapshot.Point last = points.get(points.size() - 1);
        drawPoint(canvas, x(first.atMs, start, duration, left, width),
                y(first.medianMgDl, minimum, valueSpan, top, height),
                trajectoryColor(first.medianMgDl), false);
        drawPoint(canvas, x(last.atMs, start, duration, left, width),
                y(last.medianMgDl, minimum, valueSpan, top, height),
                trajectoryColor(last.medianMgDl), true);

        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(android.graphics.Typeface.create("monospace",
                android.graphics.Typeface.NORMAL));
        paint.setTextSize(ClinicalUi.dp(getContext(), 11));
        paint.setColor(secondary);
        canvas.drawText(getContext().getString(R.string.forecast_chart_now),
                left, getHeight() - ClinicalUi.dp(getContext(), 8), paint);
        int minutes = Math.max(1, Math.round(duration / 60_000f));
        String endLabel = getContext().getString(
                R.string.forecast_chart_minutes_ahead, minutes);
        canvas.drawText(endLabel, right - paint.measureText(endLabel),
                getHeight() - ClinicalUi.dp(getContext(), 8), paint);

        String unit = displayUnit == 1 ? "mmol/L" : "mg/dL";
        paint.setTypeface(android.graphics.Typeface.create("monospace",
                android.graphics.Typeface.NORMAL));
        paint.setTextSize(ClinicalUi.dp(getContext(), 10));
        canvas.drawText(unit, left, top + ClinicalUi.dp(getContext(), 10), paint);
    }

    private void drawPoint(Canvas canvas, float x, float y, int color,
            boolean emphasis) {
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ContextCompat.getColor(getContext(),
                R.color.lifeprompt_surface));
        canvas.drawCircle(x, y, ClinicalUi.dp(getContext(), emphasis ? 7 : 5),
                paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(ClinicalUi.dp(getContext(), emphasis ? 3 : 2));
        paint.setColor(color);
        canvas.drawCircle(x, y, ClinicalUi.dp(getContext(), emphasis ? 5 : 4),
                paint);
    }

    private void drawSegmentedMedian(Canvas canvas, long start, long duration,
            float left, float width, float minimum, float valueSpan,
            float top, float height) {
        float dashOn = ClinicalUi.dp(getContext(), 9);
        float dashOff = ClinicalUi.dp(getContext(), 6);
        float dashPeriod = dashOn + dashOff;
        float travelled = 0f;
        for (int index = 1; index < points.size(); index++) {
            ForecastSnapshot.Point from = points.get(index - 1);
            ForecastSnapshot.Point to = points.get(index);
            float fromX = x(from.atMs, start, duration, left, width);
            float toX = x(to.atMs, start, duration, left, width);
            float fromY = y(from.medianMgDl, minimum, valueSpan, top, height);
            float toY = y(to.medianMgDl, minimum, valueSpan, top, height);
            float[] cuts = splitFractions(from.medianMgDl, to.medianMgDl);
            for (int cut = 1; cut < cuts.length; cut++) {
                float fromFraction = cuts[cut - 1];
                float toFraction = cuts[cut];
                float segmentFromX = lerp(fromX, toX, fromFraction);
                float segmentFromY = lerp(fromY, toY, fromFraction);
                float segmentToX = lerp(fromX, toX, toFraction);
                float segmentToY = lerp(fromY, toY, toFraction);
                float middleValue = lerp(from.medianMgDl, to.medianMgDl,
                        (fromFraction + toFraction) * .5f);

                median.reset();
                median.moveTo(segmentFromX, segmentFromY);
                median.lineTo(segmentToX, segmentToY);
                paint.setColor(trajectoryColor(middleValue));
                paint.setPathEffect(new DashPathEffect(
                        new float[]{dashOn, dashOff},
                        travelled % dashPeriod));
                canvas.drawPath(median, paint);
                travelled += (float) Math.hypot(segmentToX - segmentFromX,
                        segmentToY - segmentFromY);
            }
        }
    }

    static float[] splitFractions(float fromMgDl, float toMgDl) {
        float[] cuts = new float[4];
        int count = 0;
        cuts[count++] = 0f;
        float difference = toMgDl - fromMgDl;
        if (difference != 0f) {
            float low = (TARGET_LOW_MG_DL - fromMgDl) / difference;
            float high = (TARGET_HIGH_MG_DL - fromMgDl) / difference;
            if (low > 0f && low < 1f) cuts[count++] = low;
            if (high > 0f && high < 1f) cuts[count++] = high;
        }
        cuts[count++] = 1f;
        Arrays.sort(cuts, 0, count);
        return Arrays.copyOf(cuts, count);
    }

    static int zoneForMgDl(float valueMgDl) {
        if (valueMgDl < TARGET_LOW_MG_DL) return ZONE_LOW;
        if (valueMgDl > TARGET_HIGH_MG_DL) return ZONE_HIGH;
        return ZONE_TARGET;
    }

    private int trajectoryColor(float valueMgDl) {
        int zone = zoneForMgDl(valueMgDl);
        int resource = zone == ZONE_LOW ? R.color.lifeprompt_glucose_low
                : zone == ZONE_HIGH ? R.color.lifeprompt_glucose_high
                : R.color.lifeprompt_glucose_in_target;
        return ContextCompat.getColor(getContext(), resource);
    }

    private static float lerp(float from, float to, float fraction) {
        return from + (to - from) * fraction;
    }

    private static float x(long value, long start, long span, float left,
            float width) {
        return left + (value - start) * width / span;
    }

    private static float y(float value, float minimum, float span, float top,
            float height) {
        return top + (1f - (value - minimum) / span) * height;
    }
}
