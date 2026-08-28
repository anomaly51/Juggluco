package tk.glucodata;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.List;

import tk.glucodata.settings.Settings;

/** Unified, phone-only home and detail flow for every glucose alert. */
public final class GlucoseAlertSettingsPage {
    static final int MIN_TOUCH_TARGET_DP = 48;

    enum Kind {
        CURRENT_LOW(R.string.glucose_alert_current_low_title,
                CriticalAlarmSoundCatalog.AlertType.ACTUAL_LOW),
        CURRENT_HIGH(R.string.glucose_alert_current_high_title,
                CriticalAlarmSoundCatalog.AlertType.ACTUAL_HIGH),
        FORECAST_LOW(R.string.glucose_alert_forecast_low_title,
                CriticalAlarmSoundCatalog.AlertType.PREDICTIVE_LOW),
        FORECAST_HIGH(R.string.glucose_alert_forecast_high_title,
                CriticalAlarmSoundCatalog.AlertType.PREDICTIVE_HIGH),
        SIGNAL_LOSS(R.string.glucose_alert_signal_loss_title,
                CriticalAlarmSoundCatalog.AlertType.SIGNAL_LOSS);

        final int titleRes;
        final CriticalAlarmSoundCatalog.AlertType soundType;

        Kind(int titleRes, CriticalAlarmSoundCatalog.AlertType soundType) {
            this.titleRes = titleRes;
            this.soundType = soundType;
        }

        boolean low() {
            return this == CURRENT_LOW || this == FORECAST_LOW;
        }

        boolean forecast() {
            return this == FORECAST_LOW || this == FORECAST_HIGH;
        }

        boolean current() {
            return this == CURRENT_LOW || this == CURRENT_HIGH;
        }
    }

    /** Deliberately fixed: the hub has five equal alert destinations. */
    static final Kind[] HUB_ALERT_TYPES = {
            Kind.CURRENT_LOW,
            Kind.CURRENT_HIGH,
            Kind.FORECAST_LOW,
            Kind.FORECAST_HIGH,
            Kind.SIGNAL_LOSS
    };

    private final MainActivity activity;
    private final Runnable onClose;
    private FrameLayout root;
    private LinearLayout alertCard;
    private LinearLayout fullScreenCard;
    private boolean closed;

    private GlucoseAlertSettingsPage(MainActivity activity, Runnable onClose) {
        this.activity = activity;
        this.onClose = onClose;
    }

    public static void show(MainActivity activity, Runnable onClose) {
        if (activity == null || Applic.isWearable) return;
        new GlucoseAlertSettingsPage(activity, onClose).show();
    }

    private void show() {
        disableValueAvailableAlarm();
        CriticalGlucoseAlarm.ensureChannels(activity);

        root = new FrameLayout(activity);
        root.setBackgroundColor(ClinicalUi.window(activity));
        LinearLayout content = ClinicalUi.verticalContent(activity);

        Button close = ClinicalUi.button(activity,
                activity.getString(R.string.closename),
                ClinicalUi.ButtonRole.SECONDARY);
        close.setMinimumHeight(ClinicalUi.dp(activity, MIN_TOUCH_TARGET_DP));
        close.setOnClickListener(view -> close(true));
        content.addView(ClinicalUi.header(activity,
                activity.getString(R.string.settings_alarm_title), close));

        TextView intro = ClinicalUi.body(activity,
                activity.getString(R.string.glucose_alert_hub_intro));
        intro.setPaddingRelative(ClinicalUi.dp(activity, 4), 0,
                ClinicalUi.dp(activity, 4), ClinicalUi.dp(activity, 4));
        content.addView(intro);

        content.addView(ClinicalUi.sectionLabel(activity,
                activity.getString(R.string.glucose_alert_types_section)));
        alertCard = ClinicalUi.card(activity);
        content.addView(alertCard);
        rebuildAlertRows();

        content.addView(ClinicalUi.sectionLabel(activity,
                activity.getString(R.string.glucose_alert_global_section)));
        fullScreenCard = FullScreenAlertSettingsPage.entryCard(activity);
        fullScreenCard.setOnClickListener(view -> FullScreenAlertSettingsPage.show(
                activity, this::refreshFullScreenEntry));
        content.addView(fullScreenCard);

        TextView automatic = ClinicalUi.body(activity,
                activity.getString(R.string.glucose_alert_auto_save_hint));
        automatic.setPaddingRelative(ClinicalUi.dp(activity, 4),
                ClinicalUi.dp(activity, 16), ClinicalUi.dp(activity, 4), 0);
        content.addView(automatic);

        ScrollView scroll = ClinicalUi.scrollScreen(activity, content);
        root.addView(scroll, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        applyInsets(root, content);
        activity.addMyContentView(root,
                new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT), false);
        MainActivity.setonback(() -> close(false));
        ViewCompat.requestApplyInsets(root);
        activity.lightBars(false);
    }

    private void rebuildAlertRows() {
        if (alertCard == null) return;
        alertCard.removeAllViews();
        for (int index = 0; index < HUB_ALERT_TYPES.length; index++) {
            if (index > 0) alertCard.addView(ClinicalUi.divider(activity));
            Kind kind = HUB_ALERT_TYPES[index];
            LinearLayout row = ClinicalUi.actionRow(activity,
                    activity.getString(kind.titleRes), summary(kind));
            row.setMinimumHeight(ClinicalUi.dp(activity, 70));
            row.setContentDescription(activity.getString(kind.titleRes)
                    + ", " + summary(kind));
            row.setOnClickListener(view -> DetailPage.show(activity, kind,
                    this::rebuildAlertRows));
            alertCard.addView(row, new LinearLayout.LayoutParams(
                    MATCH_PARENT, WRAP_CONTENT));
        }
    }

    private void refreshFullScreenEntry() {
        if (root == null || closed) return;
        FullScreenAlertSettingsPage.refreshEntryCard(fullScreenCard, activity);
        fullScreenCard.setOnClickListener(view ->
                FullScreenAlertSettingsPage.show(activity,
                        this::refreshFullScreenEntry));
    }

    private String summary(Kind kind) {
        try {
            if (kind.current()) {
                boolean enabled = kind.low()
                        ? Natives.hasalarmlow() : Natives.hasalarmhigh();
                float threshold = kind.low()
                        ? Natives.alarmlow() : Natives.alarmhigh();
                int repeat = Natives.readalarmsuspension(kind.low() ? 0 : 1);
                return activity.getString(enabled
                                ? R.string.glucose_alert_current_summary_on
                                : R.string.glucose_alert_current_summary_off,
                        formatGlucose(threshold), repeat);
            }
            if (kind.forecast()) {
                PredictiveAlertPreferences.Snapshot snapshot =
                        new PredictiveAlertPreferences(activity).snapshot();
                boolean enabled = snapshot.enabled && (kind.low()
                        ? snapshot.lowEnabled : snapshot.highEnabled);
                int horizon = kind.low() ? snapshot.lowHorizonMinutes
                        : snapshot.highHorizonMinutes;
                int repeat = kind.low() ? snapshot.lowCooldownMinutes
                        : snapshot.highCooldownMinutes;
                return activity.getString(enabled
                                ? R.string.glucose_alert_forecast_summary_on
                                : R.string.glucose_alert_forecast_summary_off,
                        fixedTarget(kind.low()), horizon, repeat);
            }
            int wait = Natives.readalarmsuspension(4);
            return activity.getString(Natives.hasalarmloss()
                            ? R.string.glucose_alert_loss_summary_on
                            : R.string.glucose_alert_loss_summary_off,
                    wait);
        } catch (Throwable unavailable) {
            return activity.getString(R.string.glucose_alert_summary_unavailable);
        }
    }

    private void close(boolean popBack) {
        if (closed) return;
        closed = true;
        disableValueAvailableAlarm();
        if (popBack) MainActivity.poponback();
        ViewCompat.setOnApplyWindowInsetsListener(root, null);
        remove(root);
        activity.lightBars(false);
        if (onClose != null) onClose.run();
    }

    private String formatGlucose(float value) {
        return Settings.float2string(value) + " " + unit();
    }

    private String fixedTarget(boolean low) {
        int resource = Natives.getunit() == 1
                ? (low ? R.string.glucose_alert_target_low_mmol
                : R.string.glucose_alert_target_high_mmol)
                : (low ? R.string.glucose_alert_target_low_mgdl
                : R.string.glucose_alert_target_high_mgdl);
        return activity.getString(resource);
    }

    private String unit() {
        return Natives.getunit() == 1 ? "mmol/L" : "mg/dL";
    }

    private static void disableValueAvailableAlarm() {
        Natives.setalarms(Natives.alarmlow(), Natives.alarmhigh(),
                Natives.hasalarmlow(), Natives.hasalarmhigh(), false,
                Natives.hasalarmloss());
    }

    private static void applyInsets(View root, LinearLayout content) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout()
                    | WindowInsetsCompat.Type.ime());
            int readableGutter = ClinicalUi.readableHorizontalGutter(
                    view.getContext(),
                    Math.max(0, view.getWidth() - safe.left - safe.right), 20);
            content.setPadding(safe.left + readableGutter,
                    safe.top + ClinicalUi.dp(view.getContext(), 8),
                    safe.right + readableGutter,
                    safe.bottom + ClinicalUi.dp(view.getContext(), 30));
            return insets;
        });
        ClinicalUi.reapplyInsetsOnWidthChanges(root);
    }

    private static void remove(View view) {
        if (view == null) return;
        ViewParent parent = view.getParent();
        if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(view);
    }

    /** One reusable editor; Kind is the only branching input. */
    private static final class DetailPage {
        private final MainActivity activity;
        private final Kind kind;
        private final Runnable onClose;
        private final PredictiveAlertPreferences predictive;

        private FrameLayout root;
        private SwitchCompat enabled;
        private EditText threshold;
        private EditText repeat;
        private EditText lossWait;
        private ChoiceGroup horizon;
        private ChoiceGroup sensitivity;
        private ChoiceGroup forecastRepeat;
        private ChoiceGroup volume;
        private LinearLayout soundRow;
        private TextView selectedSound;
        private AlertDialog soundDialog;
        private boolean closed;

        private DetailPage(MainActivity activity, Kind kind, Runnable onClose) {
            this.activity = activity;
            this.kind = kind;
            this.onClose = onClose;
            predictive = new PredictiveAlertPreferences(activity);
        }

        static void show(MainActivity activity, Kind kind, Runnable onClose) {
            new DetailPage(activity, kind, onClose).show();
        }

        private void show() {
            root = new FrameLayout(activity);
            root.setBackgroundColor(ClinicalUi.window(activity));
            LinearLayout content = ClinicalUi.verticalContent(activity);

            Button close = ClinicalUi.button(activity,
                    activity.getString(R.string.closename),
                    ClinicalUi.ButtonRole.SECONDARY);
            close.setMinimumHeight(ClinicalUi.dp(activity, MIN_TOUCH_TARGET_DP));
            close.setOnClickListener(view -> close(true));
            content.addView(ClinicalUi.header(activity,
                    activity.getString(kind.titleRes), close));

            TextView intro = ClinicalUi.body(activity,
                    activity.getString(introRes()));
            intro.setPaddingRelative(ClinicalUi.dp(activity, 4), 0,
                    ClinicalUi.dp(activity, 4), ClinicalUi.dp(activity, 4));
            content.addView(intro);

            content.addView(ClinicalUi.sectionLabel(activity,
                    activity.getString(R.string.glucose_alert_status_section)));
            enabled = switchControl(initialEnabled());
            content.addView(ClinicalUi.card(activity, enabledRow(enabled)));

            if (kind.current()) addCurrentControls(content);
            if (kind.forecast()) addForecastControls(content);
            if (kind == Kind.SIGNAL_LOSS) addSignalControls(content);
            addSoundAndVolume(content);
            addTest(content);

            TextView automatic = ClinicalUi.body(activity,
                    activity.getString(R.string.glucose_alert_auto_save_hint));
            automatic.setPaddingRelative(ClinicalUi.dp(activity, 4),
                    ClinicalUi.dp(activity, 16), ClinicalUi.dp(activity, 4), 0);
            content.addView(automatic);

            ScrollView scroll = ClinicalUi.scrollScreen(activity, content);
            root.addView(scroll, new FrameLayout.LayoutParams(
                    MATCH_PARENT, MATCH_PARENT));
            applyInsets(root, content);
            activity.addMyContentView(root,
                    new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT), false);
            MainActivity.setonback(() -> close(false));
            ViewCompat.requestApplyInsets(root);
            activity.lightBars(false);
        }

        private int introRes() {
            if (kind == Kind.CURRENT_LOW) return R.string.glucose_alert_current_low_intro;
            if (kind == Kind.CURRENT_HIGH) return R.string.glucose_alert_current_high_intro;
            if (kind == Kind.FORECAST_LOW) return R.string.glucose_alert_forecast_low_intro;
            if (kind == Kind.FORECAST_HIGH) return R.string.glucose_alert_forecast_high_intro;
            return R.string.glucose_alert_signal_loss_intro;
        }

        private boolean initialEnabled() {
            if (kind == Kind.CURRENT_LOW) return Natives.hasalarmlow();
            if (kind == Kind.CURRENT_HIGH) return Natives.hasalarmhigh();
            if (kind == Kind.SIGNAL_LOSS) return Natives.hasalarmloss();
            PredictiveAlertPreferences.Snapshot snapshot = predictive.snapshot();
            return snapshot.enabled && (kind.low()
                    ? snapshot.lowEnabled : snapshot.highEnabled);
        }

        private View enabledRow(SwitchCompat toggle) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(ClinicalUi.dp(activity, 70));
            row.setPaddingRelative(ClinicalUi.dp(activity, 16),
                    ClinicalUi.dp(activity, 8), ClinicalUi.dp(activity, 8),
                    ClinicalUi.dp(activity, 8));
            row.setBackground(ClinicalUi.surface(activity, false, true));

            LinearLayout copy = new LinearLayout(activity);
            copy.setOrientation(LinearLayout.VERTICAL);
            TextView title = primaryText(R.string.glucose_alert_enabled);
            copy.addView(title);
            TextView hint = ClinicalUi.body(activity,
                    activity.getString(R.string.glucose_alert_enabled_hint));
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            hint.setPadding(0, ClinicalUi.dp(activity, 2), 0, 0);
            copy.addView(hint);
            row.addView(copy, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
            row.addView(toggle, new LinearLayout.LayoutParams(
                    WRAP_CONTENT, ClinicalUi.dp(activity, MIN_TOUCH_TARGET_DP)));
            row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.setOnClickListener(view -> {
                if (toggle.isEnabled()) toggle.toggle();
            });
            return row;
        }

        private void addCurrentControls(LinearLayout content) {
            content.addView(ClinicalUi.sectionLabel(activity,
                    activity.getString(R.string.glucose_alert_trigger_section)));
            threshold = numberInput(true, Settings.float2string(kind.low()
                    ? Natives.alarmlow() : Natives.alarmhigh()));
            repeat = numberInput(false, String.valueOf(
                    Natives.readalarmsuspension(kind.low() ? 0 : 1)));
            content.addView(ClinicalUi.card(activity,
                    ClinicalUi.fieldRow(activity,
                            activity.getString(R.string.glucose_alert_threshold),
                            threshold, suffix(unit())),
                    ClinicalUi.fieldRow(activity,
                            activity.getString(R.string.glucose_alert_repeat),
                            repeat, suffix(activity.getString(R.string.minutes)))));
        }

        private void addForecastControls(LinearLayout content) {
            PredictiveAlertPreferences.Snapshot snapshot = predictive.snapshot();
            content.addView(ClinicalUi.sectionLabel(activity,
                    activity.getString(R.string.glucose_alert_forecast_target_section)));
            LinearLayout target = ClinicalUi.actionRow(activity,
                    activity.getString(R.string.glucose_alert_forecast_target),
                    fixedTarget(kind.low()));
            // A fixed clinical target is informative, not navigable.
            target.removeViewAt(target.getChildCount() - 1);
            target.setBackground(ClinicalUi.surface(activity, false, false));
            content.addView(ClinicalUi.card(activity, target));

            content.addView(ClinicalUi.sectionLabel(activity,
                    activity.getString(R.string.glucose_alert_timing_section)));
            horizon = choiceGroup(R.string.glucose_alert_lookahead,
                    R.string.glucose_alert_lookahead_hint,
                    PredictiveAlertPreferences.HORIZON_OPTIONS_MINUTES,
                    minuteLabels(PredictiveAlertPreferences.HORIZON_OPTIONS_MINUTES),
                    kind.low() ? snapshot.lowHorizonMinutes
                            : snapshot.highHorizonMinutes);
            forecastRepeat = choiceGroup(R.string.glucose_alert_repeat,
                    R.string.glucose_alert_forecast_repeat_hint,
                    PredictiveAlertPreferences.COOLDOWN_OPTIONS_MINUTES,
                    minuteLabels(PredictiveAlertPreferences.COOLDOWN_OPTIONS_MINUTES),
                    kind.low() ? snapshot.lowCooldownMinutes
                            : snapshot.highCooldownMinutes);
            content.addView(horizon.container);
            addGap(forecastRepeat.container);
            content.addView(forecastRepeat.container);

            content.addView(ClinicalUi.sectionLabel(activity,
                    activity.getString(R.string.glucose_alert_detection_section)));
            int[] sensitivityValues = {
                    PredictiveAlertPreferences.SENSITIVITY_EARLY,
                    PredictiveAlertPreferences.SENSITIVITY_BALANCED,
                    PredictiveAlertPreferences.SENSITIVITY_FEWER
            };
            int[] sensitivityLabels = {
                    R.string.predictive_alert_sensitivity_early,
                    R.string.predictive_alert_sensitivity_balanced,
                    R.string.predictive_alert_sensitivity_fewer
            };
            sensitivity = choiceGroup(R.string.glucose_alert_sensitivity,
                    R.string.predictive_alert_sensitivity_hint,
                    sensitivityValues, sensitivityLabels,
                    kind.low() ? snapshot.lowSensitivity
                            : snapshot.highSensitivity);
            content.addView(sensitivity.container);
        }

        private void addSignalControls(LinearLayout content) {
            content.addView(ClinicalUi.sectionLabel(activity,
                    activity.getString(R.string.glucose_alert_trigger_section)));
            lossWait = numberInput(false,
                    String.valueOf(Natives.readalarmsuspension(4)));
            content.addView(ClinicalUi.card(activity,
                    ClinicalUi.fieldRow(activity,
                            activity.getString(R.string.glucose_alert_signal_wait),
                            lossWait, suffix(activity.getString(R.string.minutes)))));
        }

        private void addSoundAndVolume(LinearLayout content) {
            content.addView(ClinicalUi.sectionLabel(activity,
                    activity.getString(R.string.glucose_alert_sound_section)));
            soundRow = ClinicalUi.actionRow(activity,
                    activity.getString(R.string.glucose_alert_sound),
                    CriticalAlarmSoundCatalog.selectedLabel(activity,
                            kind.soundType));
            selectedSound = (TextView) ((LinearLayout) soundRow.getChildAt(0))
                    .getChildAt(1);
            soundRow.setOnClickListener(view -> showSoundPicker());
            content.addView(ClinicalUi.card(activity, soundRow));

            int[] volumeOptions = CriticalAlertPreferences.volumeOptions();
            volume = choiceGroup(R.string.glucose_alert_minimum_volume,
                    R.string.glucose_alert_minimum_volume_hint,
                    volumeOptions, percentLabels(volumeOptions),
                    CriticalAlertPreferences.getMinimumVolumePercent(
                            activity, kind.soundType));
            volume.group.setOnCheckedChangeListener((group, checkedId) -> {
                Integer value = checkedValue(group, checkedId);
                if (value != null) {
                    CriticalAlertPreferences.setMinimumVolumePercent(
                            activity, kind.soundType, value);
                }
            });
            addGap(volume.container);
            content.addView(volume.container);
        }

        private void addTest(LinearLayout content) {
            content.addView(ClinicalUi.sectionLabel(activity,
                    activity.getString(R.string.glucose_alert_test_section)));
            TextView note = ClinicalUi.body(activity,
                    activity.getString(R.string.glucose_alert_test_hint));
            note.setPaddingRelative(ClinicalUi.dp(activity, 4), 0,
                    ClinicalUi.dp(activity, 4), ClinicalUi.dp(activity, 8));
            content.addView(note);
            Button test = ClinicalUi.button(activity,
                    activity.getString(R.string.glucose_alert_test_button),
                    ClinicalUi.ButtonRole.DANGER);
            test.setMinimumHeight(ClinicalUi.dp(activity, MIN_TOUCH_TARGET_DP));
            test.setOnClickListener(view -> {
                boolean started = CriticalGlucoseAlarm.showTest(
                        activity, kind.soundType);
                Toast.makeText(activity, started
                                ? R.string.glucose_alert_test_started
                                : R.string.glucose_alert_test_failed,
                        Toast.LENGTH_SHORT).show();
            });
            content.addView(test, new LinearLayout.LayoutParams(
                    MATCH_PARENT, WRAP_CONTENT));
        }

        private void showSoundPicker() {
            if (soundDialog != null) soundDialog.dismiss();
            CriticalAlarmSoundCatalog.stopPreview();
            SoundPickerState picker = new SoundPickerState(
                    CriticalAlarmSoundCatalog.selectedToneId(
                            activity, kind.soundType));
            LinearLayout pickerContent = buildSoundPickerContent(picker);
            ScrollView scroll = new ScrollView(activity);
            scroll.setFillViewport(true);
            scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            scroll.addView(pickerContent, new ScrollView.LayoutParams(
                    MATCH_PARENT, WRAP_CONTENT));

            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(activity.getString(
                            R.string.critical_alarm_sound_picker_title,
                            activity.getString(kind.titleRes)))
                    .setView(scroll)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.critical_alarm_sound_save, null)
                    .create();
            soundDialog = dialog;
            dialog.setOnShowListener(ignored -> {
                dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                        .setOnClickListener(view -> {
                            if (CriticalAlarmSoundCatalog.select(activity,
                                    kind.soundType, picker.chosenToneId)) {
                                CriticalGlucoseAlarm.ensureChannels(activity);
                                selectedSound.setText(
                                        CriticalAlarmSoundCatalog.selectedLabel(
                                                activity, kind.soundType));
                            }
                            dialog.dismiss();
                        });
            });
            dialog.setOnDismissListener(ignored -> {
                CriticalAlarmSoundCatalog.stopPreview();
                if (soundDialog == dialog) soundDialog = null;
            });
            dialog.show();
        }

        /** Builds a scalable, category-first picker without mutating preferences. */
        private LinearLayout buildSoundPickerContent(SoundPickerState picker) {
            LinearLayout content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPaddingRelative(ClinicalUi.dp(activity, 16),
                    ClinicalUi.dp(activity, 2), ClinicalUi.dp(activity, 16),
                    ClinicalUi.dp(activity, 12));

            TextView intro = ClinicalUi.body(activity,
                    activity.getString(
                            R.string.critical_alarm_sound_picker_intro));
            intro.setPaddingRelative(ClinicalUi.dp(activity, 4),
                    ClinicalUi.dp(activity, 2), ClinicalUi.dp(activity, 4),
                    ClinicalUi.dp(activity, 12));
            content.addView(intro);

            LinearLayout selectedCard = new LinearLayout(activity);
            selectedCard.setOrientation(LinearLayout.VERTICAL);
            selectedCard.setPaddingRelative(ClinicalUi.dp(activity, 16),
                    ClinicalUi.dp(activity, 14), ClinicalUi.dp(activity, 16),
                    ClinicalUi.dp(activity, 14));
            selectedCard.setBackground(soundOptionBackground(true, false));

            TextView selectedCaption = ClinicalUi.body(activity,
                    activity.getString(R.string.critical_alarm_sound_selected));
            selectedCaption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            selectedCaption.setTypeface(Typeface.create("sans-serif-medium",
                    Typeface.BOLD));
            selectedCard.addView(selectedCaption);

            picker.selectedName = new TextView(activity);
            picker.selectedName.setTextColor(ClinicalUi.primaryText(activity));
            picker.selectedName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
            picker.selectedName.setTypeface(Typeface.create(
                    "sans-serif-medium", Typeface.BOLD));
            picker.selectedName.setPadding(0, ClinicalUi.dp(activity, 3), 0, 0);
            selectedCard.addView(picker.selectedName);

            picker.selectedCategory = ClinicalUi.body(activity, "");
            picker.selectedCategory.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            picker.selectedCategory.setPadding(0, ClinicalUi.dp(activity, 2),
                    0, ClinicalUi.dp(activity, 11));
            selectedCard.addView(picker.selectedCategory);

            Button preview = ClinicalUi.button(activity,
                    activity.getString(R.string.critical_alarm_sound_preview),
                    ClinicalUi.ButtonRole.SECONDARY);
            preview.setMinimumHeight(ClinicalUi.dp(activity,
                    MIN_TOUCH_TARGET_DP));
            preview.setOnClickListener(view -> previewSound(picker));
            picker.preview = preview;
            selectedCard.addView(preview, new LinearLayout.LayoutParams(
                    MATCH_PARENT, WRAP_CONTENT));
            content.addView(selectedCard);

            content.addView(ClinicalUi.sectionLabel(activity,
                    activity.getString(R.string.critical_alarm_sound_styles)));
            RadioGroup categories = new RadioGroup(activity);
            categories.setOrientation(RadioGroup.HORIZONTAL);
            CriticalAlarmSoundCatalog.Tone initial = pickerTone(
                    picker.chosenToneId);
            picker.visibleCategory = initial == null
                    ? CriticalAlarmSoundCatalog.Category.values()[0]
                    : initial.category;
            for (CriticalAlarmSoundCatalog.Category category
                    : CriticalAlarmSoundCatalog.Category.values()) {
                RadioButton tab = radioOption(category.labelRes, category);
                styleSoundCategoryTab(tab);
                RadioGroup.LayoutParams tabParams = new RadioGroup.LayoutParams(
                        WRAP_CONTENT, ClinicalUi.dp(activity,
                        MIN_TOUCH_TARGET_DP));
                tabParams.setMarginEnd(ClinicalUi.dp(activity, 7));
                categories.addView(tab, tabParams);
                if (category == picker.visibleCategory) {
                    categories.check(tab.getId());
                }
            }
            HorizontalScrollView categoryScroll = new HorizontalScrollView(
                    activity);
            categoryScroll.setHorizontalScrollBarEnabled(false);
            categoryScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            categoryScroll.addView(categories,
                    new HorizontalScrollView.LayoutParams(
                            WRAP_CONTENT, WRAP_CONTENT));
            content.addView(categoryScroll, new LinearLayout.LayoutParams(
                    MATCH_PARENT, WRAP_CONTENT));

            picker.categoryContent = new LinearLayout(activity);
            picker.categoryContent.setOrientation(LinearLayout.VERTICAL);
            content.addView(picker.categoryContent,
                    new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            categories.setOnCheckedChangeListener((group, checkedId) -> {
                View selected = group.findViewById(checkedId);
                if (selected == null
                        || !(selected.getTag()
                        instanceof CriticalAlarmSoundCatalog.Category)) {
                    return;
                }
                CriticalAlarmSoundCatalog.Category category =
                        (CriticalAlarmSoundCatalog.Category) selected.getTag();
                categoryScroll.post(() -> categoryScroll.smoothScrollTo(
                        Math.max(0, selected.getLeft()
                                - (categoryScroll.getWidth()
                                - selected.getWidth()) / 2), 0));
                if (category == picker.visibleCategory) return;
                picker.visibleCategory = category;
                showSoundCategory(picker);
            });
            showSoundCategory(picker);
            View selectedTab = categories.findViewById(
                    categories.getCheckedRadioButtonId());
            if (selectedTab != null) {
                categoryScroll.post(() -> categoryScroll.scrollTo(
                        Math.max(0, selectedTab.getLeft()
                                - (categoryScroll.getWidth()
                                - selectedTab.getWidth()) / 2), 0));
            }
            return content;
        }

        private void styleSoundCategoryTab(RadioButton tab) {
            tab.setButtonDrawable(null);
            tab.setGravity(Gravity.CENTER);
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tab.setPaddingRelative(ClinicalUi.dp(activity, 15), 0,
                    ClinicalUi.dp(activity, 15), 0);
            tab.setTextColor(new ColorStateList(
                    new int[][]{
                            new int[]{android.R.attr.state_checked},
                            new int[0]
                    },
                    new int[]{ClinicalUi.accent(activity),
                            ClinicalUi.primaryText(activity)}));
            StateListDrawable states = new StateListDrawable();
            states.addState(new int[]{android.R.attr.state_checked},
                    soundCategoryTabShape(true));
            states.addState(new int[0], soundCategoryTabShape(false));
            tab.setBackground(new RippleDrawable(ColorStateList.valueOf(
                    ClinicalUi.blend(ClinicalUi.accent(activity),
                            ClinicalUi.window(activity), .25f)), states, null));
        }

        private GradientDrawable soundCategoryTabShape(boolean selected) {
            int window = ClinicalUi.window(activity);
            int accent = ClinicalUi.accent(activity);
            int primary = ClinicalUi.primaryText(activity);
            GradientDrawable chip = new GradientDrawable();
            chip.setShape(GradientDrawable.RECTANGLE);
            chip.setColor(ClinicalUi.blend(selected ? accent : primary,
                    window, selected ? .16f : .055f));
            chip.setCornerRadius(ClinicalUi.dp(activity, 22));
            chip.setStroke(ClinicalUi.dp(activity, selected ? 2 : 1),
                    ClinicalUi.blend(selected ? accent : primary, window,
                            selected ? .75f : .17f));
            return chip;
        }

        private void showSoundCategory(SoundPickerState picker) {
            picker.options.clear();
            picker.categoryContent.removeAllViews();
            List<View> rows = new ArrayList<>();
            for (CriticalAlarmSoundCatalog.Tone tone
                    : CriticalAlarmSoundCatalog.tones()) {
                if (tone.category != picker.visibleCategory) continue;
                SoundOption option = soundOption(picker, tone);
                picker.options.add(option);
                rows.add(option.row);
            }
            if (rows.isEmpty()) return;

            TextView categoryLabel = ClinicalUi.sectionLabel(activity,
                    activity.getString(picker.visibleCategory.labelRes));
            categoryLabel.setPaddingRelative(ClinicalUi.dp(activity, 4),
                    ClinicalUi.dp(activity, 14), ClinicalUi.dp(activity, 4),
                    ClinicalUi.dp(activity, 7));
            picker.categoryContent.addView(categoryLabel);
            picker.categoryContent.addView(ClinicalUi.card(activity,
                    rows.toArray(new View[0])));
            refreshSoundPicker(picker, false);
        }

        private SoundOption soundOption(SoundPickerState picker,
                CriticalAlarmSoundCatalog.Tone tone) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(ClinicalUi.dp(activity, 62));
            row.setPaddingRelative(ClinicalUi.dp(activity, 12),
                    ClinicalUi.dp(activity, 6), ClinicalUi.dp(activity, 8),
                    ClinicalUi.dp(activity, 6));

            TextView glyph = new TextView(activity);
            glyph.setText("♪");
            glyph.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            glyph.setGravity(Gravity.CENTER);
            glyph.setImportantForAccessibility(
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(glyph, new LinearLayout.LayoutParams(
                    ClinicalUi.dp(activity, 38), ClinicalUi.dp(activity, 38)));

            RadioButton option = radioOption(tone.labelRes, tone.id);
            option.setPaddingRelative(ClinicalUi.dp(activity, 8), 0,
                    ClinicalUi.dp(activity, 8), 0);
            option.setOnClickListener(view -> chooseSound(picker, tone.id));
            row.addView(option, new LinearLayout.LayoutParams(
                    0, ClinicalUi.dp(activity, MIN_TOUCH_TARGET_DP), 1f));

            // The native radio control is the single accessible target; the
            // larger card delegates taps to it without creating a duplicate.
            row.setImportantForAccessibility(
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.setOnClickListener(view -> option.performClick());
            return new SoundOption(tone, row, option, glyph);
        }

        private void chooseSound(SoundPickerState picker, String toneId) {
            if (toneId == null) return;
            if (toneId.equals(picker.chosenToneId)) {
                // A standalone RadioButton toggles before its click listener.
                // Rebind the draft so a second tap cannot visually uncheck it.
                refreshSoundPicker(picker, false);
                return;
            }
            CriticalAlarmSoundCatalog.stopPreview();
            picker.chosenToneId = toneId;
            refreshSoundPicker(picker, true);
        }

        private void previewSound(SoundPickerState picker) {
            // Previewing is deliberately side-effect free: select() is only
            // called from the dialog's explicit positive action.
            if (!CriticalGlucoseAlarm.previewSound(activity,
                    picker.chosenToneId)) {
                Toast.makeText(activity,
                        R.string.critical_alarm_sound_preview_failed,
                        Toast.LENGTH_SHORT).show();
            }
        }

        private void refreshSoundPicker(SoundPickerState picker,
                boolean announce) {
            for (SoundOption option : picker.options) {
                boolean checked = option.tone.id.equals(picker.chosenToneId);
                option.radio.setChecked(checked);
                option.row.setBackground(soundOptionBackground(checked, true));
                option.glyph.setTextColor(checked
                        ? ClinicalUi.accent(activity)
                        : ClinicalUi.secondaryText(activity));
                option.glyph.setBackground(soundGlyphBackground(checked));
                option.radio.setContentDescription(activity.getString(checked
                                ? R.string.critical_alarm_sound_option_selected
                                : R.string.critical_alarm_sound_option_available,
                        activity.getString(option.tone.labelRes),
                        activity.getString(option.tone.category.labelRes)));
            }
            CriticalAlarmSoundCatalog.Tone selected = pickerTone(
                    picker.chosenToneId);
            if (selected == null) return;

            CharSequence name = activity.getString(selected.labelRes);
            picker.selectedName.setText(name);
            picker.selectedCategory.setText(
                    activity.getString(selected.category.labelRes));
            picker.preview.setContentDescription(activity.getString(
                    R.string.critical_alarm_sound_preview_accessibility,
                    name));
            if (announce) {
                picker.selectedName.announceForAccessibility(activity.getString(
                        R.string.critical_alarm_sound_selection_changed, name));
            }
        }

        private CriticalAlarmSoundCatalog.Tone pickerTone(String toneId) {
            if (toneId == null) return null;
            for (CriticalAlarmSoundCatalog.Tone tone
                    : CriticalAlarmSoundCatalog.tones()) {
                if (tone.id.equals(toneId)) return tone;
            }
            return null;
        }

        private android.graphics.drawable.Drawable soundOptionBackground(
                boolean selected, boolean interactive) {
            int window = ClinicalUi.window(activity);
            int accent = ClinicalUi.accent(activity);
            int primary = ClinicalUi.primaryText(activity);
            int fill = ClinicalUi.blend(selected ? accent : primary, window,
                    selected ? .13f : .045f);
            int stroke = ClinicalUi.blend(selected ? accent : primary, window,
                    selected ? .78f : .13f);
            GradientDrawable content = new GradientDrawable();
            content.setShape(GradientDrawable.RECTANGLE);
            content.setColor(fill);
            content.setCornerRadius(ClinicalUi.dp(activity, 14));
            content.setStroke(ClinicalUi.dp(activity, selected ? 2 : 1),
                    stroke);
            if (!interactive) return content;

            GradientDrawable mask = new GradientDrawable();
            mask.setShape(GradientDrawable.RECTANGLE);
            mask.setColor(Color.WHITE);
            mask.setCornerRadius(ClinicalUi.dp(activity, 14));
            return new RippleDrawable(ColorStateList.valueOf(
                    ClinicalUi.blend(accent, fill, .24f)), content, mask);
        }

        private android.graphics.drawable.Drawable soundGlyphBackground(
                boolean selected) {
            int window = ClinicalUi.window(activity);
            int accent = ClinicalUi.accent(activity);
            int primary = ClinicalUi.primaryText(activity);
            GradientDrawable glyph = new GradientDrawable();
            glyph.setShape(GradientDrawable.OVAL);
            glyph.setColor(ClinicalUi.blend(selected ? accent : primary,
                    window, selected ? .17f : .07f));
            glyph.setStroke(ClinicalUi.dp(activity, 1), ClinicalUi.blend(
                    selected ? accent : primary, window,
                    selected ? .54f : .13f));
            return glyph;
        }

        private static final class SoundPickerState {
            String chosenToneId;
            TextView selectedName;
            TextView selectedCategory;
            Button preview;
            CriticalAlarmSoundCatalog.Category visibleCategory;
            LinearLayout categoryContent;
            final List<SoundOption> options = new ArrayList<>();

            SoundPickerState(String chosenToneId) {
                this.chosenToneId = chosenToneId;
            }
        }

        private static final class SoundOption {
            final CriticalAlarmSoundCatalog.Tone tone;
            final LinearLayout row;
            final RadioButton radio;
            final TextView glyph;

            SoundOption(CriticalAlarmSoundCatalog.Tone tone, LinearLayout row,
                    RadioButton radio, TextView glyph) {
                this.tone = tone;
                this.row = row;
                this.radio = radio;
                this.glyph = glyph;
            }
        }

        private boolean save() {
            try {
                if (kind.current()) {
                    float selectedThreshold = parsePositiveFloat(threshold);
                    short selectedRepeat = parseMinutes(repeat);
                    float low = kind.low() ? selectedThreshold : Natives.alarmlow();
                    float high = kind.low() ? Natives.alarmhigh() : selectedThreshold;
                    boolean lowEnabled = kind.low()
                            ? enabled.isChecked() : Natives.hasalarmlow();
                    boolean highEnabled = kind.low()
                            ? Natives.hasalarmhigh() : enabled.isChecked();
                    Natives.setalarms(low, high, lowEnabled, highEnabled,
                            false, Natives.hasalarmloss());
                    SuperGattCallback.writealarmsuspension(
                            kind.low() ? 0 : 1, selectedRepeat);
                } else if (kind.forecast()) {
                    saveForecast();
                } else {
                    short wait = parseMinutes(lossWait);
                    Natives.setalarms(Natives.alarmlow(), Natives.alarmhigh(),
                            Natives.hasalarmlow(), Natives.hasalarmhigh(),
                            false, enabled.isChecked());
                    if (wait != Natives.readalarmsuspension(4)) {
                        Natives.writealarmsuspension(4, wait);
                        if (SuperGattCallback.glucosealarms != null) {
                            SuperGattCallback.glucosealarms.setLossAlarm();
                        }
                    }
                }
                return true;
            } catch (IllegalArgumentException invalid) {
                Toast.makeText(activity, R.string.glucose_alert_invalid_value,
                        Toast.LENGTH_SHORT).show();
                return false;
            }
        }

        private void saveForecast() {
            boolean low = kind.low();
            predictive.setDirectionEnabled(low, enabled.isChecked());
            Integer selectedHorizon = horizon.selected();
            Integer selectedSensitivity = sensitivity.selected();
            Integer selectedRepeat = forecastRepeat.selected();
            if (selectedHorizon != null) {
                if (low) predictive.setLowHorizonMinutes(selectedHorizon);
                else predictive.setHighHorizonMinutes(selectedHorizon);
            }
            if (selectedSensitivity != null) {
                if (low) predictive.setLowSensitivity(selectedSensitivity);
                else predictive.setHighSensitivity(selectedSensitivity);
            }
            if (selectedRepeat != null) {
                if (low) predictive.setLowCooldownMinutes(selectedRepeat);
                else predictive.setHighCooldownMinutes(selectedRepeat);
            }
            if (enabled.isChecked()) {
                predictive.setEnabled(true);
                PredictiveAlertNotifier.ensureChannels(activity);
                if (!PredictiveAlertNotifier.canPost(activity)) activity.askNotify();
            } else {
                PredictiveAlertPreferences.Snapshot saved = predictive.snapshot();
                if (!saved.lowEnabled && !saved.highEnabled) {
                    predictive.setEnabled(false);
                }
            }
            PredictiveAlertCoordinator.get(activity).onSettingsChanged();
        }

        private void close(boolean popBack) {
            if (closed) return;
            if (!save()) {
                if (!popBack) MainActivity.setonback(() -> close(false));
                return;
            }
            closed = true;
            if (popBack) MainActivity.poponback();
            if (soundDialog != null) soundDialog.dismiss();
            CriticalAlarmSoundCatalog.stopPreview();
            ViewCompat.setOnApplyWindowInsetsListener(root, null);
            remove(root);
            activity.lightBars(false);
            if (onClose != null) onClose.run();
        }

        private SwitchCompat switchControl(boolean checked) {
            SwitchCompat toggle = new SwitchCompat(activity);
            toggle.setShowText(false);
            toggle.setChecked(checked);
            toggle.setContentDescription(
                    activity.getString(R.string.glucose_alert_enabled));
            toggle.setMinimumWidth(ClinicalUi.dp(activity, 56));
            toggle.setMinimumHeight(ClinicalUi.dp(activity, MIN_TOUCH_TARGET_DP));
            toggle.setThumbTintList(switchColors());
            toggle.setTrackTintList(switchColors());
            if (kind.forecast()
                    && !PredictiveAlertNotifier.supportsExpiringAlerts()) {
                toggle.setEnabled(false);
                toggle.setAlpha(.55f);
            }
            return toggle;
        }

        private EditText numberInput(boolean decimal, String value) {
            EditText input = new EditText(activity);
            input.setSingleLine(true);
            input.setText(value);
            input.setSelectAllOnFocus(true);
            input.setTextColor(ClinicalUi.primaryText(activity));
            input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            input.setGravity(Gravity.CENTER);
            input.setMinimumWidth(ClinicalUi.dp(activity, 82));
            input.setMinimumHeight(ClinicalUi.dp(activity, MIN_TOUCH_TARGET_DP));
            input.setInputType(InputType.TYPE_CLASS_NUMBER
                    | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
            return input;
        }

        private TextView suffix(CharSequence text) {
            TextView suffix = ClinicalUi.body(activity, text);
            suffix.setGravity(Gravity.CENTER_VERTICAL);
            suffix.setPaddingRelative(ClinicalUi.dp(activity, 8), 0,
                    ClinicalUi.dp(activity, 6), 0);
            return suffix;
        }

        private ChoiceGroup choiceGroup(int titleRes, int hintRes,
                int[] values, int[] labels, int selected) {
            LinearLayout card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(ClinicalUi.dp(activity, 16),
                    ClinicalUi.dp(activity, 13), ClinicalUi.dp(activity, 10),
                    ClinicalUi.dp(activity, 10));
            card.setBackground(ClinicalUi.surface(activity, false, false));
            card.addView(primaryText(titleRes));
            TextView hint = ClinicalUi.body(activity,
                    activity.getString(hintRes));
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            hint.setPadding(0, ClinicalUi.dp(activity, 2), 0,
                    ClinicalUi.dp(activity, 7));
            card.addView(hint);

            RadioGroup group = new RadioGroup(activity);
            group.setOrientation(RadioGroup.HORIZONTAL);
            for (int index = 0; index < values.length; index++) {
                RadioButton option = radioOption(labels[index], values[index]);
                group.addView(option, new RadioGroup.LayoutParams(
                        WRAP_CONTENT, ClinicalUi.dp(activity,
                        MIN_TOUCH_TARGET_DP)));
                if (values[index] == selected) group.check(option.getId());
            }
            HorizontalScrollView scroller = new HorizontalScrollView(activity);
            scroller.setHorizontalScrollBarEnabled(false);
            scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
            scroller.addView(group, new HorizontalScrollView.LayoutParams(
                    WRAP_CONTENT, WRAP_CONTENT));
            card.addView(scroller, new LinearLayout.LayoutParams(
                    MATCH_PARENT, WRAP_CONTENT));
            return new ChoiceGroup(card, group);
        }

        private RadioButton radioOption(int labelRes, Object tag) {
            RadioButton option = new RadioButton(activity);
            option.setId(View.generateViewId());
            option.setTag(tag);
            option.setText(labelRes);
            option.setTextColor(ClinicalUi.primaryText(activity));
            option.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            option.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            option.setMinimumHeight(ClinicalUi.dp(activity,
                    MIN_TOUCH_TARGET_DP));
            option.setPaddingRelative(ClinicalUi.dp(activity, 5), 0,
                    ClinicalUi.dp(activity, 10), 0);
            option.setButtonTintList(choiceColors());
            return option;
        }

        private TextView primaryText(int textRes) {
            TextView text = new TextView(activity);
            text.setText(textRes);
            text.setTextColor(ClinicalUi.primaryText(activity));
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            text.setTypeface(Typeface.create("sans-serif-medium",
                    Typeface.NORMAL));
            return text;
        }

        private String fixedTarget(boolean low) {
            int resource = Natives.getunit() == 1
                    ? (low ? R.string.glucose_alert_target_low_mmol
                    : R.string.glucose_alert_target_high_mmol)
                    : (low ? R.string.glucose_alert_target_low_mgdl
                    : R.string.glucose_alert_target_high_mgdl);
            return activity.getString(resource);
        }

        private String unit() {
            return Natives.getunit() == 1 ? "mmol/L" : "mg/dL";
        }

        private float parsePositiveFloat(EditText input) {
            try {
                float value = Float.parseFloat(input.getText().toString()
                        .trim().replace(',', '.'));
                if (!Float.isFinite(value) || value <= 0f) {
                    throw new IllegalArgumentException();
                }
                return value;
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException(invalid);
            }
        }

        private short parseMinutes(EditText input) {
            try {
                int value = Integer.parseInt(input.getText().toString().trim());
                if (value < 1 || value > 1440) {
                    throw new IllegalArgumentException();
                }
                return (short) value;
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException(invalid);
            }
        }

        private int[] minuteLabels(int[] values) {
            int[] labels = new int[values.length];
            for (int index = 0; index < values.length; index++) {
                labels[index] = minuteLabel(values[index]);
            }
            return labels;
        }

        private int minuteLabel(int minutes) {
            if (minutes == 15) return R.string.glucose_alert_minutes_15;
            if (minutes == 20) return R.string.glucose_alert_minutes_20;
            if (minutes == 30) return R.string.glucose_alert_minutes_30;
            if (minutes == 45) return R.string.glucose_alert_minutes_45;
            if (minutes == 60) return R.string.glucose_alert_minutes_60;
            return R.string.glucose_alert_minutes_120;
        }

        private int[] percentLabels(int[] values) {
            int[] labels = new int[values.length];
            for (int index = 0; index < values.length; index++) {
                labels[index] = values[index] == 70
                        ? R.string.glucose_alert_volume_70
                        : values[index] == 85
                        ? R.string.glucose_alert_volume_85
                        : R.string.glucose_alert_volume_100;
            }
            return labels;
        }

        private void addGap(View view) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    MATCH_PARENT, WRAP_CONTENT);
            params.topMargin = ClinicalUi.dp(activity, 12);
            view.setLayoutParams(params);
        }

        private ColorStateList switchColors() {
            return new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked},
                            new int[]{}},
                    new int[]{ClinicalUi.accent(activity),
                            ClinicalUi.secondaryText(activity)});
        }

        private ColorStateList choiceColors() {
            return new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked},
                            new int[]{}},
                    new int[]{ClinicalUi.accent(activity),
                            ClinicalUi.secondaryText(activity)});
        }

        private static Integer checkedValue(RadioGroup group, int checkedId) {
            View selected = group.findViewById(checkedId);
            return selected != null && selected.getTag() instanceof Integer
                    ? (Integer) selected.getTag() : null;
        }
    }

    private static final class ChoiceGroup {
        final LinearLayout container;
        final RadioGroup group;

        ChoiceGroup(LinearLayout container, RadioGroup group) {
            this.container = container;
            this.group = group;
        }

        Integer selected() {
            View selected = group.findViewById(group.getCheckedRadioButtonId());
            return selected != null && selected.getTag() instanceof Integer
                    ? (Integer) selected.getTag() : null;
        }
    }
}
