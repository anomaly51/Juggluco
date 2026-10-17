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
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.List;

import tk.glucodata.settings.Settings;

/** Unified, phone-only home and detail flow for every glucose alert. */
public final class GlucoseAlertSettingsPage {
    static final int MIN_TOUCH_TARGET_DP = 48;
    private static GlucoseAlertSettingsPage activeHub;
    private static DetailPage activeDetail;

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
    private LinearLayout currentAlertCard;
    private LinearLayout forecastAlertCard;
    private LinearLayout signalAlertCard;
    private TextView overviewTitle;
    private LinearLayout fullScreenCard;
    private ScrollView scroll;
    private boolean closed;

    private GlucoseAlertSettingsPage(MainActivity activity, Runnable onClose) {
        this.activity = activity;
        this.onClose = onClose;
    }

    public static void show(MainActivity activity, Runnable onClose) {
        if (activity == null || Applic.isWearable) return;
        GlucoseAlertSettingsPage page = new GlucoseAlertSettingsPage(
                activity, onClose);
        activeHub = page;
        page.show();
    }

    static boolean hasActiveScreen(MainActivity activity) {
        return (activeHub != null && activeHub.isShowingFor(activity))
                || (activeDetail != null
                && activeDetail.isShowingFor(activity))
                || FullScreenAlertSettingsPage.isShowingFor(activity);
    }

    static void onHostConfigurationChanged(MainActivity activity) {
        if (activeHub != null && activeHub.isShowingFor(activity)) {
            activeHub.reinflateForConfiguration();
        }
        if (activeDetail != null && activeDetail.isShowingFor(activity)) {
            activeDetail.reinflateForConfiguration();
        }
        FullScreenAlertSettingsPage.onHostConfigurationChanged(activity);
    }

    private boolean isShowingFor(MainActivity host) {
        return activity == host && !closed && root != null
                && root.getParent() != null;
    }

    private void show() {
        disableValueAvailableAlarm();
        CriticalGlucoseAlarm.ensureChannels(activity);
        inflateHub(true, 0);
    }

    private void inflateHub(boolean registerBack, int restoredScrollY) {
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

        content.addView(overviewCard());

        content.addView(ClinicalUi.sectionLabel(activity,
                activity.getString(R.string.glucose_alert_current_section)));
        currentAlertCard = ClinicalUi.card(activity);
        content.addView(currentAlertCard);

        content.addView(ClinicalUi.sectionLabel(activity,
                activity.getString(R.string.glucose_alert_forecast_section)));
        forecastAlertCard = ClinicalUi.card(activity);
        content.addView(forecastAlertCard);

        content.addView(ClinicalUi.sectionLabel(activity,
                activity.getString(R.string.glucose_alert_signal_section)));
        signalAlertCard = ClinicalUi.card(activity);
        content.addView(signalAlertCard);
        rebuildAlertRows();

        content.addView(ClinicalUi.sectionLabel(activity,
                activity.getString(R.string.glucose_alert_global_section)));
        fullScreenCard = FullScreenAlertSettingsPage.entryCard(activity);
        fullScreenCard.setOnClickListener(view -> FullScreenAlertSettingsPage.show(
                activity, this::refreshFullScreenEntry));
        content.addView(fullScreenCard);

        scroll = ClinicalUi.scrollScreen(activity, content);
        root.addView(scroll, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        applyInsets(root, content);
        activity.addMyContentView(root,
                new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT), false);
        if (registerBack) MainActivity.setonback(() -> close(false));
        ViewCompat.requestApplyInsets(root);
        activity.themeLightBars();
        if (restoredScrollY > 0) {
            scroll.post(() -> scroll.scrollTo(0, restoredScrollY));
        }
    }

    private void reinflateForConfiguration() {
        int preservedScrollY = scroll == null ? 0 : scroll.getScrollY();
        ViewCompat.setOnApplyWindowInsetsListener(root, null);
        remove(root);
        inflateHub(false, preservedScrollY);
    }

    private void rebuildAlertRows() {
        if (currentAlertCard == null || forecastAlertCard == null
                || signalAlertCard == null) return;
        currentAlertCard.removeAllViews();
        forecastAlertCard.removeAllViews();
        signalAlertCard.removeAllViews();
        addAlertRow(currentAlertCard, Kind.CURRENT_LOW, false);
        addAlertRow(currentAlertCard, Kind.CURRENT_HIGH, true);
        addAlertRow(forecastAlertCard, Kind.FORECAST_LOW, false);
        addAlertRow(forecastAlertCard, Kind.FORECAST_HIGH, true);
        addAlertRow(signalAlertCard, Kind.SIGNAL_LOSS, false);
        if (overviewTitle != null) {
            int count = activeAlertCount();
            overviewTitle.setText(activity.getResources().getQuantityString(
                    R.plurals.glucose_alert_active_count, count, count));
        }
    }

    private View overviewCard() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(ClinicalUi.dp(activity, 18),
                ClinicalUi.dp(activity, 16), ClinicalUi.dp(activity, 18),
                ClinicalUi.dp(activity, 16));
        overviewTitle = new TextView(activity);
        overviewTitle.setTextColor(ClinicalUi.primaryText(activity));
        overviewTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        overviewTitle.setTypeface(Typeface.create("monospace",
                Typeface.NORMAL));
        row.addView(overviewTitle);
        TextView hint = ClinicalUi.body(activity,
                activity.getString(R.string.glucose_alert_overview_hint));
        hint.setPadding(0, ClinicalUi.dp(activity, 4), 0, 0);
        row.addView(hint);
        int count = activeAlertCount();
        overviewTitle.setText(activity.getResources().getQuantityString(
                R.plurals.glucose_alert_active_count, count, count));
        return ClinicalUi.card(activity, row);
    }

    private void addAlertRow(LinearLayout card, Kind kind,
            boolean addDivider) {
        if (addDivider) card.addView(ClinicalUi.divider(activity));
        card.addView(alertRow(kind), new LinearLayout.LayoutParams(
                MATCH_PARENT, WRAP_CONTENT));
    }

    private View alertRow(Kind kind) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(ClinicalUi.dp(activity, 78));
        row.setPaddingRelative(ClinicalUi.dp(activity, 10),
                ClinicalUi.dp(activity, 6), ClinicalUi.dp(activity, 8),
                ClinicalUi.dp(activity, 6));

        LinearLayout destination = new LinearLayout(activity);
        destination.setOrientation(LinearLayout.HORIZONTAL);
        destination.setGravity(Gravity.CENTER_VERTICAL);
        destination.setBackground(ClinicalUi.rowSurface(activity));
        destination.setMinimumHeight(ClinicalUi.dp(activity, 64));
        destination.setContentDescription(activity.getString(kind.titleRes)
                + ", " + summary(kind));
        destination.setOnClickListener(view -> DetailPage.show(activity, kind,
                this::rebuildAlertRows));

        TextView glyph = new TextView(activity);
        glyph.setText(kind == Kind.SIGNAL_LOSS ? "⌁" : kind.low() ? "↓" : "↑");
        glyph.setGravity(Gravity.CENTER);
        glyph.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        glyph.setTextColor(ContextCompat.getColor(activity, kind.low()
                ? R.color.lifeprompt_glucose_low
                : kind == Kind.SIGNAL_LOSS
                ? R.color.lifeprompt_secondary
                : R.color.lifeprompt_glucose_high));
        glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        destination.addView(glyph, new LinearLayout.LayoutParams(
                ClinicalUi.dp(activity, 44), ClinicalUi.dp(activity, 48)));

        LinearLayout copy = new LinearLayout(activity);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(activity);
        title.setText(kind.titleRes);
        title.setTextColor(ClinicalUi.primaryText(activity));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.create("monospace",
                Typeface.NORMAL));
        copy.addView(title);
        TextView subtitle = ClinicalUi.body(activity, summary(kind));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subtitle.setMaxLines(2);
        subtitle.setPadding(0, ClinicalUi.dp(activity, 2), 0, 0);
        copy.addView(subtitle);
        destination.addView(copy, new LinearLayout.LayoutParams(
                0, WRAP_CONTENT, 1f));

        TextView chevron = new TextView(activity);
        chevron.setText("›");
        chevron.setTextColor(ClinicalUi.secondaryText(activity));
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        chevron.setGravity(Gravity.CENTER);
        chevron.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        destination.addView(chevron, new LinearLayout.LayoutParams(
                ClinicalUi.dp(activity, 30), ClinicalUi.dp(activity, 48)));
        row.addView(destination, new LinearLayout.LayoutParams(
                0, WRAP_CONTENT, 1f));

        SwitchCompat toggle = hubSwitch(isEnabled(kind));
        toggle.setContentDescription(activity.getString(
                R.string.glucose_alert_toggle_description,
                activity.getString(kind.titleRes)));
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (!checked && kind.current() && isEnabled(kind)) {
                // A stray thumb tap must not silently disable a current-value
                // safety alarm. Restore persisted state while confirmation is
                // visible; enabling remains a one-tap action.
                button.post(this::rebuildAlertRows);
                confirmDisableCurrentAlert(kind);
                return;
            }
            if (setEnabled(kind, checked)) rebuildAlertRows();
            else button.post(this::rebuildAlertRows);
        });
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(
                WRAP_CONTENT, ClinicalUi.dp(activity, MIN_TOUCH_TARGET_DP));
        toggleParams.setMarginStart(ClinicalUi.dp(activity, 8));
        row.addView(toggle, toggleParams);
        return row;
    }

    private SwitchCompat hubSwitch(boolean checked) {
        SwitchCompat toggle = new SwitchCompat(activity);
        toggle.setShowText(false);
        toggle.setChecked(checked);
        toggle.setMinimumWidth(ClinicalUi.dp(activity, 56));
        toggle.setMinimumHeight(ClinicalUi.dp(activity, MIN_TOUCH_TARGET_DP));
        toggle.setThumbTintList(ClinicalUi.selectionTint(activity));
        toggle.setTrackTintList(ClinicalUi.switchTrackTint(activity));
        return toggle;
    }

    private void confirmDisableCurrentAlert(Kind kind) {
        new AlertDialog.Builder(activity)
                .setTitle(activity.getString(
                        R.string.glucose_alert_disable_confirm_title,
                        activity.getString(kind.titleRes)))
                .setMessage(R.string.glucose_alert_disable_confirm_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.glucose_alert_disable_action,
                        (dialog, which) -> {
                            if (setEnabled(kind, false)) rebuildAlertRows();
                        })
                .show();
    }

    private boolean isEnabled(Kind kind) {
        try {
            if (kind == Kind.CURRENT_LOW) return Natives.hasalarmlow();
            if (kind == Kind.CURRENT_HIGH) return Natives.hasalarmhigh();
            if (kind == Kind.SIGNAL_LOSS) return Natives.hasalarmloss();
            PredictiveAlertPreferences.Snapshot snapshot =
                    new PredictiveAlertPreferences(activity).snapshot();
            return snapshot.enabled && (kind.low()
                    ? snapshot.lowEnabled : snapshot.highEnabled);
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private int activeAlertCount() {
        int count = 0;
        for (Kind kind : HUB_ALERT_TYPES) if (isEnabled(kind)) count++;
        return count;
    }

    private boolean setEnabled(Kind kind, boolean enabled) {
        try {
            if (kind.current()) {
                boolean low = kind == Kind.CURRENT_LOW
                        ? enabled : Natives.hasalarmlow();
                boolean high = kind == Kind.CURRENT_HIGH
                        ? enabled : Natives.hasalarmhigh();
                Natives.setalarms(Natives.alarmlow(), Natives.alarmhigh(),
                        low, high, false, Natives.hasalarmloss());
            } else if (kind == Kind.SIGNAL_LOSS) {
                Natives.setalarms(Natives.alarmlow(), Natives.alarmhigh(),
                        Natives.hasalarmlow(), Natives.hasalarmhigh(), false,
                        enabled);
                if (SuperGattCallback.glucosealarms != null) {
                    SuperGattCallback.glucosealarms.setLossAlarm();
                }
            } else {
                PredictiveAlertPreferences preferences =
                        new PredictiveAlertPreferences(activity);
                preferences.setDirectionEnabled(kind.low(), enabled);
                if (enabled) {
                    preferences.setEnabled(true);
                    PredictiveAlertNotifier.ensureChannels(activity);
                    if (!PredictiveAlertNotifier.canPost(activity)) {
                        activity.askNotify();
                    }
                } else {
                    PredictiveAlertPreferences.Snapshot saved =
                            preferences.snapshot();
                    if (!saved.lowEnabled && !saved.highEnabled) {
                        preferences.setEnabled(false);
                    }
                }
                PredictiveAlertCoordinator.get(activity).onSettingsChanged();
            }
            return true;
        } catch (Throwable unavailable) {
            Toast.makeText(activity, R.string.glucose_alert_summary_unavailable,
                    Toast.LENGTH_SHORT).show();
            return false;
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
        if (activeHub == this) activeHub = null;
        disableValueAvailableAlarm();
        if (popBack) MainActivity.poponback();
        ViewCompat.setOnApplyWindowInsetsListener(root, null);
        remove(root);
        activity.themeLightBars();
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
        private ScrollView scroll;
        private boolean initiallyEnabled;
        private boolean initialStateCaptured;
        private boolean closed;

        private static final class DetailDraft {
            final boolean enabled;
            final String threshold;
            final String repeat;
            final String lossWait;
            final Integer horizon;
            final Integer sensitivity;
            final Integer forecastRepeat;
            final Integer volume;

            DetailDraft(boolean enabled, String threshold, String repeat,
                    String lossWait, Integer horizon, Integer sensitivity,
                    Integer forecastRepeat, Integer volume) {
                this.enabled = enabled;
                this.threshold = threshold;
                this.repeat = repeat;
                this.lossWait = lossWait;
                this.horizon = horizon;
                this.sensitivity = sensitivity;
                this.forecastRepeat = forecastRepeat;
                this.volume = volume;
            }
        }

        private DetailPage(MainActivity activity, Kind kind, Runnable onClose) {
            this.activity = activity;
            this.kind = kind;
            this.onClose = onClose;
            predictive = new PredictiveAlertPreferences(activity);
        }

        static void show(MainActivity activity, Kind kind, Runnable onClose) {
            DetailPage page = new DetailPage(activity, kind, onClose);
            activeDetail = page;
            page.show();
        }

        private void show() {
            inflateDetail(true, null, 0);
        }

        private boolean isShowingFor(MainActivity host) {
            return activity == host && !closed && root != null
                    && root.getParent() != null;
        }

        private void inflateDetail(boolean registerBack, DetailDraft draft,
                int restoredScrollY) {
            root = new FrameLayout(activity);
            root.setBackgroundColor(ClinicalUi.window(activity));
            LinearLayout content = ClinicalUi.verticalContent(activity);

            Button close = ClinicalUi.button(activity,
                    activity.getString(R.string.glucose_alert_save_action),
                    ClinicalUi.ButtonRole.SECONDARY);
            close.setMinimumHeight(ClinicalUi.dp(activity, MIN_TOUCH_TARGET_DP));
            close.setOnClickListener(view -> close(true));
            content.addView(ClinicalUi.header(activity,
                    activity.getString(kind.titleRes), close));

            content.addView(ClinicalUi.sectionLabel(activity,
                    activity.getString(R.string.glucose_alert_status_section)));
            if (!initialStateCaptured) {
                initiallyEnabled = initialEnabled();
                initialStateCaptured = true;
            }
            enabled = switchControl(draft == null
                    ? initiallyEnabled : draft.enabled);
            content.addView(ClinicalUi.card(activity, enabledRow(enabled)));

            if (kind.current()) addCurrentControls(content);
            if (kind.forecast()) addForecastControls(content);
            if (kind == Kind.SIGNAL_LOSS) addSignalControls(content);
            addSoundAndVolume(content);
            addTest(content);
            if (draft != null) applyDraft(draft);

            scroll = ClinicalUi.scrollScreen(activity, content);
            root.addView(scroll, new FrameLayout.LayoutParams(
                    MATCH_PARENT, MATCH_PARENT));
            applyInsets(root, content);
            activity.addMyContentView(root,
                    new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT), false);
            if (registerBack) MainActivity.setonback(() -> close(false));
            ViewCompat.requestApplyInsets(root);
            activity.themeLightBars();
            if (restoredScrollY > 0) {
                scroll.post(() -> scroll.scrollTo(0, restoredScrollY));
            }
        }

        private DetailDraft captureDraft() {
            return new DetailDraft(enabled != null && enabled.isChecked(),
                    editTextValue(threshold), editTextValue(repeat),
                    editTextValue(lossWait), selectedValue(horizon),
                    selectedValue(sensitivity), selectedValue(forecastRepeat),
                    selectedValue(volume));
        }

        private void applyDraft(DetailDraft draft) {
            setEditTextValue(threshold, draft.threshold);
            setEditTextValue(repeat, draft.repeat);
            setEditTextValue(lossWait, draft.lossWait);
            selectValue(horizon, draft.horizon);
            selectValue(sensitivity, draft.sensitivity);
            selectValue(forecastRepeat, draft.forecastRepeat);
            selectValue(volume, draft.volume);
        }

        private void reinflateForConfiguration() {
            DetailDraft draft = captureDraft();
            int preservedScrollY = scroll == null ? 0 : scroll.getScrollY();
            if (soundDialog != null) soundDialog.dismiss();
            CriticalAlarmSoundCatalog.stopPreview();
            ViewCompat.setOnApplyWindowInsetsListener(root, null);
            remove(root);
            inflateDetail(false, draft, preservedScrollY);
        }

        private static String editTextValue(EditText input) {
            return input == null ? null : input.getText().toString();
        }

        private static void setEditTextValue(EditText input, String value) {
            if (input != null && value != null) input.setText(value);
        }

        private static Integer selectedValue(ChoiceGroup choices) {
            return choices == null ? null : choices.selected();
        }

        private static void selectValue(ChoiceGroup choices, Integer value) {
            if (choices != null && value != null) choices.select(value);
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
            selectedCaption.setTypeface(Typeface.create("monospace",
                    Typeface.BOLD));
            selectedCard.addView(selectedCaption);

            picker.selectedName = new TextView(activity);
            picker.selectedName.setTextColor(ClinicalUi.primaryText(activity));
            picker.selectedName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
            picker.selectedName.setTypeface(Typeface.create(
                    "monospace", Typeface.BOLD));
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
            chip.setCornerRadius(ClinicalUi.dp(activity,
                    ClinicalUi.RETRO_CORNER_RADIUS_DP));
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
            content.setCornerRadius(ClinicalUi.dp(activity,
                    ClinicalUi.RETRO_CORNER_RADIUS_DP));
            content.setStroke(ClinicalUi.dp(activity, selected ? 2 : 1),
                    stroke);
            if (!interactive) return content;

            GradientDrawable mask = new GradientDrawable();
            mask.setShape(GradientDrawable.RECTANGLE);
            mask.setColor(Color.WHITE);
            mask.setCornerRadius(ClinicalUi.dp(activity,
                    ClinicalUi.RETRO_CORNER_RADIUS_DP));
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
            close(popBack, false);
        }

        private void close(boolean popBack, boolean disableConfirmed) {
            if (closed) return;
            if (!disableConfirmed && kind.current() && initiallyEnabled
                    && !enabled.isChecked()) {
                Runnable restoreBack = () -> {
                    if (!popBack && !closed) {
                        MainActivity.setonback(() -> close(false));
                    }
                };
                new AlertDialog.Builder(activity)
                        .setTitle(activity.getString(
                                R.string.glucose_alert_disable_confirm_title,
                                activity.getString(kind.titleRes)))
                        .setMessage(R.string.glucose_alert_disable_confirm_message)
                        .setNegativeButton(R.string.cancel,
                                (dialog, which) -> restoreBack.run())
                        .setOnCancelListener(dialog -> restoreBack.run())
                        .setPositiveButton(R.string.glucose_alert_disable_action,
                                (dialog, which) -> close(popBack, true))
                        .show();
                return;
            }
            if (!save()) {
                if (!popBack) MainActivity.setonback(() -> close(false));
                return;
            }
            closed = true;
            if (activeDetail == this) activeDetail = null;
            if (popBack) MainActivity.poponback();
            if (soundDialog != null) soundDialog.dismiss();
            CriticalAlarmSoundCatalog.stopPreview();
            ViewCompat.setOnApplyWindowInsetsListener(root, null);
            remove(root);
            activity.themeLightBars();
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
            text.setTypeface(Typeface.create("monospace",
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
                input.setError(activity.getString(
                        R.string.glucose_alert_invalid_value));
                input.requestFocus();
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
                input.setError(activity.getString(
                        R.string.glucose_alert_invalid_value));
                input.requestFocus();
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

        void select(int value) {
            for (int index = 0; index < group.getChildCount(); index++) {
                View option = group.getChildAt(index);
                if (option.getTag() instanceof Integer
                        && ((Integer) option.getTag()) == value) {
                    group.check(option.getId());
                    return;
                }
            }
        }
    }
}
