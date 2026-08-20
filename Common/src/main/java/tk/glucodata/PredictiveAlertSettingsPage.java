package tk.glucodata;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/** Minimal phone settings for forecast-driven low and high alerts. */
public final class PredictiveAlertSettingsPage {
    static final int MIN_TOUCH_TARGET_DP = 48;

    private final MainActivity activity;
    private final PredictiveAlertPreferences preferences;
    private final Runnable onClose;

    private FrameLayout root;
    private LinearLayout content;
    private SwitchCompat masterSwitch;
    private SwitchCompat lowSwitch;
    private SwitchCompat highSwitch;
    private LinearLayout lowToggleRow;
    private LinearLayout highToggleRow;
    private ChoiceGroup lowHorizon;
    private ChoiceGroup highHorizon;
    private ChoiceGroup sensitivity;
    private ChoiceGroup cooldown;
    private TextView criticalSummary;
    private DiagnosticAction criticalActualChannels;
    private DiagnosticAction criticalPredictiveChannels;
    private DiagnosticAction criticalNotificationAccess;
    private DiagnosticAction criticalAlarmVolume;
    private DiagnosticAction criticalDndAccess;
    private DiagnosticAction criticalFullScreenAccess;
    private DiagnosticAction criticalOverlayAccess;
    private DiagnosticAction criticalExactAlarmAccess;
    private Button criticalTest;
    private TextView permissionStatus;
    private TextView channelStatus;
    private TextView forecastStatus;
    private ForecastRepository.Listener forecastListener;
    private boolean closed;

    private PredictiveAlertSettingsPage(MainActivity activity, Runnable onClose) {
        this.activity = activity;
        this.onClose = onClose;
        preferences = new PredictiveAlertPreferences(activity);
    }

    public static void show(MainActivity activity) {
        show(activity, null);
    }

    public static void show(MainActivity activity, Runnable onClose) {
        if (activity == null || Applic.isWearable) return;
        new PredictiveAlertSettingsPage(activity, onClose).show();
    }

    /** Entry shown near the top of the regular glucose-alert screen. */
    public static LinearLayout entryCard(Context context) {
        PredictiveAlertPreferences.Snapshot snapshot =
                new PredictiveAlertPreferences(context).snapshot();
        LinearLayout row = ClinicalUi.actionRow(context,
                context.getString(R.string.predictive_alert_entry_title),
                context.getString(snapshot.enabled
                        ? R.string.predictive_alert_entry_summary_on
                        : R.string.predictive_alert_entry_summary_off));
        row.setContentDescription(context.getString(
                R.string.predictive_alert_entry_accessibility,
                context.getString(snapshot.enabled
                        ? R.string.predictive_alert_state_on
                        : R.string.predictive_alert_state_off)));
        return ClinicalUi.card(context, row);
    }

    /** Refreshes the summary after the dedicated screen is closed. */
    public static void refreshEntryCard(LinearLayout target, Context context) {
        if (target == null || context == null) return;
        LinearLayout fresh = entryCard(context);
        target.removeAllViews();
        while (fresh.getChildCount() > 0) {
            View child = fresh.getChildAt(0);
            fresh.removeViewAt(0);
            target.addView(child);
        }
    }

    /** Re-evaluates the fail-closed handoff after local calibration changes. */
    public static void onLocalCalibrationStateChanged(Context context) {
        if (context == null) return;
        PredictiveAlertCoordinator.get(context).onSettingsChanged();
    }

    private void show() {
        PredictiveAlertNotifier.ensureChannels(activity);
        root = new FrameLayout(activity);
        root.setBackgroundColor(ClinicalUi.window(activity));
        content = ClinicalUi.verticalContent(activity);
        content.setPadding(ClinicalUi.dp(activity, 20),
                ClinicalUi.dp(activity, 8), ClinicalUi.dp(activity, 20),
                ClinicalUi.dp(activity, 30));

        Button close = ClinicalUi.button(activity,
                activity.getString(R.string.closename),
                ClinicalUi.ButtonRole.SECONDARY);
        close.setMinWidth(ClinicalUi.dp(activity, 64));
        close.setMinimumHeight(ClinicalUi.dp(activity, MIN_TOUCH_TARGET_DP));
        close.setOnClickListener(view -> close(true));
        content.addView(ClinicalUi.header(activity,
                activity.getString(R.string.predictive_alert_settings_title), close));

        TextView intro = ClinicalUi.body(activity,
                activity.getString(R.string.predictive_alert_settings_intro));
        intro.setPaddingRelative(ClinicalUi.dp(activity, 4), 0,
                ClinicalUi.dp(activity, 4), ClinicalUi.dp(activity, 8));
        content.addView(intro);

        content.addView(ClinicalUi.sectionLabel(activity,
                activity.getString(R.string.predictive_alert_target_section)));
        content.addView(targetHero());

        PredictiveAlertPreferences.Snapshot snapshot = preferences.snapshot();
        ToggleControl master = toggleControl(
                R.string.predictive_alert_master,
                R.string.predictive_alert_master_hint,
                snapshot.enabled);
        masterSwitch = master.toggle;
        boolean platformSupported =
                PredictiveAlertNotifier.supportsExpiringAlerts();
        masterSwitch.setEnabled(platformSupported);
        if (!platformSupported) master.row.setAlpha(.55f);
        content.addView(ClinicalUi.card(activity, master.row));

        content.addView(ClinicalUi.sectionLabel(activity,
                activity.getString(R.string.predictive_alert_direction_section)));
        ToggleControl low = toggleControl(
                R.string.predictive_alert_low_toggle,
                R.string.predictive_alert_low_toggle_hint,
                snapshot.lowEnabled);
        lowSwitch = low.toggle;
        lowToggleRow = low.row;
        ToggleControl high = toggleControl(
                R.string.predictive_alert_high_toggle,
                R.string.predictive_alert_high_toggle_hint,
                snapshot.highEnabled);
        highSwitch = high.toggle;
        highToggleRow = high.row;
        content.addView(ClinicalUi.card(activity, low.row, high.row));

        content.addView(ClinicalUi.sectionLabel(activity,
                activity.getString(R.string.predictive_alert_timing_section)));
        lowHorizon = minutesChoice(
                R.string.predictive_alert_low_horizon,
                R.string.predictive_alert_low_horizon_hint,
                PredictiveAlertPreferences.HORIZON_OPTIONS_MINUTES,
                snapshot.lowHorizonMinutes);
        highHorizon = minutesChoice(
                R.string.predictive_alert_high_horizon,
                R.string.predictive_alert_high_horizon_hint,
                PredictiveAlertPreferences.HORIZON_OPTIONS_MINUTES,
                snapshot.highHorizonMinutes);
        addWithTopGap(lowHorizon.container, 0);
        addWithTopGap(highHorizon.container, 12);

        content.addView(ClinicalUi.sectionLabel(activity,
                activity.getString(R.string.predictive_alert_behavior_section)));
        sensitivity = choiceGroup(
                R.string.predictive_alert_sensitivity,
                R.string.predictive_alert_sensitivity_hint,
                new int[]{PredictiveAlertPreferences.SENSITIVITY_EARLY,
                        PredictiveAlertPreferences.SENSITIVITY_BALANCED,
                        PredictiveAlertPreferences.SENSITIVITY_FEWER},
                new int[]{R.string.predictive_alert_sensitivity_early,
                        R.string.predictive_alert_sensitivity_balanced,
                        R.string.predictive_alert_sensitivity_fewer},
                snapshot.sensitivity);
        cooldown = minutesChoice(
                R.string.predictive_alert_cooldown,
                R.string.predictive_alert_cooldown_hint,
                PredictiveAlertPreferences.COOLDOWN_OPTIONS_MINUTES,
                snapshot.cooldownMinutes);
        addWithTopGap(sensitivity.container, 0);
        addWithTopGap(cooldown.container, 12);

        content.addView(ClinicalUi.sectionLabel(activity,
                activity.getString(R.string.critical_alarm_delivery_section)));
        TextView criticalIntro = ClinicalUi.body(activity,
                activity.getString(R.string.critical_alarm_delivery_intro));
        criticalIntro.setPaddingRelative(ClinicalUi.dp(activity, 4), 0,
                ClinicalUi.dp(activity, 4), ClinicalUi.dp(activity, 8));
        content.addView(criticalIntro);
        content.addView(criticalDeliveryCard());
        criticalTest = ClinicalUi.button(activity,
                activity.getString(R.string.critical_alarm_test_button),
                ClinicalUi.ButtonRole.SECONDARY);
        criticalTest.setMinimumHeight(ClinicalUi.dp(activity,
                MIN_TOUCH_TARGET_DP));
        LinearLayout.LayoutParams criticalTestParams =
                new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        criticalTestParams.topMargin = ClinicalUi.dp(activity, 12);
        criticalTest.setLayoutParams(criticalTestParams);
        criticalTest.setOnClickListener(view -> sendCriticalTest());
        content.addView(criticalTest);

        content.addView(ClinicalUi.sectionLabel(activity,
                activity.getString(R.string.predictive_alert_delivery_section)));
        forecastStatus = diagnosticRow(
                R.string.predictive_alert_model_status_title);
        permissionStatus = diagnosticRow(
                R.string.predictive_alert_permission_title);
        channelStatus = diagnosticRow(
                R.string.predictive_alert_channels_title);
        LinearLayout systemSettings = ClinicalUi.actionRow(activity,
                activity.getString(R.string.predictive_alert_open_notification_settings),
                activity.getString(R.string.predictive_alert_open_notification_settings_hint));
        systemSettings.setOnClickListener(view -> {
            PredictiveAlertPreferences.Snapshot selected =
                    preferences.snapshot();
            PredictiveAlertNotifier.openSystemChannelSettings(activity,
                    selected.lowEnabled, selected.highEnabled);
        });
        content.addView(ClinicalUi.card(activity,
                forecastStatus, permissionStatus, channelStatus, systemSettings));

        Button refresh = ClinicalUi.button(activity,
                activity.getString(R.string.predictive_alert_refresh_status),
                ClinicalUi.ButtonRole.SECONDARY);
        Button test = ClinicalUi.button(activity,
                activity.getString(R.string.predictive_alert_send_test),
                ClinicalUi.ButtonRole.PRIMARY);
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(
                0, WRAP_CONTENT, 1f);
        actions.addView(refresh, half);
        LinearLayout.LayoutParams testParams = new LinearLayout.LayoutParams(
                0, WRAP_CONTENT, 1f);
        testParams.setMarginStart(ClinicalUi.dp(activity, 10));
        actions.addView(test, testParams);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                MATCH_PARENT, WRAP_CONTENT);
        actionsParams.topMargin = ClinicalUi.dp(activity, 12);
        actions.setLayoutParams(actionsParams);
        content.addView(actions);

        content.addView(ClinicalUi.sectionLabel(activity,
                activity.getString(R.string.predictive_alert_safety_section)));
        content.addView(safetyCard());

        ScrollView scroll = ClinicalUi.scrollScreen(activity, content);
        root.addView(scroll, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout()
                    | WindowInsetsCompat.Type.ime());
            content.setPadding(safe.left + ClinicalUi.dp(activity, 20),
                    safe.top + ClinicalUi.dp(activity, 8),
                    safe.right + ClinicalUi.dp(activity, 20),
                    safe.bottom + ClinicalUi.dp(activity, 30));
            return insets;
        });

        bindControls();
        updateEnabledState(snapshot.enabled);
        updateDiagnostics();

        activity.addMyContentView(root,
                new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT), false);
        MainActivity.setonback(() -> close(false));
        ViewCompat.requestApplyInsets(root);
        activity.lightBars(false);

        forecastListener = this::updateForecastStatus;
        ForecastRepository.get(activity).addListener(forecastListener);

        refresh.setOnClickListener(view -> refreshDeliveryStatus());
        test.setOnClickListener(view -> sendTest());
    }

    private View targetHero() {
        LinearLayout hero = new LinearLayout(activity);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(ClinicalUi.dp(activity, 20), ClinicalUi.dp(activity, 18),
                ClinicalUi.dp(activity, 20), ClinicalUi.dp(activity, 18));
        hero.setBackground(ClinicalUi.surface(activity, true, false));

        TextView label = ClinicalUi.body(activity,
                activity.getString(R.string.predictive_alert_target_label));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hero.addView(label);

        TextView mmol = new TextView(activity);
        mmol.setText(R.string.predictive_alert_target_mmol);
        mmol.setTextColor(ClinicalUi.primaryText(activity));
        mmol.setTextSize(TypedValue.COMPLEX_UNIT_SP, 27);
        mmol.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        mmol.setIncludeFontPadding(false);
        mmol.setPadding(0, ClinicalUi.dp(activity, 5), 0, 0);
        hero.addView(mmol);

        TextView mg = ClinicalUi.body(activity,
                activity.getString(R.string.predictive_alert_target_mgdl));
        mg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mg.setPadding(0, ClinicalUi.dp(activity, 4), 0, 0);
        hero.addView(mg);
        return hero;
    }

    private View safetyCard() {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(ClinicalUi.dp(activity, 18), ClinicalUi.dp(activity, 16),
                ClinicalUi.dp(activity, 18), ClinicalUi.dp(activity, 16));
        card.setBackground(ClinicalUi.surface(activity, false, false));

        TextView title = new TextView(activity);
        title.setText(R.string.predictive_alert_safety_title);
        title.setTextColor(ClinicalUi.primaryText(activity));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(title);

        TextView body = ClinicalUi.body(activity,
                activity.getString(R.string.predictive_alert_safety_body));
        body.setPadding(0, ClinicalUi.dp(activity, 6), 0, 0);
        card.addView(body);

        TextView availability = ClinicalUi.body(activity,
                activity.getString(R.string.predictive_alert_shadow_body));
        availability.setPadding(0, ClinicalUi.dp(activity, 9), 0, 0);
        card.addView(availability);
        return card;
    }

    private ToggleControl toggleControl(int titleRes, int subtitleRes,
            boolean checked) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(ClinicalUi.dp(activity, 72));
        row.setPaddingRelative(ClinicalUi.dp(activity, 16),
                ClinicalUi.dp(activity, 8), ClinicalUi.dp(activity, 8),
                ClinicalUi.dp(activity, 8));
        row.setBackground(ClinicalUi.surface(activity, false, true));

        LinearLayout copy = new LinearLayout(activity);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(activity);
        title.setText(titleRes);
        title.setTextColor(ClinicalUi.primaryText(activity));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        copy.addView(title);
        TextView subtitle = ClinicalUi.body(activity,
                activity.getString(subtitleRes));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subtitle.setPadding(0, ClinicalUi.dp(activity, 2), 0, 0);
        copy.addView(subtitle);
        row.addView(copy, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));

        SwitchCompat toggle = new SwitchCompat(activity);
        toggle.setShowText(false);
        toggle.setChecked(checked);
        toggle.setContentDescription(activity.getString(titleRes));
        toggle.setMinimumWidth(ClinicalUi.dp(activity, 56));
        toggle.setMinimumHeight(ClinicalUi.dp(activity, MIN_TOUCH_TARGET_DP));
        toggle.setPadding(ClinicalUi.dp(activity, 7), 0,
                ClinicalUi.dp(activity, 7), 0);
        toggle.setThumbTintList(switchThumbColors());
        toggle.setTrackTintList(switchTrackColors());
        row.addView(toggle, new LinearLayout.LayoutParams(
                WRAP_CONTENT, ClinicalUi.dp(activity, MIN_TOUCH_TARGET_DP)));
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.setOnClickListener(view -> {
            if (toggle.isEnabled()) toggle.toggle();
        });
        return new ToggleControl(row, toggle);
    }

    private ChoiceGroup minutesChoice(int titleRes, int subtitleRes,
            int[] values, int selected) {
        int[] labels = new int[values.length];
        for (int index = 0; index < labels.length; index++) {
            labels[index] = R.string.predictive_alert_minutes_option;
        }
        return choiceGroup(titleRes, subtitleRes, values, labels, selected);
    }

    private ChoiceGroup choiceGroup(int titleRes, int subtitleRes, int[] values,
            int[] labelResources, int selected) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(ClinicalUi.dp(activity, 16), ClinicalUi.dp(activity, 13),
                ClinicalUi.dp(activity, 10), ClinicalUi.dp(activity, 10));
        card.setBackground(ClinicalUi.surface(activity, false, false));

        TextView title = new TextView(activity);
        title.setText(titleRes);
        title.setTextColor(ClinicalUi.primaryText(activity));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        card.addView(title);
        TextView subtitle = ClinicalUi.body(activity,
                activity.getString(subtitleRes));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subtitle.setPadding(0, ClinicalUi.dp(activity, 2), 0,
                ClinicalUi.dp(activity, 7));
        card.addView(subtitle);

        RadioGroup group = new RadioGroup(activity);
        group.setOrientation(RadioGroup.HORIZONTAL);
        for (int index = 0; index < values.length; index++) {
            int value = values[index];
            RadioButton option = new RadioButton(activity);
            option.setId(View.generateViewId());
            option.setTag(value);
            option.setText(labelResources[index]
                    == R.string.predictive_alert_minutes_option
                    ? activity.getString(labelResources[index], value)
                    : activity.getString(labelResources[index]));
            option.setTextColor(ClinicalUi.primaryText(activity));
            option.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            option.setGravity(Gravity.CENTER_VERTICAL);
            option.setMinimumHeight(ClinicalUi.dp(activity, MIN_TOUCH_TARGET_DP));
            option.setPaddingRelative(ClinicalUi.dp(activity, 6), 0,
                    ClinicalUi.dp(activity, 10), 0);
            option.setButtonTintList(choiceColors());
            group.addView(option, new RadioGroup.LayoutParams(
                    WRAP_CONTENT, ClinicalUi.dp(activity, MIN_TOUCH_TARGET_DP)));
            if (value == selected) group.check(option.getId());
        }
        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(group, new HorizontalScrollView.LayoutParams(
                WRAP_CONTENT, WRAP_CONTENT));
        card.addView(scroll, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        return new ChoiceGroup(card, group);
    }

    private View criticalDeliveryCard() {
        criticalSummary = diagnosticRow(R.string.critical_alarm_summary_title);
        criticalActualChannels = diagnosticAction(
                R.string.critical_alarm_actual_channels_title,
                () -> CriticalAlarmDiagnostics.openNotificationSettings(activity));
        criticalPredictiveChannels = diagnosticAction(
                R.string.critical_alarm_predictive_channels_title,
                () -> CriticalAlarmDiagnostics.openNotificationSettings(activity));
        criticalNotificationAccess = diagnosticAction(
                R.string.critical_alarm_notification_access_title, () -> {
                    CriticalAlarmDiagnostics.Snapshot snapshot =
                            CriticalAlarmDiagnostics.inspect(activity);
                    if (Build.VERSION.SDK_INT >= 33 && !snapshot.postPermission) {
                        activity.askNotify();
                    } else {
                        CriticalAlarmDiagnostics.openNotificationSettings(activity);
                    }
                });
        criticalAlarmVolume = diagnosticAction(
                R.string.critical_alarm_volume_title,
                () -> CriticalAlarmDiagnostics.openAlarmSoundSettings(activity));
        criticalDndAccess = diagnosticAction(
                R.string.critical_alarm_dnd_title,
                () -> CriticalAlarmDiagnostics.openDndSettings(activity));
        criticalFullScreenAccess = diagnosticAction(
                R.string.critical_alarm_full_screen_title,
                () -> CriticalAlarmDiagnostics.openFullScreenSettings(activity));
        criticalOverlayAccess = diagnosticAction(
                R.string.critical_alarm_overlay_title,
                () -> CriticalAlarmDiagnostics.openOverlaySettings(activity));
        criticalExactAlarmAccess = diagnosticAction(
                R.string.critical_alarm_exact_title,
                () -> CriticalAlarmDiagnostics.openExactAlarmSettings(activity));
        return ClinicalUi.card(activity, criticalSummary,
                criticalActualChannels.row, criticalPredictiveChannels.row,
                criticalNotificationAccess.row, criticalAlarmVolume.row,
                criticalDndAccess.row, criticalFullScreenAccess.row,
                criticalOverlayAccess.row, criticalExactAlarmAccess.row);
    }

    private DiagnosticAction diagnosticAction(int titleRes, Runnable action) {
        LinearLayout row = ClinicalUi.actionRow(activity,
                activity.getString(titleRes), "…");
        LinearLayout copy = (LinearLayout) row.getChildAt(0);
        TextView status = (TextView) copy.getChildAt(1);
        row.setContentDescription(activity.getString(titleRes));
        row.setOnClickListener(view -> {
            if (action != null) action.run();
        });
        return new DiagnosticAction(row, status, titleRes);
    }

    private TextView diagnosticRow(int titleRes) {
        TextView row = new TextView(activity);
        row.setText(titleRes);
        row.setTextColor(ClinicalUi.secondaryText(activity));
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(ClinicalUi.dp(activity, 58));
        row.setPaddingRelative(ClinicalUi.dp(activity, 16),
                ClinicalUi.dp(activity, 8), ClinicalUi.dp(activity, 16),
                ClinicalUi.dp(activity, 8));
        return row;
    }

    private void bindControls() {
        masterSwitch.setOnCheckedChangeListener((button, enabled) -> {
            preferences.setEnabled(enabled);
            PredictiveAlertCoordinator.get(activity).onSettingsChanged();
            if (enabled) {
                PredictiveAlertNotifier.ensureChannels(activity);
                if (!PredictiveAlertNotifier.canPost(activity)) activity.askNotify();
            }
            updateEnabledState(enabled);
            updateDiagnostics();
        });
        lowSwitch.setOnCheckedChangeListener((button, enabled) ->
                updateLowEnabled(enabled));
        highSwitch.setOnCheckedChangeListener((button, enabled) ->
                updateHighEnabled(enabled));
        lowHorizon.group.setOnCheckedChangeListener((group, id) -> {
            Integer value = checkedValue(group, id);
            if (value != null) preferences.setLowHorizonMinutes(value);
        });
        highHorizon.group.setOnCheckedChangeListener((group, id) -> {
            Integer value = checkedValue(group, id);
            if (value != null) preferences.setHighHorizonMinutes(value);
        });
        sensitivity.group.setOnCheckedChangeListener((group, id) -> {
            Integer value = checkedValue(group, id);
            if (value != null) preferences.setSensitivity(value);
        });
        cooldown.group.setOnCheckedChangeListener((group, id) -> {
            Integer value = checkedValue(group, id);
            if (value != null) preferences.setCooldownMinutes(value);
        });
    }

    private void updateEnabledState(boolean enabled) {
        lowSwitch.setEnabled(enabled);
        highSwitch.setEnabled(enabled);
        if (lowToggleRow != null) lowToggleRow.setAlpha(enabled ? 1f : .5f);
        if (highToggleRow != null) highToggleRow.setAlpha(enabled ? 1f : .5f);
        setChoiceEnabled(lowHorizon, enabled && lowSwitch.isChecked());
        setChoiceEnabled(highHorizon, enabled && highSwitch.isChecked());
        setChoiceEnabled(sensitivity, enabled);
        setChoiceEnabled(cooldown, enabled);
        lowSwitch.setOnCheckedChangeListener((button, checked) -> {
            updateLowEnabled(checked);
            setChoiceEnabled(lowHorizon, masterSwitch.isChecked() && checked);
        });
        highSwitch.setOnCheckedChangeListener((button, checked) -> {
            updateHighEnabled(checked);
            setChoiceEnabled(highHorizon, masterSwitch.isChecked() && checked);
        });
    }

    private void updateLowEnabled(boolean enabled) {
        preferences.setLowEnabled(enabled);
        PredictiveAlertCoordinator.get(activity).onSettingsChanged();
    }

    private void updateHighEnabled(boolean enabled) {
        preferences.setHighEnabled(enabled);
        PredictiveAlertCoordinator.get(activity).onSettingsChanged();
    }

    private void setChoiceEnabled(ChoiceGroup choice, boolean enabled) {
        choice.container.setAlpha(enabled ? 1f : .48f);
        choice.group.setEnabled(enabled);
        for (int index = 0; index < choice.group.getChildCount(); index++) {
            choice.group.getChildAt(index).setEnabled(enabled);
        }
    }

    private void updateDiagnostics() {
        updateCriticalDiagnostics();
        boolean canPost = PredictiveAlertNotifier.canPost(activity);
        PredictiveAlertPreferences.Snapshot selected = preferences.snapshot();
        boolean channels = PredictiveAlertNotifier.channelsEnabled(activity,
                selected.lowEnabled, selected.highEnabled);
        styleDiagnostic(permissionStatus,
                R.string.predictive_alert_permission_title,
                canPost ? R.string.predictive_alert_status_ready
                        : R.string.predictive_alert_status_permission_needed,
                canPost);
        if (!PredictiveAlertNotifier.supportsExpiringAlerts()) {
            styleDiagnostic(channelStatus,
                    R.string.predictive_alert_channels_title,
                    R.string.predictive_alert_status_os_unsupported, false);
        } else {
            styleDiagnostic(channelStatus,
                    R.string.predictive_alert_channels_title,
                    channels ? R.string.predictive_alert_status_channels_ready
                            : R.string.predictive_alert_status_channels_blocked,
                    channels);
        }
    }

    private void updateCriticalDiagnostics() {
        if (criticalSummary == null) return;
        CriticalAlarmDiagnostics.Snapshot snapshot =
                CriticalAlarmDiagnostics.inspect(activity);
        boolean configured = snapshot.maximallyConfigured();
        styleDiagnostic(criticalSummary,
                R.string.critical_alarm_summary_title,
                configured ? R.string.critical_alarm_summary_configured
                        : R.string.critical_alarm_summary_action_needed,
                configured);

        boolean actualChannelsReady = snapshot.actualChannels.ready()
                && snapshot.actualChannels.bypassDnd;
        boolean predictiveChannelsReady = snapshot.predictiveChannels.ready()
                && snapshot.predictiveChannels.bypassDnd;
        styleCriticalDiagnostic(criticalActualChannels,
                actualChannelsReady
                        ? activity.getString(
                        R.string.critical_alarm_channels_configured)
                        : activity.getString(
                        R.string.critical_alarm_channels_action_needed),
                actualChannelsReady);
        styleCriticalDiagnostic(criticalPredictiveChannels,
                predictiveChannelsReady
                        ? activity.getString(
                        R.string.critical_alarm_channels_configured)
                        : activity.getString(
                        R.string.critical_alarm_channels_action_needed),
                predictiveChannelsReady);
        styleCriticalDiagnostic(criticalNotificationAccess,
                activity.getString(snapshot.notificationAccess()
                        ? R.string.critical_alarm_notification_access_ready
                        : R.string.critical_alarm_notification_access_needed),
                snapshot.notificationAccess());

        int volumePercent = snapshot.alarmVolumePercent();
        boolean volumeReady = snapshot.alarmVolumeAudible();
        styleCriticalDiagnostic(criticalAlarmVolume,
                volumeReady
                        ? activity.getString(R.string.critical_alarm_volume_ready,
                        Math.max(0, volumePercent))
                        : activity.getString(
                        R.string.critical_alarm_volume_silent),
                volumeReady);
        styleCriticalDiagnostic(criticalDndAccess,
                activity.getString(snapshot.dndPolicyAccess
                        ? R.string.critical_alarm_dnd_ready
                        : R.string.critical_alarm_dnd_needed),
                snapshot.dndPolicyAccess);
        styleCriticalDiagnostic(criticalFullScreenAccess,
                activity.getString(snapshot.fullScreenAccess
                        ? R.string.critical_alarm_full_screen_ready
                        : R.string.critical_alarm_full_screen_needed),
                snapshot.fullScreenAccess);
        styleCriticalDiagnostic(criticalOverlayAccess,
                activity.getString(snapshot.overlayAccess
                        ? R.string.critical_alarm_overlay_ready
                        : R.string.critical_alarm_overlay_needed),
                snapshot.overlayAccess);
        styleCriticalDiagnostic(criticalExactAlarmAccess,
                activity.getString(snapshot.exactAlarmAccess
                        ? R.string.critical_alarm_exact_ready
                        : R.string.critical_alarm_exact_needed),
                snapshot.exactAlarmAccess);
        if (criticalTest != null) {
            criticalTest.setEnabled(snapshot.testAvailable);
            criticalTest.setAlpha(snapshot.testAvailable ? 1f : .55f);
        }
    }

    private void updateForecastStatus(ForecastRepository.State state) {
        if (forecastStatus == null || state == null) return;
        ForecastSnapshot.AlertAssessment assessment =
                state.forecast == null ? null : state.forecast.alertAssessment;
        String monitoring = assessment == null ? "unavailable"
                : assessment.monitoringStatus;
        int statusRes;
        int color;
        boolean ready = state.forecast != null
                && "ready".equalsIgnoreCase(state.forecast.status);
        boolean fresh = ready && state.error.isEmpty()
                && state.forecast.isAlertFresh(System.currentTimeMillis());
        if (PredictiveAlertCoordinator.localCalibrationActive()) {
            statusRes = R.string.predictive_alert_model_status_calibration;
            color = 0xFFF2B84B;
        } else if (state.loading) {
            statusRes = R.string.predictive_alert_model_status_refreshing;
            color = ClinicalUi.secondaryText(activity);
        } else if (fresh && assessment != null && assessment.deliveryEligible
                && "eligible".equals(monitoring)) {
            statusRes = R.string.predictive_alert_model_status_eligible;
            color = ClinicalUi.accent(activity);
        } else if (fresh && "shadow".equals(monitoring)) {
            statusRes = R.string.predictive_alert_model_status_shadow;
            color = 0xFFF2B84B;
        } else {
            statusRes = R.string.predictive_alert_model_status_unavailable;
            color = ClinicalUi.secondaryText(activity);
        }
        forecastStatus.setText(activity.getString(
                R.string.predictive_alert_diagnostic_value,
                activity.getString(R.string.predictive_alert_model_status_title),
                activity.getString(statusRes)));
        forecastStatus.setTextColor(color);
    }

    private void refreshDeliveryStatus() {
        updateDiagnostics();
        ForecastRepository repository = ForecastRepository.get(activity);
        // Re-evaluate freshness immediately, then expose the in-flight state
        // while the repository performs an authoritative network refresh.
        updateForecastStatus(repository.snapshot());
        setForecastStatus(R.string.predictive_alert_model_status_refreshing,
                ClinicalUi.secondaryText(activity));
        repository.refreshNow();
    }

    private void setForecastStatus(int statusRes, int color) {
        if (forecastStatus == null) return;
        forecastStatus.setText(activity.getString(
                R.string.predictive_alert_diagnostic_value,
                activity.getString(R.string.predictive_alert_model_status_title),
                activity.getString(statusRes)));
        forecastStatus.setTextColor(color);
    }

    private void styleDiagnostic(TextView target, int titleRes, int statusRes,
            boolean ready) {
        target.setText(activity.getString(R.string.predictive_alert_diagnostic_value,
                activity.getString(titleRes), activity.getString(statusRes)));
        target.setTextColor(ready ? ClinicalUi.accent(activity)
                : ClinicalUi.danger(activity));
    }

    private void styleCriticalDiagnostic(DiagnosticAction target,
            String status, boolean ready) {
        if (target == null) return;
        target.status.setText(status);
        target.status.setTextColor(ready ? ClinicalUi.accent(activity)
                : ClinicalUi.danger(activity));
        target.row.setContentDescription(activity.getString(target.titleRes)
                + ". " + status);
    }

    private void sendCriticalTest() {
        CriticalAlarmDiagnostics.Snapshot snapshot =
                CriticalAlarmDiagnostics.inspect(activity);
        if (!snapshot.testAvailable) {
            Toast.makeText(activity, R.string.critical_alarm_test_unavailable,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        boolean shown = CriticalAlarmDiagnostics.showTest(activity, true);
        updateDiagnostics();
        Toast.makeText(activity, shown
                        ? R.string.critical_alarm_test_sent
                        : R.string.critical_alarm_test_failed,
                Toast.LENGTH_LONG).show();
    }

    private void sendTest() {
        if (!PredictiveAlertNotifier.supportsExpiringAlerts()) {
            updateDiagnostics();
            Toast.makeText(activity,
                    R.string.predictive_alert_status_os_unsupported,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && !PredictiveAlertNotifier.canPost(activity)) {
            activity.askNotify();
            updateDiagnostics();
            Toast.makeText(activity,
                    R.string.predictive_alert_test_permission_needed,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        PredictiveAlertPreferences.Snapshot selected = preferences.snapshot();
        boolean shown = PredictiveAlertNotifier.showTest(activity,
                selected.lowEnabled, selected.highEnabled);
        updateDiagnostics();
        Toast.makeText(activity, shown
                        ? R.string.predictive_alert_test_sent
                        : R.string.predictive_alert_test_failed,
                Toast.LENGTH_SHORT).show();
    }

    private void addWithTopGap(View view, int gapDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                MATCH_PARENT, WRAP_CONTENT);
        params.topMargin = ClinicalUi.dp(activity, gapDp);
        view.setLayoutParams(params);
        content.addView(view);
    }

    private void close(boolean popBack) {
        if (closed) return;
        closed = true;
        if (popBack) MainActivity.poponback();
        ViewCompat.setOnApplyWindowInsetsListener(root, null);
        if (forecastListener != null) {
            ForecastRepository.get(activity).removeListener(forecastListener);
            forecastListener = null;
        }
        ViewParent parent = root.getParent();
        if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(root);
        activity.lightBars(false);
        if (onClose != null) onClose.run();
    }

    private ColorStateList switchThumbColors() {
        return new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled},
                        new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{ClinicalUi.blend(ClinicalUi.secondaryText(activity),
                                ClinicalUi.window(activity), .45f),
                        ClinicalUi.accent(activity),
                        ClinicalUi.secondaryText(activity)});
    }

    private ColorStateList switchTrackColors() {
        return new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled},
                        new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{ClinicalUi.blend(ClinicalUi.secondaryText(activity),
                                ClinicalUi.window(activity), .14f),
                        ClinicalUi.blend(ClinicalUi.accent(activity),
                                ClinicalUi.window(activity), .42f),
                        ClinicalUi.blend(ClinicalUi.secondaryText(activity),
                                ClinicalUi.window(activity), .28f)});
    }

    private ColorStateList choiceColors() {
        return new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{ClinicalUi.accent(activity),
                        ClinicalUi.secondaryText(activity)});
    }

    private static Integer checkedValue(RadioGroup group, int checkedId) {
        View selected = group.findViewById(checkedId);
        return selected != null && selected.getTag() instanceof Integer
                ? (Integer) selected.getTag() : null;
    }

    private static final class ToggleControl {
        final LinearLayout row;
        final SwitchCompat toggle;

        ToggleControl(LinearLayout row, SwitchCompat toggle) {
            this.row = row;
            this.toggle = toggle;
        }
    }

    private static final class DiagnosticAction {
        final LinearLayout row;
        final TextView status;
        final int titleRes;

        DiagnosticAction(LinearLayout row, TextView status, int titleRes) {
            this.row = row;
            this.status = status;
            this.titleRes = titleRes;
        }
    }

    private static final class ChoiceGroup {
        final LinearLayout container;
        final RadioGroup group;

        ChoiceGroup(LinearLayout container, RadioGroup group) {
            this.container = container;
            this.group = group;
        }
    }
}
