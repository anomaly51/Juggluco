package tk.glucodata;

import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.widget.TextViewCompat;

/**
 * Reusable visual/control surface shared by the full-screen Activity and any
 * app-owned foreground alarm window.
 */
final class CriticalAlarmSurface extends LinearLayout {
    static final int ACKNOWLEDGE_ID = View.generateViewId();
    static final int SNOOZE_ID = View.generateViewId();
    static final int OPEN_GRAPH_ID = View.generateViewId();

    private Runnable forecastFreshnessRefresh;

    interface Actions {
        void acknowledge();
        void snooze();
        void openGraph();
    }

    CriticalAlarmSurface(Context context) {
        super(context);
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
        removeAllViews();
        if (session == null) return;

        int accent = session.low() ? 0xFFFF5F69 : 0xFFFFBF4B;
        int accentText = session.low() ? 0xFF170809 : 0xFF171209;
        setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{withAlpha(accent, 44), 0xFF090B0D,
                        0xFF07090A}));

        ScrollView scroll = new ScrollView(getContext());
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(20), dp(22), dp(20), dp(16));
        scroll.addView(content, new ScrollView.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        TextView badge = text(session.test()
                        ? getString(R.string.critical_alarm_test_badge)
                        : session.actual()
                        ? getString(R.string.critical_alarm_actual_badge)
                        : getString(R.string.critical_alarm_predictive_badge),
                12, accent, true);
        badge.setGravity(Gravity.CENTER);
        badge.setLetterSpacing(.06f);
        badge.setPadding(dp(13), dp(7), dp(13), dp(7));
        badge.setBackground(shape(withAlpha(accent, 25), 99,
                withAlpha(accent, 105), 1));
        content.addView(badge, wrapCenter(0));

        TextView direction = text(session.low()
                        ? getString(R.string.critical_alarm_low_direction)
                        : getString(R.string.critical_alarm_high_direction),
                31, Color.WHITE, true);
        direction.setGravity(Gravity.CENTER);
        direction.setLetterSpacing(.025f);
        direction.setMaxLines(2);
        direction.setAccessibilityLiveRegion(
                View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
        content.addView(direction, matchWrap(15));

        TextView status = text(session.actual()
                        ? getString(R.string.critical_alarm_status_current)
                        : session.test()
                        ? getString(R.string.critical_alarm_status_test)
                        : getString(R.string.critical_alarm_status_predicted),
                13, 0xFFAAB4AF, false);
        status.setGravity(Gravity.CENTER);
        content.addView(status, matchWrap(5));

        LinearLayout alertCard = new LinearLayout(getContext());
        alertCard.setOrientation(VERTICAL);
        alertCard.setGravity(Gravity.CENTER_HORIZONTAL);
        alertCard.setPadding(dp(18), dp(17), dp(18), dp(18));
        alertCard.setBackground(shape(0xE814181A, 22,
                withAlpha(accent, 65), 1));
        content.addView(alertCard, matchWrap(17));

        TextView title = text(session.title, 19, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(3);
        alertCard.addView(title, matchWrap(0));

        String visibleValue = locked && !session.test()
                ? getString(R.string.critical_alarm_unlock_for_value)
                : session.value;
        LinearLayout valueRow = new LinearLayout(getContext());
        valueRow.setGravity(Gravity.CENTER);
        valueRow.setOrientation(HORIZONTAL);
        alertCard.addView(valueRow, wrapCenter(12));

        TextView value = text(visibleValue, locked ? 15 : 38,
                locked ? 0xFFD3DBD7 : accent, !locked);
        value.setGravity(Gravity.CENTER);
        value.setMaxLines(2);
        if (!locked) {
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(value,
                    22, 38, 1, TypedValue.COMPLEX_UNIT_SP);
        }
        valueRow.addView(value, new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        if (!locked && chartData != null && chartData.history.size() >= 2) {
            TextView trend = trendView(chartData.trend(), accent);
            LayoutParams trendParams = new LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            trendParams.leftMargin = dp(9);
            valueRow.addView(trend, trendParams);
        }

        String visibleBody = locked && !session.test()
                ? getString(R.string.critical_alarm_lockscreen_body)
                : session.body;
        TextView body = text(visibleBody, 15, 0xFFC7D0CC, false);
        body.setGravity(Gravity.CENTER);
        body.setMaxLines(5);
        alertCard.addView(body, matchWrap(9));

        LinearLayout chartCard = new LinearLayout(getContext());
        chartCard.setOrientation(VERTICAL);
        chartCard.setPadding(dp(12), dp(13), dp(12), dp(8));
        chartCard.setBackground(shape(0xE8111516, 20,
                0xFF252C2A, 1));
        content.addView(chartCard, matchWrap(13));

        TextView chartTitle = text(
                getString(R.string.critical_alarm_chart_title),
                15, Color.WHITE, true);
        chartTitle.setPadding(dp(4), 0, dp(4), 0);
        chartCard.addView(chartTitle, matchWrap(0));

        TextView chartSubtitle = text(locked
                        ? getString(R.string.critical_alarm_chart_private_hint)
                        : chartData != null && chartData.hasForecast()
                        ? getString(R.string.critical_alarm_chart_subtitle_forecast,
                                chartData.historyMinutes,
                                chartData.forecastMinutes)
                        : getString(R.string.critical_alarm_chart_subtitle_recent,
                                chartData == null ? 45
                                        : chartData.historyMinutes),
                12, 0xFF8F9A95, false);
        chartSubtitle.setPadding(dp(4), 0, dp(4), 0);
        chartCard.addView(chartSubtitle, matchWrap(3));

        CriticalAlarmMiniChart chart = new CriticalAlarmMiniChart(getContext());
        chart.bind(chartData, accent, locked);
        chartCard.addView(chart, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        TextView instruction = text(getString(
                        R.string.critical_alarm_instruction),
                13, 0xFF98A29D, false);
        instruction.setGravity(Gravity.CENTER);
        instruction.setLineSpacing(0f, 1.13f);
        content.addView(instruction, matchWrap(15));

        LinearLayout footer = new LinearLayout(getContext());
        footer.setOrientation(VERTICAL);
        footer.setPadding(dp(20), dp(10), dp(20), dp(18));
        footer.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xF20A0C0D, 0xFF07090A}));
        addView(footer, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        Button acknowledge = button(getString(
                R.string.critical_alarm_ack_button), accent, accentText, true);
        acknowledge.setId(ACKNOWLEDGE_ID);
        acknowledge.setOnClickListener(view -> {
            if (actions != null) actions.acknowledge();
        });
        acknowledge.setMinHeight(dp(60));
        acknowledge.setMinimumHeight(dp(60));
        footer.addView(acknowledge, matchWrap(0));

        Button snooze = button(getString(
                R.string.critical_alarm_snooze_button), 0xFF1B211F,
                Color.WHITE, false);
        snooze.setId(SNOOZE_ID);
        snooze.setOnClickListener(view -> {
            if (actions != null) actions.snooze();
        });
        Button graph = button(getString(
                R.string.critical_alarm_open_graph_button), 0xFF111513,
                0xFFB7C1BC, false);
        graph.setId(OPEN_GRAPH_ID);
        graph.setOnClickListener(view -> {
            if (actions != null) actions.openGraph();
        });
        snooze.setMinHeight(dp(56));
        snooze.setMinimumHeight(dp(56));
        graph.setMinHeight(dp(56));
        graph.setMinimumHeight(dp(56));
        LinearLayout secondary = new LinearLayout(getContext());
        secondary.setOrientation(HORIZONTAL);
        LayoutParams secondaryParams = matchWrap(9);
        footer.addView(secondary, secondaryParams);
        LayoutParams half = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        secondary.addView(snooze, half);
        LayoutParams otherHalf = new LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f);
        otherHalf.leftMargin = dp(8);
        secondary.addView(graph, otherHalf);

        scheduleForecastFreshnessRefresh(session, locked, chartData,
                actions);
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
        long delayMs = refreshAtMs - System.currentTimeMillis();
        String token = session.token;
        forecastFreshnessRefresh = () -> {
            forecastFreshnessRefresh = null;
            if (!isAttachedToWindow()) return;
            long nowMs = System.currentTimeMillis();
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
        TextView result = text(glyph, 24, accent, true);
        result.setGravity(Gravity.CENTER);
        result.setPadding(dp(8), dp(3), dp(8), dp(3));
        result.setBackground(shape(withAlpha(accent, 24), 99,
                withAlpha(accent, 75), 1));
        result.setContentDescription(getString(label));
        return result;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(value == null ? "" : value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setLineSpacing(0f, 1.08f);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(String value, int background, int foreground,
            boolean primary) {
        Button button = new Button(getContext());
        button.setText(value);
        button.setTextSize(primary ? 16 : 15);
        button.setTypeface(Typeface.DEFAULT,
                primary ? Typeface.BOLD : Typeface.NORMAL);
        button.setTextColor(foreground);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), dp(9), dp(12), dp(9));
        button.setBackground(shape(background, 17,
                primary || background == Color.TRANSPARENT
                        ? Color.TRANSPARENT : 0xFF303835,
                primary || background == Color.TRANSPARENT ? 0 : 1));
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

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }
}
