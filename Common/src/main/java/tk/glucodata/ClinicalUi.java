package tk.glucodata;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;
import androidx.core.view.ViewCompat;

/**
 * Small code-native component kit for Juggluco's programmatically built phone
 * screens. It keeps legacy handlers and data bindings while replacing the old
 * row-engine presentation with one clinical, accessible visual language.
 */
public final class ClinicalUi {
    public enum ButtonRole { PRIMARY, SECONDARY, DANGER }

    static final int READABLE_WIDTH_BREAKPOINT_DP = 600;
    static final int READABLE_MIN_GUTTER_DP = 32;
    static final int READABLE_MAX_CONTENT_DP = 840;

    private ClinicalUi() {}

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /**
     * Pure large-window policy. Compact windows keep their existing gutter;
     * Fold/tablet windows gain breathing room and cap readable content near
     * 840dp without assuming a particular display density.
     */
    public static int readableHorizontalGutterDp(int availableWidthDp,
            int compactGutterDp) {
        int compact = Math.max(0, compactGutterDp);
        if (availableWidthDp < READABLE_WIDTH_BREAKPOINT_DP) return compact;
        int widthCapGutter = Math.max(0,
                (availableWidthDp - READABLE_MAX_CONTENT_DP + 1) / 2);
        return Math.max(Math.max(compact, READABLE_MIN_GUTTER_DP),
                widthCapGutter);
    }

    /** Converts the pure policy to pixels using the current window width. */
    public static int readableHorizontalGutter(Context context,
            int availableWidthPx, int compactGutterDp) {
        float density = context.getResources().getDisplayMetrics().density;
        int availableWidthDp = availableWidthPx > 0 && density > 0f
                ? (int) Math.floor(availableWidthPx / density)
                : context.getResources().getConfiguration().screenWidthDp;
        return dp(context, readableHorizontalGutterDp(availableWidthDp,
                compactGutterDp));
    }

    /** Re-dispatches Insets when a Fold, split window or rotation changes width. */
    public static void reapplyInsetsOnWidthChanges(View root) {
        root.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft) {
                ViewCompat.requestApplyInsets(view);
            }
        });
    }

    public static int window(Context context) {
        return util.getColorFromTheme(context, android.R.attr.windowBackground);
    }

    public static int primaryText(Context context) {
        return util.getColorFromTheme(context, android.R.attr.textColorPrimary);
    }

    public static int secondaryText(Context context) {
        int themed = util.getColorFromTheme(context, android.R.attr.textColorSecondary);
        return themed == 0 ? blend(primaryText(context), window(context), 0.64f) : themed;
    }

    public static int accent(Context context) {
        int themed = util.getColorFromTheme(context, R.attr.colorControlActivated);
        return themed == 0 ? 0xFF5ACB85 : themed;
    }

    public static int danger(Context context) {
        return 0xFFF06B65;
    }

    public static int blend(int foreground, int background, float foregroundAmount) {
        float backgroundAmount = 1f - foregroundAmount;
        return Color.rgb(
                Math.round(Color.red(foreground) * foregroundAmount
                        + Color.red(background) * backgroundAmount),
                Math.round(Color.green(foreground) * foregroundAmount
                        + Color.green(background) * backgroundAmount),
                Math.round(Color.blue(foreground) * foregroundAmount
                        + Color.blue(background) * backgroundAmount)
        );
    }

    private static GradientDrawable shape(
            Context context,
            int fill,
            int radiusDp,
            int stroke,
            int strokeColor
    ) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(context, radiusDp));
        if (stroke > 0) drawable.setStroke(dp(context, stroke), strokeColor);
        return drawable;
    }

    public static android.graphics.drawable.Drawable surface(
            Context context,
            boolean elevated,
            boolean interactive
    ) {
        int window = window(context);
        int primary = primaryText(context);
        int fill = blend(primary, window, elevated ? 0.075f : 0.052f);
        int border = blend(primary, window, 0.14f);
        int radius = elevated ? 22 : 16;
        GradientDrawable content = shape(context, fill, radius, 1, border);
        if (!interactive) return content;
        GradientDrawable mask = shape(context, Color.WHITE, radius, 0, Color.TRANSPARENT);
        int ripple = blend(accent(context), window, 0.30f);
        return new RippleDrawable(ColorStateList.valueOf(ripple), content, mask);
    }

    public static LinearLayout verticalContent(Context context) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(context, 20), dp(context, 12),
                dp(context, 20), dp(context, 28));
        content.setBackgroundColor(window(context));
        content.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return content;
    }

    public static ScrollView scrollScreen(Context context, View content) {
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setScrollbarFadingEnabled(true);
        scroll.setBackgroundColor(window(context));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    public static TextView title(Context context, CharSequence text) {
        TextView title = new TextView(context);
        title.setText(text);
        title.setTextColor(primaryText(context));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        title.setIncludeFontPadding(false);
        title.setMinHeight(dp(context, 64));
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return title;
    }

    public static TextView sectionLabel(Context context, CharSequence text) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextColor(secondaryText(context));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        label.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        label.setPadding(dp(context, 4), dp(context, 22),
                dp(context, 4), dp(context, 9));
        label.setAllCaps(false);
        return label;
    }

    public static TextView body(Context context, CharSequence text) {
        TextView body = new TextView(context);
        body.setText(text);
        body.setTextColor(secondaryText(context));
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        body.setLineSpacing(0f, 1.16f);
        body.setGravity(Gravity.START);
        return body;
    }

    public static Button button(
            Context context,
            CharSequence text,
            ButtonRole role
    ) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(context, 50));
        button.setPadding(dp(context, 16), dp(context, 8),
                dp(context, 16), dp(context, 8));
        button.setStateListAnimator(null);

        int window = window(context);
        int fill;
        int stroke;
        int textColor;
        if (role == ButtonRole.PRIMARY) {
            fill = accent(context);
            stroke = fill;
            textColor = blend(Color.BLACK, fill, 0.82f);
        } else if (role == ButtonRole.DANGER) {
            fill = blend(danger(context), window, 0.16f);
            stroke = blend(danger(context), window, 0.72f);
            textColor = danger(context);
        } else {
            fill = blend(primaryText(context), window, 0.06f);
            stroke = blend(primaryText(context), window, 0.15f);
            textColor = primaryText(context);
        }
        GradientDrawable content = shape(context, fill, 16, 1, stroke);
        GradientDrawable mask = shape(context, Color.WHITE, 16, 0, Color.TRANSPARENT);
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(blend(textColor, fill, 0.24f)), content, mask));
        button.setTextColor(textColor);
        return button;
    }

    public static LinearLayout header(
            Context context,
            CharSequence text,
            View close
    ) {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(context, 72));
        header.addView(title(context, text));
        if (close != null) {
            ViewGroup.LayoutParams current = close.getLayoutParams();
            close.setLayoutParams(new LinearLayout.LayoutParams(
                    current == null ? ViewGroup.LayoutParams.WRAP_CONTENT : current.width,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            header.addView(close);
        }
        return header;
    }

    public static LinearLayout card(Context context, View... rows) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(surface(context, false, false));
        card.setPadding(dp(context, 6), dp(context, 6),
                dp(context, 6), dp(context, 6));
        card.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        boolean first = true;
        for (View row : rows) {
            if (row == null || row.getVisibility() == View.GONE) continue;
            if (!first) card.addView(divider(context));
            ViewGroup.LayoutParams params = row.getLayoutParams();
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    params == null ? ViewGroup.LayoutParams.WRAP_CONTENT : params.height));
            card.addView(row);
            first = false;
        }
        return card;
    }

    public static View divider(Context context) {
        View divider = new View(context);
        divider.setBackgroundColor(blend(primaryText(context), window(context), 0.13f));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1));
        params.setMarginStart(dp(context, 14));
        params.setMarginEnd(dp(context, 14));
        divider.setLayoutParams(params);
        divider.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return divider;
    }

    public static LinearLayout actionRow(
            Context context,
            CharSequence title,
            CharSequence subtitle
    ) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(context, subtitle == null ? 58 : 70));
        row.setPaddingRelative(dp(context, 16), dp(context, 9),
                dp(context, 14), dp(context, 9));
        row.setBackground(surface(context, false, true));

        LinearLayout copy = new LinearLayout(context);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        copy.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView primary = new TextView(context);
        primary.setText(title);
        primary.setTextColor(primaryText(context));
        primary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        primary.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        primary.setGravity(Gravity.START);
        copy.addView(primary);
        if (subtitle != null && subtitle.length() > 0) {
            TextView secondary = body(context, subtitle);
            secondary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            secondary.setPadding(0, dp(context, 2), 0, 0);
            copy.addView(secondary);
        }
        row.addView(copy);

        TextView chevron = new TextView(context);
        chevron.setText("›");
        chevron.setTextColor(secondaryText(context));
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        chevron.setGravity(Gravity.CENTER);
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(chevron, new LinearLayout.LayoutParams(
                dp(context, 34), ViewGroup.LayoutParams.MATCH_PARENT));
        return row;
    }

    public static LinearLayout fieldRow(
            Context context,
            CharSequence label,
            View... fields
    ) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(context, 64));
        row.setPaddingRelative(dp(context, 16), dp(context, 7),
                dp(context, 10), dp(context, 7));
        TextView text = new TextView(context);
        text.setText(label);
        text.setTextColor(primaryText(context));
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        row.addView(text, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        for (View field : fields) {
            if (field == null) continue;
            if (field instanceof EditText edit) {
                edit.setSingleLine(true);
                edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                edit.setGravity(Gravity.CENTER);
                edit.setMinWidth(dp(context, 72));
            }
            row.addView(field);
        }
        return row;
    }

    public static LinearLayout toggleRow(
            Context context,
            CheckDirectionBox source,
            CharSequence subtitle
    ) {
        CharSequence labelText = source.getText();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(context, subtitle == null ? 58 : 70));
        row.setPaddingRelative(dp(context, 16), dp(context, 8),
                dp(context, 8), dp(context, 8));
        row.setBackground(surface(context, false, true));
        row.setVisibility(source.getVisibility());

        LinearLayout copy = new LinearLayout(context);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        copy.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView label = new TextView(context);
        label.setText(labelText);
        label.setTextColor(primaryText(context));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        label.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        copy.addView(label);
        if (subtitle != null && subtitle.length() > 0) {
            TextView detail = body(context, subtitle);
            detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            detail.setPadding(0, dp(context, 2), 0, 0);
            copy.addView(detail);
        }
        row.addView(copy);

        SwitchCompat toggle = new SwitchCompat(context);
        toggle.setShowText(false);
        toggle.setChecked(source.isChecked());
        toggle.setEnabled(source.isEnabled());
        toggle.setContentDescription(labelText);
        toggle.setMinimumWidth(dp(context, 54));
        toggle.setMinimumHeight(dp(context, 48));
        toggle.setThumbTintList(new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{}
                },
                new int[]{accent(context), secondaryText(context)}));
        toggle.setTrackTintList(new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{}
                },
                new int[]{blend(accent(context), window(context), 0.42f),
                        blend(secondaryText(context), window(context), 0.28f)}));
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (source.isChecked() != checked) source.setChecked(checked);
        });
        row.addView(toggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 48)));
        row.setOnClickListener(view -> toggle.toggle());
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return row;
    }
}
