package tk.glucodata.settings;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.view.Gravity;
import android.view.ViewGroup.LayoutParams;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static tk.glucodata.Applic.isWearable;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.settings.Settings.removeContentView;
import static tk.glucodata.util.getbutton;
import static tk.glucodata.util.getcheckbox;
import static tk.glucodata.util.getlabel;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;

import tk.glucodata.Applic;
import tk.glucodata.CheckDirectionBox;
import tk.glucodata.ClinicalUi;
import tk.glucodata.GlucoseCurve;
import tk.glucodata.Layout;
import tk.glucodata.Log;
import tk.glucodata.MainActivity;
import tk.glucodata.Natives;
import tk.glucodata.R;

class LogConfig {
   final private static String LOG_ID="LogConfig" ;


private static void saveRequest(MainActivity context,String filename,int request) {
    if(doLog) {
        Log.i(LOG_ID,"saveRequest "+filename);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_TITLE, filename);
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        try {
            context.startActivityForResult(intent, request);
            } catch(Throwable th) {
                Log.stack(LOG_ID,"ACTION_CREATE_DOCUMENT",th);
              }
         }
    }
private static void savefile(MainActivity context) {
    if(doLog) {
        saveRequest(context,"trace.log", MainActivity.REQUEST_SAVE_LOG);
           }
    }
private static void savelogcat(MainActivity context) {
    if(doLog) {
        saveRequest(context,"logcat.txt", MainActivity.REQUEST_SAVE_LOGCAT);
           }
    }

private static LinearLayout logFileCard(MainActivity context,String name,String description,
        TextView size,CheckDirectionBox source,Button save,Button delete) {
    LinearLayout copy=new LinearLayout(context);
    copy.setOrientation(LinearLayout.VERTICAL);
    copy.setPaddingRelative(ClinicalUi.dp(context,16),ClinicalUi.dp(context,14),
            ClinicalUi.dp(context,16),ClinicalUi.dp(context,10));
    TextView title=new TextView(context);
    title.setText(name);
    title.setTextColor(ClinicalUi.primaryText(context));
    title.setTextSize(18);
    copy.addView(title);
    TextView detail=ClinicalUi.body(context,description);
    detail.setPadding(0,ClinicalUi.dp(context,3),0,ClinicalUi.dp(context,8));
    copy.addView(detail);
    size.setTextColor(ClinicalUi.secondaryText(context));
    size.setTextSize(14);
    copy.addView(size);

    LinearLayout actions=new LinearLayout(context);
    actions.setOrientation(LinearLayout.HORIZONTAL);
    actions.setPaddingRelative(ClinicalUi.dp(context,10),ClinicalUi.dp(context,8),
            ClinicalUi.dp(context,10),ClinicalUi.dp(context,10));
    actions.addView(save,new LinearLayout.LayoutParams(0,WRAP_CONTENT,1f));
    Space gap=new Space(context);
    actions.addView(gap,new LinearLayout.LayoutParams(ClinicalUi.dp(context,10),1));
    actions.addView(delete,new LinearLayout.LayoutParams(0,WRAP_CONTENT,1f));

    return ClinicalUi.card(context,copy,
            ClinicalUi.toggleRow(context,source,
                    context.getString(R.string.clinical_log_toggle_hint)),actions);
    }

private static void makePhone(MainActivity act,View parent) {
    parent.setVisibility(GONE);
    Button close=ClinicalUi.button(act,act.getString(R.string.closename),
            ClinicalUi.ButtonRole.SECONDARY);
    Button help=ClinicalUi.button(act,act.getString(R.string.helpname),
            ClinicalUi.ButtonRole.SECONDARY);
    Button saveTrace=ClinicalUi.button(act,act.getString(R.string.save),
            ClinicalUi.ButtonRole.SECONDARY);
    Button deleteTrace=ClinicalUi.button(act,act.getString(R.string.delete),
            ClinicalUi.ButtonRole.DANGER);
    Button saveLogcat=ClinicalUi.button(act,act.getString(R.string.save),
            ClinicalUi.ButtonRole.SECONDARY);
    Button deleteLogcat=ClinicalUi.button(act,act.getString(R.string.delete),
            ClinicalUi.ButtonRole.DANGER);
    CheckDirectionBox traceToggle=getcheckbox(act,R.string.logging,Natives.islogging());
    CheckDirectionBox logcatToggle=getcheckbox(act,R.string.logging,Natives.islogcat());
    TextView traceSize=ClinicalUi.body(act,act.getString(R.string.clinical_log_size_bytes,
            Natives.getLogfilesize()));
    TextView logcatSize=ClinicalUi.body(act,act.getString(R.string.clinical_log_size_bytes,
            Natives.getLogcatfilesize()));

    traceToggle.setOnCheckedChangeListener((button,checked)->Natives.dolog(checked));
    logcatToggle.setOnCheckedChangeListener((button,checked)->Natives.dologcat(checked));
    saveTrace.setOnClickListener(view->savefile(act));
    saveLogcat.setOnClickListener(view->savelogcat(act));
    deleteTrace.setOnClickListener(view->{
        Natives.zeroLog();
        traceSize.setText(act.getString(R.string.clinical_log_size_bytes,
                Natives.getLogfilesize()));
        });
    deleteLogcat.setOnClickListener(view->{
        Natives.zeroLogcat();
        logcatSize.setText(act.getString(R.string.clinical_log_size_bytes,
                Natives.getLogcatfilesize()));
        });

    LinearLayout content=ClinicalUi.verticalContent(act);
    content.setPaddingRelative(MainActivity.systembarLeft+ClinicalUi.dp(act,20),
            MainActivity.systembarTop+ClinicalUi.dp(act,8),
            MainActivity.systembarRight+ClinicalUi.dp(act,20),
            MainActivity.systembarBottom+ClinicalUi.dp(act,24));
    content.addView(ClinicalUi.header(act,act.getString(R.string.clinical_log_title),close));
    TextView intro=ClinicalUi.body(act,act.getString(R.string.clinical_log_intro));
    intro.setPaddingRelative(ClinicalUi.dp(act,4),0,ClinicalUi.dp(act,4),
            ClinicalUi.dp(act,6));
    content.addView(intro);
    content.addView(ClinicalUi.sectionLabel(act,
            act.getString(R.string.clinical_log_app_section)));
    content.addView(logFileCard(act,"trace.log",
            act.getString(R.string.clinical_trace_description),traceSize,traceToggle,
            saveTrace,deleteTrace));
    content.addView(ClinicalUi.sectionLabel(act,
            act.getString(R.string.clinical_log_system_section)));
    content.addView(logFileCard(act,"logcat.txt",
            act.getString(R.string.clinical_logcat_description),logcatSize,logcatToggle,
            saveLogcat,deleteLogcat));
    help.setOnClickListener(view->tk.glucodata.help.help(R.string.loghelp,act));
    LinearLayout.LayoutParams helpParams=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
    helpParams.topMargin=ClinicalUi.dp(act,20);
    content.addView(help,helpParams);

    ScrollView screen=ClinicalUi.scrollScreen(act,content);
    act.addMyContentView(screen,new ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT));
    MainActivity.setonback(()->{
        removeContentView(screen);
        parent.setVisibility(VISIBLE);
        });
    close.setOnClickListener(view->MainActivity.doonback());
    }

static void make(MainActivity act,View parent) {
    if(doLog) {
       if(!isWearable) {
           makePhone(act,parent);
           return;
           }
       parent.setVisibility(GONE);
       final   int subpad=(int)(tk.glucodata.GlucoseCurve.metrics.density*7.0);
        var trace=getlabel(act,"trace.log");
        trace.setPadding(subpad,0,0,0);
        var sizelabel=getlabel(act,R.string.filesize);
        sizelabel.setPadding(0,0,subpad,0);
        var size=getlabel(act,Long.toString(Natives.getLogfilesize()));
        var delete=getbutton(act,R.string.delete);
        var save=getbutton(act,R.string.save);
       save.setOnClickListener(v-> savefile(act));
        var log=getcheckbox(act,R.string.logging, Natives.islogging());
        log.setOnCheckedChangeListener( (buttonView,  isChecked) -> Natives.dolog(isChecked));
        log.setPadding(0,0,subpad,0);

        var logcat=getlabel(act,"logcat");
        logcat.setPadding(subpad,0,0,0);
        var logcaton=getcheckbox(act,R.string.logging, Natives.islogcat());
        logcaton.setOnCheckedChangeListener( (buttonView,  isChecked) -> Natives.dologcat(isChecked));
        logcaton.setPadding(0,0,subpad,0);
        var sizelabel2=getlabel(act,R.string.filesize);
        var logcatsize=getlabel(act,Long.toString(Natives.getLogcatfilesize()));
        var deletelogcat=getbutton(act,R.string.delete);
        var savelogcat=getbutton(act,R.string.save);
       savelogcat.setOnClickListener(v->savelogcat(act));
       // var email=getbutton(act,"E-Mail");
        var close=getbutton(act,R.string.closename);
        View[] closerow;
       ViewGroup alllayout;
    ViewGroup.LayoutParams params;
        if(isWearable) {
            closerow=new View[]{close};
            Layout layout = new Layout(act, (l, w, h) -> {
                int[] ret={w,h};
                return ret;
                },new View[]{trace},new View[]{delete,save},new View[]{log},new View[] {sizelabel,size},new View[]{logcat},
                new View[]{deletelogcat,savelogcat},new View[]{logcaton},new View[] {sizelabel2,logcatsize},
                closerow);
            var scroll=new ScrollView(act);
            scroll.addView(layout);
            scroll.setFillViewport(true);
            scroll.setSmoothScrollingEnabled(false);
           scroll.setScrollbarFadingEnabled(true);
           scroll.setVerticalScrollBarEnabled(Applic.scrollbar);
            alllayout=scroll;
            layout.setBackgroundColor(Applic.backgroundcolor);
            int pararg=MATCH_PARENT;
            params=new ViewGroup.LayoutParams(pararg,pararg);
           final   int pad=(int)(tk.glucodata.GlucoseCurve.metrics.density*15.0);
           layout.setPadding(pad,pad,pad,pad);
            }
        else {
            var help=getbutton(act, R.string.helpname);
            help.setOnClickListener(v-> {
                tk.glucodata.help.help(R.string.loghelp,act);
            });
            closerow=new View[]{help,close};
             var width= GlucoseCurve.getwidth();
             var height=GlucoseCurve.getheight();
            Layout layout = new Layout(act, (l, w, h) -> {
            /*
                 l.setX((width-w)*.5f);
                 l.setY((height-h)*.33f);
                 */
                int[] ret={w,h};
                return ret;
                },new View[]{trace,delete,save},new View[] {log,sizelabel,size},
                new View[]{logcat,deletelogcat,savelogcat},new View[] {logcaton,sizelabel2,logcatsize},
                closerow);
            alllayout=layout;
            layout.setBackgroundResource(R.drawable.dialogbackground);
           final   int pad=(int)(tk.glucodata.GlucoseCurve.metrics.density*9.0);
           layout.setPadding(pad,pad,pad,pad);

          params =    new FrameLayout.LayoutParams( WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER|Gravity.CENTER_HORIZONTAL);
            }


   // params.topMargin=MainActivity.systembarTop;
        act.addMyContentView(alllayout, params);
        delete.setOnClickListener(v-> {
            Natives.zeroLog();
            MainActivity.poponback();
            removeContentView(alllayout) ;
            make(act,parent);
        });
        deletelogcat.setOnClickListener(v-> {
            Natives.zeroLogcat();
            MainActivity.poponback();
            removeContentView(alllayout) ;
            make(act,parent);
        });
        MainActivity.setonback(() -> {
            removeContentView(alllayout) ;
            parent.setVisibility(VISIBLE);
            });

       close.setOnClickListener(v-> MainActivity.doonback());
        }
   }

};
