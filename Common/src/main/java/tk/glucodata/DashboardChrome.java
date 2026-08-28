/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from supported glucose sensors.                               */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */

package tk.glucodata;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.graphics.Color;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.text.DateFormat;

/**
 * Phone presentation shell around the native glucose graph.
 *
 * <p>The shell owns only layout, navigation and presentation state. Glucose storage,
 * graph rendering, gestures, records, statistics and sensor management continue to
 * use the existing Java/JNI entry points.</p>
 */
final class DashboardChrome {
    private static final long DATA_REFRESH_INTERVAL_MS = 60_000L;
    private static final long INTAKE_HISTORY_WINDOW_MS = 30L * 24L * 60L * 60L * 1000L;
    static final int RANGE_UNKNOWN = 0;
    static final int RANGE_IN_TARGET = 1;
    static final int RANGE_LOW = 2;
    static final int RANGE_HIGH = 3;
    private static final int READING_NEUTRAL_COLOR = Color.rgb(242, 244, 243);
    private static final int READING_IN_TARGET_COLOR = Color.rgb(78, 203, 131);
    private static final int READING_HIGH_COLOR = Color.rgb(242, 169, 59);
    private static final int READING_LOW_COLOR = Color.rgb(240, 107, 101);
    private static final int INTAKE_FLAG_MEAL = 1;
    private static final int INTAKE_FLAG_CARBS_PRESENT = 1 << 1;
    private static final int INTAKE_FLAG_RAPID_INSULIN = 1 << 2;
    private static final int INTAKE_FLAG_LONG_INSULIN = 1 << 3;
    private static final int NAVIGATION_RAIL_WIDTH_DP = 72;
    static final int NAVIGATION_RAIL_ITEM_HEIGHT_DP = 96;
    private static final int MAX_BODY_CONTENT_WIDTH_DP = 1240;
    private static final int[] RANGE_IDS = {
            R.id.modern_dashboard_range_3h,
            R.id.modern_dashboard_range_6h,
            R.id.modern_dashboard_range_8h,
            R.id.modern_dashboard_range_12h,
            R.id.modern_dashboard_range_24h
    };

    private static final int[] RANGE_HOURS = {3, 6, 8, 12, 24};

    private static final int[] DESTINATION_IDS = {
            R.id.modern_dashboard_overview,
            R.id.modern_dashboard_records,
            R.id.modern_dashboard_statistics,
            R.id.modern_dashboard_menu
    };

    private static final int[] NAVIGATION_IDS = {
            R.id.modern_dashboard_overview,
            R.id.modern_dashboard_add_intake,
            R.id.modern_dashboard_menu
    };

    private final MainActivity activity;
    private final GlucoseCurve curve;
    private final IntakeRepository intakeRepository;
    private final ForecastRepository forecastRepository;
    private final int cardSpacing;

    private FrameLayout root;
    private FrameLayout graphHost;
    private LinearLayout content;
    private LinearLayout body;
    private LinearLayout actions;
    private View appBar;
    private View heroCard;
    private LinearLayout heroContent;
    private LinearLayout readingColumn;
    private LinearLayout heroMeta;
    private Button sensorAction;
    private View graphCard;
    private View graphControls;
    private HorizontalScrollView rangeScroller;
    private LinearLayout rangeTrack;
    private View heroReading;
    private TextView heroValue;
    private TextView heroTrend;
    private TextView heroUnit;
    private TextView heroStatus;
    private TextView heroFreshness;
    private TextView heroRangeState;
    private TextView headerDate;
    private View backendAction;
    private TextView backendStatus;
    private Button forecastAction;
    private TextView targetRange;
    private View heroMetaSeparator;
    private View[] presentationControls;

    private Insets systemInsets = Insets.NONE;
    private boolean attached;
    private boolean foreground;
    private boolean obscured;
    private boolean graphPreviewEnabled;
    private int selectedDestination = R.id.modern_dashboard_overview;
    private int selectedHours = -1;
    private long lastIntakeRefreshMs;
    private int backendStatusGeneration;
    private boolean backendCheckInFlight;
    private CharSequence graphSummaryDescription = "";

    private final IntakeRepository.Listener intakeListener;
    private final ForecastRepository.Listener forecastListener;
    private final Runnable backendConfigurationListener;

    private final Set<View> overlays = Collections.newSetFromMap(
            new IdentityHashMap<>());
    private final Map<View, View.OnAttachStateChangeListener> overlayListeners =
            new IdentityHashMap<>();
    private final ViewTreeObserver.OnGlobalLayoutListener overlayVisibilityListener =
            this::updateOverlayVisibility;

    private final View.OnLayoutChangeListener layoutChangeListener =
            (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if ((right - left) != (oldRight - oldLeft)
                        || (bottom - top) != (oldBottom - oldTop)) {
                    updateLayout(right - left, bottom - top);
                }
            };

    private final Runnable refreshClock = new Runnable() {
        @Override
        public void run() {
            if (!attached || !foreground || root == null) {
                return;
            }
            refreshData();
            refreshBackendStatus();
            root.postDelayed(this, DATA_REFRESH_INTERVAL_MS);
        }
    };

    DashboardChrome(MainActivity activity, GlucoseCurve curve) {
        if (activity == null || curve == null) {
            throw new IllegalArgumentException("DashboardChrome requires an activity and graph");
        }
        this.activity = activity;
        this.curve = curve;
        intakeRepository = IntakeRepository.get(activity);
        forecastRepository = ForecastRepository.get(activity);
        intakeListener = events -> {
            pushIntakeEventsToGraph(events);
            // This callback is emitted only for cached/confirmed backend
            // records. Unconfirmed meal-chat proposals never reach it.
            forecastRepository.refreshAfterConfirmedIntake();
            this.activity.requestRender();
        };
        forecastListener = state -> {
            updateForecastAction(state);
            this.activity.requestRender();
        };
        backendConfigurationListener =
                this::handleBackendConfigurationChanged;
        cardSpacing = Math.round(
                8.0f * activity.getResources().getDisplayMetrics().density);
    }

    /**
     * Builds the real Activity content view and places the existing native graph in
     * its dedicated card. No listeners or native reads are started here.
     */
    View createView() {
        if (root != null) {
            return root;
        }

        root = (FrameLayout) LayoutInflater.from(activity)
                .inflate(R.layout.modern_dashboard_chrome, null, false);
        graphHost = root.findViewById(R.id.modern_dashboard_graph_host);
        content = root.findViewById(R.id.modern_dashboard_content);
        body = root.findViewById(R.id.modern_dashboard_body);
        actions = root.findViewById(R.id.modern_dashboard_actions);
        appBar = root.findViewById(R.id.modern_dashboard_app_bar);
        heroCard = root.findViewById(R.id.modern_dashboard_hero);
        heroContent = root.findViewById(R.id.modern_dashboard_hero_content);
        readingColumn = root.findViewById(R.id.modern_dashboard_reading_column);
        heroMeta = root.findViewById(R.id.modern_dashboard_meta);
        sensorAction = root.findViewById(R.id.modern_dashboard_sensor);
        graphCard = root.findViewById(R.id.modern_dashboard_graph_card);
        graphCard.setClipToOutline(true);
        graphControls = root.findViewById(R.id.modern_dashboard_graph_controls);
        rangeScroller = root.findViewById(
                R.id.modern_dashboard_range_scroller);
        rangeTrack = root.findViewById(R.id.modern_dashboard_ranges);
        heroReading = root.findViewById(R.id.modern_dashboard_hero_reading);
        heroValue = root.findViewById(R.id.modern_dashboard_value);
        heroTrend = root.findViewById(R.id.modern_dashboard_trend);
        heroUnit = root.findViewById(R.id.modern_dashboard_unit);
        heroStatus = root.findViewById(R.id.modern_dashboard_status);
        heroFreshness = root.findViewById(R.id.modern_dashboard_freshness);
        heroRangeState = root.findViewById(R.id.modern_dashboard_range_state);
        headerDate = root.findViewById(R.id.modern_dashboard_date);
        backendAction = root.findViewById(
                R.id.modern_dashboard_backend_action);
        backendStatus = root.findViewById(
                R.id.modern_dashboard_backend_status);
        forecastAction = root.findViewById(
                R.id.modern_dashboard_forecast);
        targetRange = root.findViewById(R.id.modern_dashboard_target_range);
        heroMetaSeparator = root.findViewById(
                R.id.modern_dashboard_meta_separator);
        presentationControls = new View[]{appBar, heroCard, graphControls,
                forecastAction, actions};

        ViewParent currentParent = curve.getParent();
        if (currentParent instanceof ViewGroup) {
            ((ViewGroup) currentParent).removeView(curve);
        }
        graphHost.addView(curve, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        curve.setContentDescription(activity.getString(
                R.string.dashboard_graph_hint));

        updateDestinationSelection();
        updateRangeSelection();
        refreshHeaderDate();
        refreshTargetRange();
        showLoadingState();
        return root;
    }

    /** Activates the already-created shell. It never adds another Activity view. */
    void attach() {
        if (attached || Applic.isWearable) {
            return;
        }
        if (root == null) {
            throw new IllegalStateException("createView() must be called before attach()");
        }

        configureActions();
        root.addOnLayoutChangeListener(layoutChangeListener);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            systemInsets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            updateLayout(view.getWidth(), view.getHeight());
            // The nested GlucoseCurve listener still needs the original insets in
            // order to keep legacy Java overlay globals in sync.
            return windowInsets;
        });

        attached = true;
        intakeRepository.addListener(intakeListener);
        forecastRepository.addListener(forecastListener);
        intakeRepository.addConfigurationListener(
                backendConfigurationListener);
        root.getViewTreeObserver().addOnGlobalLayoutListener(
                overlayVisibilityListener);

        refreshLayout();
        refreshData();
        root.removeCallbacks(refreshClock);
    }

    private void configureActions() {
        configureAction(R.id.modern_dashboard_overview,
                R.string.dashboard_overview, view -> {
                    setSelectedDestination(R.id.modern_dashboard_overview);
                    showNow();
                });
        configureAction(R.id.modern_dashboard_add_intake,
                R.string.intake_add_short,
                view -> activity.showIntakeComposer());
        configureAction(R.id.modern_dashboard_records,
                R.string.dashboard_records, view -> {
                    setSelectedDestination(R.id.modern_dashboard_records);
                    showRecords();
                });
        configureAction(R.id.modern_dashboard_statistics,
                R.string.dashboard_insights, view -> {
                    setSelectedDestination(R.id.modern_dashboard_statistics);
                    showStatistics();
                });
        configureAction(R.id.modern_dashboard_menu,
                R.string.dashboard_more, view -> {
                    setSelectedDestination(R.id.modern_dashboard_menu);
                    openMenu();
                });
        configureAction(R.id.modern_dashboard_sensor,
                R.string.dashboard_sensor_status,
                view -> bluediag.start(activity));
        configureAction(R.id.modern_dashboard_now,
                R.string.now, view -> showNow());
        backendAction.setOnClickListener(view ->
                IntakeBackendSettings.show(activity));
        ViewCompat.setTooltipText(backendAction,
                activity.getString(R.string.dashboard_backend_settings));
        forecastAction.setOnClickListener(view ->
                activity.showForecastDetails());
        ViewCompat.setTooltipText(forecastAction,
                activity.getString(R.string.forecast_dashboard_unavailable));

        for (int index = 0; index < RANGE_IDS.length; index++) {
            final int hours = RANGE_HOURS[index];
            Button range = root.findViewById(RANGE_IDS[index]);
            range.setOnClickListener(view -> selectRange(hours));
            ViewCompat.setTooltipText(range,
                    activity.getString(R.string.dashboard_show_hours, hours));
        }
    }

    private void configureAction(int viewId, int descriptionId,
            View.OnClickListener listener) {
        View action = root.findViewById(viewId);
        // Records and Insights deliberately no longer live in the primary chrome.
        // Their IDs and handlers remain available to secondary entry points.
        if (action == null) {
            if (isPrimaryNavigationAction(viewId)) {
                throw new IllegalStateException(
                        "Missing primary dashboard action " + viewId);
            }
            return;
        }
        action.setContentDescription(activity.getString(descriptionId));
        action.setOnClickListener(listener);
        ViewCompat.setTooltipText(action, activity.getString(descriptionId));
    }

    private void clearActionListeners() {
        if (root == null) {
            return;
        }
        int[] actionIds = {
                R.id.modern_dashboard_overview,
                R.id.modern_dashboard_add_intake,
                R.id.modern_dashboard_records,
                R.id.modern_dashboard_statistics,
                R.id.modern_dashboard_menu,
                R.id.modern_dashboard_sensor,
                R.id.modern_dashboard_now,
                R.id.modern_dashboard_backend_action,
                R.id.modern_dashboard_forecast
        };
        for (int actionId : actionIds) {
            View action = root.findViewById(actionId);
            if (action != null) {
                action.setOnClickListener(null);
            }
        }
        for (int rangeId : RANGE_IDS) {
            View range = root.findViewById(rangeId);
            if (range != null) {
                range.setOnClickListener(null);
            }
        }
    }

    private void selectRange(int hours) {
        selectedHours = hours;
        updateRangeSelection();
        Natives.setgraphhours(hours);
        activity.requestRender();
        if (root != null) {
            root.announceForAccessibility(
                    activity.getString(R.string.dashboard_hours_selected, hours));
        }
    }

    private void updateRangeSelection() {
        if (root == null) {
            return;
        }
        for (int index = 0; index < RANGE_IDS.length; index++) {
            View range = root.findViewById(RANGE_IDS[index]);
            boolean selected = RANGE_HOURS[index] == selectedHours;
            range.setSelected(selected);
            range.setActivated(selected);
        }
    }

    private void setSelectedDestination(int destinationId) {
        selectedDestination = destinationId;
        updateDestinationSelection();
    }

    private void updateDestinationSelection() {
        if (root == null) {
            return;
        }
        for (int destinationId : DESTINATION_IDS) {
            View destination = root.findViewById(destinationId);
            if (destination == null) {
                continue;
            }
            boolean selected = destinationId == selectedDestination;
            destination.setSelected(selected);
            destination.setActivated(selected);
        }
    }

    /**
     * Refreshes the Java presentation model. Debug builds may request the native
     * render-only preview when no real reading exists; that preview never enters
     * sensor storage and is always labelled as non-medical demo data.
     */
    void refreshData() {
        if (root == null) {
            return;
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            root.post(this::refreshData);
            return;
        }

        syncRangeSelection();
        refreshHeaderDate();
        refreshTargetRange();
        refreshIntakeEvents(false);
        forecastRepository.refreshFromDashboard();

        final strGlucose glucose;
        try {
            glucose = Natives.lastglucose();
        } catch (UnsatisfiedLinkError error) {
            setGraphPreviewEnabled(false);
            showEmptyState();
            return;
        }

        if (glucose == null || glucose.time <= 0L || glucose.value == null
                || glucose.value.trim().isEmpty()) {
            if (BuildConfig.DEBUG) {
                setGraphPreviewEnabled(true);
                showPreviewState();
            } else {
                setGraphPreviewEnabled(false);
                showEmptyState();
            }
            return;
        }

        setGraphPreviewEnabled(false);

        long nowSeconds = System.currentTimeMillis() / 1000L;
        long ageSeconds = Math.max(0L, nowSeconds - glucose.time);
        boolean stale = isReadingStale(ageSeconds);

        heroValue.setText(glucose.value);
        heroUnit.setText(readUnitLabel());
        heroStatus.setText(stale
                ? R.string.dashboard_status_outdated
                : R.string.dashboard_status_live);
        heroStatus.setSelected(!stale);
        heroStatus.setActivated(stale);
        heroFreshness.setText(freshnessText(ageSeconds, stale));
        heroFreshness.setActivated(stale);
        heroFreshness.setVisibility(View.VISIBLE);
        heroReading.setActivated(stale);
        int readingRange = applyReadingRangeColor(glucose.value);
        updateReadingRangeState(readingRange);

        String trendName = readTrendName(glucose.rate);
        String trendArrow = trendArrowForName(trendName);
        if (trendArrow.isEmpty()) {
            heroTrend.setText("");
            heroTrend.setVisibility(View.GONE);
            heroTrend.setContentDescription(null);
        } else {
            heroTrend.setVisibility(View.VISIBLE);
            heroTrend.setText(trendArrow);
            heroTrend.setContentDescription(activity.getString(
                    trendDescriptionForName(trendName)));
        }

        String unit = heroUnit.getText().toString();
        String trendDescription = trendArrow.isEmpty()
                ? activity.getString(R.string.dashboard_trend_unavailable)
                : heroTrend.getContentDescription().toString();
        heroReading.setContentDescription(activity.getString(
                R.string.dashboard_reading_description,
                glucose.value,
                unit,
                trendDescription,
                heroFreshness.getText()));
        graphSummaryDescription = heroReading.getContentDescription();
        refreshGraphAccessibilityDescription();
    }

    private void setGraphPreviewEnabled(boolean enabled) {
        if (graphPreviewEnabled == enabled) {
            if (enabled && BuildConfig.DEBUG) {
                forecastRepository.showDebugPreview(
                        System.currentTimeMillis(), previewMgDl());
            }
            return;
        }
        try {
            Natives.setgraphpreview(enabled);
            graphPreviewEnabled = enabled;
            if (enabled && BuildConfig.DEBUG) {
                forecastRepository.showDebugPreview(
                        System.currentTimeMillis(), previewMgDl());
                updateForecastAction(forecastRepository.snapshot());
            } else {
                forecastRepository.restoreGraphProjection();
                updateForecastAction(forecastRepository.snapshot());
            }
            activity.requestRender();
        } catch (UnsatisfiedLinkError error) {
            graphPreviewEnabled = false;
            forecastRepository.restoreGraphProjection();
        }
    }

    private float previewMgDl() {
        int unit = 2;
        float low = 70f;
        float high = 180f;
        try {
            unit = Natives.getunit();
            low = Natives.targetlow();
            high = Natives.targethigh();
        } catch (UnsatisfiedLinkError ignored) {
        }
        float midpoint = low < high ? (low + high) * .5f
                : (unit == 1 ? 6.6f : 118f);
        return unit == 1 ? midpoint * 18f : midpoint;
    }

    private void syncRangeSelection() {
        final int currentHours;
        try {
            currentHours = Natives.getgraphhours();
        } catch (UnsatisfiedLinkError error) {
            return;
        }
        if (selectedHours != currentHours) {
            selectedHours = currentHours;
            updateRangeSelection();
        }
    }

    private void showEmptyState() {
        if (heroValue == null) {
            return;
        }
        setReadingColor(READING_NEUTRAL_COLOR);
        heroValue.setText(R.string.dashboard_empty_value);
        heroUnit.setText("");
        heroTrend.setText("");
        heroTrend.setVisibility(View.GONE);
        heroTrend.setContentDescription(null);
        heroStatus.setText(R.string.dashboard_status_waiting);
        heroStatus.setSelected(false);
        heroStatus.setActivated(false);
        updateReadingRangeState(RANGE_UNKNOWN);
        heroRangeState.setText(R.string.dashboard_state_waiting);
        heroFreshness.setText(R.string.dashboard_no_current_reading);
        heroFreshness.setActivated(false);
        heroFreshness.setVisibility(View.GONE);
        heroReading.setActivated(false);
        heroReading.setContentDescription(
                activity.getString(R.string.dashboard_no_current_reading));
        graphSummaryDescription = heroReading.getContentDescription();
        refreshGraphAccessibilityDescription();
    }

    private void showPreviewState() {
        if (heroValue == null) {
            return;
        }
        String unit = readUnitLabel();
        if (unit.isEmpty()) {
            unit = activity.getString(R.string.mgdL);
        }
        String value = previewValueLabel();
        setReadingColor(READING_IN_TARGET_COLOR);
        updateReadingRangeState(RANGE_IN_TARGET);
        heroValue.setText(value);
        heroUnit.setText(unit);
        heroTrend.setText("\u2192");
        heroTrend.setVisibility(View.VISIBLE);
        heroTrend.setContentDescription(
                activity.getString(R.string.dashboard_trend_steady));
        heroStatus.setText(R.string.dashboard_status_demo);
        heroStatus.setSelected(false);
        heroStatus.setActivated(false);
        heroFreshness.setText(R.string.dashboard_preview_short);
        heroFreshness.setActivated(false);
        heroFreshness.setVisibility(View.VISIBLE);
        heroReading.setActivated(false);
        heroReading.setContentDescription(activity.getString(
                R.string.dashboard_preview_description, value, unit));
        graphSummaryDescription = heroReading.getContentDescription();
        refreshGraphAccessibilityDescription();
    }

    private void showLoadingState() {
        if (heroValue == null) {
            return;
        }
        setReadingColor(READING_NEUTRAL_COLOR);
        updateReadingRangeState(RANGE_UNKNOWN);
        heroValue.setText(R.string.dashboard_empty_value);
        heroUnit.setText("");
        heroTrend.setText("");
        heroTrend.setVisibility(View.GONE);
        heroTrend.setContentDescription(null);
        heroStatus.setText(R.string.dashboard_status_loading);
        heroStatus.setSelected(false);
        heroStatus.setActivated(false);
        heroFreshness.setText("");
        heroFreshness.setActivated(false);
        heroFreshness.setVisibility(View.GONE);
        heroReading.setActivated(false);
        heroReading.setContentDescription(
                activity.getString(R.string.dashboard_loading_description));
        graphSummaryDescription = heroReading.getContentDescription();
        refreshGraphAccessibilityDescription();
    }

    private String previewValueLabel() {
        int unit = 2;
        float low = unit == 1 ? 3.9f : 70.0f;
        float high = unit == 1 ? 10.0f : 180.0f;
        try {
            unit = Natives.getunit();
            low = Natives.targetlow();
            high = Natives.targethigh();
        } catch (UnsatisfiedLinkError ignored) {
            // The fallback stays a clearly labelled preview value.
        }
        float midpoint = low < high ? (low + high) * .5f
                : (unit == 1 ? 6.6f : 118.0f);
        return unit == 1
                ? String.format(Locale.getDefault(), "%.1f", midpoint)
                : String.format(Locale.getDefault(), "%.0f", midpoint);
    }

    private int applyReadingRangeColor(String value) {
        float low;
        float high;
        try {
            low = Natives.targetlow();
            high = Natives.targethigh();
        } catch (UnsatisfiedLinkError error) {
            setReadingColor(READING_NEUTRAL_COLOR);
            return RANGE_UNKNOWN;
        }
        int range = classifyReadingRange(value, low, high);
        switch (range) {
            case RANGE_IN_TARGET:
                setReadingColor(READING_IN_TARGET_COLOR);
                break;
            case RANGE_LOW:
                setReadingColor(READING_LOW_COLOR);
                break;
            case RANGE_HIGH:
                setReadingColor(READING_HIGH_COLOR);
                break;
            default:
                setReadingColor(READING_NEUTRAL_COLOR);
                break;
        }
        return range;
    }

    private void updateReadingRangeState(int range) {
        if (heroRangeState == null) {
            return;
        }
        int label;
        int color;
        switch (range) {
            case RANGE_IN_TARGET:
                label = R.string.dashboard_state_in_target;
                color = READING_IN_TARGET_COLOR;
                break;
            case RANGE_LOW:
                label = R.string.dashboard_state_low;
                color = READING_LOW_COLOR;
                break;
            case RANGE_HIGH:
                label = R.string.dashboard_state_high;
                color = READING_HIGH_COLOR;
                break;
            default:
                label = R.string.dashboard_state_unknown;
                color = Color.rgb(147, 155, 151);
                break;
        }
        heroRangeState.setText(label);
        heroRangeState.setTextColor(color);
    }

    private void refreshHeaderDate() {
        if (headerDate == null) {
            return;
        }
        headerDate.setText(DateFormat.getDateInstance(
                DateFormat.MEDIUM, Locale.getDefault()).format(new Date()));
    }

    private void refreshTargetRange() {
        if (targetRange == null) {
            return;
        }
        try {
            int unit = Natives.getunit();
            float low = Natives.targetlow();
            float high = Natives.targethigh();
            if (!(low < high)) {
                targetRange.setText(R.string.dashboard_target_unavailable);
                return;
            }
            String unitLabel = readUnitLabel();
            targetRange.setText(activity.getString(
                    R.string.dashboard_target_range,
                    formatTargetValue(low, unit),
                    formatTargetValue(high, unit),
                    unitLabel));
        } catch (UnsatisfiedLinkError error) {
            targetRange.setText(R.string.dashboard_target_unavailable);
        } finally {
            refreshGraphAccessibilityDescription();
        }
    }

    private void refreshGraphAccessibilityDescription() {
        if (curve == null) {
            return;
        }
        StringBuilder description = new StringBuilder();
        appendAccessibilitySentence(description, graphSummaryDescription);
        if (targetRange != null && targetRange.getText().length() > 0) {
            appendAccessibilitySentence(description, targetRange.getText());
        }
        appendAccessibilitySentence(description,
                activity.getString(R.string.dashboard_graph_hint));
        curve.setContentDescription(description);
    }

    static void appendAccessibilitySentence(StringBuilder target,
            CharSequence text) {
        if (text == null || text.length() == 0) {
            return;
        }
        if (target.length() > 0) {
            target.append(' ');
        }
        target.append(text);
        char last = target.charAt(target.length() - 1);
        if (last != '.' && last != '!' && last != '?') {
            target.append('.');
        }
    }

    static String formatTargetValue(float value, int unit) {
        return unit == 1
                ? String.format(Locale.getDefault(), "%.1f", value)
                : String.format(Locale.getDefault(), "%.0f", value);
    }

    private void setReadingColor(int color) {
        heroValue.setTextColor(color);
        heroTrend.setTextColor(color);
    }

    static int classifyReadingRange(String value, float low, float high) {
        if (value == null || !(low < high)) {
            return RANGE_UNKNOWN;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.equals("LO") || normalized.equals("LOW")) {
            return RANGE_LOW;
        }
        if (normalized.equals("HI") || normalized.equals("HIGH")) {
            return RANGE_HIGH;
        }
        try {
            float reading = Float.parseFloat(normalized.replace(',', '.'));
            if (reading < low) {
                return RANGE_LOW;
            }
            if (reading > high) {
                return RANGE_HIGH;
            }
            return RANGE_IN_TARGET;
        } catch (NumberFormatException error) {
            return RANGE_UNKNOWN;
        }
    }

    private String readUnitLabel() {
        try {
            switch (Natives.getunit()) {
                case 1:
                    return activity.getString(R.string.mmolL);
                case 2:
                    return activity.getString(R.string.mgdL);
                default:
                    return "";
            }
        } catch (UnsatisfiedLinkError error) {
            return "";
        }
    }

    private String readTrendName(float rate) {
        if (Float.isNaN(rate)) {
            return "";
        }
        try {
            String trend = Natives.getxDripTrendName(rate);
            return trend == null ? "" : trend;
        } catch (UnsatisfiedLinkError error) {
            return "";
        }
    }

    private CharSequence freshnessText(long ageSeconds, boolean stale) {
        if (ageSeconds < 60L) {
            return activity.getString(R.string.dashboard_updated_just_now);
        }
        int minutes = (int) Math.min(Integer.MAX_VALUE, ageSeconds / 60L);
        int plurals = stale
                ? R.plurals.dashboard_outdated_minutes
                : R.plurals.dashboard_updated_minutes;
        return activity.getResources().getQuantityString(
                plurals, minutes, minutes);
    }

    static boolean isReadingStale(long ageSeconds) {
        return ageSeconds >= Notify.glucosetimeoutSEC;
    }

    static String trendArrowForName(String trendName) {
        if (trendName == null) {
            return "";
        }
        switch (trendName) {
            case "DoubleUp":
                return "\u21C8";
            case "SingleUp":
                return "\u2191";
            case "FortyFiveUp":
                return "\u2197";
            case "Flat":
                return "\u2192";
            case "FortyFiveDown":
                return "\u2198";
            case "SingleDown":
                return "\u2193";
            case "DoubleDown":
                return "\u21CA";
            default:
                return "";
        }
    }

    private static int trendDescriptionForName(String trendName) {
        if (trendName == null) {
            return R.string.dashboard_trend_unavailable;
        }
        switch (trendName) {
            case "DoubleUp":
                return R.string.dashboard_trend_double_up;
            case "SingleUp":
                return R.string.dashboard_trend_up;
            case "FortyFiveUp":
                return R.string.dashboard_trend_up_slightly;
            case "Flat":
                return R.string.dashboard_trend_steady;
            case "FortyFiveDown":
                return R.string.dashboard_trend_down_slightly;
            case "SingleDown":
                return R.string.dashboard_trend_down;
            case "DoubleDown":
                return R.string.dashboard_trend_double_down;
            default:
                return R.string.dashboard_trend_unavailable;
        }
    }

    /**
     * Keeps all presentation controls out of sight and out of the accessibility
     * tree while a legacy modal view is shown. The graph stays attached and visible.
     */
    void trackOverlay(View overlay) {
        if (!attached || root == null || overlay == root
                || overlays.contains(overlay)) {
            return;
        }
        View.OnAttachStateChangeListener listener =
                new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View view) {
                        updateOverlayVisibility();
                    }

                    @Override
                    public void onViewDetachedFromWindow(View view) {
                        untrackOverlay(view);
                    }
                };
        overlays.add(overlay);
        overlayListeners.put(overlay, listener);
        overlay.addOnAttachStateChangeListener(listener);
        updateOverlayVisibility();
    }

    static boolean isGraphGestureState(boolean attached, boolean obscured,
            int selectedDestination) {
        return attached && !obscured
                && selectedDestination == R.id.modern_dashboard_overview;
    }

    /** True only while the native chart is the unobscured dashboard surface. */
    boolean acceptsGraphGestures() {
        if (!isGraphGestureState(attached, obscured, selectedDestination)
                || root == null || graphHost == null
                || curve.getParent() != graphHost || !curve.isShown()) {
            return false;
        }
        for (View overlay : overlays) {
            if (overlay.isAttachedToWindow()
                    && overlay.getVisibility() == View.VISIBLE
                    && overlay.isShown()) {
                return false;
            }
        }
        return true;
    }

    private void untrackOverlay(View overlay) {
        View.OnAttachStateChangeListener listener = overlayListeners.remove(overlay);
        if (listener != null) {
            overlay.removeOnAttachStateChangeListener(listener);
        }
        overlays.remove(overlay);
        // Defer the visibility pass until ViewGroup has finished removing it.
        if (root != null) {
            root.post(this::updateOverlayVisibility);
        }
    }

    private void updateOverlayVisibility() {
        if (!attached || root == null || presentationControls == null) {
            return;
        }
        boolean nowObscured = false;
        for (View overlay : overlays) {
            if (overlay.isAttachedToWindow()
                    && overlay.getVisibility() == View.VISIBLE
                    && overlay.isShown()) {
                nowObscured = true;
                break;
            }
        }

        if (obscured != nowObscured) {
            obscured = nowObscured;
            for (View presentationControl : presentationControls) {
                presentationControl.setVisibility(
                        obscured ? View.GONE : View.VISIBLE);
                presentationControl.setImportantForAccessibility(obscured
                        ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                        : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            }
            if (!obscured) {
                setSelectedDestination(R.id.modern_dashboard_overview);
                activity.lightBars(false);
            }
        }

        if (!obscured) {
            root.invalidate();
        }
    }

    void refreshLayout() {
        if (!attached || root == null) {
            return;
        }
        root.setLayoutDirection(
                activity.getResources().getConfiguration().getLayoutDirection());
        root.post(() -> {
            if (root == null || !attached) {
                return;
            }
            ViewCompat.requestApplyInsets(root);
            updateLayout(root.getWidth(), root.getHeight());
        });
    }

    /** Starts network-backed presentation refresh only while the Activity is visible. */
    void onForeground() {
        if (!attached || root == null || foreground) {
            return;
        }
        foreground = true;
        forecastRepository.onForeground();
        forceRefreshBackendStatus();
        root.removeCallbacks(refreshClock);
        root.postDelayed(refreshClock, DATA_REFRESH_INTERVAL_MS);
    }

    /** Stops polling and invalidates callbacks as soon as the Activity is paused. */
    void onBackground() {
        if (!foreground || root == null) {
            return;
        }
        foreground = false;
        forecastRepository.onBackground();
        root.removeCallbacks(refreshClock);
        backendStatusGeneration++;
        backendCheckInFlight = false;
    }

    void detach() {
        if (!attached || root == null) {
            return;
        }
        setGraphPreviewEnabled(false);
        intakeRepository.removeListener(intakeListener);
        forecastRepository.removeListener(forecastListener);
        intakeRepository.removeConfigurationListener(
                backendConfigurationListener);
        try {
            Natives.setTimelineEvents(new int[0],new long[0],
                    new float[0],new float[0],new int[0]);
        } catch (UnsatisfiedLinkError ignored) {
        }
        ForecastRepository.clearNativeForecast();
        attached = false;
        foreground = false;
        backendStatusGeneration++;
        backendCheckInFlight = false;
        root.removeCallbacks(refreshClock);

        ViewTreeObserver observer = root.getViewTreeObserver();
        if (observer.isAlive()) {
            observer.removeOnGlobalLayoutListener(overlayVisibilityListener);
        }
        for (Map.Entry<View, View.OnAttachStateChangeListener> entry
                : overlayListeners.entrySet()) {
            entry.getKey().removeOnAttachStateChangeListener(entry.getValue());
        }
        overlayListeners.clear();
        overlays.clear();
        root.removeOnLayoutChangeListener(layoutChangeListener);
        ViewCompat.setOnApplyWindowInsetsListener(root, null);
        clearActionListeners();
        obscured = false;
        systemInsets = Insets.NONE;
    }

    private void refreshIntakeEvents(boolean force) {
        long now=System.currentTimeMillis();
        if(!force&&now-lastIntakeRefreshMs<DATA_REFRESH_INTERVAL_MS)
            return;
        lastIntakeRefreshMs=now;
        intakeRepository.refresh(now-INTAKE_HISTORY_WINDOW_MS,
                now+60L*60L*1000L,new IntakeRepository.Callback<java.util.List<IntakeEvent>>() {
                    @Override
                    public void onSuccess(java.util.List<IntakeEvent> value) {
                        // Listener owns the projection and redraw.
                    }

                    @Override
                    public void onError(String message) {
                        // Cached events stay visible; composer/settings expose status.
                    }
                });
    }

    /**
     * Checks the actual configured service, including the protected read used by
     * {@link IntakeRepository#health}. The chip therefore reports reachability,
     * not merely whether a URL has been entered in Settings.
     */
    private void refreshBackendStatus() {
        if (!attached || !foreground || backendStatus == null
                || backendCheckInFlight) {
            return;
        }
        backendCheckInFlight = true;
        final int generation = ++backendStatusGeneration;
        if (backendStatus.getText().length() == 0) {
            showBackendStatus(R.string.dashboard_backend_checking,
                    false, false);
        }
        intakeRepository.health(new IntakeRepository.Callback<org.json.JSONObject>() {
            @Override
            public void onSuccess(org.json.JSONObject value) {
                if (generation != backendStatusGeneration) {
                    return;
                }
                backendCheckInFlight = false;
                if (!attached || backendStatus == null) {
                    return;
                }
                boolean connected = "ok".equalsIgnoreCase(
                        value.optString("status", "ok"))
                        && "ok".equalsIgnoreCase(
                        value.optString("database", ""));
                showBackendStatus(connected
                                ? R.string.dashboard_backend_connected
                                : R.string.dashboard_backend_offline,
                        connected, !connected);
            }

            @Override
            public void onError(String message) {
                if (generation != backendStatusGeneration) {
                    return;
                }
                backendCheckInFlight = false;
                if (!attached || backendStatus == null) {
                    return;
                }
                showBackendStatus(R.string.dashboard_backend_offline,
                        false, true);
            }
        });
    }

    private void forceRefreshBackendStatus() {
        backendStatusGeneration++;
        backendCheckInFlight = false;
        if (attached && foreground && backendStatus != null) {
            showBackendStatus(R.string.dashboard_backend_checking,
                    false, false);
            refreshBackendStatus();
        }
    }

    private void handleBackendConfigurationChanged() {
        lastIntakeRefreshMs = 0L;
        forceRefreshBackendStatus();
        if (attached) refreshIntakeEvents(true);
    }

    private void showBackendStatus(int label, boolean connected,
            boolean offline) {
        backendStatus.setSelected(connected);
        backendStatus.setActivated(offline);
        backendStatus.setText(label);
        String state = activity.getString(label);
        String actionDescription = activity.getString(
                R.string.dashboard_backend_open_settings, state);
        backendAction.setContentDescription(actionDescription);
        ViewCompat.setTooltipText(backendAction, actionDescription);
    }

    private void updateForecastAction(ForecastRepository.State state) {
        if (forecastAction == null || state == null) return;
        if (graphPreviewEnabled) {
            forecastAction.setText(R.string.forecast_dashboard_preview);
            forecastAction.setSelected(false);
            forecastAction.setTextColor(activity.getColor(
                    R.color.modern_secondary_text_secondary));
            String description = activity.getString(
                    R.string.forecast_dashboard_description,
                    forecastAction.getText());
            forecastAction.setContentDescription(description);
            ViewCompat.setTooltipText(forecastAction, description);
            return;
        }
        ForecastSnapshot forecast = state.forecast;
        boolean usable = state.error.isEmpty()
                && forecast.isGraphUsable(System.currentTimeMillis());
        if (state.loading && state.updatedAtMs == 0L) {
            forecastAction.setText(R.string.forecast_dashboard_loading);
        } else if (usable) {
            forecastAction.setText(activity.getString(
                    R.string.forecast_dashboard_ready,
                    forecast.horizonMinutes));
        } else if ("learning".equalsIgnoreCase(forecast.status)
                || "cold_start".equalsIgnoreCase(forecast.status)) {
            forecastAction.setText(R.string.forecast_dashboard_learning);
        } else {
            forecastAction.setText(R.string.forecast_dashboard_unavailable);
        }
        forecastAction.setSelected(usable);
        forecastAction.setTextColor(activity.getColor(usable
                ? R.color.modern_secondary_accent
                : R.color.modern_secondary_text_secondary));
        String description = activity.getString(
                R.string.forecast_dashboard_description,
                forecastAction.getText());
        forecastAction.setContentDescription(description);
        ViewCompat.setTooltipText(forecastAction, description);
    }

    private void pushIntakeEventsToGraph(java.util.List<IntakeEvent> events) {
        int size=events.size();
        int[] keys=intakeRepository.assignRenderKeys(events);
        long[] times=new long[size];
        float[] carbs=new float[size];
        float[] insulin=new float[size];
        int[] flags=new int[size];
        for(int index=0;index<size;index++) {
            IntakeEvent event=events.get(index);
            times[index]=event.occurredAtMs;
            carbs[index]=event.carbsGrams;
            insulin[index]=event.insulinUnits;
            flags[index]=(event.hasMeal()?INTAKE_FLAG_MEAL:0)
                    |(event.hasCarbs()?INTAKE_FLAG_CARBS_PRESENT:0)
                    |insulinGraphFlag(event);
        }
        try {
            Natives.setTimelineEvents(keys,times,carbs,insulin,flags);
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

    static int insulinGraphFlag(IntakeEvent event) {
        if(!event.hasInsulin()) return 0;
        if("Tresiba".equalsIgnoreCase(event.insulinName)
                ||"long".equalsIgnoreCase(event.insulinType)) {
            return INTAKE_FLAG_LONG_INSULIN;
        }
        if("NovoRapid".equalsIgnoreCase(event.insulinName)
                ||"rapid".equalsIgnoreCase(event.insulinType)) {
            return INTAKE_FLAG_RAPID_INSULIN;
        }
        return 0;
    }

    private void updateLayout(int width, int height) {
        if (root == null || content == null || body == null || actions == null
                || appBar == null || heroCard == null || graphCard == null
                || heroContent == null || readingColumn == null || heroMeta == null
                || heroMetaSeparator == null || sensorAction == null
                || width <= 0 || height <= 0) {
            return;
        }

        root.setPadding(
                systemInsets.left,
                systemInsets.top,
                systemInsets.right,
                systemInsets.bottom);

        float density = activity.getResources().getDisplayMetrics().density;
        int availableWidth = Math.max(0,
                width - systemInsets.left - systemInsets.right);
        int availableHeight = Math.max(0,
                height - systemInsets.top - systemInsets.bottom);
        int widthDp = Math.round(availableWidth / density);
        int heightDp = Math.round(availableHeight / density);
        boolean railLayout = shouldUseNavigationRail(widthDp, heightDp);
        boolean twoPaneLayout = shouldUseTwoPaneLayout(widthDp, heightDp);
        int horizontalGutter = chartContentGutterDp(
                widthDp, railLayout);

        content.setOrientation(railLayout
                ? LinearLayout.HORIZONTAL
                : LinearLayout.VERTICAL);
        body.setOrientation(twoPaneLayout
                ? LinearLayout.HORIZONTAL
                : LinearLayout.VERTICAL);
        heroContent.setOrientation(LinearLayout.HORIZONTAL);
        heroMeta.setOrientation(twoPaneLayout
                ? LinearLayout.VERTICAL
                : LinearLayout.HORIZONTAL);
        heroMetaSeparator.setVisibility(twoPaneLayout ? View.GONE : View.VISIBLE);
        actions.setOrientation(railLayout
                ? LinearLayout.VERTICAL
                : LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        body.setPadding(dp(horizontalGutter), 0, dp(horizontalGutter),
                dp(railLayout ? 12 : 8));
        appBar.setPadding(dp(horizontalGutter + 4
                        + (railLayout ? NAVIGATION_RAIL_WIDTH_DP : 0)), 0,
                dp(horizontalGutter), 0);
        if (rangeScroller != null) {
            rangeScroller.setFillViewport(true);
        }
        if (rangeTrack != null) {
            rangeTrack.setGravity(Gravity.CENTER);
        }

        int desiredActionsIndex = railLayout ? 0 : content.getChildCount() - 1;
        if (content.indexOfChild(actions) != desiredActionsIndex) {
            content.removeView(actions);
            content.addView(actions, desiredActionsIndex);
        }

        LinearLayout.LayoutParams appBarParams =
                (LinearLayout.LayoutParams) appBar.getLayoutParams();
        appBarParams.height = dp(railLayout ? 52 : 56);
        appBar.setLayoutParams(appBarParams);

        LinearLayout.LayoutParams bodyParams = railLayout
                ? new LinearLayout.LayoutParams(0, MATCH_PARENT, 1.0f)
                : new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1.0f);
        LinearLayout.LayoutParams actionsParams = railLayout
                ? new LinearLayout.LayoutParams(dp(NAVIGATION_RAIL_WIDTH_DP),
                        MATCH_PARENT)
                : new LinearLayout.LayoutParams(MATCH_PARENT, dp(64));
        body.setLayoutParams(bodyParams);
        actions.setLayoutParams(actionsParams);
        actions.setBackgroundResource(railLayout
                ? R.drawable.modern_dashboard_nav_rail_background
                : R.drawable.modern_dashboard_nav_background);
        actions.setPadding(dp(4), railLayout ? dp(6) : 0,
                dp(4), railLayout ? dp(6) : 0);

        for (int navigationId : NAVIGATION_IDS) {
            View navigation = root.findViewById(navigationId);
            if (navigation == null) {
                continue;
            }
            LinearLayout.LayoutParams navigationParams = railLayout
                    ? new LinearLayout.LayoutParams(MATCH_PARENT,
                            dp(NAVIGATION_RAIL_ITEM_HEIGHT_DP))
                    : new LinearLayout.LayoutParams(0, MATCH_PARENT, 1.0f);
            navigation.setLayoutParams(navigationParams);
        }

        int twoPaneHeroWidth = summaryPaneWidthDp(widthDp, horizontalGutter,
                railLayout);
        LinearLayout.LayoutParams heroParams = twoPaneLayout
                ? new LinearLayout.LayoutParams(dp(twoPaneHeroWidth), WRAP_CONTENT)
                : new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        LinearLayout.LayoutParams graphParams = twoPaneLayout
                ? new LinearLayout.LayoutParams(0, MATCH_PARENT, 1.0f)
                : new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1.0f);
        LinearLayout.LayoutParams heroContentParams =
                new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);

        LinearLayout.LayoutParams readingParams =
                new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        LinearLayout.LayoutParams sensorParams =
                new LinearLayout.LayoutParams(WRAP_CONTENT, dp(48));
        LinearLayout.LayoutParams freshnessParams = twoPaneLayout
                ? new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                : new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.0f);
        freshnessParams.topMargin = twoPaneLayout ? dp(5) : 0;

        if (twoPaneLayout) {
            heroParams.setMarginEnd(cardSpacing);
            heroParams.gravity = Gravity.CENTER_VERTICAL;
        } else {
            heroParams.bottomMargin = cardSpacing;
        }
        heroCard.setLayoutParams(heroParams);
        graphCard.setLayoutParams(graphParams);
        heroContent.setLayoutParams(heroContentParams);
        readingColumn.setLayoutParams(readingParams);
        heroFreshness.setLayoutParams(freshnessParams);
        sensorAction.setLayoutParams(sensorParams);
        int expandedChipWidth = railLayout ? 176 : 128;
        targetRange.setMaxWidth(dp(expandedChipWidth));
        targetRange.setVisibility(railLayout ? View.VISIBLE : View.GONE);
        forecastAction.setMaxWidth(dp(railLayout ? 120 : 88));
        int heroHorizontalPadding = twoPaneLayout ? 18 : 16;
        int heroVerticalPadding = twoPaneLayout ? 18 : 12;
        heroCard.setPadding(dp(heroHorizontalPadding),
                dp(heroVerticalPadding), dp(heroHorizontalPadding),
                dp(twoPaneLayout ? 18 : 13));
    }

    static boolean shouldUseNavigationRail(int widthDp, int heightDp) {
        return widthDp >= 600 && heightDp >= 360;
    }

    static boolean isPrimaryNavigationAction(int viewId) {
        for (int navigationId : NAVIGATION_IDS) {
            if (viewId == navigationId) {
                return true;
            }
        }
        return false;
    }

    static boolean shouldUseTwoPaneLayout(int widthDp, int heightDp) {
        return shouldUseNavigationRail(widthDp, heightDp)
                && widthDp >= 700
                && widthDp > heightDp;
    }

    /**
     * Medical timelines benefit from more horizontal context than text pages.
     * Keep the plot comfortably inset while allowing up to 1240dp of data.
     */
    static int chartContentGutterDp(int widthDp, boolean railLayout) {
        int usableWidth = Math.max(0, widthDp
                - (railLayout ? NAVIGATION_RAIL_WIDTH_DP : 0));
        int baseGutter = usableWidth >= 840 ? 20 : 12;
        int excess = Math.max(0,
                usableWidth - (MAX_BODY_CONTENT_WIDTH_DP + baseGutter * 2));
        return baseGutter + excess / 2;
    }

    static int summaryPaneWidthDp(int widthDp, int horizontalGutter,
            boolean railLayout) {
        int usableWidth = Math.max(0, widthDp
                - (railLayout ? NAVIGATION_RAIL_WIDTH_DP : 0)
                - horizontalGutter * 2);
        return Math.max(232, Math.min(320,
                Math.round(usableWidth * 0.28f)));
    }

    private int dp(int value) {
        return Math.round(value
                * activity.getResources().getDisplayMetrics().density);
    }

    private void openMenu() {
        if (!Menus.on) {
            Menus.show(activity);
        }
    }

    private void showNow() {
        Natives.settonow();
        activity.requestRender();
        if (root != null) {
            root.announceForAccessibility(
                    activity.getString(R.string.now));
        }
    }

    private void showRecords() {
        Natives.makenumbers();
        curve.getnumcontrol(activity);
        activity.requestRender();
    }

    private void showStatistics() {
        if (Natives.makepercentages()) {
            activity.requestRender();
            Stats.mkstats(activity);
        } else {
            setSelectedDestination(R.id.modern_dashboard_overview);
            Toast.makeText(activity, R.string.dashboard_no_insights_yet,
                    Toast.LENGTH_SHORT).show();
        }
    }
}
