package tk.glucodata;

import android.graphics.Typeface;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

/** Shared phone presentation helpers for connection and data-transfer screens. */
public final class ConnectionUi {
    public static final int MIN_TOUCH_DP = 48;

    private ConnectionUi() {}

    public static LinearLayout content(MainActivity context) {
        LinearLayout content=ClinicalUi.verticalContent(context);
        content.setPaddingRelative(
                MainActivity.systembarLeft+ClinicalUi.dp(context,20),
                MainActivity.systembarTop+ClinicalUi.dp(context,8),
                MainActivity.systembarRight+ClinicalUi.dp(context,20),
                MainActivity.systembarBottom+ClinicalUi.dp(context,30));
        return content;
    }

    public static Button headerButton(MainActivity context,int label) {
        Button button=ClinicalUi.button(context,context.getString(label),
                ClinicalUi.ButtonRole.SECONDARY);
        button.setMinimumHeight(ClinicalUi.dp(context,MIN_TOUCH_DP));
        button.setMinWidth(ClinicalUi.dp(context,68));
        return button;
    }

    public static void styleInput(EditText input) {
        MainActivity context=(MainActivity)input.getContext();
        input.setSingleLine(true);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP,16);
        input.setTextColor(ClinicalUi.primaryText(context));
        input.setHintTextColor(ClinicalUi.secondaryText(context));
        input.setGravity(Gravity.CENTER_VERTICAL);
        input.setMinimumHeight(ClinicalUi.dp(context,52));
        input.setPaddingRelative(ClinicalUi.dp(context,13),0,
                ClinicalUi.dp(context,13),0);
        input.setBackground(ClinicalUi.surface(context,false,true));
    }

    public static LinearLayout field(MainActivity context,CharSequence label,EditText input) {
        styleInput(input);
        return ClinicalUi.fieldRow(context,label,input);
    }

    public static CheckDirectionBox directToggle(MainActivity context,
            CheckDirectionBox toggle) {
        toggle.setMinimumHeight(ClinicalUi.dp(context,64));
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP,16);
        toggle.setTextColor(ClinicalUi.primaryText(context));
        toggle.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
        toggle.setPaddingRelative(ClinicalUi.dp(context,16),ClinicalUi.dp(context,8),
                ClinicalUi.dp(context,12),ClinicalUi.dp(context,8));
        toggle.setBackground(ClinicalUi.surface(context,false,true));
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.LOLLIPOP)
            toggle.setButtonTintList(ContextCompat.getColorStateList(context,
                    R.color.modern_settings_choice_control));
        return toggle;
    }

    public static <T extends TextView> T choice(MainActivity context,T choice) {
        choice.setMinimumHeight(ClinicalUi.dp(context,MIN_TOUCH_DP));
        choice.setTextSize(TypedValue.COMPLEX_UNIT_SP,15);
        choice.setTextColor(ClinicalUi.primaryText(context));
        choice.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
        choice.setPaddingRelative(ClinicalUi.dp(context,12),ClinicalUi.dp(context,6),
                ClinicalUi.dp(context,12),ClinicalUi.dp(context,6));
        return choice;
    }

    public static Button styleButton(MainActivity context,Button target,
            ClinicalUi.ButtonRole role) {
        Button model=ClinicalUi.button(context,target.getText(),role);
        target.setAllCaps(false);
        target.setTextSize(TypedValue.COMPLEX_UNIT_SP,15);
        target.setTextColor(model.getTextColors());
        target.setGravity(Gravity.CENTER);
        target.setMinimumHeight(ClinicalUi.dp(context,50));
        target.setPaddingRelative(ClinicalUi.dp(context,16),ClinicalUi.dp(context,8),
                ClinicalUi.dp(context,16),ClinicalUi.dp(context,8));
        target.setBackground(model.getBackground());
        target.setStateListAnimator(null);
        return target;
    }

    public static TextView intro(MainActivity context,int stringId) {
        TextView text=ClinicalUi.body(context,context.getString(stringId));
        text.setPaddingRelative(ClinicalUi.dp(context,4),0,ClinicalUi.dp(context,4),
                ClinicalUi.dp(context,6));
        return text;
    }

    public static TextView status(MainActivity context,CharSequence text,boolean error) {
        TextView status=ClinicalUi.body(context,text);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP,14);
        status.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        status.setTextColor(error?ClinicalUi.danger(context):ClinicalUi.secondaryText(context));
        status.setPadding(ClinicalUi.dp(context,16),ClinicalUi.dp(context,12),
                ClinicalUi.dp(context,16),ClinicalUi.dp(context,12));
        status.setBackground(ClinicalUi.surface(context,false,false));
        status.setVisibility(text==null||text.length()==0?View.GONE:View.VISIBLE);
        return status;
    }

    public static ScrollView screen(MainActivity context,View content) {
        return ClinicalUi.scrollScreen(context,content);
    }

    public static void fullScreen(MainActivity context,View screen) {
        context.addMyContentView(screen,new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** A focused, code-native confirmation sheet for connection and transfer flows. */
    public static void confirmSheet(MainActivity context,View parent,CharSequence title,
            CharSequence message,CharSequence confirmLabel,ClinicalUi.ButtonRole confirmRole,
            Runnable confirmed) {
        if(parent!=null)
            RingTones.EnableControls(parent,false);
        FrameLayout overlay=new FrameLayout(context);
        overlay.setClickable(true);
        overlay.setFocusable(true);
        overlay.setBackgroundColor(0xB8000000);

        LinearLayout sheet=new LinearLayout(context);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(context,20),dp(context,10),dp(context,20),
                dp(context,20)+MainActivity.systembarBottom);
        sheet.setBackground(ClinicalUi.surface(context,true,false));

        View handle=new View(context);
        handle.setBackgroundColor(ClinicalUi.blend(ClinicalUi.primaryText(context),
                ClinicalUi.window(context),0.28f));
        LinearLayout.LayoutParams handleParams=new LinearLayout.LayoutParams(dp(context,42),
                dp(context,4));
        handleParams.gravity=Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin=dp(context,10);
        sheet.addView(handle,handleParams);

        TextView heading=ClinicalUi.title(context,title);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP,24);
        heading.setMinHeight(dp(context,48));
        heading.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        sheet.addView(heading);
        TextView copy=ClinicalUi.body(context,message);
        copy.setTextSize(TypedValue.COMPLEX_UNIT_SP,15);
        copy.setPadding(0,dp(context,4),0,dp(context,18));
        sheet.addView(copy);

        LinearLayout actions=new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button cancel=ClinicalUi.button(context,context.getString(R.string.cancel),
                ClinicalUi.ButtonRole.SECONDARY);
        Button confirm=ClinicalUi.button(context,confirmLabel,confirmRole);
        actions.addView(cancel,new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT,1f));
        View gap=new View(context);
        actions.addView(gap,new LinearLayout.LayoutParams(dp(context,12),1));
        actions.addView(confirm,new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT,1f));
        sheet.addView(actions);

        FrameLayout.LayoutParams sheetParams=new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        sheetParams.leftMargin=MainActivity.systembarLeft+dp(context,10);
        sheetParams.rightMargin=MainActivity.systembarRight+dp(context,10);
        sheetParams.bottomMargin=dp(context,10);
        overlay.addView(sheet,sheetParams);
        fullScreen(context,overlay);

        Runnable dismiss=()-> {
            tk.glucodata.settings.Settings.removeContentView(overlay);
            if(parent!=null)
                RingTones.EnableControls(parent,true);
            };
        MainActivity.setonback(dismiss);
        cancel.setOnClickListener(view->MainActivity.doonback());
        confirm.setOnClickListener(view-> {
            MainActivity.poponback();
            dismiss.run();
            confirmed.run();
            });
    }

    private static int dp(MainActivity context,float value) {
        return ClinicalUi.dp(context,value);
    }
}
