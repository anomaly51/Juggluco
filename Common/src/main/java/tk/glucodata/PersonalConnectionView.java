package tk.glucodata;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

/** Rendering only: independently measurable without a sensor, network or Activity. */
final class PersonalConnectionView extends LinearLayout {
    final Button back, open, change, primary;
    final TextView status, link;
    final ProgressBar progress;
    final ScrollView scroll;

    PersonalConnectionView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setBackgroundColor(ClinicalUi.window(context));
        setClickable(true);
        setFocusable(true);
        back=ClinicalUi.button(context,context.getString(R.string.settings_back),ClinicalUi.ButtonRole.SECONDARY);
        addView(ClinicalUi.header(context,context.getString(R.string.personal_connection_title),back));
        LinearLayout content=ClinicalUi.verticalContent(context);
        // The screen applies adaptive outer gutters; avoid doubling them here.
        content.setPadding(0,ClinicalUi.dp(context,16),0,ClinicalUi.dp(context,24));
        status=ClinicalUi.body(context,"");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        progress=new ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(ClinicalUi.accent(context)));
        content.addView(status);
        addSpaced(content,progress,context,8);
        addSpaced(content,ClinicalUi.body(context,context.getString(R.string.personal_connection_stages)),context,-2);
        link=ClinicalUi.body(context,"");
        link.setTextIsSelectable(true);
        link.setTextColor(ClinicalUi.accent(context));
        addSpaced(content,ClinicalUi.card(context,link),context,-2);
        addSpaced(content,ClinicalUi.body(context,context.getString(R.string.personal_connection_privacy)),context,-2);
        addSpaced(content,ClinicalUi.body(context,context.getString(R.string.personal_connection_forecast)),context,-2);
        open=ClinicalUi.button(context,context.getString(R.string.personal_connection_open),ClinicalUi.ButtonRole.SECONDARY);
        change=ClinicalUi.button(context,context.getString(R.string.personal_connection_change),ClinicalUi.ButtonRole.SECONDARY);
        addSpaced(content,open,context,-2);
        addSpaced(content,change,context,-2);
        scroll=ClinicalUi.scrollScreen(context,content);
        addView(scroll,new LayoutParams(-1,0,1));
        primary=ClinicalUi.button(context,"",ClinicalUi.ButtonRole.PRIMARY);
        addSpaced(this,primary,context,-2);
    }

    void render(int percent,int message,String url,boolean busy,boolean needsClaim) {
        status.setText(getContext().getString(R.string.personal_connection_progress,percent,getContext().getString(message)));
        progress.setProgress(percent);
        boolean ready=!url.isEmpty()&&percent==100;
        link.setText(!url.isEmpty()?url:getContext().getString(R.string.personal_connection_link_pending));
        open.setEnabled(ready&&!busy);
        change.setEnabled(ready&&!busy);
        primary.setEnabled(!busy);
        primary.setText(ready?R.string.personal_connection_copy:needsClaim?
                R.string.personal_connection_claim:R.string.personal_connection_try);
        for(Button button:new Button[]{open,change,primary}) button.setAlpha(button.isEnabled()?1f:0.5f);
    }

    private static void addSpaced(LinearLayout parent,View child,Context context,int heightDp) {
        LayoutParams params=new LayoutParams(-1,heightDp<0?heightDp:ClinicalUi.dp(context,heightDp));
        params.topMargin=ClinicalUi.dp(context,12);
        parent.addView(child,params);
    }
}
