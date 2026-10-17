package tk.glucodata;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatImageView;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
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
    static final int RETRO_CONTROL_CORNER_RADIUS_DP = 2;
    static final int RETRO_CARD_CORNER_RADIUS_DP = 4;
    // Compatibility name shared with the alarm surfaces: compact controls keep
    // the original two-pixel terminal geometry.
    static final int RETRO_CORNER_RADIUS_DP = RETRO_CONTROL_CORNER_RADIUS_DP;
    static final int FOCUS_STROKE_DP = 2;
    private static final String RETRO_FONT_FAMILY = "monospace";

    private ClinicalUi() {}

    static Typeface retroTypeface(int style) {
        return Typeface.create(RETRO_FONT_FAMILY, style);
    }

    private static int controlCornerRadius(Context context) {
        return dp(context, RETRO_CONTROL_CORNER_RADIUS_DP);
    }

    private static int cardCornerRadius(Context context) {
        return dp(context, RETRO_CARD_CORNER_RADIUS_DP);
    }

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
        return color(context, R.color.lifeprompt_background,
                util.getColorFromTheme(context, android.R.attr.windowBackground));
    }

    public static int primaryText(Context context) {
        return color(context, R.color.lifeprompt_on_background,
                util.getColorFromTheme(context, android.R.attr.textColorPrimary));
    }

    public static int secondaryText(Context context) {
        return color(context, R.color.lifeprompt_on_surface_variant,
                blend(primaryText(context), window(context), 0.68f));
    }

    public static int accent(Context context) {
        return color(context, R.color.lifeprompt_primary,
                util.getColorFromTheme(context, R.attr.colorControlActivated));
    }

    public static int danger(Context context) {
        return color(context, R.color.lifeprompt_error, 0xFFBA1A1A);
    }

    private static int color(Context context, int colorResource, int fallback) {
        try {
            return ContextCompat.getColor(context, colorResource);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int dimension(Context context, int dimensionResource,
            float fallbackDp) {
        try {
            return context.getResources().getDimensionPixelSize(dimensionResource);
        } catch (RuntimeException ignored) {
            return dp(context, fallbackDp);
        }
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
            int radius,
            int strokeWidth,
            int strokeColor
    ) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) drawable.setStroke(strokeWidth, strokeColor);
        return drawable;
    }

    /**
     * Stable state layers for keyboard, D-pad and touch input.  Every shape is
     * drawn inside the same bounds, so gaining focus never moves neighbouring
     * content or changes a row's measured size.
     */
    private static Drawable interactiveSurface(
            Context context,
            int fill,
            int pressedFill,
            int radius,
            int strokeWidth,
            int strokeColor,
            int focusColor,
            int rippleColor
    ) {
        int focusStroke = Math.max(dp(context, FOCUS_STROKE_DP), strokeWidth);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused},
                shape(context, pressedFill, radius, focusStroke, focusColor));
        states.addState(new int[]{android.R.attr.state_selected},
                shape(context, pressedFill, radius, focusStroke, focusColor));
        states.addState(new int[]{android.R.attr.state_activated},
                shape(context, pressedFill, radius, focusStroke, focusColor));
        states.addState(new int[]{android.R.attr.state_pressed},
                shape(context, pressedFill, radius, strokeWidth, strokeColor));
        states.addState(new int[]{},
                shape(context, fill, radius, strokeWidth, strokeColor));
        GradientDrawable mask = shape(context, Color.WHITE, radius, 0,
                Color.TRANSPARENT);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), states,
                mask);
    }

    public static android.graphics.drawable.Drawable surface(
            Context context,
            boolean elevated,
            boolean interactive
    ) {
        int fill = color(context, elevated
                        ? R.color.lifeprompt_surface_container_high
                        : R.color.lifeprompt_surface,
                window(context));
        int border = color(context, R.color.lifeprompt_outline_variant,
                blend(primaryText(context), window(context), 0.18f));
        int radius = interactive
                ? controlCornerRadius(context) : cardCornerRadius(context);
        int outline = dimension(context, R.dimen.lifeprompt_outline_width, 1);
        GradientDrawable content = shape(context, fill, radius, outline, border);
        if (!interactive) return content;
        int pressed = color(context, R.color.lifeprompt_surface_container_high,
                blend(accent(context), fill, 0.14f));
        return interactiveSurface(context, fill, pressed, radius, outline,
                border, accent(context), blend(accent(context), fill, 0.20f));
    }

    /** Seamless interactive row used inside an outlined card. */
    public static android.graphics.drawable.Drawable rowSurface(Context context) {
        int fill = color(context, R.color.lifeprompt_surface, window(context));
        int pressed = color(context, R.color.lifeprompt_surface_container_high,
                blend(accent(context), fill, 0.14f));
        int radius = controlCornerRadius(context);
        return interactiveSurface(context, fill, pressed, radius, 0,
                Color.TRANSPARENT, accent(context),
                blend(accent(context), fill, 0.20f));
    }

    /** Outlined compact control surface for fields, chips and small actions. */
    public static android.graphics.drawable.Drawable controlSurface(Context context) {
        int fill = color(context, R.color.lifeprompt_surface_container_low,
                window(context));
        int stroke = color(context, R.color.lifeprompt_outline,
                secondaryText(context));
        int radius = controlCornerRadius(context);
        int outline = dimension(context, R.dimen.lifeprompt_outline_width, 1);
        int pressed = color(context, R.color.lifeprompt_surface_container_high,
                blend(accent(context), fill, 0.14f));
        return interactiveSurface(context, fill, pressed, radius, outline,
                stroke, accent(context), blend(accent(context), fill, 0.20f));
    }

    public static ColorStateList selectionTint(Context context) {
        int normal = secondaryText(context);
        int active = accent(context);
        int background = window(context);
        return new ColorStateList(
                new int[][]{
                        new int[]{-android.R.attr.state_enabled},
                        new int[]{android.R.attr.state_checked},
                        new int[]{}
                },
                new int[]{blend(normal, background, 0.38f), active, normal});
    }

    public static ColorStateList switchTrackTint(Context context) {
        int normal = secondaryText(context);
        int active = accent(context);
        int background = window(context);
        return new ColorStateList(
                new int[][]{
                        new int[]{-android.R.attr.state_enabled},
                        new int[]{android.R.attr.state_checked},
                        new int[]{}
                },
                new int[]{blend(normal, background, 0.16f),
                        blend(active, background, 0.42f),
                        blend(normal, background, 0.28f)});
    }

    public static LinearLayout verticalContent(Context context) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int gutter = dimension(context, R.dimen.lifeprompt_phone_gutter, 16);
        content.setPadding(gutter,
                dimension(context, R.dimen.lifeprompt_space_xs, 8), gutter,
                dimension(context, R.dimen.lifeprompt_space_xl, 24));
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
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackgroundColor(window(context));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    public static TextView title(Context context, CharSequence text) {
        TextView title = new TextView(context);
        title.setText(text);
        title.setTextColor(primaryText(context));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        title.setTypeface(retroTypeface(Typeface.BOLD));
        title.setLetterSpacing(0.015f);
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        title.setIncludeFontPadding(false);
        title.setMinHeight(dp(context, 60));
        ViewCompat.setAccessibilityHeading(title, true);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return title;
    }

    public static TextView sectionLabel(Context context, CharSequence text) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextColor(accent(context));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setTypeface(retroTypeface(Typeface.NORMAL));
        label.setLetterSpacing(0.045f);
        label.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        label.setPadding(dp(context, 4), dp(context, 16),
                dp(context, 4), dp(context, 8));
        label.setAllCaps(false);
        ViewCompat.setAccessibilityHeading(label, true);
        return label;
    }

    public static TextView body(Context context, CharSequence text) {
        TextView body = new TextView(context);
        body.setText(text);
        body.setTextColor(secondaryText(context));
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        body.setTypeface(retroTypeface(Typeface.NORMAL));
        body.setLineSpacing(0f, 1.28f);
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
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setTypeface(retroTypeface(Typeface.NORMAL));
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dimension(context,
                R.dimen.lifeprompt_touch_target, 48));
        button.setPadding(dp(context, 16), dp(context, 8),
                dp(context, 16), dp(context, 8));
        button.setStateListAnimator(null);

        int fill;
        int stroke;
        int pressedFill;
        int focusStroke;
        int textColor;
        if (role == ButtonRole.PRIMARY) {
            fill = accent(context);
            stroke = fill;
            pressedFill = blend(primaryText(context), fill, 0.12f);
            textColor = color(context, R.color.lifeprompt_on_primary,
                    Color.WHITE);
            focusStroke = textColor;
        } else if (role == ButtonRole.DANGER) {
            fill = color(context, R.color.lifeprompt_error_container,
                    blend(danger(context), window(context), 0.16f));
            stroke = danger(context);
            pressedFill = blend(danger(context), fill, 0.22f);
            textColor = danger(context);
            focusStroke = danger(context);
        } else {
            fill = color(context, R.color.lifeprompt_surface_container_low,
                    window(context));
            stroke = color(context, R.color.lifeprompt_outline,
                    secondaryText(context));
            pressedFill = color(context,
                    R.color.lifeprompt_surface_container_high,
                    blend(accent(context), fill, 0.14f));
            textColor = accent(context);
            focusStroke = accent(context);
        }
        int radius = controlCornerRadius(context);
        int outline = dimension(context, R.dimen.lifeprompt_outline_width, 1);
        button.setBackground(interactiveSurface(context, fill, pressedFill,
                radius, outline, stroke, focusStroke,
                blend(textColor, fill, 0.24f)));
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
        header.setMinimumHeight(dp(context, 64));
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
        card.setPadding(dp(context, 4), dp(context, 4),
                dp(context, 4), dp(context, 4));
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
        divider.setBackgroundColor(color(context, R.color.lifeprompt_outline_variant,
                blend(primaryText(context), window(context), 0.13f)));
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
        row.setMinimumHeight(dp(context, subtitle == null ? 56 : 68));
        row.setPaddingRelative(dp(context, 16), dp(context, 8),
                dp(context, 12), dp(context, 8));
        row.setBackground(rowSurface(context));
        String semanticLabel = subtitle == null || subtitle.length() == 0
                ? String.valueOf(title)
                : title + ", " + subtitle;
        row.setContentDescription(semanticLabel);
        row.setFocusable(true);
        row.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_YES);

        LinearLayout copy = new LinearLayout(context);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        copy.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        copy.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

        TextView primary = new TextView(context);
        primary.setText(title);
        primary.setTextColor(primaryText(context));
        primary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        primary.setTypeface(retroTypeface(Typeface.NORMAL));
        primary.setGravity(Gravity.START);
        copy.addView(primary);
        if (subtitle != null && subtitle.length() > 0) {
            TextView secondary = body(context, subtitle);
            secondary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            secondary.setPadding(0, dp(context, 2), 0, 0);
            copy.addView(secondary);
        }
        row.addView(copy);

        ImageView chevron = new AppCompatImageView(context);
        chevron.setImageResource(R.drawable.modern_settings_chevron);
        chevron.setImageTintList(ColorStateList.valueOf(secondaryText(context)));
        if (chevron.getDrawable() != null) chevron.getDrawable().setAutoMirrored(true);
        chevron.setScaleType(ImageView.ScaleType.CENTER);
        chevron.setFocusable(false);
        chevron.setClickable(false);
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(chevron, new LinearLayout.LayoutParams(
                dp(context, 32), ViewGroup.LayoutParams.MATCH_PARENT));
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
        row.setMinimumHeight(dp(context, 60));
        row.setPaddingRelative(dp(context, 16), dp(context, 7),
                dp(context, 10), dp(context, 7));
        TextView text = new TextView(context);
        text.setText(label);
        text.setTextColor(primaryText(context));
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setTypeface(retroTypeface(Typeface.NORMAL));
        text.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        row.addView(text, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        for (View field : fields) {
            if (field == null) continue;
            if (field instanceof EditText edit) {
                edit.setSingleLine(true);
                edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                edit.setTypeface(retroTypeface(Typeface.NORMAL));
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
        row.setMinimumHeight(dp(context, subtitle == null ? 56 : 68));
        row.setPaddingRelative(dp(context, 16), dp(context, 8),
                dp(context, 8), dp(context, 8));
        row.setBackground(rowSurface(context));
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
        label.setTypeface(retroTypeface(Typeface.NORMAL));
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
        toggle.setThumbTintList(selectionTint(context));
        toggle.setTrackTintList(switchTrackTint(context));
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (source.isChecked() != checked) source.setChecked(checked);
        });
        row.addView(toggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 48)));
        row.setEnabled(source.isEnabled());
        row.setAlpha(source.isEnabled() ? 1f : .38f);
        row.setOnClickListener(view -> {
            if (toggle.isEnabled()) toggle.toggle();
        });
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return row;
    }
}
