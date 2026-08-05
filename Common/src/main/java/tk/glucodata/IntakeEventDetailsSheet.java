package tk.glucodata;

import static android.view.View.GONE;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/** Modern graph-marker details and confirmed backend deletion flow. */
final class IntakeEventDetailsSheet {
    private final MainActivity activity;
    private final IntakeRepository repository;
    private final IntakeEvent event;

    private View root;
    private View sheet;
    private View confirmation;
    private View deleteAction;
    private View cancelDelete;
    private View confirmDelete;
    private ProgressBar deleteProgress;
    private TextView error;
    private Insets safeInsets = Insets.NONE;
    private boolean busy;
    private boolean closed;

    IntakeEventDetailsSheet(MainActivity activity, IntakeEvent event) {
        this.activity = activity;
        this.event = event;
        repository = IntakeRepository.get(activity);
    }

    boolean isShowing() {
        return !closed && root != null && root.getParent() != null;
    }

    void show() {
        if (event == null || closed) return;
        root = LayoutInflater.from(activity).inflate(
                R.layout.modern_intake_event_details, null, false);
        sheet = root.findViewById(R.id.intake_event_details_sheet);
        confirmation = root.findViewById(
                R.id.intake_event_delete_confirmation);
        deleteAction = root.findViewById(R.id.intake_event_details_delete);
        cancelDelete = root.findViewById(R.id.intake_event_delete_cancel);
        confirmDelete = root.findViewById(R.id.intake_event_delete_confirm);
        deleteProgress = root.findViewById(R.id.intake_event_delete_progress);
        error = root.findViewById(R.id.intake_event_details_error);

        renderEvent();
        root.setOnClickListener(view -> close(true));
        sheet.setOnClickListener(view -> { });
        root.findViewById(R.id.intake_event_details_close)
                .setOnClickListener(view -> close(true));
        deleteAction.setOnClickListener(view -> showDeleteConfirmation());
        cancelDelete.setOnClickListener(view -> hideDeleteConfirmation());
        confirmDelete.setOnClickListener(view -> deleteConfirmed());

        ViewCompat.setAccessibilityPaneTitle(sheet,
                activity.getString(titleResource()));
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            safeInsets = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout()
                            | WindowInsetsCompat.Type.ime());
            updateSheetBounds();
            return insets;
        });
        root.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> updateSheetBounds());

        activity.addMyContentView(root,
                new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT), false);
        MainActivity.setonback(this::handleSystemBack);
        ViewCompat.requestApplyInsets(root);
        activity.lightBars(false);
        sheet.requestFocus();
    }

    private void renderEvent() {
        boolean meal = event.hasMeal();
        boolean insulin = event.hasInsulin();
        TextView title = root.findViewById(R.id.intake_event_details_title);
        TextView subtitle = root.findViewById(
                R.id.intake_event_details_subtitle);
        TextView kind = root.findViewById(R.id.intake_event_details_kind);
        TextView name = root.findViewById(R.id.intake_event_details_name);
        TextView amount = root.findViewById(R.id.intake_event_details_amount);
        TextView type = root.findViewById(R.id.intake_event_details_type);
        TextView time = root.findViewById(R.id.intake_event_details_time);
        TextView source = root.findViewById(R.id.intake_event_details_source);
        View absorption = root.findViewById(
                R.id.intake_event_details_absorption);
        TextView absorptionValue = root.findViewById(
                R.id.intake_event_details_absorption_value);

        title.setText(titleResource());
        String recorded = dateTime(event.occurredAtMs);
        subtitle.setText(recorded);
        time.setText(recorded);
        kind.setText(kindResource(meal, insulin));
        type.setText(typeResource(meal, insulin));
        name.setText(displayName(meal, insulin));
        amount.setText(displayAmount(meal, insulin));

        if (meal) {
            AbsorptionEstimate estimate = absorptionEstimate();
            absorption.setVisibility(VISIBLE);
            absorptionValue.setText(CarbAbsorptionUi.valueDetails(activity,
                    estimate.speed, estimate.peakMinutes,
                    estimate.durationMinutes, estimate.confidence));
        } else {
            absorption.setVisibility(GONE);
        }

        if (meal && "ai_estimate".equalsIgnoreCase(event.carbsSource)) {
            source.setVisibility(VISIBLE);
            source.setText(event.aiConfidence > 0f
                    ? activity.getString(R.string.intake_event_details_ai_source,
                            Math.round(event.aiConfidence * 100f))
                    : activity.getString(
                            R.string.intake_event_details_ai_source_reviewed));
        } else {
            source.setVisibility(GONE);
        }
    }

    /** Prefer the durable intake payload, then fill older cached records from
     * the current forecast activity when that exact event is still present. */
    private AbsorptionEstimate absorptionEstimate() {
        Float speed = event.absorptionSpeed;
        Integer peak = event.absorptionPeakMinutes;
        Integer duration = event.absorptionDurationMinutes;
        Float confidence = event.absorptionConfidence;
        ForecastSnapshot forecast = ForecastRepository.get(activity)
                .snapshot().forecast;
        for (ForecastSnapshot.Activity factor : forecast.activities) {
            if (factor.kind != ForecastSnapshot.Activity.KIND_MEAL
                    || !event.id.equals(factor.eventId)) continue;
            if (speed == null) speed = factor.absorptionSpeed;
            if (peak == null && factor.peakMs >= factor.startMs) {
                peak = minutesBetween(factor.startMs, factor.peakMs);
            }
            if (duration == null && factor.endMs >= factor.startMs) {
                duration = minutesBetween(factor.startMs, factor.endMs);
            }
            if (confidence == null) {
                confidence = factor.profileConfidence == null
                        ? factor.confidence : factor.profileConfidence;
            }
            break;
        }
        return new AbsorptionEstimate(speed, peak, duration, confidence);
    }

    private static int minutesBetween(long startMs, long endMs) {
        long minutes = Math.max(0L, Math.round(
                (endMs - startMs) / 60_000.0));
        return (int) Math.min(Integer.MAX_VALUE, minutes);
    }

    private static final class AbsorptionEstimate {
        final Float speed;
        final Integer peakMinutes;
        final Integer durationMinutes;
        final Float confidence;

        AbsorptionEstimate(Float speed, Integer peakMinutes,
                Integer durationMinutes, Float confidence) {
            this.speed = speed;
            this.peakMinutes = peakMinutes;
            this.durationMinutes = durationMinutes;
            this.confidence = confidence;
        }
    }

    private int titleResource() {
        return event.hasMeal() && event.hasInsulin()
                ? R.string.intake_event_details_title_combined
                : event.hasMeal()
                        ? R.string.intake_event_details_title_meal
                        : R.string.intake_event_details_title_insulin;
    }

    private int kindResource(boolean meal, boolean insulin) {
        if (meal && insulin) return R.string.intake_event_details_kind_combined;
        if (meal) return R.string.intake_event_details_kind_meal;
        if (isLongInsulin(event)) {
            return R.string.intake_event_details_kind_long;
        }
        if (isRapidInsulin(event)) {
            return R.string.intake_event_details_kind_rapid;
        }
        return R.string.intake_event_details_kind_insulin;
    }

    private int typeResource(boolean meal, boolean insulin) {
        if (meal && insulin) return R.string.intake_event_details_type_combined;
        if (meal) return R.string.intake_event_details_type_meal;
        if (isLongInsulin(event)) {
            return R.string.intake_event_details_type_long;
        }
        if (isRapidInsulin(event)) {
            return R.string.intake_event_details_type_rapid;
        }
        return R.string.intake_event_details_type_insulin;
    }

    private CharSequence displayName(boolean meal, boolean insulin) {
        String mealName = event.mealText.isEmpty()
                ? activity.getString(R.string.intake_event_details_meal_name)
                : event.mealText;
        String insulinName = event.insulinDisplayName();
        if (insulinName.isEmpty()) {
            insulinName = activity.getString(
                    R.string.intake_event_details_insulin_name);
        }
        if (meal && insulin) {
            return TextUtils.concat(mealName, "  +  ", insulinName);
        }
        return meal ? mealName : insulinName;
    }

    private CharSequence displayAmount(boolean meal, boolean insulin) {
        String carbs = formatNumber(event.carbsGrams);
        String units = formatNumber(event.insulinUnits);
        if (meal && insulin && event.hasCarbs()) {
            return activity.getString(
                    R.string.intake_event_details_combined_amount,
                    carbs, units);
        }
        if (meal && event.hasCarbs()) {
            return activity.getString(R.string.intake_event_details_carbs,
                    carbs);
        }
        if (insulin) {
            return activity.getString(R.string.intake_event_details_dose,
                    units);
        }
        return activity.getString(R.string.intake_event_details_amount_unknown);
    }

    private void showDeleteConfirmation() {
        if (busy || closed) return;
        error.setVisibility(GONE);
        deleteAction.setVisibility(GONE);
        confirmation.setVisibility(VISIBLE);
        confirmation.announceForAccessibility(activity.getString(
                R.string.intake_event_delete_confirm_title));
    }

    private void hideDeleteConfirmation() {
        if (busy || closed) return;
        confirmation.setVisibility(GONE);
        deleteAction.setVisibility(VISIBLE);
        error.setVisibility(GONE);
    }

    private void deleteConfirmed() {
        if (busy || closed) return;
        setBusy(true);
        repository.deleteEvent(event, new IntakeRepository.Callback<Boolean>() {
            @Override
            public void onSuccess(Boolean deleted) {
                if (closed) return;
                // replaceEvents() has already notified DashboardChrome. That
                // listener repopulates native markers, refreshes the forecast,
                // and requests a graph render. This immediate render keeps the
                // sheet dismissal visually atomic with the deletion.
                activity.requestRender();
                busy = false;
                close(true);
            }

            @Override
            public void onError(String message) {
                if (closed) return;
                setBusy(false);
                error.setVisibility(VISIBLE);
                error.announceForAccessibility(error.getText());
            }
        });
    }

    private void setBusy(boolean value) {
        busy = value;
        confirmDelete.setVisibility(value ? INVISIBLE : VISIBLE);
        deleteProgress.setVisibility(value ? VISIBLE : GONE);
        cancelDelete.setEnabled(!value);
        deleteAction.setEnabled(!value);
        root.findViewById(R.id.intake_event_details_close)
                .setEnabled(!value);
    }

    private void handleSystemBack() {
        if (closed) return;
        if (MainActivity.isConfigurationBackDrain()) {
            // Fallback for a teardown that did not retain this modern overlay.
            // The backend request remains owned by IntakeRepository; its one
            // callback will simply observe closed and never touch stale views.
            dismiss(false);
            return;
        }
        if (busy) {
            MainActivity.setonback(this::handleSystemBack);
            deleteProgress.announceForAccessibility(activity.getString(
                    R.string.intake_event_deleting));
            return;
        }
        if (confirmation.getVisibility() == VISIBLE) {
            hideDeleteConfirmation();
            MainActivity.setonback(this::handleSystemBack);
            return;
        }
        close(false);
    }

    private void updateSheetBounds() {
        if (root == null || sheet == null) return;
        int width = root.getWidth();
        int height = root.getHeight();
        boolean landscape = width > 0 && height > 0 && width > height;
        int horizontal = ClinicalUi.dp(activity, landscape ? 36 : 8);
        if (width > ClinicalUi.dp(activity, 760)) {
            horizontal = Math.max(horizontal,
                    (width - ClinicalUi.dp(activity, 720)) / 2);
        }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams)
                sheet.getLayoutParams();
        params.leftMargin = safeInsets.left + horizontal;
        params.rightMargin = safeInsets.right + horizontal;
        params.topMargin = safeInsets.top
                + ClinicalUi.dp(activity, landscape ? 10 : 64);
        params.bottomMargin = safeInsets.bottom;
        sheet.setLayoutParams(params);
    }

    private String dateTime(long timestampMs) {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM,
                DateFormat.SHORT, Locale.getDefault())
                .format(new Date(timestampMs));
    }

    static boolean isRapidInsulin(IntakeEvent value) {
        String type = value == null ? "" : value.insulinType.toLowerCase(
                Locale.ROOT);
        String name = value == null ? "" : value.insulinDisplayName()
                .toLowerCase(Locale.ROOT);
        return type.contains("rapid") || name.contains("novorapid")
                || name.contains("novo rapid");
    }

    static boolean isLongInsulin(IntakeEvent value) {
        String type = value == null ? "" : value.insulinType.toLowerCase(
                Locale.ROOT);
        String name = value == null ? "" : value.insulinDisplayName()
                .toLowerCase(Locale.ROOT);
        return type.contains("long") || type.contains("basal")
                || name.contains("tresiba");
    }

    static String formatNumber(float value) {
        if (Math.abs(value - Math.round(value)) < .01f) {
            return String.format(Locale.getDefault(), "%d", Math.round(value));
        }
        return String.format(Locale.getDefault(), "%.1f", value);
    }

    void onConfigurationChanged() {
        if (closed || root == null) return;
        root.requestLayout();
        sheet.requestLayout();
        updateSheetBounds();
        ViewCompat.requestApplyInsets(root);
    }

    void destroy() {
        dismiss(false);
    }

    private void close(boolean popBack) {
        if (closed || busy) return;
        dismiss(popBack);
    }

    private void dismiss(boolean popBack) {
        if (closed) return;
        closed = true;
        if (popBack) MainActivity.poponback();
        if (root != null) {
            ViewCompat.setOnApplyWindowInsetsListener(root, null);
            ViewParent parent = root.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(root);
            }
        }
        activity.onIntakeEventDetailsClosed(this);
        activity.lightBars(false);
    }
}
