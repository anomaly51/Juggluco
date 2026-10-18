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

import android.content.res.ColorStateList;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Phone presentation shell around the native glucose graph.
 *
 * <p>The shell owns only layout, navigation and presentation state. Glucose storage,
 * graph rendering, gestures, treatment records and sensor management continue to
 * use the existing Java/JNI entry points.</p>
 */
final class DashboardChrome {
    private static final String LOG_ID = "DashboardChrome";
    private static final long DATA_REFRESH_INTERVAL_MS = 60_000L;
    private static final long PRESENTATION_TICK_MS = 1_000L;
    private static final int CADENCE_READING_COUNT = 13;
    private static final long INTAKE_HISTORY_WINDOW_MS = 30L * 24L * 60L * 60L * 1000L;
    static final int RANGE_UNKNOWN = 0;
    static final int RANGE_IN_TARGET = 1;
    static final int RANGE_LOW = 2;
    static final int RANGE_HIGH = 3;
    private static final int INTAKE_FLAG_MEAL = 1;
    private static final int INTAKE_FLAG_CARBS_PRESENT = 1 << 1;
    private static final int INTAKE_FLAG_RAPID_INSULIN = 1 << 2;
    private static final int INTAKE_FLAG_LONG_INSULIN = 1 << 3;
    private static final int NAVIGATION_RAIL_WIDTH_DP = 72;
    static final int NAVIGATION_RAIL_ITEM_HEIGHT_DP = 68;
    private static final int NAVIGATION_BAR_HEIGHT_DP = 64;
    private static final int MAX_BODY_CONTENT_WIDTH_DP = 1320;
    static final int DESTINATION_HOME = 0;
    static final int DESTINATION_MORE = 1;
    static final float COMPACT_ADD_WEIGHT = 1.65f;
    static final float COMPACT_MORE_WEIGHT = 1.0f;
    private static final int[] RANGE_IDS = {
            R.id.modern_dashboard_range_3h,
            R.id.modern_dashboard_range_6h,
            R.id.modern_dashboard_range_8h,
            R.id.modern_dashboard_range_12h,
            R.id.modern_dashboard_range_24h
    };

    private static final int[] RANGE_HOURS = {3, 6, 8, 12, 24};

    private static final int[] NAVIGATION_IDS = {
            R.id.modern_dashboard_add_intake,
            R.id.modern_dashboard_menu
    };

    private final MainActivity activity;
    private final GlucoseCurve curve;
    private final IntakeRepository intakeRepository;
    private final ForecastRepository forecastRepository;

    private FrameLayout root;
    private FrameLayout graphHost;
    private ModernGlucoseChartView modernChartView;
    private LinearLayout content;
    private LinearLayout body;
    private LinearLayout actions;
    private View heroCard;
    private LinearLayout heroContent;
    private LinearLayout readingColumn;
    private LinearLayout heroMeta;
    private View graphCard;
    private View graphHeader;
    private View graphControls;
    private View nowAction;
    private HorizontalScrollView rangeScroller;
    private LinearLayout rangeTrack;
    private LinearLayout heroReading;
    private LinearLayout readingValueRow;
    private TextView heroValue;
    private TextView heroTrend;
    private TextView heroUnit;
    private TextView heroFreshness;
    private TextView heroRangeState;
    private TextView targetRange;
    private Button updateBanner;

    private Insets systemInsets = Insets.NONE;
    private boolean attached;
    private boolean foreground;
    private boolean obscured;
    private boolean modernRendererActive;
    private boolean graphPreviewEnabled;
    private int selectedDestination = DESTINATION_HOME;
    private int selectedHours = -1;
    private long lastIntakeRefreshMs;
    private long latestReadingAtMs;
    private long averageReadingIntervalMs = -1L;
    private String latestReadingValue = "";
    private String latestReadingTrendDescription = "";
    private String latestReadingSensorId = "";
    private int latestReadingSensorGeneration;
    private boolean displayedReadingStale;
    private int currentReadingRange = RANGE_UNKNOWN;
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
            root.postDelayed(this, DATA_REFRESH_INTERVAL_MS);
        }
    };

    private final Runnable presentationClock = new Runnable() {
        @Override
        public void run() {
            if (!attached || !foreground || root == null) {
                return;
            }
            refreshLivePresentation();
            schedulePresentationTick();
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
            // ModernGlucoseChartView owns its repository subscription. This
            // listener only keeps the native fallback projection warm.
            requestLegacyGraphRenderIfActive();
        };
        forecastListener = state -> requestLegacyGraphRenderIfActive();
        backendConfigurationListener =
                this::handleBackendConfigurationChanged;
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
        updateBanner = ClinicalUi.button(activity, "", ClinicalUi.ButtonRole.SECONDARY);
        updateBanner.setTag("app_update_banner");
        updateBanner.setVisibility(View.GONE);
        updateBanner.setOnClickListener(v -> AppUpdates.showSettings(activity));
        body = root.findViewById(R.id.modern_dashboard_body);
        body.addView(updateBanner, 0, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        actions = root.findViewById(R.id.modern_dashboard_actions);
        heroCard = root.findViewById(R.id.modern_dashboard_hero);
        heroContent = root.findViewById(R.id.modern_dashboard_hero_content);
        readingColumn = root.findViewById(R.id.modern_dashboard_reading_column);
        heroMeta = root.findViewById(R.id.modern_dashboard_meta);
        graphCard = root.findViewById(R.id.modern_dashboard_graph_card);
        // The plot already owns the only strong frame on the dashboard. Keeping
        // this container planar prevents nested rounded cards from competing
        // with the medical data.
        graphCard.setClipToOutline(false);
        graphHeader = root.findViewById(R.id.modern_dashboard_graph_header);
        graphControls = root.findViewById(R.id.modern_dashboard_graph_controls);
        nowAction = root.findViewById(R.id.modern_dashboard_now);
        rangeScroller = root.findViewById(
                R.id.modern_dashboard_range_scroller);
        rangeTrack = root.findViewById(R.id.modern_dashboard_ranges);
        heroReading = root.findViewById(R.id.modern_dashboard_hero_reading);
        readingValueRow = root.findViewById(R.id.modern_dashboard_value_row);
        heroValue = root.findViewById(R.id.modern_dashboard_value);
        heroTrend = root.findViewById(R.id.modern_dashboard_trend);
        heroUnit = root.findViewById(R.id.modern_dashboard_unit);
        heroFreshness = root.findViewById(R.id.modern_dashboard_freshness);
        heroRangeState = root.findViewById(R.id.modern_dashboard_range_state);
        targetRange = root.findViewById(R.id.modern_dashboard_target_range);

        ViewParent currentParent = curve.getParent();
        if (currentParent instanceof ViewGroup) {
            ((ViewGroup) currentParent).removeView(curve);
        }
        graphHost.addView(curve, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        curve.setContentDescription(activity.getString(
                R.string.dashboard_graph_hint));
        try {
            modernChartView = new ModernGlucoseChartView(activity);
            modernChartView.setInteractionEnabled(false);
            graphHost.addView(modernChartView,
                    new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
            setModernRendererActive(true);
        } catch (RuntimeException | LinkageError error) {
            Log.stack(LOG_ID, "Modern chart unavailable", error);
            modernChartView = null;
            setModernRendererActive(false);
        }
        installAccessibilityDelegates();

        refreshTheme();
        refreshFeatureControls();
        updateDestinationSelection();
        updateRangeSelection();
        refreshTargetRange();
        showLoadingState();
        return root;
    }

    /**
     * Rebinds the semantic amber-terminal palette after an in-place uiMode change.
     *
     * <p>MainActivity handles configuration changes without recreating itself, so
     * drawables and ColorStateLists already attached to the hierarchy would
     * otherwise retain stale stateful resource instances.</p>
     */
    void refreshTheme() {
        if (root == null) {
            return;
        }

        root.setBackgroundColor(activity.getColor(R.color.lifeprompt_background));
        if (heroCard != null) {
            heroCard.setBackgroundResource(
                    R.drawable.lifeprompt_dashboard_hero_strip);
        }
        if (graphCard != null) {
            graphCard.setBackgroundResource(
                    R.drawable.lifeprompt_dashboard_graph_card);
        }
        if (rangeTrack != null) {
            rangeTrack.setBackgroundResource(
                    R.drawable.lifeprompt_dashboard_segmented_track);
        }
        int muted = activity.getColor(R.color.lifeprompt_on_surface_variant);
        if (heroUnit != null) heroUnit.setTextColor(muted);
        if (targetRange != null) {
            targetRange.setTextColor(muted);
        }
        if (heroFreshness != null) {
            heroFreshness.setTextColor(colorStateList(
                    R.color.modern_dashboard_freshness_text));
        }
        Button nowAction = root.findViewById(R.id.modern_dashboard_now);
        if (nowAction != null) {
            nowAction.setBackgroundResource(
                    R.drawable.lifeprompt_dashboard_tonal_action);
            ColorStateList tonalActionText = colorStateList(
                    R.color.modern_dashboard_tonal_action_text);
            nowAction.setTextColor(tonalActionText);
            nowAction.setCompoundDrawableTintList(tonalActionText);
        }

        ColorStateList rangeText = colorStateList(
                R.color.modern_dashboard_chip_text);
        for (int rangeId : RANGE_IDS) {
            Button range = root.findViewById(rangeId);
            if (range == null) continue;
            range.setBackgroundResource(
                    R.drawable.lifeprompt_dashboard_segmented_item);
            range.setTextColor(rangeText);
        }

        ColorStateList navigationText = colorStateList(
                R.color.modern_dashboard_nav_text);
        for (int navigationId : NAVIGATION_IDS) {
            Button navigation = root.findViewById(navigationId);
            if (navigation == null) continue;
            boolean primaryAction = navigationId
                    == R.id.modern_dashboard_add_intake;
            navigation.setBackgroundResource(primaryAction
                    ? R.drawable.lifeprompt_dashboard_primary_action
                    : R.drawable.lifeprompt_dashboard_nav_item);
            ColorStateList tint = primaryAction
                    ? colorStateList(
                            R.color.modern_dashboard_primary_action_text)
                    : navigationText;
            navigation.setTextColor(tint);
            navigation.setCompoundDrawableTintList(tint);
        }

        applyReadingFreshnessState(displayedReadingStale);
        updateRangeSelection();
        updateDestinationSelection();
        refreshModernChartTheme();
        root.invalidate();
    }

    private ColorStateList colorStateList(int resourceId) {
        ColorStateList colors = AppCompatResources.getColorStateList(
                activity, resourceId);
        return colors != null
                ? colors
                : ColorStateList.valueOf(activity.getColor(resourceId));
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
        updateGraphInteraction();
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
        configureAction(R.id.modern_dashboard_add_intake,
                R.string.dashboard_add_description,
                view -> {
                    activity.showIntakeComposer();
                });
        configureAction(R.id.modern_dashboard_menu,
                R.string.dashboard_more_description, view -> {
                    setSelectedDestination(DESTINATION_MORE);
                    openMenu();
                });
        configureAction(R.id.modern_dashboard_now,
                R.string.now, view -> showNow());

        for (int index = 0; index < RANGE_IDS.length; index++) {
            final int hours = RANGE_HOURS[index];
            Button range = root.findViewById(RANGE_IDS[index]);
            range.setOnClickListener(view -> selectRange(hours));
            ViewCompat.setTooltipText(range,
                    activity.getString(R.string.dashboard_show_hours, hours));
        }
    }

    void refreshFeatureControls() {
        if (root != null) FeatureControls.applyToDashboard(root);
    }

    private void configureAction(int viewId, int descriptionId,
            View.OnClickListener listener) {
        View action = root.findViewById(viewId);
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
                R.id.modern_dashboard_add_intake,
                R.id.modern_dashboard_menu,
                R.id.modern_dashboard_now
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
        if (selectedHours == hours) {
            return;
        }
        selectedHours = hours;
        updateRangeSelection();
        Natives.setgraphhours(hours);
        refreshData();
        requestLegacyGraphRenderIfActive();
        if (nowAction != null) {
            nowAction.setVisibility(View.GONE);
        }
        if (root != null) {
            View selected = rangeView(hours);
            if (selected != null) {
                selected.performHapticFeedback(
                        HapticFeedbackConstants.CLOCK_TICK);
                centerRangeOption(selected);
            }
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
            range.setContentDescription(activity.getString(selected
                    ? R.string.dashboard_hours_selected
                    : R.string.dashboard_show_hours, RANGE_HOURS[index]));
        }
    }

    private View rangeView(int hours) {
        if (root == null) return null;
        for (int index = 0; index < RANGE_HOURS.length; index++) {
            if (RANGE_HOURS[index] == hours) {
                return root.findViewById(RANGE_IDS[index]);
            }
        }
        return null;
    }

    private void centerRangeOption(View option) {
        if (rangeScroller == null || option == null
                || rangeScroller.getWidth() <= 0) return;
        int destination = option.getLeft() -
                (rangeScroller.getWidth() - option.getWidth()) / 2;
        rangeScroller.smoothScrollTo(Math.max(0, destination), 0);
    }

    private void setSelectedDestination(int destinationId) {
        selectedDestination = destinationId;
        updateDestinationSelection();
        updateGraphInteraction();
    }

    private void updateDestinationSelection() {
        if (root == null) {
            return;
        }
        View more = root.findViewById(R.id.modern_dashboard_menu);
        if (more != null) {
            boolean selected = selectedDestination == DESTINATION_MORE;
            more.setSelected(selected);
            more.setActivated(selected);
        }
        refreshGraphAccessibilityDescription();
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
        refreshTargetRange();
        refreshIntakeEvents(false);
        forecastRepository.refreshFromDashboard();
        refreshModernChartData();

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

        showReadingState(glucose);
    }

    private void showReadingState(strGlucose glucose) {
        setGraphPreviewEnabled(false);
        long readingAtMs = glucose.time > Long.MAX_VALUE / 1_000L
                ? Long.MAX_VALUE : glucose.time * 1_000L;
        boolean newSample = readingAtMs != latestReadingAtMs
                || !Objects.equals(glucose.sensorid, latestReadingSensorId)
                || glucose.sensorgen2 != latestReadingSensorGeneration;
        latestReadingAtMs = readingAtMs;
        latestReadingValue = glucose.value;
        latestReadingSensorId = glucose.sensorid;
        latestReadingSensorGeneration = glucose.sensorgen2;
        if (newSample || averageReadingIntervalMs <= 0L) {
            refreshReadingCadence();
        }
        heroValue.setText(glucose.value);
        heroUnit.setText(readUnitLabel());
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

        latestReadingTrendDescription = trendArrow.isEmpty()
                ? activity.getString(R.string.dashboard_trend_unavailable)
                : heroTrend.getContentDescription().toString();
        updateTimingPresentation(System.currentTimeMillis(), newSample);
    }

    private void refreshLivePresentation() {
        if (updateBanner != null) {
            String message = AppUpdatePanel.banner(activity);
            if (!updateBanner.getText().toString().equals(message)) updateBanner.setText(message);
            updateBanner.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (root == null || graphPreviewEnabled) return;
        // Real readings already invalidate MainActivity immediately. The one-second
        // clock only advances visible timing; the slower refresh remains a fallback.
        updateTimingPresentation(System.currentTimeMillis(), false);
    }

    private void refreshReadingCadence() {
        try {
            averageReadingIntervalMs = calculateAverageReadingIntervalMs(
                    Natives.recentCurrentSensorReadingTimes(
                            CADENCE_READING_COUNT),
                    Notify.glucosetimeoutSEC * 1_000L);
        } catch (UnsatisfiedLinkError error) {
            averageReadingIntervalMs = -1L;
        }
    }

    private void updateTimingPresentation(long nowMs, boolean forceAccessibility) {
        if (latestReadingAtMs <= 0L || heroFreshness == null) return;
        long ageMs = Math.max(0L, nowMs - latestReadingAtMs);
        boolean stale = isReadingStale(ageMs / 1_000L);
        heroFreshness.setText(timingText(nowMs, ageMs, averageReadingIntervalMs));
        heroFreshness.setActivated(stale);
        heroFreshness.setVisibility(View.VISIBLE);
        heroReading.setActivated(stale);

        boolean staleChanged = stale != displayedReadingStale;
        if (forceAccessibility || staleChanged) {
            applyReadingFreshnessState(stale);
        }
        boolean refreshAccessibility = forceAccessibility || staleChanged;
        if (!refreshAccessibility) return;
        heroReading.setContentDescription(
                currentReadingAccessibilityDescription(nowMs));
        graphSummaryDescription = heroReading.getContentDescription();
        refreshGraphAccessibilityDescription();
    }

    private void installAccessibilityDelegates() {
        heroReading.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host,
                    AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                CharSequence liveDescription =
                        currentReadingAccessibilityDescription(
                                System.currentTimeMillis());
                if (liveDescription != null) {
                    info.setContentDescription(liveDescription);
                }
            }
        });
        curve.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host,
                    AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                CharSequence liveDescription =
                        currentGraphAccessibilityDescription(
                                System.currentTimeMillis());
                if (liveDescription != null) {
                    info.setContentDescription(liveDescription);
                }
            }
        });
        if (modernChartView != null) {
            modernChartView.setAccessibilityDelegate(
                    new View.AccessibilityDelegate() {
                        @Override
                        public void onInitializeAccessibilityNodeInfo(View host,
                                AccessibilityNodeInfo info) {
                            super.onInitializeAccessibilityNodeInfo(host, info);
                            CharSequence liveDescription =
                                    currentGraphAccessibilityDescription(
                                            System.currentTimeMillis());
                            if (liveDescription != null) {
                                info.setContentDescription(liveDescription);
                            }
                        }
                    });
        }
    }

    private CharSequence currentReadingAccessibilityDescription(long nowMs) {
        if (latestReadingAtMs <= 0L || heroUnit == null) return null;
        long ageMs = Math.max(0L, nowMs - latestReadingAtMs);
        boolean stale = isReadingStale(ageMs / 1_000L);
        String actualRange = activity.getString(
                readingRangeLabel(currentReadingRange));
        CharSequence rangeDescription = stale
                ? activity.getString(R.string.dashboard_state_stale_description,
                        actualRange)
                : actualRange;
        return activity.getString(R.string.dashboard_reading_description,
                latestReadingValue,
                heroUnit.getText(),
                latestReadingTrendDescription,
                rangeDescription,
                timingText(nowMs, ageMs, averageReadingIntervalMs));
    }

    private CharSequence currentGraphAccessibilityDescription(long nowMs) {
        CharSequence readingDescription =
                currentReadingAccessibilityDescription(nowMs);
        if (readingDescription == null) return null;
        StringBuilder description = new StringBuilder();
        appendAccessibilitySentence(description, readingDescription);
        if (targetRange != null && targetRange.getText().length() > 0) {
            appendAccessibilitySentence(description, targetRange.getText());
        }
        appendAccessibilitySentence(description,
                activity.getString(R.string.dashboard_graph_hint));
        return description;
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
            } else {
                forecastRepository.restoreGraphProjection();
            }
            requestLegacyGraphRenderIfActive();
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
        setReadingColor(readingColor(RANGE_UNKNOWN));
        clearReadingTiming();
        heroValue.setText(R.string.dashboard_empty_value);
        heroUnit.setText("");
        heroTrend.setText("");
        heroTrend.setVisibility(View.GONE);
        heroTrend.setContentDescription(null);
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
        clearReadingTiming();
        setReadingColor(readingColor(RANGE_IN_TARGET));
        updateReadingRangeState(RANGE_IN_TARGET);
        heroValue.setText(value);
        heroUnit.setText(unit);
        heroTrend.setText("\u2192");
        heroTrend.setVisibility(View.VISIBLE);
        heroTrend.setContentDescription(
                activity.getString(R.string.dashboard_trend_steady));
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
        setReadingColor(readingColor(RANGE_UNKNOWN));
        clearReadingTiming();
        updateReadingRangeState(RANGE_UNKNOWN);
        heroValue.setText(R.string.dashboard_empty_value);
        heroUnit.setText("");
        heroTrend.setText("");
        heroTrend.setVisibility(View.GONE);
        heroTrend.setContentDescription(null);
        heroFreshness.setText("");
        heroFreshness.setActivated(false);
        heroFreshness.setVisibility(View.GONE);
        heroReading.setActivated(false);
        heroReading.setContentDescription(
                activity.getString(R.string.dashboard_loading_description));
        graphSummaryDescription = heroReading.getContentDescription();
        refreshGraphAccessibilityDescription();
    }

    private void clearReadingTiming() {
        latestReadingAtMs = 0L;
        averageReadingIntervalMs = -1L;
        latestReadingValue = "";
        latestReadingTrendDescription = "";
        latestReadingSensorId = "";
        latestReadingSensorGeneration = 0;
        displayedReadingStale = false;
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
            setReadingColor(readingColor(RANGE_UNKNOWN));
            return RANGE_UNKNOWN;
        }
        int range = classifyReadingRange(value, low, high);
        setReadingColor(readingColor(range));
        return range;
    }

    private void updateReadingRangeState(int range) {
        if (heroRangeState == null) {
            return;
        }
        currentReadingRange = range;
        heroRangeState.setText(readingRangeLabel(range));
        heroRangeState.setTextColor(readingColor(range));
    }

    private int readingRangeLabel(int range) {
        switch (range) {
            case RANGE_IN_TARGET:
                return R.string.dashboard_state_in_target;
            case RANGE_LOW:
                return R.string.dashboard_state_low;
            case RANGE_HIGH:
                return R.string.dashboard_state_high;
            default:
                return R.string.dashboard_state_unknown;
        }
    }

    private void applyReadingFreshnessState(boolean stale) {
        displayedReadingStale = stale;
        if (stale) {
            int neutral = readingColor(RANGE_UNKNOWN);
            setReadingColor(neutral);
            heroRangeState.setText(R.string.dashboard_state_stale);
            heroRangeState.setTextColor(neutral);
            return;
        }
        setReadingColor(readingColor(currentReadingRange));
        heroRangeState.setText(readingRangeLabel(currentReadingRange));
        heroRangeState.setTextColor(readingColor(currentReadingRange));
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
        if (modernChartView != null) {
            modernChartView.setContentSummary(description);
        }
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

    private int readingColor(int range) {
        final int resource;
        switch (range) {
            case RANGE_IN_TARGET:
                resource = R.color.lifeprompt_glucose_in_target;
                break;
            case RANGE_LOW:
                resource = R.color.lifeprompt_glucose_low;
                break;
            case RANGE_HIGH:
                resource = R.color.lifeprompt_glucose_high;
                break;
            default:
                resource = R.color.lifeprompt_glucose_neutral;
                break;
        }
        return activity.getColor(resource);
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

    private CharSequence timingText(long nowMs, long ageMs, long averageIntervalMs) {
        String age = compactDuration(Math.max(0L, ageMs) / 1_000L);
        if (averageIntervalMs <= 0L || latestReadingAtMs <= 0L) {
            return activity.getString(R.string.dashboard_timing_unknown, age);
        }
        String average = averageDuration(averageIntervalMs);
        long expectedAtMs = latestReadingAtMs > Long.MAX_VALUE - averageIntervalMs
                ? Long.MAX_VALUE : latestReadingAtMs + averageIntervalMs;
        final String next;
        if (expectedAtMs >= nowMs) {
            long remainingMs = expectedAtMs - nowMs;
            long remainingSeconds = remainingMs / 1_000L
                    + (remainingMs % 1_000L == 0L ? 0L : 1L);
            next = remainingSeconds == 0L
                    ? activity.getString(R.string.dashboard_timing_due_now)
                    : activity.getString(R.string.dashboard_timing_next,
                            compactDuration(remainingSeconds));
        } else {
            long overdueSeconds = Math.max(1L,
                    (nowMs - expectedAtMs) / 1_000L);
            next = activity.getString(R.string.dashboard_timing_overdue,
                    compactDuration(overdueSeconds));
        }
        return activity.getString(R.string.dashboard_timing_summary,
                age, average, next);
    }

    private String averageDuration(long intervalMs) {
        long seconds = Math.max(1L, Math.round(intervalMs / 1_000.0));
        if (seconds < 90L) {
            return activity.getString(R.string.dashboard_seconds_short, seconds);
        }
        long minutes = Math.max(1L, Math.round(seconds / 60.0));
        return activity.getString(R.string.dashboard_minutes_short, minutes);
    }

    private String compactDuration(long seconds) {
        long safeSeconds = Math.max(0L, seconds);
        if (safeSeconds < 60L) {
            return activity.getString(R.string.dashboard_seconds_short,
                    safeSeconds);
        }
        return formatClockDuration(safeSeconds);
    }

    static String formatClockDuration(long seconds) {
        long safeSeconds = Math.max(0L, seconds);
        long hours = safeSeconds / 3_600L;
        long minutes = (safeSeconds % 3_600L) / 60L;
        long remainder = safeSeconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%d:%02d:%02d",
                    hours, minutes, remainder);
        }
        return String.format(Locale.ROOT, "%d:%02d", minutes, remainder);
    }

    static long calculateAverageReadingIntervalMs(long[] timestamps,
            long outageThresholdMs) {
        if (timestamps == null || timestamps.length < 2
                || outageThresholdMs <= 0L) return -1L;
        long previous = -1L;
        long sum = 0L;
        int count = 0;
        for (long timestamp : timestamps) {
            if (timestamp <= 0L || timestamp <= previous) continue;
            if (previous > 0L) {
                long gap = timestamp - previous;
                if (gap > 0L && gap < outageThresholdMs) {
                    if (Long.MAX_VALUE - sum < gap) return -1L;
                    sum += gap;
                    count++;
                }
            }
            previous = timestamp;
        }
        return count == 0 ? -1L : Math.round(sum / (double) count);
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

    /** Keeps the live canvas visible but inert behind a modal or side drawer. */
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
                && selectedDestination == DESTINATION_HOME;
    }

    static boolean isGraphInteractionState(boolean attached, boolean foreground,
            boolean obscured, int selectedDestination) {
        return foreground && isGraphGestureState(attached, obscured,
                selectedDestination);
    }

    private View activeDashboardGraphView() {
        if (graphHost == null) {
            return null;
        }
        if (modernRendererActive && modernChartView != null
                && modernChartView.getParent() == graphHost
                && modernChartView.getVisibility() == View.VISIBLE) {
            return modernChartView;
        }
        if (curve.getParent() == graphHost
                && curve.getVisibility() == View.VISIBLE) {
            return curve;
        }
        return null;
    }

    private boolean hasVisibleOverlay() {
        for (View overlay : overlays) {
            if (overlay.isAttachedToWindow()
                    && overlay.getVisibility() == View.VISIBLE
                    && overlay.isShown()) {
                return true;
            }
        }
        return false;
    }

    private void updateGraphInteraction() {
        if (modernChartView == null) {
            return;
        }
        boolean enabled = modernChartView == activeDashboardGraphView()
                && isGraphInteractionState(attached, foreground, obscured,
                        selectedDestination)
                && !hasVisibleOverlay();
        modernChartView.setInteractionEnabled(enabled);
    }

    /** True only while the legacy graph is the active, unobscured fallback. */
    boolean acceptsGraphGestures() {
        View activeGraph = activeDashboardGraphView();
        if (!isGraphInteractionState(attached, foreground, obscured,
                selectedDestination) || root == null || graphHost == null
                || activeGraph != curve || !curve.isShown()) {
            return false;
        }
        return !hasVisibleOverlay();
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
        if (!attached || root == null) {
            return;
        }
        int visibleOverlayCount = 0;
        for (View overlay : overlays) {
            if (overlay.isAttachedToWindow()
                    && overlay.getVisibility() == View.VISIBLE
                    && overlay.isShown()) {
                visibleOverlayCount++;
            }
        }
        boolean nowObscured = visibleOverlayCount > 0;
        boolean obscuredChanged = obscured != nowObscured;
        obscured = nowObscured;
        updateGraphInteraction();

        // Sibling destinations cover this surface. Keep its geometry stable so
        // the translucent side drawer opens over an unchanged live graph, while
        // removing the background from the accessibility and gesture planes.
        root.setImportantForAccessibility(obscured
                ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        if (obscuredChanged && !obscured) {
            refreshFeatureControls();
            setSelectedDestination(DESTINATION_HOME);
            activity.syncDashboardSystemBars();
        }

        if (!obscured) {
            root.invalidate();
        }
    }

    void refreshLayout() {
        if (!attached || root == null) {
            return;
        }
        refreshTheme();
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
        refreshFeatureControls();
        updateGraphInteraction();
        forecastRepository.onForeground();
        root.removeCallbacks(refreshClock);
        root.postDelayed(refreshClock, DATA_REFRESH_INTERVAL_MS);
        root.removeCallbacks(presentationClock);
        refreshLivePresentation();
        schedulePresentationTick();
    }

    private void schedulePresentationTick() {
        if (!attached || !foreground || root == null) return;
        root.removeCallbacks(presentationClock);
        long remainder = Math.floorMod(System.currentTimeMillis(),
                PRESENTATION_TICK_MS);
        long delay = remainder == 0L
                ? PRESENTATION_TICK_MS
                : PRESENTATION_TICK_MS - remainder;
        root.postDelayed(presentationClock, delay);
    }

    /** Stops polling and invalidates callbacks as soon as the Activity is paused. */
    void onBackground() {
        if (!foreground || root == null) {
            return;
        }
        foreground = false;
        updateGraphInteraction();
        forecastRepository.onBackground();
        root.removeCallbacks(refreshClock);
        root.removeCallbacks(presentationClock);
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
            Natives.setGraphEvents(new int[0],new long[0],
                    new float[0],new float[0],new int[0]);
        } catch (UnsatisfiedLinkError ignored) {
        }
        ForecastRepository.clearNativeForecast();
        attached = false;
        foreground = false;
        disposeModernChart();
        root.removeCallbacks(refreshClock);
        root.removeCallbacks(presentationClock);

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

    private void refreshModernChartData() {
        if (!modernRendererActive || modernChartView == null) {
            return;
        }
        try {
            modernChartView.refreshData();
        } catch (RuntimeException | LinkageError error) {
            activateLegacyGraphFallback(error);
        }
    }

    private void refreshModernChartTheme() {
        if (!modernRendererActive || modernChartView == null) {
            return;
        }
        try {
            modernChartView.refreshTheme();
        } catch (RuntimeException | LinkageError error) {
            activateLegacyGraphFallback(error);
        }
    }

    /**
     * Keeps the GL surface laid out so its native viewport geometry remains a
     * valid compatibility boundary, but only one renderer is interactive and
     * exposed to accessibility at a time.
     */
    private void setModernRendererActive(boolean active) {
        boolean useModern = active && modernChartView != null
                && graphHost != null
                && modernChartView.getParent() == graphHost;
        modernRendererActive = useModern;
        curve.setVisibility(View.VISIBLE);
        curve.setEnabled(!useModern);
        curve.setImportantForAccessibility(useModern
                ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                : View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        if (modernChartView != null) {
            modernChartView.setVisibility(useModern ? View.VISIBLE : View.GONE);
            modernChartView.setImportantForAccessibility(useModern
                    ? View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            if (!useModern) {
                modernChartView.setInteractionEnabled(false);
            }
        }
        updateGraphInteraction();
    }

    boolean isLegacyGraphRendererActive() {
        return !modernRendererActive;
    }

    private void requestLegacyGraphRenderIfActive() {
        if (isLegacyGraphRendererActive() && curve.getParent() == graphHost) {
            curve.requestRender();
        }
    }

    private void activateLegacyGraphFallback(Throwable error) {
        if (!modernRendererActive && modernChartView == null) {
            return;
        }
        Log.stack(LOG_ID, "Modern chart failed; using legacy graph", error);
        setModernRendererActive(false);
        disposeModernChart();
        requestLegacyGraphRenderIfActive();
        refreshGraphAccessibilityDescription();
    }

    private void disposeModernChart() {
        if (modernChartView == null) {
            modernRendererActive = false;
            return;
        }
        modernRendererActive = false;
        modernChartView.setInteractionEnabled(false);
        modernChartView.dispose();
        ViewParent parent = modernChartView.getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(modernChartView);
        }
        modernChartView = null;
        curve.setEnabled(true);
        curve.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_YES);
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

    private void handleBackendConfigurationChanged() {
        lastIntakeRefreshMs = 0L;
        if (attached) refreshIntakeEvents(true);
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
            Natives.setGraphEvents(keys,times,carbs,insulin,flags);
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
                || heroCard == null || graphCard == null
                || graphHeader == null || heroContent == null
                || readingColumn == null || heroReading == null
                || readingValueRow == null || heroMeta == null
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
        boolean stackedHeader = usesStackedHeader(
                activity.getResources().getConfiguration().fontScale, widthDp);
        int horizontalGutter = chartContentGutterDp(
                widthDp, railLayout);

        content.setOrientation(railLayout
                ? LinearLayout.HORIZONTAL
                : LinearLayout.VERTICAL);
        body.setOrientation(LinearLayout.VERTICAL);
        heroContent.setOrientation(LinearLayout.HORIZONTAL);
        // Keep the value, trend and unit as one scannable expression even at
        // larger font scales; only the secondary metadata is allowed to stack.
        heroReading.setOrientation(LinearLayout.HORIZONTAL);
        heroReading.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        heroMeta.setOrientation(stackedHeader
                ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        actions.setOrientation(railLayout
                ? LinearLayout.VERTICAL
                : LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        body.setPadding(dp(horizontalGutter), dp(railLayout ? 12 : 6),
                dp(horizontalGutter), dp(railLayout ? 12 : 0));
        if (rangeScroller != null) {
            rangeScroller.setFillViewport(true);
            LinearLayout.LayoutParams rangeParams = new LinearLayout.LayoutParams(
                    railLayout ? dp(400) : MATCH_PARENT, dp(48));
            rangeParams.gravity = Gravity.END;
            rangeScroller.setLayoutParams(rangeParams);
        }
        if (rangeTrack != null) {
            rangeTrack.setGravity(Gravity.CENTER);
        }

        int desiredActionsIndex = railLayout ? 0 : content.getChildCount() - 1;
        if (content.indexOfChild(actions) != desiredActionsIndex) {
            content.removeView(actions);
            content.addView(actions, desiredActionsIndex);
        }

        LinearLayout.LayoutParams bodyParams = railLayout
                ? new LinearLayout.LayoutParams(0, MATCH_PARENT, 1.0f)
                : new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1.0f);
        LinearLayout.LayoutParams actionsParams = railLayout
                ? new LinearLayout.LayoutParams(dp(NAVIGATION_RAIL_WIDTH_DP),
                        MATCH_PARENT)
                : new LinearLayout.LayoutParams(MATCH_PARENT,
                        dp(NAVIGATION_BAR_HEIGHT_DP));
        if (railLayout) {
            actionsParams.setMargins(0, 0, 0, 0);
        } else {
            actionsParams.setMargins(0, 0, 0, 0);
        }
        body.setLayoutParams(bodyParams);
        actions.setLayoutParams(actionsParams);
        actions.setBackgroundResource(railLayout
                ? R.drawable.lifeprompt_dashboard_nav_rail_background
                : R.drawable.lifeprompt_dashboard_nav_background);
        // Let the selected destination indicator share the dock hairline instead
        // of rendering as a second parallel rule four dp below it.
        actions.setPadding(0, 0, 0, 0);

        for (int navigationId : NAVIGATION_IDS) {
            Button navigation = root.findViewById(navigationId);
            if (navigation == null) {
                continue;
            }
            LinearLayout.LayoutParams navigationParams = railLayout
                    ? new LinearLayout.LayoutParams(MATCH_PARENT,
                            dp(NAVIGATION_RAIL_ITEM_HEIGHT_DP))
                    : new LinearLayout.LayoutParams(0, MATCH_PARENT,
                            compactNavigationWeight(navigationId));
            if (navigationId == R.id.modern_dashboard_add_intake) {
                int horizontalMargin = dp(railLayout ? 6 : 8);
                int verticalMargin = dp(railLayout ? 8 : 6);
                navigationParams.setMargins(horizontalMargin, verticalMargin,
                        horizontalMargin, verticalMargin);
            }
            navigation.setLayoutParams(navigationParams);
            int icon = navigationIcon(navigationId);
            navigation.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    railLayout ? 0 : icon,
                    railLayout ? icon : 0,
                    0,
                    0);
            navigation.setCompoundDrawablePadding(dp(railLayout ? 2 : 6));
        }

        LinearLayout.LayoutParams heroParams =
                new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        LinearLayout.LayoutParams graphParams =
                new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1.0f);
        LinearLayout.LayoutParams heroContentParams =
                new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);

        LinearLayout.LayoutParams readingParams =
                new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.0f);
        LinearLayout.LayoutParams freshnessParams = stackedHeader
                ? new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                : new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.0f);
        freshnessParams.setMarginStart(stackedHeader ? 0 : dp(12));
        freshnessParams.topMargin = stackedHeader ? dp(3) : 0;

        heroCard.setLayoutParams(heroParams);
        graphCard.setLayoutParams(graphParams);
        heroContent.setLayoutParams(heroContentParams);
        readingColumn.setLayoutParams(readingParams);
        heroFreshness.setLayoutParams(freshnessParams);
        heroFreshness.setGravity(stackedHeader ? Gravity.START : Gravity.END);
        heroFreshness.setMaxLines(stackedHeader ? 3 : 2);
        readingValueRow.setLayoutParams(new LinearLayout.LayoutParams(
                WRAP_CONTENT, WRAP_CONTENT));
        LinearLayout.LayoutParams unitParams = new LinearLayout.LayoutParams(
                WRAP_CONTENT, WRAP_CONTENT);
        unitParams.gravity = Gravity.BOTTOM;
        unitParams.setMarginStart(dp(7));
        unitParams.bottomMargin = dp(stackedHeader ? 6 : 8);
        heroUnit.setLayoutParams(unitParams);
        heroValue.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                stackedHeader ? 46 : 54);
        heroTrend.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                stackedHeader ? 27 : 30);
        targetRange.setMaxWidth(Integer.MAX_VALUE);
        targetRange.setMaxLines(stackedHeader ? 2 : 1);
        targetRange.setEllipsize(stackedHeader
                ? null : android.text.TextUtils.TruncateAt.END);
        targetRange.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                stackedHeader ? 13 : 12);
        targetRange.setVisibility(View.VISIBLE);
        ViewGroup.LayoutParams graphHeaderParams = graphHeader.getLayoutParams();
        graphHeaderParams.height = stackedHeader ? WRAP_CONTENT : dp(32);
        graphHeader.setLayoutParams(graphHeaderParams);
        graphHeader.setMinimumHeight(dp(32));
        int heroHorizontalPadding = railLayout ? 20 : 16;
        int heroVerticalPadding = railLayout ? 12 : 10;
        heroCard.setPadding(dp(heroHorizontalPadding),
                dp(heroVerticalPadding), dp(heroHorizontalPadding),
                dp(heroVerticalPadding));
    }

    static boolean shouldUseNavigationRail(int widthDp, int heightDp) {
        return widthDp >= 600 && heightDp >= 360;
    }

    static float compactNavigationWeight(int navigationId) {
        return navigationId == R.id.modern_dashboard_add_intake
                ? COMPACT_ADD_WEIGHT : COMPACT_MORE_WEIGHT;
    }

    private static int navigationIcon(int navigationId) {
        if (navigationId == R.id.modern_dashboard_add_intake) {
            return R.drawable.modern_dashboard_intake;
        }
        return R.drawable.modern_dashboard_menu;
    }

    static boolean usesStackedHeader(float fontScale, int widthDp) {
        return fontScale >= 1.3f && widthDp < 600;
    }

    static boolean isPrimaryNavigationAction(int viewId) {
        for (int navigationId : NAVIGATION_IDS) {
            if (viewId == navigationId) {
                return true;
            }
        }
        return false;
    }

    /**
     * Medical time-series charts benefit from more horizontal context than text pages.
     * Keep the plot comfortably inset while allowing up to 1320dp of data.
     */
    static int chartContentGutterDp(int widthDp, boolean railLayout) {
        int usableWidth = Math.max(0, widthDp
                - (railLayout ? NAVIGATION_RAIL_WIDTH_DP : 0));
        int baseGutter = usableWidth >= 840 ? 24 : 16;
        int excess = Math.max(0,
                usableWidth - (MAX_BODY_CONTENT_WIDTH_DP + baseGutter * 2));
        return baseGutter + excess / 2;
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
        refreshData();
        requestLegacyGraphRenderIfActive();
        if (nowAction != null) {
            nowAction.setVisibility(View.GONE);
        }
        if (root != null) {
            root.announceForAccessibility(
                    activity.getString(R.string.now));
        }
    }

    void showReturnToNow() {
        if (nowAction != null && selectedDestination
                == DESTINATION_HOME) {
            nowAction.setVisibility(View.VISIBLE);
        }
    }

}
