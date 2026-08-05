package tk.glucodata;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Compact, non-interactive activity/contribution chart for one forecast factor. */
final class ForecastActivityMiniChart extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path activityPath = new Path();
    private final Path contributionPath = new Path();
    private ForecastSnapshot.Activity factor;

    ForecastActivityMiniChart(Context context) {
        this(context, null);
    }

    ForecastActivityMiniChart(Context context, AttributeSet attrs) {
        super(context, attrs);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setMinimumHeight(ClinicalUi.dp(context, 72));
    }

    void setFactor(ForecastSnapshot.Activity value) {
        factor = value;
        invalidate();
    }

    int sourcePointCount() {
        return factor == null ? 0 : factor.points.size();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredHeight = ClinicalUi.dp(getContext(), 84);
        setMeasuredDimension(resolveSize(getSuggestedMinimumWidth(),
                widthMeasureSpec), resolveSize(desiredHeight,
                heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (factor == null || getWidth() <= 0 || getHeight() <= 0) return;
        List<PlotPoint> points = plotPoints(factor);
        if (points.size() < 2) return;

        float left = ClinicalUi.dp(getContext(), 4);
        float right = getWidth() - ClinicalUi.dp(getContext(), 4);
        float top = ClinicalUi.dp(getContext(), 7);
        float bottom = getHeight() - ClinicalUi.dp(getContext(), 8);
        float width = Math.max(1f, right - left);
        float height = Math.max(1f, bottom - top);
        float baseline = top + height * .52f;

        int color = factorColor(factor.kind);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(ClinicalUi.dp(getContext(), 1));
        paint.setColor(Color.argb(55, 170, 176, 174));
        canvas.drawLine(left, baseline, right, baseline, paint);

        long first = Math.min(points.get(0).atMs, factor.startMs);
        long last = Math.max(points.get(points.size() - 1).atMs,
                factor.effectiveEndHighMs());
        long span = Math.max(1L, last - first);
        float maxAbsContribution = 1f;
        for (PlotPoint point : points) {
            maxAbsContribution = Math.max(maxAbsContribution,
                    Math.abs(point.contribution));
        }

        // These translucent bands communicate uncertainty without pretending
        // that a single timestamp is a directly measured physiological peak.
        drawRangeBand(canvas, factor.effectivePeakLowMs(),
                factor.effectivePeakHighMs(), first, last, left, width,
                top, bottom, color, 34);
        if (factor.endLowMs != null || factor.endHighMs != null) {
            drawRangeBand(canvas, factor.effectiveEndLowMs(),
                    factor.effectiveEndHighMs(), first, last, left, width,
                    top, bottom, color, 17);
        }

        activityPath.reset();
        activityPath.moveTo(left, bottom);
        contributionPath.reset();
        boolean firstContribution = true;
        for (PlotPoint point : points) {
            float x = left + (point.atMs - first) * width / span;
            float activityY = bottom - point.activity * height * .82f;
            activityPath.lineTo(x, activityY);
            float contributionY = baseline - point.contribution
                    / maxAbsContribution * height * .38f;
            if (firstContribution) {
                contributionPath.moveTo(x, contributionY);
                firstContribution = false;
            } else {
                contributionPath.lineTo(x, contributionY);
            }
        }
        activityPath.lineTo(right, bottom);
        activityPath.close();

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0f, top, 0f, bottom,
                Color.argb(92, Color.red(color), Color.green(color),
                        Color.blue(color)),
                Color.argb(5, Color.red(color), Color.green(color),
                        Color.blue(color)), Shader.TileMode.CLAMP));
        canvas.drawPath(activityPath, paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(ClinicalUi.dp(getContext(), 2));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
        canvas.drawPath(contributionPath, paint);

        if (factor.onsetMs != null) {
            float onsetX = left + (Math.max(first,
                    Math.min(last, factor.effectiveOnsetMs())) - first)
                    * width / span;
            paint.setStrokeWidth(ClinicalUi.dp(getContext(), 1));
            paint.setColor(Color.argb(100, Color.red(color),
                    Color.green(color), Color.blue(color)));
            canvas.drawLine(onsetX, bottom - ClinicalUi.dp(getContext(), 8),
                    onsetX, bottom, paint);
        }

        float peakX = left + (Math.max(first,
                Math.min(last, factor.peakMs)) - first) * width / span;
        paint.setStrokeWidth(ClinicalUi.dp(getContext(), 1));
        paint.setColor(Color.argb(105, Color.red(color), Color.green(color),
                Color.blue(color)));
        canvas.drawLine(peakX, top, peakX, bottom, paint);
    }

    private void drawRangeBand(Canvas canvas, long rangeStart,
            long rangeEnd, long domainStart, long domainEnd, float left,
            float width, float top, float bottom, int color, int alpha) {
        long start = Math.max(domainStart,
                Math.min(domainEnd, Math.min(rangeStart, rangeEnd)));
        long end = Math.max(domainStart,
                Math.min(domainEnd, Math.max(rangeStart, rangeEnd)));
        long span = Math.max(1L, domainEnd - domainStart);
        float x1 = left + (start - domainStart) * width / span;
        float x2 = left + (end - domainStart) * width / span;
        float minimum = ClinicalUi.dp(getContext(), 2);
        if (x2 - x1 < minimum) {
            float center = (x1 + x2) * .5f;
            x1 = center - minimum * .5f;
            x2 = center + minimum * .5f;
        }
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(alpha, Color.red(color), Color.green(color),
                Color.blue(color)));
        canvas.drawRect(x1, top, x2, bottom, paint);
    }

    private static List<PlotPoint> plotPoints(
            ForecastSnapshot.Activity factor) {
        ArrayList<PlotPoint> result = new ArrayList<>();
        if (!factor.points.isEmpty()) {
            for (ForecastSnapshot.ActivityPoint point : factor.points) {
                result.add(new PlotPoint(point.atMs,
                        point.contributionMgDl, point.activity));
            }
            return result;
        }
        long actionStart = factor.effectiveOnsetMs();
        long span = Math.max(1L, factor.endMs - actionStart);
        float direction = factor.kind == ForecastSnapshot.Activity.KIND_MEAL
                ? 1f : -1f;
        for (int index = 0; index <= 16; index++) {
            long at = actionStart + span * index / 16L;
            float activity;
            if (at <= factor.peakMs) {
                activity = (at - actionStart) /
                        (float) Math.max(1L, factor.peakMs - actionStart);
            } else {
                activity = (factor.endMs - at) /
                        (float) Math.max(1L, factor.endMs - factor.peakMs);
            }
            activity = ForecastSnapshot.clamp01(activity);
            result.add(new PlotPoint(at,
                    direction * activity * Math.max(1f, factor.strength),
                    activity));
        }
        return result;
    }

    private static int factorColor(int kind) {
        if (kind == ForecastSnapshot.Activity.KIND_RAPID) {
            return Color.rgb(66, 187, 211);
        }
        if (kind == ForecastSnapshot.Activity.KIND_LONG) {
            return Color.rgb(142, 122, 217);
        }
        return Color.rgb(216, 164, 66);
    }

    private static final class PlotPoint {
        final long atMs;
        final float contribution;
        final float activity;

        PlotPoint(long atMs, float contribution, float activity) {
            this.atMs = atMs;
            this.contribution = contribution;
            this.activity = ForecastSnapshot.clamp01(activity);
        }
    }
}
