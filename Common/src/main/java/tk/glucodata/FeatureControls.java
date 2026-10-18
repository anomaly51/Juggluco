package tk.glucodata;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.LinearLayout;

/** Local presentation choices only; never changes records, alarms or sensor work. */
public final class FeatureControls {
    private FeatureControls() {}
    static final String ADD_ENABLED = "add_button_enabled";

    static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences("feature_controls", Context.MODE_PRIVATE);
    }

    public static boolean isAddEnabled(Context context) {
        // Applies equally to first installs and upgrades without an explicit choice.
        return preferences(context).getBoolean(ADD_ENABLED, false);
    }

    static void setAddEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(ADD_ENABLED, enabled).apply();
    }

    public static LinearLayout addButtonSetting(Context context, Runnable changed) {
        CheckDirectionBox source = new CheckDirectionBox(context);
        source.setText(R.string.feature_controls_add_button);
        source.setChecked(isAddEnabled(context));
        source.setOnCheckedChangeListener((button, enabled) -> {
            setAddEnabled(context, enabled);
            changed.run();
        });
        return ClinicalUi.toggleRow(context, source,
                context.getString(R.string.feature_controls_add_hint));
    }

    static void applyToDashboard(View root) {
        View add = root.findViewById(R.id.modern_dashboard_add_intake);
        boolean enabled = isAddEnabled(root.getContext());
        add.setVisibility(enabled ? View.VISIBLE : View.GONE);
        add.setEnabled(enabled);
        add.setFocusable(enabled);
    }
}
