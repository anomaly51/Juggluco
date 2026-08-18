package tk.glucodata;

import static android.view.View.GONE;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/** Full-screen, read-only explanation of the personal forecast state. */
final class ForecastDetailsPage {
    private final MainActivity activity;
    private final ForecastRepository repository;
    private final ForecastRepository.Listener listener = this::render;

    private View root;
    private View page;
    private View loading;
    private View refresh;
    private TextView status;
    private TextView horizon;
    private TextView confidence;
    private TextView generated;
    private TextView error;
    private TextView conditional;
    private ViewGroup factors;
    private TextView data;
    private TextView accuracy7d;
    private TextView accuracy30d;
    private TextView training;
    private TextView version;
    private boolean closed;

    private ForecastDetailsPage(MainActivity activity) {
        this.activity = activity;
        repository = ForecastRepository.get(activity);
    }

    static ForecastDetailsPage show(MainActivity activity) {
        if (activity == null || Applic.isWearable) return null;
        ForecastDetailsPage page = new ForecastDetailsPage(activity);
        page.show();
        return page;
    }

    boolean isShowing() {
        return !closed && root != null && root.getParent() != null;
    }

    private void show() {
        root = LayoutInflater.from(activity).inflate(
                R.layout.modern_forecast_details, null, false);
        page = root.findViewById(R.id.forecast_details_page);
        loading = root.findViewById(R.id.forecast_details_loading);
        refresh = root.findViewById(R.id.forecast_details_refresh);
        status = root.findViewById(R.id.forecast_details_status);
        horizon = root.findViewById(R.id.forecast_details_horizon);
        confidence = root.findViewById(R.id.forecast_details_confidence);
        generated = root.findViewById(R.id.forecast_details_generated);
        error = root.findViewById(R.id.forecast_details_error);
        conditional = root.findViewById(R.id.forecast_details_conditional);
        factors = root.findViewById(R.id.forecast_details_factors);
        data = root.findViewById(R.id.forecast_details_data);
        accuracy7d = root.findViewById(R.id.forecast_details_accuracy_7d);
        accuracy30d = root.findViewById(R.id.forecast_details_accuracy_30d);
        training = root.findViewById(R.id.forecast_details_training);
        version = root.findViewById(R.id.forecast_details_version);

        root.findViewById(R.id.forecast_details_close)
                .setOnClickListener(view -> close(true));
        refresh.setOnClickListener(view -> repository.refreshNow());
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets safe = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout()
                            | WindowInsetsCompat.Type.ime());
            page.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });

        activity.addMyContentView(root,
                new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT), false);
        MainActivity.setonback(() -> close(false));
        repository.addListener(listener);
        ViewCompat.requestApplyInsets(root);
        activity.lightBars(false);
        repository.refreshNow();
    }

    private void render(ForecastRepository.State state) {
        if (closed || root == null) return;
        loading.setVisibility(state.loading ? VISIBLE : GONE);
        refresh.setEnabled(!state.loading);
        refresh.setVisibility(state.loading ? INVISIBLE : VISIBLE);

        ForecastSnapshot forecast = state.forecast;
        ForecastModelStatus model = state.model;
        int statusLabel = state.loading ? R.string.forecast_refresh_loading
                : statusLabel(state);
        updateLiveStatus(status, activity.getString(statusLabel));
        boolean ready = forecast.isGraphUsable(System.currentTimeMillis())
                && state.error.isEmpty();
        status.setTextColor(ContextCompat.getColor(activity, ready
                ? R.color.modern_secondary_accent
                : R.color.modern_secondary_text_secondary));

        if (!forecast.points.isEmpty() && forecast.horizonMinutes > 0) {
            horizon.setText(activity.getString(
                    R.string.forecast_horizon_minutes,
                    forecast.horizonMinutes));
            confidence.setText(activity.getString(
                    R.string.forecast_confidence_percent,
                    Math.round(forecast.confidence * 100f)));
        } else {
            horizon.setText(R.string.forecast_value_unavailable);
            confidence.setText(R.string.forecast_value_unavailable);
        }

        if (!forecast.points.isEmpty() && forecast.generatedAtMs > 0L
                && forecast.basedOnReadingAtMs > 0L) {
            generated.setText(activity.getString(
                    R.string.forecast_generated_at,
                    dateTime(forecast.generatedAtMs),
                    time(forecast.basedOnReadingAtMs)));
        } else {
            generated.setText(R.string.forecast_not_generated);
        }

        if (state.error.isEmpty()) {
            error.setVisibility(GONE);
        } else {
            error.setVisibility(VISIBLE);
            error.setText(R.string.forecast_backend_error);
        }
        if (forecast.conditionalNotice.isEmpty()
                || forecast.conditionalNotice.startsWith(
                        "Experimental estimate only.")) {
            conditional.setText(R.string.forecast_default_notice);
        } else {
            conditional.setText(forecast.conditionalNotice);
        }
        renderFactors(forecast.activities);

        if (model.readingCount > 0L || model.daysCovered > 0d) {
            String summary = activity.getString(R.string.forecast_data_summary,
                    model.readingCount, model.daysCovered,
                    model.confirmedMeals, model.rapidEvents, model.longEvents);
            if (model.lastReadingAtMs > 0L) {
                summary += "\n" + activity.getString(
                        R.string.forecast_last_reading,
                        dateTime(model.lastReadingAtMs));
            }
            data.setText(summary);
        } else {
            data.setText(R.string.forecast_data_empty);
        }

        if (model.mae7d != null) {
            accuracy7d.setText(activity.getString(
                    R.string.forecast_accuracy_period,
                    activity.getString(R.string.forecast_accuracy_7d),
                    mae(model.mae7d)));
        } else {
            accuracy7d.setText(accuracyText(R.string.forecast_accuracy_7d,
                    model.accuracy7d));
        }
        if (model.mae30d != null) {
            accuracy30d.setText(activity.getString(
                    R.string.forecast_accuracy_period,
                    activity.getString(R.string.forecast_accuracy_30d),
                    mae(model.mae30d)));
        } else if (model.accuracy30d.hasValues()) {
            accuracy30d.setText(accuracyText(R.string.forecast_accuracy_30d,
                    model.accuracy30d));
        } else if (model.scoredPoints > 0L || model.mae30 != null
                || model.mae60 != null || model.mae120 != null) {
            accuracy30d.setText(activity.getString(
                    R.string.forecast_accuracy_overall,
                    mae(model.mae30), mae(model.mae60), mae(model.mae120)));
        } else {
            accuracy30d.setText(activity.getString(
                    R.string.forecast_accuracy_not_ready,
                    activity.getString(R.string.forecast_accuracy_30d)));
        }

        training.setText(trainingText(model));
        String modelVersion = !forecast.modelVersion.isEmpty()
                ? forecast.modelVersion : model.modelVersion;
        version.setText(modelVersion.isEmpty()
                ? activity.getString(R.string.forecast_model_version_unknown)
                : activity.getString(R.string.forecast_model_version,
                        modelVersion));
    }

    static boolean updateLiveStatus(TextView view, CharSequence next) {
        if (view == null || TextUtils.equals(view.getText(), next)) {
            return false;
        }
        // The status chip is a polite live region. Updating its text only when
        // the semantic state changes announces loading -> result/error once,
        // without duplicate explicit accessibility events.
        view.setText(next);
        return true;
    }

    private int statusLabel(ForecastRepository.State state) {
        if (!state.error.isEmpty()) return R.string.forecast_status_unavailable;
        if (state.loading && state.updatedAtMs == 0L) {
            return R.string.forecast_status_checking;
        }
        String value = !state.forecast.status.isEmpty()
                ? state.forecast.status : state.model.status;
        if ("ready".equalsIgnoreCase(value)) {
            return R.string.forecast_status_ready;
        }
        if ("learning".equalsIgnoreCase(value)) {
            return R.string.forecast_status_learning;
        }
        if ("cold_start".equalsIgnoreCase(value)) {
            return R.string.forecast_status_cold_start;
        }
        if ("no_data".equalsIgnoreCase(value)) {
            return R.string.forecast_status_no_data;
        }
        if ("stale".equalsIgnoreCase(value)) {
            return R.string.forecast_status_stale;
        }
        if ("low_confidence".equalsIgnoreCase(value)) {
            return R.string.forecast_status_low_confidence;
        }
        return R.string.forecast_status_unavailable;
    }

    private CharSequence accuracyText(int label,
            ForecastModelStatus.AccuracyWindow window) {
        String name = activity.getString(label);
        if (window == null || !window.hasValues()) {
            return activity.getString(R.string.forecast_accuracy_not_ready, name);
        }
        return activity.getString(R.string.forecast_accuracy_window, name,
                mae(window.mae30), mae(window.mae60), mae(window.mae120));
    }

    private void renderFactors(java.util.List<ForecastSnapshot.Activity> values) {
        factors.removeAllViews();
        if (values == null || values.isEmpty()) {
            factors.addView(factorEmptyView());
            return;
        }
        int shown = 0;
        long now = System.currentTimeMillis();
        for (ForecastSnapshot.Activity factor : values) {
            if (factor.effectiveEndHighMs() < now - 5L * 60_000L
                    || shown >= 12) continue;
            View card = factorCard(factor, now);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (shown > 0) params.topMargin = ClinicalUi.dp(activity, 10);
            factors.addView(card, params);
            shown++;
        }
        if (shown == 0) factors.addView(factorEmptyView());
    }

    private View factorEmptyView() {
        TextView empty = bodyText(13f,
                R.color.modern_secondary_text_secondary, Typeface.NORMAL);
        empty.setText(R.string.forecast_factors_empty);
        empty.setPadding(0, ClinicalUi.dp(activity, 3), 0,
                ClinicalUi.dp(activity, 3));
        return empty;
    }

    private View factorCard(ForecastSnapshot.Activity factor, long now) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(ClinicalUi.dp(activity, 14),
                ClinicalUi.dp(activity, 14), ClinicalUi.dp(activity, 14),
                ClinicalUi.dp(activity, 13));
        GradientDrawable background = new GradientDrawable();
        background.setColor(ContextCompat.getColor(activity,
                R.color.modern_secondary_surface_raised));
        background.setCornerRadius(ClinicalUi.dp(activity, 18));
        int factorColor = factorColor(factor.kind);
        background.setStroke(ClinicalUi.dp(activity, 1),
                Color.argb(105, Color.red(factorColor),
                        Color.green(factorColor), Color.blue(factorColor)));
        card.setBackground(background);

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = bodyText(16f,
                R.color.modern_secondary_text_primary, Typeface.BOLD);
        title.setText(factorLabel(factor));
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView amount = bodyText(12f,
                R.color.modern_secondary_text_primary, Typeface.BOLD);
        amount.setGravity(Gravity.CENTER);
        amount.setMinHeight(ClinicalUi.dp(activity, 30));
        amount.setPadding(ClinicalUi.dp(activity, 10), 0,
                ClinicalUi.dp(activity, 10), 0);
        amount.setBackgroundResource(R.drawable.intake_chip);
        amount.setText(factorAmount(factor));
        header.addView(amount, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(header);

        TextView explanation = bodyText(13f,
                R.color.modern_secondary_text_secondary, Typeface.NORMAL);
        explanation.setLineSpacing(0f, 1.12f);
        explanation.setText(factorExplanation(factor));
        LinearLayout.LayoutParams explanationParams =
                new LinearLayout.LayoutParams(MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        explanationParams.topMargin = ClinicalUi.dp(activity, 9);
        card.addView(explanation, explanationParams);

        TextView timeline = bodyText(12f,
                R.color.modern_secondary_text_secondary, Typeface.NORMAL);
        timeline.setText(factor.hasEstimatedActionWindow()
                ? activity.getString(
                        R.string.forecast_factor_timeline_estimated,
                        moment(factor.startMs, now),
                        moment(factor.effectiveOnsetMs(), now),
                        moment(factor.effectivePeakLowMs(), now),
                        moment(factor.effectivePeakHighMs(), now),
                        moment(factor.effectiveEndLowMs(), now),
                        moment(factor.effectiveEndHighMs(), now))
                : activity.getString(R.string.forecast_factor_timeline,
                        moment(factor.startMs, now),
                        moment(factor.peakMs, now),
                        moment(factor.endMs, now)));
        LinearLayout.LayoutParams timelineParams =
                new LinearLayout.LayoutParams(MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        timelineParams.topMargin = ClinicalUi.dp(activity, 10);
        card.addView(timeline, timelineParams);

        TextView remaining = bodyText(12f,
                R.color.modern_secondary_text_muted, Typeface.BOLD);
        remaining.setText(remainingText(factor.effectiveEndHighMs(), now));
        LinearLayout.LayoutParams remainingParams =
                new LinearLayout.LayoutParams(MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        remainingParams.topMargin = ClinicalUi.dp(activity, 4);
        card.addView(remaining, remainingParams);

        ForecastActivityMiniChart chart =
                new ForecastActivityMiniChart(activity);
        chart.setFactor(factor);
        String contribution = contributionText(factor);
        chart.setContentDescription(activity.getString(
                R.string.forecast_factor_chart_description,
                factorLabel(factor), contribution));
        LinearLayout.LayoutParams chartParams = new LinearLayout.LayoutParams(
                MATCH_PARENT, ClinicalUi.dp(activity, 84));
        chartParams.topMargin = ClinicalUi.dp(activity, 10);
        card.addView(chart, chartParams);

        TextView contributionView = bodyText(12f,
                R.color.modern_secondary_text_primary, Typeface.BOLD);
        contributionView.setText(contribution);
        card.addView(contributionView);

        float profileConfidence = factor.profileConfidence == null
                ? factor.confidence : factor.profileConfidence;
        TextView profile = bodyText(11f,
                R.color.modern_secondary_text_muted, Typeface.NORMAL);
        profile.setText(activity.getString(R.string.forecast_factor_profile,
                profileSource(factor.profileSource),
                Math.round(profileConfidence * 100f)));
        LinearLayout.LayoutParams profileParams = new LinearLayout.LayoutParams(
                MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        profileParams.topMargin = ClinicalUi.dp(activity, 7);
        card.addView(profile, profileParams);

        if (!factor.actionModel.isEmpty()
                || factor.attributionConfidence != null
                || !factor.identifiability.isEmpty()) {
            TextView effective = bodyText(11f,
                    R.color.modern_secondary_text_muted, Typeface.NORMAL);
            float attribution = factor.attributionConfidence == null
                    ? factor.confidence : factor.attributionConfidence;
            effective.setText(activity.getString(
                    R.string.forecast_factor_effective_action,
                    actionModel(factor.actionModel),
                    Math.round(attribution * 100f),
                    identifiability(factor.identifiability)));
            LinearLayout.LayoutParams effectiveParams =
                    new LinearLayout.LayoutParams(MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            effectiveParams.topMargin = ClinicalUi.dp(activity, 3);
            card.addView(effective, effectiveParams);
        }
        if (factor.overlapCount > 0) {
            TextView overlap = bodyText(11f,
                    R.color.modern_secondary_text_muted, Typeface.BOLD);
            overlap.setText(activity.getResources().getQuantityString(
                    R.plurals.forecast_factor_overlap,
                    factor.overlapCount, factor.overlapCount));
            LinearLayout.LayoutParams overlapParams =
                    new LinearLayout.LayoutParams(MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            overlapParams.topMargin = ClinicalUi.dp(activity, 4);
            card.addView(overlap, overlapParams);
        }
        return card;
    }

    private TextView bodyText(float size, int color, int style) {
        TextView view = new TextView(activity);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(ContextCompat.getColor(activity, color));
        view.setTypeface(Typeface.create("sans-serif", style));
        view.setIncludeFontPadding(false);
        return view;
    }

    private String factorAmount(ForecastSnapshot.Activity factor) {
        Float amount = factor.amount;
        if (amount == null && !factor.eventId.isEmpty()) {
            for (IntakeEvent event : IntakeRepository.get(activity).snapshot()) {
                if (!factor.eventId.equals(event.id)) continue;
                amount = factor.kind == ForecastSnapshot.Activity.KIND_MEAL
                        && event.hasCarbs() ? event.carbsGrams
                        : factor.kind != ForecastSnapshot.Activity.KIND_MEAL
                                && event.hasInsulin() ? event.insulinUnits
                                : null;
                break;
            }
        }
        if (amount == null) {
            return activity.getString(R.string.forecast_factor_amount_unknown);
        }
        String number = IntakeEventDetailsSheet.formatNumber(amount);
        if (factor.kind == ForecastSnapshot.Activity.KIND_MEAL) {
            return activity.getString(R.string.forecast_factor_carbs_amount,
                    number);
        }
        return activity.getString(R.string.forecast_factor_insulin_amount,
                number);
    }

    private CharSequence factorExplanation(ForecastSnapshot.Activity factor) {
        if (factor.kind == ForecastSnapshot.Activity.KIND_MEAL) {
            return activity.getString(
                    R.string.carb_absorption_forecast_explanation,
                    CarbAbsorptionUi.compact(activity,
                            factor.absorptionSpeed));
        }
        if (factor.kind == ForecastSnapshot.Activity.KIND_LONG) {
            return activity.getString(R.string.forecast_factor_long_explanation);
        }
        return activity.getString(R.string.forecast_factor_rapid_explanation);
    }

    private CharSequence remainingText(long endMs, long nowMs) {
        long minutes = Math.max(0L, (endMs - nowMs + 59_999L) / 60_000L);
        if (minutes <= 0L) {
            return activity.getString(R.string.forecast_factor_complete);
        }
        long hours = minutes / 60L;
        long rest = minutes % 60L;
        return hours > 0L
                ? activity.getString(R.string.forecast_factor_remaining_hours,
                        hours, rest)
                : activity.getString(R.string.forecast_factor_remaining_minutes,
                        minutes);
    }

    private String contributionText(ForecastSnapshot.Activity factor) {
        Float largest = null;
        for (ForecastSnapshot.ActivityPoint point : factor.points) {
            if (largest == null || Math.abs(point.contributionMgDl)
                    > Math.abs(largest)) {
                largest = point.contributionMgDl;
            }
        }
        if (largest == null) {
            return activity.getString(
                    R.string.forecast_factor_contribution_unknown);
        }
        int unit = displayUnit();
        boolean mmol = unit == 1;
        double display = displayGlucoseDelta(largest, unit);
        String signed = String.format(Locale.getDefault(), "%+.1f", display);
        return activity.getString(mmol
                        ? R.string.forecast_factor_contribution_mmol
                        : R.string.forecast_factor_contribution,
                signed);
    }

    private String profileSource(String source) {
        if (source == null) source = "";
        switch (source.trim().toLowerCase(Locale.ROOT)) {
            case "ai_estimate":
                return activity.getString(R.string.forecast_profile_ai);
            case "nutrient_estimate":
                return activity.getString(R.string.forecast_profile_nutrient);
            case "personalized":
                return activity.getString(
                        R.string.forecast_profile_personalized);
            case "population_prior":
                return activity.getString(R.string.forecast_profile_population);
            default:
                return activity.getString(R.string.forecast_profile_unknown);
        }
    }

    private String actionModel(String model) {
        if (model == null) model = "";
        switch (model.trim().toLowerCase(Locale.ROOT)) {
            case "population_prior":
                return activity.getString(
                        R.string.forecast_action_model_population);
            case "personalized_kernel":
                return activity.getString(
                        R.string.forecast_action_model_personalized);
            case "contextual_counterfactual":
                return activity.getString(
                        R.string.forecast_action_model_contextual);
            case "basal_depot":
                return activity.getString(R.string.forecast_action_model_basal);
            default:
                return activity.getString(R.string.forecast_profile_unknown);
        }
    }

    private String identifiability(String value) {
        if (value == null) value = "";
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "high":
                return activity.getString(
                        R.string.forecast_identifiability_high);
            case "medium":
                return activity.getString(
                        R.string.forecast_identifiability_medium);
            case "low":
                return activity.getString(R.string.forecast_identifiability_low);
            case "not_identifiable":
                return activity.getString(
                        R.string.forecast_identifiability_not_identifiable);
            default:
                return activity.getString(
                        R.string.forecast_identifiability_unknown);
        }
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

    private String factorLabel(ForecastSnapshot.Activity factor) {
        int fallback;
        switch (factor.kind) {
            case ForecastSnapshot.Activity.KIND_RAPID:
                fallback = R.string.forecast_factor_rapid;
                break;
            case ForecastSnapshot.Activity.KIND_LONG:
                fallback = R.string.forecast_factor_long;
                break;
            default:
                fallback = R.string.forecast_factor_meal;
                break;
        }
        String kind = activity.getString(fallback);
        String supplied = structuredFactorName(factor);
        return composeFactorLabel(factor.kind, kind, supplied);
    }

    static String structuredFactorName(ForecastSnapshot.Activity factor) {
        if (factor == null) return "";
        String supplied = factor.label == null ? "" : factor.label.trim();
        if (factor.amount != null && factor.unit != null
                && !factor.unit.trim().isEmpty()) {
            int suffix = supplied.indexOf(" \u00b7 ");
            if (suffix > 0) supplied = supplied.substring(0, suffix).trim();
        }
        return supplied;
    }

    static String composeFactorLabel(int factorKind, String kind,
            String supplied) {
        kind = kind == null ? "" : kind.trim();
        supplied = supplied == null ? "" : supplied.trim();
        if (supplied.isEmpty() || supplied.equalsIgnoreCase(kind)
                || (factorKind == ForecastSnapshot.Activity.KIND_MEAL
                        && supplied.equalsIgnoreCase("meal"))
                || (factorKind == ForecastSnapshot.Activity.KIND_RAPID
                        && supplied.equalsIgnoreCase("rapid"))
                || (factorKind == ForecastSnapshot.Activity.KIND_LONG
                        && supplied.equalsIgnoreCase("long"))) {
            return kind;
        }
        return kind + " \u2014 " + supplied;
    }

    private String moment(long timestampMs, long nowMs) {
        return Math.abs(timestampMs - nowMs) > 18L * 60L * 60L * 1000L
                ? dateTime(timestampMs) : time(timestampMs);
    }

    private String mae(Double value) {
        if (value == null) return "\u2014";
        int unit = displayUnit();
        boolean mmol = unit == 1;
        return activity.getString(mmol ? R.string.forecast_mae_value_mmol
                : R.string.forecast_mae_value,
                displayGlucoseDelta(value, unit));
    }

    static double displayGlucoseDelta(double mgDl, int unit) {
        return unit == 1 ? mgDl / Applic.mgdLmult : mgDl;
    }

    private static int displayUnit() {
        try {
            return Natives.getunit();
        } catch (UnsatisfiedLinkError error) {
            return 0;
        }
    }

    private String humanState(String value) {
        if (value == null || value.trim().isEmpty()) {
            return activity.getString(R.string.forecast_training_state_unknown);
        }
        String raw = value.trim().toLowerCase(Locale.ROOT);
        if ("not_started".equals(raw)) {
            return activity.getString(R.string.forecast_training_not_started);
        }
        if ("insufficient_data".equals(raw)) {
            return activity.getString(R.string.forecast_training_insufficient);
        }
        if ("trained".equals(raw)) {
            return activity.getString(R.string.forecast_training_trained);
        }
        if ("training".equals(raw)) {
            return activity.getString(R.string.forecast_training_in_progress);
        }
        if ("frozen".equals(raw)) {
            return activity.getString(R.string.forecast_training_frozen);
        }
        if ("manual_only".equals(raw)) {
            return activity.getString(R.string.forecast_training_manual_only);
        }
        String clean = value.trim().replace('_', ' ');
        return clean.substring(0, 1).toUpperCase(Locale.getDefault())
                + clean.substring(1);
    }

    private CharSequence trainingText(ForecastModelStatus model) {
        boolean manual = "manual".equalsIgnoreCase(model.trainingMode)
                || Boolean.FALSE.equals(model.automaticTrainingEnabled)
                || "manual_only".equalsIgnoreCase(model.trainingState)
                || "frozen".equalsIgnoreCase(model.trainingState);
        StringBuilder text = new StringBuilder();
        if (manual) {
            text.append(humanState(model.trainingState));
            text.append("\n").append(activity.getString(
                    R.string.forecast_training_manual_mode));
            text.append("\n").append(activity.getString(
                    R.string.forecast_training_samples, model.sampleCount));
        } else if (model.minimumSamples > 0L || model.sampleCount > 0L) {
            text.append(activity.getString(
                    R.string.forecast_training_summary,
                    humanState(model.trainingState), model.sampleCount,
                    model.minimumSamples));
        } else {
            text.append(activity.getString(R.string.forecast_model_waiting));
        }
        if (manual || model.minimumSamples > 0L || model.sampleCount > 0L) {
            text.append("\n").append(model.lastTrainedAtMs > 0L
                    ? activity.getString(R.string.forecast_last_trained,
                            dateTime(model.lastTrainedAtMs))
                    : activity.getString(R.string.forecast_not_trained));
        }
        if (Boolean.TRUE.equals(model.dataChangedSinceTraining)) {
            text.append("\n\n").append(activity.getString(
                    R.string.forecast_training_data_changed));
        }
        return text;
    }

    private String dateTime(long timestampMs) {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT,
                DateFormat.SHORT, Locale.getDefault())
                .format(new Date(timestampMs));
    }

    private String time(long timestampMs) {
        return DateFormat.getTimeInstance(DateFormat.SHORT,
                Locale.getDefault()).format(new Date(timestampMs));
    }

    void onConfigurationChanged() {
        if (closed || root == null) return;
        root.requestLayout();
        page.requestLayout();
        ViewCompat.requestApplyInsets(root);
    }

    void destroy() {
        close(false);
    }

    private void close(boolean popBack) {
        if (closed) return;
        closed = true;
        repository.removeListener(listener);
        if (popBack) MainActivity.poponback();
        ViewCompat.setOnApplyWindowInsetsListener(root, null);
        ViewParent parent = root.getParent();
        if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(root);
        activity.onForecastDetailsClosed(this);
        activity.lightBars(false);
    }

}
