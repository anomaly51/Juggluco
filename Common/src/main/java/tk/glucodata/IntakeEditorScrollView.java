package tk.glucodata;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ScrollView;

/** A bounded, independently scrollable quick-entry sheet above the thumb bar. */
public final class IntakeEditorScrollView extends ScrollView {
    public IntakeEditorScrollView(Context context) {
        super(context);
    }

    public IntakeEditorScrollView(Context context, AttributeSet attributes) {
        super(context, attributes);
    }

    public IntakeEditorScrollView(Context context, AttributeSet attributes,
            int style) {
        super(context, attributes, style);
    }

    @Override protected void onMeasure(int widthMeasureSpec,
            int heightMeasureSpec) {
        int available = MeasureSpec.getSize(heightMeasureSpec);
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            available = getResources().getDisplayMetrics().heightPixels;
        }
        // Use the available (already IME/inset-adjusted) height, not a fixed
        // phone height. The sticky 56dp Save action plus its 8dp gap consumes
        // part of the same budget; it must never push Back off a short screen.
        int footerReserve = Math.round(64 * getResources()
                .getDisplayMetrics().density);
        if (getParent() instanceof android.view.ViewGroup) {
            android.view.View footer = ((android.view.ViewGroup) getParent())
                    .findViewById(R.id.intake_quick_submit_bar);
            if (footer != null) {
                footerReserve = Math.max(footerReserve, footer.getMeasuredHeight()
                        + Math.round(8 * getResources().getDisplayMetrics().density));
            }
        }
        int cap = Math.min(Math.round(available * 0.58f) - footerReserve,
                Math.round(440 * getResources().getDisplayMetrics().density));
        super.onMeasure(widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(Math.max(0, cap), MeasureSpec.AT_MOST));
    }
}
