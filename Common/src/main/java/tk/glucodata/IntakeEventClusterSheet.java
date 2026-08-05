package tk.glucodata;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.content.res.Resources;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** A zoom-aware graph-marker cluster with a route to each record's details. */
final class IntakeEventClusterSheet {
    static final class Entry {
        final int renderKey;
        final IntakeEvent event;

        Entry(int renderKey, IntakeEvent event) {
            this.renderKey = renderKey;
            this.event = event;
        }
    }

    private final MainActivity activity;
    private final IntakeRepository repository;
    private final List<Entry> entries;
    private View root;
    private View sheet;
    private TextView title;
    private TextView subtitle;
    private LinearLayout list;
    private Insets safeInsets = Insets.NONE;
    private boolean closed;

    IntakeEventClusterSheet(MainActivity activity, List<Entry> entries) {
        this.activity = activity;
        repository = IntakeRepository.get(activity);
        this.entries = new ArrayList<>(entries);
    }

    static List<Entry> resolve(IntakeRepository repository, int[] renderKeys) {
        ArrayList<Entry> result = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        if (repository == null || renderKeys == null) return result;
        for (int renderKey : renderKeys) {
            if (renderKey == 0 || !seen.add(renderKey)) continue;
            IntakeEvent event = repository.findByRenderKey(renderKey);
            if (event != null) result.add(new Entry(renderKey, event));
        }
        result.sort(Comparator
                .comparingLong((Entry entry) -> entry.event.occurredAtMs)
                .thenComparingInt(entry -> entry.renderKey));
        return result;
    }

    boolean isShowing() {
        return !closed && root != null && root.getParent() != null;
    }

    void show() {
        if (closed || entries.size() < 2) return;
        root = LayoutInflater.from(activity).inflate(
                R.layout.modern_intake_event_cluster, null, false);
        sheet = root.findViewById(R.id.intake_event_cluster_sheet);

        title = root.findViewById(R.id.intake_event_cluster_title);
        subtitle = root.findViewById(R.id.intake_event_cluster_subtitle);
        list = root.findViewById(R.id.intake_event_cluster_list);
        renderEntries();

        root.setOnClickListener(view -> close(true));
        sheet.setOnClickListener(view -> { });
        root.findViewById(R.id.intake_event_cluster_close)
                .setOnClickListener(view -> close(true));
        ViewCompat.setAccessibilityPaneTitle(sheet, title.getText());
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            safeInsets = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
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

    private void renderEntries() {
        if (root == null || title == null || subtitle == null || list == null
                || entries.size() < 2) return;
        Resources resources = activity.getResources();
        title.setText(resources.getQuantityString(
                R.plurals.intake_event_cluster_title, entries.size(),
                entries.size()));
        subtitle.setText(timeSummary(entries));
        list.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (Entry entry : entries) {
            View item = inflater.inflate(
                    R.layout.modern_intake_event_cluster_item, list, false);
            bindItem(item, entry);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = ClinicalUi.dp(activity, 10);
            list.addView(item, params);
        }
    }

    private void bindItem(View item, Entry entry) {
        IntakeEvent event = entry.event;
        boolean meal = event.hasMeal();
        boolean insulin = event.hasInsulin();
        TextView kind = item.findViewById(
                R.id.intake_event_cluster_item_kind);
        TextView name = item.findViewById(
                R.id.intake_event_cluster_item_name);
        TextView meta = item.findViewById(
                R.id.intake_event_cluster_item_meta);
        TextView absorption = item.findViewById(
                R.id.intake_event_cluster_item_absorption);

        kind.setText(kindResource(event, meal, insulin));
        kind.setTextColor(accentColor(event, meal));
        item.findViewById(R.id.intake_event_cluster_item_accent)
                .setBackgroundColor(accentColor(event, meal));
        CharSequence displayName = displayName(event, meal, insulin);
        name.setText(displayName);
        String amount = displayAmount(event, meal, insulin);
        String recorded = dateTime(event.occurredAtMs);
        meta.setText(activity.getString(R.string.intake_event_cluster_meta,
                amount, recorded));

        if (meal && event.hasAbsorptionSpeed()) {
            absorption.setVisibility(VISIBLE);
            absorption.setText(activity.getString(
                    R.string.intake_event_cluster_absorption,
                    CarbAbsorptionUi.compact(activity,
                            event.absorptionSpeed)));
        } else {
            absorption.setVisibility(GONE);
        }
        CharSequence accessibleDetails = absorption.getVisibility() == VISIBLE
                ? TextUtils.concat(kind.getText(), ", ", meta.getText(), ", ",
                        absorption.getText())
                : TextUtils.concat(kind.getText(), ", ", meta.getText());
        item.setContentDescription(activity.getString(
                R.string.intake_event_cluster_open, displayName,
                accessibleDetails));
        item.setOnClickListener(view -> openDetails(entry.renderKey));
    }

    private int kindResource(IntakeEvent event, boolean meal,
            boolean insulin) {
        if (meal && insulin) return R.string.intake_event_details_kind_combined;
        if (meal) return R.string.intake_event_details_kind_meal;
        if (IntakeEventDetailsSheet.isLongInsulin(event)) {
            return R.string.intake_event_details_kind_long;
        }
        if (IntakeEventDetailsSheet.isRapidInsulin(event)) {
            return R.string.intake_event_details_kind_rapid;
        }
        return R.string.intake_event_details_kind_insulin;
    }

    private CharSequence displayName(IntakeEvent event, boolean meal,
            boolean insulin) {
        String mealName = event.mealText.isEmpty()
                ? activity.getString(R.string.intake_event_details_meal_name)
                : event.mealText;
        String insulinName = event.insulinDisplayName();
        if (insulinName.isEmpty()) {
            insulinName = activity.getString(
                    R.string.intake_event_details_insulin_name);
        }
        return meal && insulin ? TextUtils.concat(mealName, "  +  ",
                insulinName) : meal ? mealName : insulinName;
    }

    private String displayAmount(IntakeEvent event, boolean meal,
            boolean insulin) {
        String carbs = IntakeEventDetailsSheet.formatNumber(event.carbsGrams);
        String units = IntakeEventDetailsSheet.formatNumber(event.insulinUnits);
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

    private int accentColor(IntakeEvent event, boolean meal) {
        if (meal) return Color.rgb(242, 169, 59);
        if (IntakeEventDetailsSheet.isRapidInsulin(event)) {
            return Color.rgb(85, 200, 242);
        }
        if (IntakeEventDetailsSheet.isLongInsulin(event)) {
            return Color.rgb(182, 154, 245);
        }
        return Color.rgb(145, 165, 184);
    }

    private String timeSummary(List<Entry> values) {
        long first = values.get(0).event.occurredAtMs;
        long last = values.get(values.size() - 1).event.occurredAtMs;
        if (first / 1000L == last / 1000L) {
            return activity.getString(R.string.intake_event_cluster_same_time,
                    dateTime(first));
        }
        return activity.getString(R.string.intake_event_cluster_time_range,
                dateTime(first), dateTime(last));
    }

    private String dateTime(long timestampMs) {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM,
                DateFormat.MEDIUM, Locale.getDefault())
                .format(new Date(timestampMs));
    }

    private void openDetails(int renderKey) {
        if (closed) return;
        activity.showIntakeEvent(renderKey);
    }

    /** Refreshes/removes a row after returning from its details/delete flow. */
    void onEventDetailsClosed() {
        if (closed) return;
        int[] renderKeys = new int[entries.size()];
        for (int index = 0; index < entries.size(); index++) {
            renderKeys[index] = entries.get(index).renderKey;
        }
        List<Entry> current = resolve(repository, renderKeys);
        entries.clear();
        entries.addAll(current);
        if (entries.size() < 2) {
            close(true);
            return;
        }
        renderEntries();
        ViewCompat.setAccessibilityPaneTitle(sheet, title.getText());
    }

    private void handleSystemBack() {
        if (closed) return;
        if (MainActivity.isConfigurationBackDrain()) {
            dismiss(false);
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
                + ClinicalUi.dp(activity, landscape ? 10 : 56);
        params.bottomMargin = safeInsets.bottom;
        sheet.setLayoutParams(params);
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
        if (closed) return;
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
        activity.onIntakeEventClusterClosed(this);
        activity.lightBars(false);
    }
}
