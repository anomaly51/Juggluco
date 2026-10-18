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
    final Button back, open, change, primary, recheck;
    final TextView status, link, timing, reading;
    final TextView[] steps=new TextView[8];
    static final int[] MILESTONES={10,25,40,55,70,85,95,100};
    static final int[] STEP_TITLES={R.string.personal_connection_step_identity,R.string.personal_connection_step_server,
            R.string.personal_connection_step_saved,R.string.personal_connection_step_dns,R.string.personal_connection_step_https,
            R.string.personal_connection_step_page,R.string.personal_connection_step_data,R.string.personal_connection_step_final};
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
        timing=ClinicalUi.body(context,"");
        timing.setTextSize(14);
        addSpaced(content,timing,context,-2);
        LinearLayout checklist=new LinearLayout(context);
        checklist.setOrientation(VERTICAL);
        for(int i=0;i<steps.length;i++) {
            steps[i]=ClinicalUi.body(context,"");
            steps[i].setTextSize(16);
            steps[i].setPadding(0,ClinicalUi.dp(context,8),0,ClinicalUi.dp(context,8));
            checklist.addView(steps[i]);
        }
        addSpaced(content,ClinicalUi.card(context,checklist),context,-2);
        addSpaced(content,ClinicalUi.body(context,context.getString(R.string.personal_connection_stages)),context,-2);
        link=ClinicalUi.body(context,"");
        link.setTextIsSelectable(true);
        link.setTextColor(ClinicalUi.accent(context));
        addSpaced(content,ClinicalUi.card(context,link),context,-2);
        reading=ClinicalUi.body(context,"");
        addSpaced(content,reading,context,-2);
        open=ClinicalUi.button(context,context.getString(R.string.personal_connection_open),ClinicalUi.ButtonRole.SECONDARY);
        change=ClinicalUi.button(context,context.getString(R.string.personal_connection_change),ClinicalUi.ButtonRole.SECONDARY);
        addSpaced(content,open,context,-2);
        addSpaced(content,change,context,-2);
        recheck=ClinicalUi.button(context,context.getString(R.string.personal_connection_recheck),ClinicalUi.ButtonRole.SECONDARY);
        addSpaced(content,recheck,context,-2);
        addSpaced(content,ClinicalUi.body(context,context.getString(R.string.personal_connection_browser_help)),context,-2);
        addSpaced(content,ClinicalUi.body(context,context.getString(R.string.personal_connection_privacy)),context,-2);
        scroll=ClinicalUi.scrollScreen(context,content);
        addView(scroll,new LayoutParams(-1,0,1));
        primary=ClinicalUi.button(context,"",ClinicalUi.ButtonRole.PRIMARY);
        addSpaced(this,primary,context,-2);
    }

    void render(int percent,int message,String url,boolean busy,boolean needsClaim) {
        String title=getContext().getString(R.string.personal_connection_progress,percent,getContext().getString(message));
        if(!title.contentEquals(status.getText())) status.setText(title);
        progress.setProgress(percent);
        boolean current=false;
        for(int i=0;i<steps.length;i++) {
            boolean done=percent>=MILESTONES[i];
            boolean active=!done&&!current;
            if(active) current=true;
            int state=done?R.string.personal_connection_step_done:active?
                    (busy?R.string.personal_connection_step_active:R.string.personal_connection_step_retry):R.string.personal_connection_step_waiting;
            steps[i].setText(getContext().getString(R.string.personal_connection_step_format,i+1,
                    getContext().getString(STEP_TITLES[i]),getContext().getString(state),MILESTONES[i]));
            steps[i].setTextColor(active?ClinicalUi.accent(getContext()):ClinicalUi.primaryText(getContext()));
        }
        boolean ready=!url.isEmpty()&&percent==100;
        link.setText(!url.isEmpty()?url:getContext().getString(R.string.personal_connection_link_pending));
        open.setEnabled(ready&&!busy);
        change.setEnabled(ready&&!busy);
        recheck.setEnabled(!busy);
        recheck.setVisibility(ready?View.VISIBLE:View.GONE);
        primary.setEnabled(!busy);
        primary.setText(busy?R.string.personal_connection_checking_action:ready?R.string.personal_connection_copy:needsClaim?
                R.string.personal_connection_claim:R.string.personal_connection_try);
        for(Button button:new Button[]{open,change,primary,recheck}) button.setAlpha(button.isEnabled()?1f:0.5f);
    }

    void renderTiming(long startedAt,long nextRetry,int attempts,long verifiedAt,long readingAt,int percent) {
        long now=android.os.SystemClock.elapsedRealtime();
        if(percent==100) {
            timing.setText(getContext().getString(R.string.personal_connection_verified,
                    android.text.format.DateFormat.getTimeFormat(getContext()).format(new java.util.Date(verifiedAt))));
            reading.setText(readingAt>0?getContext().getString(R.string.personal_connection_reading,
                    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(new java.util.Date(readingAt))):
                    getContext().getString(R.string.personal_connection_no_readings));
            reading.setVisibility(View.VISIBLE);
        } else {
            long elapsed=startedAt==0?0:Math.max(0,(now-startedAt)/1000);
            String text=getContext().getString(R.string.personal_connection_elapsed,attempts,
                    String.format(java.util.Locale.ROOT,"%d:%02d",elapsed/60,elapsed%60));
            if(nextRetry>0) text+="\n"+getContext().getString(R.string.personal_connection_next_retry,Math.max(0,(nextRetry-now+999)/1000));
            timing.setText(text);
            reading.setVisibility(View.GONE);
        }
    }

    private static void addSpaced(LinearLayout parent,View child,Context context,int heightDp) {
        LayoutParams params=new LayoutParams(-1,heightDp<0?heightDp:ClinicalUi.dp(context,heightDp));
        params.topMargin=ClinicalUi.dp(context,12);
        parent.addView(child,params);
    }
}
