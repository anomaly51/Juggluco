package tk.glucodata;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import tk.glucodata.settings.AppTheme;

public class DynamicThemeUtils {
private static final String LOG_ID="DynamicThemeUtils";
    private static final int MIN_TOUCH_TARGET_DP = 48;
    private static final int DEFAULT_CORNER_RADIUS_DP = 14;
    private static final int MODAL_CORNER_RADIUS_DP = 24;

    public static int resolveAttributeColor(Context ctx, int attrId, int defaultColor) {
        if (attrId == 0) return defaultColor;
        TypedValue val = new TypedValue();
        
        if (ctx.getTheme().resolveAttribute(attrId, val, true)) {
            try {
                if (val.resourceId != 0) {
                    return ContextCompat.getColor(ctx, val.resourceId);
                }
                if (val.type >= TypedValue.TYPE_FIRST_COLOR_INT && val.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                    return val.data;
                }
            } catch (Exception e) {
                // Fall back
            }
        }
        return defaultColor;
    }

    private static int resolveAttributeDimension(Context ctx, int attrId, int defaultDp) {
        if (attrId != 0) {
            TypedValue val = new TypedValue();
            if (ctx.getTheme().resolveAttribute(attrId, val, true)) {
                if (val.type == TypedValue.TYPE_DIMENSION) {
                    return TypedValue.complexToDimensionPixelSize(
                            val.data,
                            ctx.getResources().getDisplayMetrics()
                    );
                }
                if (val.resourceId != 0) {
                    try {
                        return ctx.getResources().getDimensionPixelSize(val.resourceId);
                    } catch (Exception ignored) {
                        // Use the density-safe fallback below.
                    }
                }
            }
        }
        return dp(ctx, defaultDp);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** Blends towards the background while keeping an opaque, readable state color. */
    private static int blend(int foreground, int background, float foregroundAmount) {
        float backgroundAmount = 1.0f - foregroundAmount;
        return Color.rgb(
                Math.round(Color.red(foreground) * foregroundAmount
                        + Color.red(background) * backgroundAmount),
                Math.round(Color.green(foreground) * foregroundAmount
                        + Color.green(background) * backgroundAmount),
                Math.round(Color.blue(foreground) * foregroundAmount
                        + Color.blue(background) * backgroundAmount)
        );
    }

    private static GradientDrawable createButtonShape(
            int shape,
            int fillColor,
            int radiusPx,
            int strokeWidth,
            int strokeColor
    ) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(shape);
        drawable.setColor(fillColor);
        if (shape != GradientDrawable.OVAL) {
            drawable.setCornerRadius(radiusPx);
        }
        if (strokeWidth > 0) {
            drawable.setStroke(strokeWidth, strokeColor);
        }
        return drawable;
    }

    private static boolean isDark(int color) {
        double red = Color.red(color) / 255.0;
        double green = Color.green(color) / 255.0;
        double blue = Color.blue(color) / 255.0;
        return (0.2126 * red + 0.7152 * green + 0.0722 * blue) < 0.5;
    }

    private static int windowColor(Context context) {
        return resolveAttributeColor(context, android.R.attr.windowBackground, Color.BLACK);
    }

    private static int primaryTextColor(Context context) {
        return resolveAttributeColor(context, android.R.attr.textColorPrimary, Color.WHITE);
    }

    private static int secondaryTextColor(Context context) {
        return resolveAttributeColor(context, android.R.attr.textColorSecondary,
                blend(primaryTextColor(context), windowColor(context), 0.68f));
    }

    private static int accentColor(Context context) {
        return resolveAttributeColor(context, R.attr.colorControlActivated,
                resolveAttributeColor(context, android.R.attr.colorAccent, 0xFF5ACB85));
    }

    private static int neutralControlColor(Context context) {
        return resolveAttributeColor(context, R.attr.colorControlNormal,
                blend(primaryTextColor(context), windowColor(context), 0.58f));
    }

    private static int surfaceColor(Context context, float textAmount) {
        return blend(primaryTextColor(context), windowColor(context), textAmount);
    }

    private static Drawable createSurface(Context context, boolean modal, boolean interactive) {
        int window = windowColor(context);
        boolean dark = isDark(window);
        int fill = surfaceColor(context, modal ? (dark ? 0.075f : 0.045f)
                : (dark ? 0.055f : 0.03f));
        int border = surfaceColor(context, dark ? 0.14f : 0.10f);
        int radius = dp(context, modal ? MODAL_CORNER_RADIUS_DP : DEFAULT_CORNER_RADIUS_DP);

        GradientDrawable content = createButtonShape(
                GradientDrawable.RECTANGLE, fill, radius, dp(context, 1), border);
        if (!interactive) return content;

        GradientDrawable mask = createButtonShape(
                GradientDrawable.RECTANGLE, Color.WHITE, radius, 0, Color.TRANSPARENT);
        int highlight = resolveAttributeColor(context, R.attr.colorControlHighlight,
                blend(accentColor(context), window, 0.24f));
        return new RippleDrawable(ColorStateList.valueOf(highlight), content, mask);
    }

    private static boolean isReplaceableBackground(Drawable drawable) {
        return drawable == null || drawable instanceof ColorDrawable;
    }

    /**
     * Gives every legacy phone overlay the same screen/modal surface before its
     * individual controls are themed. Purpose-built XML backgrounds are kept.
     */
    public static void applyOverlayTheme(View root, ViewGroup.LayoutParams params) {
        int radius = Natives.getradius();
        boolean isOval = Natives.getisOval();
        applyOverlayTheme(root, params, radius, isOval);
    }

    static void applyOverlayTheme(
            View root,
            ViewGroup.LayoutParams params,
            int radius,
            boolean isOval
    ) {
        if (root == null) return;
        boolean fullScreen = params != null
                && (params.width == ViewGroup.LayoutParams.MATCH_PARENT
                || params.height == ViewGroup.LayoutParams.MATCH_PARENT);
        if (isReplaceableBackground(root.getBackground())) {
            if (fullScreen) {
                root.setBackgroundColor(windowColor(root.getContext()));
                root.setElevation(0f);
                root.setClipToOutline(false);
            } else {
                root.setBackground(createSurface(root.getContext(), true, false));
                root.setElevation(dp(root.getContext(), 12));
                root.setClipToOutline(true);
            }
        }
        traverseAndStyle(root, radius, isOval);
    }

    public static InsetDrawable createDynamicButton(Context ctx, int radiusDp, boolean isOval) {
        int bgId = R.attr.colorButtonBackground;
        int strokeId = R.attr.colorButtonStroke;
        int strokeWidthId = R.attr.buttonStrokeWidth;
        int normId = R.attr.colorControlNormal;
        int activeId = R.attr.colorControlActivated;
        int highId = R.attr.colorControlHighlight;

        int nativeBtnColor = resolveAttributeColor(ctx, android.R.attr.colorButtonNormal, Color.LTGRAY);
        int cBg = resolveAttributeColor(ctx, bgId, nativeBtnColor);
        int cNorm = resolveAttributeColor(ctx, normId, Color.GRAY);
        int cActive = resolveAttributeColor(ctx, activeId, cNorm);
        int cStroke = resolveAttributeColor(ctx, strokeId, cActive);
        int cHigh = resolveAttributeColor(ctx, highId, 0x33FFFFFF);
        int cWindow = resolveAttributeColor(ctx, android.R.attr.windowBackground, Color.BLACK);

        float density = ctx.getResources().getDisplayMetrics().density;
        int radiusPx = (int) (radiusDp * density);
        int strokeWidth = resolveAttributeDimension(ctx, strokeWidthId, 1);
        int focusStrokeWidth = Math.max(strokeWidth, dp(ctx, 2));
        int shape = isOval ? GradientDrawable.OVAL : GradientDrawable.RECTANGLE;

        GradientDrawable normalShape = createButtonShape(
                shape, cBg, radiusPx, strokeWidth, cStroke);
        GradientDrawable focusedShape = createButtonShape(
                shape, cBg, radiusPx, focusStrokeWidth, cActive);
        GradientDrawable disabledShape = createButtonShape(
                shape,
                blend(cBg, cWindow, 0.38f),
                radiusPx,
                strokeWidth,
                blend(cStroke, cWindow, 0.38f)
        );

        StateListDrawable stateList = new StateListDrawable();
        stateList.setEnterFadeDuration(120);
        stateList.setExitFadeDuration(120);
        stateList.addState(new int[]{-android.R.attr.state_enabled}, disabledShape);
        stateList.addState(new int[]{android.R.attr.state_focused}, focusedShape);
        stateList.addState(new int[]{android.R.attr.state_activated}, focusedShape);
        stateList.addState(new int[]{}, normalShape);

        GradientDrawable mask = new GradientDrawable();
        mask.setShape(shape);
        mask.setColor(Color.WHITE);
        if (!isOval) mask.setCornerRadius(radiusPx);

        ColorStateList rippleColors = new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_pressed},
                        new int[]{android.R.attr.state_focused},
                        new int[]{}
                },
                new int[]{cHigh, cHigh, Color.TRANSPARENT}
        );
        RippleDrawable ripple = new RippleDrawable(rippleColors, stateList, mask);

        int insetH = (int) (4 * density);
        int insetV = (int) (4 * density);
        
        return new InsetDrawable(ripple, insetH, insetV, insetH, insetV);
    }

    public static void applyTheme(View root) {
        int radius = Natives.getradius();
        boolean isOval = Natives.getisOval();
        applyTheme(root,radius,isOval);
       }
    public static void applyTheme(View root,int radius,boolean isOval) {
        traverseAndStyle(root, radius, isOval);
       }

    private static ColorStateList controlTint(Context context) {
        int normal = neutralControlColor(context);
        int active = accentColor(context);
        int window = windowColor(context);
        return new ColorStateList(
                new int[][]{
                        new int[]{-android.R.attr.state_enabled},
                        new int[]{android.R.attr.state_checked},
                        new int[]{android.R.attr.state_focused},
                        new int[]{}
                },
                new int[]{blend(normal, window, 0.38f), active, active, normal}
        );
    }

    private static ColorStateList switchTrackTint(Context context) {
        int normal = neutralControlColor(context);
        int active = accentColor(context);
        int window = windowColor(context);
        return new ColorStateList(
                new int[][]{
                        new int[]{-android.R.attr.state_enabled},
                        new int[]{android.R.attr.state_checked},
                        new int[]{}
                },
                new int[]{blend(normal, window, 0.16f), blend(active, window, 0.42f),
                        blend(normal, window, 0.28f)}
        );
    }

    private static boolean isNeutralTextColor(Context context, int color) {
        if (Color.alpha(color) == 0) return false;
        int rgb = color | 0xFF000000;
        int primary = primaryTextColor(context) | 0xFF000000;
        int secondary = secondaryTextColor(context) | 0xFF000000;
        return rgb == (Color.BLACK | 0xFF000000)
                || rgb == (Color.WHITE | 0xFF000000)
                || rgb == (Color.GRAY | 0xFF000000)
                || rgb == (Color.DKGRAY | 0xFF000000)
                || rgb == (Color.LTGRAY | 0xFF000000)
                || rgb == primary
                || rgb == secondary;
    }

    private static void styleTextView(TextView text) {
        Context context = text.getContext();
        float scaledDensity = context.getResources().getDisplayMetrics().scaledDensity;
        float sizeSp = scaledDensity == 0f ? 16f : text.getTextSize() / scaledDensity;

        if (sizeSp >= 22f) {
            text.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            text.setLetterSpacing(-0.01f);
        } else if (sizeSp >= 17f || text.isClickable()) {
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            text.setLetterSpacing(0f);
        } else {
            text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        }
        text.setLineSpacing(0f, 1.08f);

        ColorStateList colors = text.getTextColors();
        if (colors != null && !colors.isStateful()
                && isNeutralTextColor(context, colors.getDefaultColor())) {
            text.setTextColor(text.isEnabled()
                    ? primaryTextColor(context)
                    : secondaryTextColor(context));
        }

        if (text.isClickable()) {
            text.setMinimumHeight(dp(context, MIN_TOUCH_TARGET_DP));
            text.setGravity((text.getGravity() & Gravity.HORIZONTAL_GRAVITY_MASK)
                    | Gravity.CENTER_VERTICAL);
            if (isReplaceableBackground(text.getBackground())) {
                text.setBackground(createSurface(context, false, true));
                text.setPadding(
                        Math.max(text.getPaddingLeft(), dp(context, 16)),
                        Math.max(text.getPaddingTop(), dp(context, 10)),
                        Math.max(text.getPaddingRight(), dp(context, 16)),
                        Math.max(text.getPaddingBottom(), dp(context, 10))
                );
            }
        }
    }

    private static void traverseAndStyle(View view, int radius, boolean isOval) {
        Context context = view.getContext();
        if (view instanceof Button && !(view instanceof CompoundButton)) {
            int pL = view.getPaddingLeft();
            int pT = view.getPaddingTop();
            int pR = view.getPaddingRight();
            int pB = view.getPaddingBottom();

            view.setBackground(createDynamicButton(context, radius, isOval));

            view.setPadding(pL, pT, pR, pB);
            view.setMinimumHeight(dp(context, MIN_TOUCH_TARGET_DP));
            view.setMinimumWidth(dp(context, 64));

            Button button = (Button) view;
            button.setAllCaps(false);
            button.setGravity(Gravity.CENTER);
            button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            button.setLetterSpacing(0.01f);

            int textId = R.attr.colorButtonText;
            int textColor = resolveAttributeColor(
                    context, textId,
                    resolveAttributeColor(context, android.R.attr.textColorPrimary, Color.WHITE));
            int windowColor = resolveAttributeColor(context, android.R.attr.windowBackground, Color.BLACK);
            button.setTextColor(new ColorStateList(
                    new int[][]{
                            new int[]{-android.R.attr.state_enabled},
                            new int[]{}
                    },
                    new int[]{blend(textColor, windowColor, 0.38f), textColor}
            ));
        } else if (view instanceof EditText) {
            int pL = Math.max(view.getPaddingLeft(), dp(context, 12));
            int pT = Math.max(view.getPaddingTop(), dp(context, 8));
            int pR = Math.max(view.getPaddingRight(), dp(context, 12));
            int pB = Math.max(view.getPaddingBottom(), dp(context, 8));
            view.setBackgroundResource(R.drawable.modern_component_text_field);
            view.setPadding(pL, pT, pR, pB);
            view.setMinimumHeight(dp(context, MIN_TOUCH_TARGET_DP));
        } else if (view instanceof Spinner) {
            int pL = Math.max(view.getPaddingLeft(), dp(context, 12));
            int pT = view.getPaddingTop();
            int pR = Math.max(view.getPaddingRight(), dp(context, 44));
            int pB = view.getPaddingBottom();
            view.setBackgroundResource(R.drawable.modern_component_spinner);
            view.setPadding(pL, pT, pR, pB);
            view.setMinimumHeight(dp(context, MIN_TOUCH_TARGET_DP));
        } else if (view instanceof SwitchCompat) {
            SwitchCompat control = (SwitchCompat) view;
            control.setThumbTintList(controlTint(context));
            control.setTrackTintList(switchTrackTint(context));
            control.setGravity(Gravity.CENTER_VERTICAL);
            control.setMinimumHeight(dp(context, MIN_TOUCH_TARGET_DP));
            control.setMinimumWidth(dp(context, MIN_TOUCH_TARGET_DP));
        } else if (view instanceof Switch) {
            Switch control = (Switch) view;
            control.setThumbTintList(controlTint(context));
            control.setTrackTintList(switchTrackTint(context));
            control.setGravity(Gravity.CENTER_VERTICAL);
            control.setMinimumHeight(dp(context, MIN_TOUCH_TARGET_DP));
            control.setMinimumWidth(dp(context, MIN_TOUCH_TARGET_DP));
        } else if (view instanceof CompoundButton) {
            CompoundButton control = (CompoundButton) view;
            control.setButtonTintList(controlTint(context));
            control.setGravity(Gravity.CENTER_VERTICAL);
            control.setMinimumHeight(dp(context, MIN_TOUCH_TARGET_DP));
            control.setMinimumWidth(dp(context, MIN_TOUCH_TARGET_DP));
        } else if (view instanceof SeekBar) {
            SeekBar seekBar = (SeekBar) view;
            seekBar.setProgressTintList(ColorStateList.valueOf(accentColor(context)));
            seekBar.setThumbTintList(ColorStateList.valueOf(accentColor(context)));
            seekBar.setProgressBackgroundTintList(
                    ColorStateList.valueOf(surfaceColor(context, 0.16f)));
            seekBar.setMinimumHeight(dp(context, MIN_TOUCH_TARGET_DP));
        } else if (view instanceof ProgressBar) {
            ProgressBar progress = (ProgressBar) view;
            progress.setIndeterminateTintList(ColorStateList.valueOf(accentColor(context)));
            progress.setProgressTintList(ColorStateList.valueOf(accentColor(context)));
        } else if (view instanceof ImageButton) {
            ImageButton button = (ImageButton) view;
            button.setBackground(createSurface(context, false, true));
            button.setImageTintList(ColorStateList.valueOf(primaryTextColor(context)));
            button.setMinimumHeight(dp(context, MIN_TOUCH_TARGET_DP));
            button.setMinimumWidth(dp(context, MIN_TOUCH_TARGET_DP));
            int pad = dp(context, 12);
            button.setPadding(pad, pad, pad, pad);
        } else if (view instanceof ImageView && view.isClickable()) {
            ImageView image = (ImageView) view;
            if (isReplaceableBackground(image.getBackground())) {
                image.setBackground(createSurface(context, false, true));
            }
            image.setImageTintList(ColorStateList.valueOf(primaryTextColor(context)));
            image.setMinimumHeight(dp(context, MIN_TOUCH_TARGET_DP));
            image.setMinimumWidth(dp(context, MIN_TOUCH_TARGET_DP));
        } else if (view instanceof TextView) {
            styleTextView((TextView) view);
        }

        if (view instanceof ListView) {
            ListView list = (ListView) view;
            list.setDivider(new ColorDrawable(surfaceColor(context, 0.14f)));
            list.setDividerHeight(dp(context, 1));
            list.setSelector(createSurface(context, false, true));
            list.setCacheColorHint(windowColor(context));
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                traverseAndStyle(group.getChildAt(i), radius, isOval);
            }
        }
    }
static void setTheme(Activity act) {
       int theme=Natives.getTheme();
       Log.i(LOG_ID,"getTheme()="+theme);
       AppTheme[] themes = AppTheme.values();
       if (theme < 0 || theme >= themes.length) {
            Log.w(LOG_ID, "Invalid stored theme index " + theme + "; using the default theme");
            theme = 0;
           }
       act.setTheme(themes[theme].getStyleResId());
       if(Natives.getisOval()) {
            act.getTheme().applyStyle(R.style.ShapeOverride_Oval, true);
           }
       }
}
