/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2 and 3 sensors.                         */
/*                                                                                   */
/*      Copyright (C) 2021 Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>         */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */
/*                                                                                   */
/*      Juggluco is distributed in the hope that it will be useful, but              */
/*      WITHOUT ANY WARRANTY; without even the implied warranty of                   */
/*      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.                         */
/*      See the GNU General Public License for more details.                         */
/*                                                                                   */
/*      You should have received a copy of the GNU General Public License            */
/*      along with Juggluco. If not, see <https://www.gnu.org/licenses/>.            */
/*                                                                                   */
/*      Fri Jan 27 15:31:32 CET 2023                                                 */


package tk.glucodata.settings;

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.DONT_KILL_APP;
import static android.graphics.Color.BLACK;
import static android.view.View.GONE;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static android.widget.Spinner.MODE_DIALOG;
import static android.widget.Spinner.MODE_DROPDOWN;
import static androidx.core.os.LocaleListCompat.getEmptyLocaleList;
import static tk.glucodata.Applic.DynamicTheme;
import static tk.glucodata.Applic.isWearable;
import static tk.glucodata.Applic.usedlocale;
import static tk.glucodata.Backup.getnumedit;
import static tk.glucodata.Layout.getMargins;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.Natives.getInvertColors;
import static tk.glucodata.Natives.getRTL;
import static tk.glucodata.Natives.getScheduleProfile;
import static tk.glucodata.Natives.getalarmSoundType;
import static tk.glucodata.Natives.getshowcalibratedstream;
import static tk.glucodata.Natives.getshowhistories;
import static tk.glucodata.Natives.getshownumbers;
import static tk.glucodata.Natives.getshowscans;
import static tk.glucodata.Natives.getshowstream;
import static tk.glucodata.Natives.removeScheduleProfile;
import static tk.glucodata.Natives.setthreshold;
import static tk.glucodata.NumberView.avoidSpinnerDropdownFocus;
import static tk.glucodata.RingTones.EnableControls;
import static tk.glucodata.Specific.useclose;
import static tk.glucodata.help.help;
import static tk.glucodata.util.getbutton;
import static tk.glucodata.util.getcheckbox;
import static tk.glucodata.util.getlabel;
import static tk.glucodata.util.getlocale;
import static tk.glucodata.util.getradiobuttonId;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.text.Html;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.Button;

import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.core.os.LocaleListCompat;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

//import com.google.android.material.slider.LabelFormatter;
//import com.google.android.material.slider.RangeSlider;

import java.text.DecimalFormatSymbols;
import java.util.Arrays;
import java.util.List;

import tk.glucodata.Applic;
import tk.glucodata.Backup;
import tk.glucodata.BooleanSupplier;
import tk.glucodata.BuildConfig;
import tk.glucodata.CheckDirectionBox;
import tk.glucodata.CheckDirectionRadio;
import tk.glucodata.ClinicalUi;
import tk.glucodata.Floating;
import tk.glucodata.GlucoseAlertSettingsPage;
import tk.glucodata.GlucoseCurve;
import tk.glucodata.HealthConnection;
import tk.glucodata.IntakeBackendSettings;
import tk.glucodata.LabelAdapter;
import tk.glucodata.Layout;
import tk.glucodata.LegacySettingsRoutes;
import tk.glucodata.Libreview;
import tk.glucodata.Log;
import tk.glucodata.MainActivity;
import tk.glucodata.Menus;
import tk.glucodata.MeterList;
import tk.glucodata.Natives;
import tk.glucodata.OrientationPolicy;
import tk.glucodata.Notify;
import tk.glucodata.NumAlarm;
import tk.glucodata.PredictiveAlertSettingsPage;
import tk.glucodata.R;
import tk.glucodata.Specific;
import tk.glucodata.SuperGattCallback;

import java.text.DecimalFormat;
import java.util.Locale;

public class Settings  {
private final static String LOG_ID="Settings";
MainActivity activity;

/*
public static String    oldfloat2string(Float get) {
    return get.toString();
} */
private static final DecimalFormat df1 = new DecimalFormat("#.#",new DecimalFormatSymbols(Locale.US));
public static String    float2string(Float get) {
    return df1.format(get);
} 
/*
public static String    float2string(Float get) {
      return String.format("%.1f",get);
    }  */
boolean IntentscanEnabled() {
    try{
    Application app= activity.getApplication();
      PackageManager manage = app.getPackageManager();
    ComponentName  scan= new ComponentName(app, "tk.glucodata.glucodata");
    return manage.getComponentEnabledSetting(scan)!=COMPONENT_ENABLED_STATE_DISABLED;
    }
    catch (Throwable e) {

        Log.stack(LOG_ID,e);
    }
    return false;
    }
void EnableIntentScanning(boolean val) {
    try{
    Application app= activity.getApplication();
      PackageManager manage = app.getPackageManager();
    ComponentName  scan= new ComponentName(app, "tk.glucodata.glucodata");
    int com=val?COMPONENT_ENABLED_STATE_ENABLED:COMPONENT_ENABLED_STATE_DISABLED;
       manage.setComponentEnabledSetting(scan,com , DONT_KILL_APP);
    }
    catch (Throwable e) {

        Log.stack(LOG_ID,e);
    }
    }
static private Settings thisone=null;
public static void set(MainActivity act) {
    act.themeLightBars();
    thisone=new Settings();

    if(!isWearable&&!Natives.getsystemUI()) {
        act.showui=true;
        act.showSystemUI();
        thisone.makesettings(act);
        }
    else
        thisone.makesettingsin(act);

    }
private class Closerun implements Runnable {
 public  void run() {
        int unit=mmolL.isChecked()?1:(mgdl.isChecked()?2:0);
        if(unit==0) {
           activity.setonback(this);
           Applic.argToaster(activity, R.string.setunitfirst,Toast.LENGTH_SHORT);
           return;
           }
        hidekeyboard();
        finish();
         activity.lightBars(!getInvertColors( ));
        if(tk.glucodata.Menus.on)
            tk.glucodata.Menus.show(activity);
        }
    };
private void makesettingsin(MainActivity act) {
        activity=act;

        colorwindowbackground=Applic.backgroundcolor;
       mksettings(activity);
       final var  closerun=new Closerun(); 
       /*
    final Runnable[] closerun=new Runnable[1]; //to get rid of may not be initialize nonsense
    closerun[0]=() -> {
        int unit=mmolL.isChecked()?1:(mgdl.isChecked()?2:0);
        if(unit==0) {
           act.setonback(closerun[0]);
           Applic.argToaster(act, R.string.setunitfirst,Toast.LENGTH_SHORT);
           return;
           }
        hidekeyboard();
        finish();
           act.lightBars(!getInvertColors( ));
        if(tk.glucodata.Menus.on)
            tk.glucodata.Menus.show(activity);

        };
    act.setonback(closerun[0]);
        */
    act.setonback(closerun);
}
private void makesettings(MainActivity act) {
    Applic.app.getHandler().postDelayed( ()->{ makesettingsin(act);},1);
        
}


void recreate() {
// removeContentView(settinglayout);
    layoutweg();
   mksettings(activity);

   }
void layoutweg() {
/*     removeContentView(settinglayout);
    settinglayout=null;*/
    }
public static void closeview() {
    if(thisone!=null)
        thisone.finish();
    }
static void hideSystemUI() {
    }
void finish() {
    layoutweg();
    settinglayout.setVisibility(GONE);
    
    try {
        activity.setRequestedOrientation(OrientationPolicy.requestedOrientation());
        }
        catch(       Throwable  error) {
        String mess=error!=null?error.getMessage():null;
        if(mess==null) {
            mess="error";
            }
           Log.e(LOG_ID ,mess);
       }
//    if(editlabel!=null) removeContentView(editlabel) ;
    removeContentView(settinglayout);
    thisone=null;


    if(!isWearable) {
    activity.showui=false;
//   activity.hideSystemUI();

    if(!Natives.getsystemUI()) {
        Applic.app.getHandler().postDelayed( ()->{
                    activity.hideSystemUI();
                    },1);
        }
        }
    activity.requestRender();
    }

//    Button deletelabel;
public static    int editoptions=(isWearable?0:(EditorInfo.IME_FLAG_NO_EXTRACT_UI| EditorInfo.IME_FLAG_NO_FULLSCREEN))| EditorInfo.IME_ACTION_DONE;

 int colorwindowbackground;

static int getbackgroundcolor(Context context) {
    TypedValue typedValue = new TypedValue();
    if (context.getTheme().resolveAttribute(android.R.attr.windowBackground, typedValue, true) && typedValue.type >= TypedValue.TYPE_FIRST_COLOR_INT && typedValue.type <= TypedValue.TYPE_LAST_COLOR_INT) {
        return typedValue.data;
    } else
        return Color.RED;
}

//HorizontalScrollView settinglayout=null;
FrameLayout settinglayout=null;
    CheckDirectionRadio mmolL;
    CheckDirectionRadio mgdl;

static View[] mkalarm(MainActivity context,String label1,boolean show,Float value,int kind) {

       
    CheckDirectionBox yeslow = new CheckDirectionBox(context);
    yeslow.setText(label1);
    EditText alow = new EditText(context);

    final int minheight= GlucoseCurve.dpToPx(48);
    alow.setMinimumHeight(minheight);
    alow.setImeOptions(editoptions);
    alow.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
//    alow.setImeOptions(editoptions);
    alow.setMinEms(2);
    Button ring=getbutton(context,R.string.ringtonename);


    if(show)  {
         yeslow.setChecked(true);
        }
   else {
        ring.setVisibility(INVISIBLE);
        alow.setVisibility(INVISIBLE);
        }

    alow.setText( float2string(value));
yeslow.setOnCheckedChangeListener(
                 (buttonView,  isChecked) -> {
         if(isChecked) {
                    alow.setVisibility(VISIBLE);
                    ring.setVisibility(VISIBLE);
            }
        else {
            ring.setVisibility(INVISIBLE);
            alow.setVisibility(INVISIBLE);
            }
    });

    return new View[]{yeslow, alow,ring};
    }
public static float str2float(String str) {
    if(str!=null) {
         try {
        return Float.parseFloat(str);
                } catch(Exception e) {};
        }
    return 0.0f;
  }
public static float edit2float(EditText ed) {
    return str2float(ed.getText().toString());
    }

void hidekeyboard() {
 tk.glucodata.help.hidekeyboard(activity) ;
 }
boolean scanenabled=true;
//int addindex=-1;

//EditText glow, ghigh ,tlow,thigh;
EditText tlow,thigh;
static float round(float value,float size)  {
   return Math.round(value*size)/size;
   }
void setvalues() {
      final var unit= Natives.getunit();
        switch(unit) {
            case 1: mmolL.setChecked(true);break;
            case 2: mgdl.setChecked(true);break;
        }

//        alow.setText( float2string(value));
    }




static public Spinner getProfileSpinner(MainActivity context) {
   var spin=  getGenSpin(context);
   final String profile=context.getString(R.string.profile);
   final List<String> strprofiles= Arrays.asList(context.getString(R.string.defaultname),profile+"1",profile+"2",profile+"3",profile+"4",profile+"5");
   final var adapt=new LabelAdapter<String>(context,strprofiles,0);
    spin.setAdapter(adapt);
    return spin;
    }

private static LinearLayout clinicalAdvancedAlarmCard(MainActivity context,
        View[] alarm,int ringtoneKind) {
    CheckDirectionBox toggle=(CheckDirectionBox)alarm[0];
    EditText threshold=(EditText)alarm[1];
    Button ringtone=(Button)alarm[2];
    LinearLayout thresholdRow=clinicalAlarmThresholdRow(context,threshold);
    clinicalStyleAction(ringtone);
    LinearLayout card=clinicalExpandableCard(context,
            clinicalToggleRow(context,toggle,
                    context.getString(R.string.settings_alarm_level_hint)),
            thresholdRow,ringtone);
    Runnable sync=()->clinicalSyncExpandable(toggle.isChecked(),thresholdRow,ringtone);
    toggle.setOnCheckedChangeListener((button,isChecked)->sync.run());
    sync.run();
    ringtone.setOnClickListener(view->new tk.glucodata.RingTones(ringtoneKind).mkviews(
            context,toggle.getText().toString(),card));
    return card;
    }

private static void clinicalAdvancedAlarm(MainActivity context,View parent) {
    View[] veryLow=mkalarm(context,context.getString(R.string.verylowglucosealarm),
            Natives.hasalarmverylow(),Natives.alarmverylow(),5);
    View[] preLow=mkalarm(context,context.getString(R.string.prelowglucosealarm),
            Natives.hasalarmprelow(),Natives.alarmprelow(),7);
    View[] veryHigh=mkalarm(context,context.getString(R.string.veryhighglucosealarm),
            Natives.hasalarmveryhigh(),Natives.alarmveryhigh(),6);
    View[] preHigh=mkalarm(context,context.getString(R.string.prehighglucosealarm),
            Natives.hasalarmprehigh(),Natives.alarmprehigh(),8);

    Button close=clinicalHeaderButton(context,R.string.closename);
    LinearLayout schedules=ClinicalUi.actionRow(context,
            context.getString(R.string.schedules),
            context.getString(R.string.settings_schedules_hint));
    LinearLayout alarmHelp=ClinicalUi.actionRow(context,
            context.getString(R.string.helpname),
            context.getString(R.string.settings_advanced_help_hint));

    LinearLayout content=clinicalScreenContent(context);
    content.addView(ClinicalUi.header(context,
            context.getString(R.string.settings_advanced_alarm_title),close));
    TextView intro=ClinicalUi.body(context,
            context.getString(R.string.settings_advanced_alarm_intro));
    intro.setPaddingRelative(ClinicalUi.dp(context,4),0,ClinicalUi.dp(context,4),
            ClinicalUi.dp(context,6));
    content.addView(intro);
    content.addView(ClinicalUi.sectionLabel(context,
            context.getString(R.string.settings_schedule_section)));
    content.addView(ClinicalUi.card(context,schedules));
    content.addView(ClinicalUi.sectionLabel(context,
            context.getString(R.string.settings_urgent_alerts_section)));
    LinearLayout veryLowCard=clinicalAdvancedAlarmCard(context,veryLow,5);
    LinearLayout veryHighCard=clinicalAdvancedAlarmCard(context,veryHigh,6);
    LinearLayout.LayoutParams cardGap=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
    cardGap.topMargin=ClinicalUi.dp(context,12);
    veryHighCard.setLayoutParams(cardGap);
    content.addView(veryLowCard);
    content.addView(veryHighCard);
    content.addView(ClinicalUi.sectionLabel(context,
            context.getString(R.string.settings_early_alerts_section)));
    LinearLayout preLowCard=clinicalAdvancedAlarmCard(context,preLow,7);
    LinearLayout preHighCard=clinicalAdvancedAlarmCard(context,preHigh,8);
    LinearLayout.LayoutParams preGap=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
    preGap.topMargin=ClinicalUi.dp(context,12);
    preHighCard.setLayoutParams(preGap);
    content.addView(preLowCard);
    content.addView(preHighCard);
    content.addView(ClinicalUi.sectionLabel(context,
            context.getString(R.string.settings_support_section)));
    content.addView(ClinicalUi.card(context,alarmHelp));

    ScrollView screen=ClinicalUi.scrollScreen(context,content);
    context.addMyContentView(screen,new ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT));
    ((Button)veryLow[2]).setOnClickListener(view->new tk.glucodata.RingTones(5).mkviews(
            context,context.getString(R.string.verylowglucosealarm),screen));
    ((Button)veryHigh[2]).setOnClickListener(view->new tk.glucodata.RingTones(6).mkviews(
            context,context.getString(R.string.veryhighglucosealarm),screen));
    ((Button)preLow[2]).setOnClickListener(view->new tk.glucodata.RingTones(7).mkviews(
            context,context.getString(R.string.prelowglucosealarm),screen));
    ((Button)preHigh[2]).setOnClickListener(view->new tk.glucodata.RingTones(8).mkviews(
            context,context.getString(R.string.prehighglucosealarm),screen));
    Runnable saver=()->Natives.setAdvancedAlarms(
            str2float(((EditText)veryLow[1]).getText().toString()),
            str2float(((EditText)veryHigh[1]).getText().toString()),
            ((CheckDirectionBox)veryLow[0]).isChecked(),
            ((CheckDirectionBox)veryHigh[0]).isChecked(),
            ((CheckDirectionBox)preLow[0]).isChecked(),
            ((CheckDirectionBox)preHigh[0]).isChecked(),
            str2float(((EditText)preLow[1]).getText().toString()),
            str2float(((EditText)preHigh[1]).getText().toString()));
    MainActivity.setonback(()-> {
        saver.run();
        removeContentView(screen);
        alarmsettings(context,parent);
        });
    close.setOnClickListener(view->MainActivity.doonback());
    schedules.setOnClickListener(view->scheduleProfiles(context,screen));
    alarmHelp.setOnClickListener(view->help(R.string.advancedAlarmshelp,context));
    }

static private void advancedalarm(MainActivity context,View parview) {
    if(useClinicalPhoneChild(isWearable)) {
        clinicalAdvancedAlarm(context,parview);
        return;
        }
    View[] verylowalarm= mkalarm(context,context.getString(R.string.verylowglucosealarm),Natives.hasalarmverylow(),Natives.alarmverylow(),5);
    View[] prelowalarm= mkalarm(context,context.getString(R.string.prelowglucosealarm),Natives.hasalarmprelow(),Natives.alarmprelow(),7);
    View[] veryhighalarm=mkalarm(context,context.getString(R.string.veryhighglucosealarm),Natives.hasalarmveryhigh(),Natives.alarmveryhigh(),6);
    View[] prehighalarm=mkalarm(context,context.getString(R.string.prehighglucosealarm),Natives.hasalarmprehigh(),Natives.alarmprehigh(),8);

    var close=getbutton(context,R.string.closename);
    var schedules=getbutton(context,R.string.schedules);
   ViewGroup layout;
    if(isWearable) {
       final var height= GlucoseCurve.getheight();
       getMargins(schedules).topMargin=getMargins(close).bottomMargin=(int)(height*.05);
       final var width= GlucoseCurve.getwidth();
       var hormarg= (int)(height*.1f);
       getMargins(veryhighalarm[1]).setMarginStart(hormarg);getMargins(veryhighalarm[2]).setMarginEnd(hormarg);
       getMargins(verylowalarm[1]).setMarginStart(hormarg);getMargins(verylowalarm[2]).setMarginEnd(hormarg);
       getMargins(prehighalarm[1]).setMarginStart(hormarg);getMargins(prehighalarm[2]).setMarginEnd(hormarg);
       getMargins(prelowalarm[1]).setMarginStart(hormarg);getMargins(prelowalarm[2]).setMarginEnd(hormarg);

        Layout lay = new Layout(context, (l, w, h) -> {
            int[] ret={w,h};
            return ret;
            },
            new View[]{schedules},
            new View[]{verylowalarm[0]},new View[]{verylowalarm[1],verylowalarm[2]},
            new View[]{veryhighalarm[0]},new View[]{veryhighalarm[1],veryhighalarm[2]},
            new View[]{prelowalarm[0]},new View[]{prelowalarm[1],prelowalarm[2]},
            new View[]{prehighalarm[0]},new View[]{prehighalarm[1],prehighalarm[2]},
            new View[]{close});
        var scroll=new ScrollView(context);    
        scroll.addView(lay);
        scroll.setFillViewport(true);
        scroll.setSmoothScrollingEnabled(false);
       scroll.setScrollbarFadingEnabled(true);
       scroll.setVerticalScrollBarEnabled(Applic.scrollbar);
       layout=scroll;
    lay.setPaddingRelative((int)(width*0.02f),0,(int)(width*0.05f),0);
       }
    else {
     var help=getbutton(context,R.string.helpname);
     help.setOnClickListener(v-> help(R.string.advancedAlarmshelp,context));
     final var width= GlucoseCurve.getwidth();
     var nwmarg= (int)(width*.15);
     getMargins(close).setMarginEnd(nwmarg);
     getMargins(help).setMarginStart(nwmarg);
        Layout lay = new Layout(context, (l, w, h) -> {
            int[] ret={w,h};
            return ret;

            },verylowalarm,veryhighalarm,prelowalarm,prehighalarm,new View[]{help,schedules,close});
        layout=lay;
    final int sidepad=(int)(GlucoseCurve.metrics.density*8);
    layout.setPadding(MainActivity.systembarLeft+sidepad,MainActivity.systembarTop*2/3,sidepad+MainActivity.systembarRight,sidepad+MainActivity.systembarBottom*9/10);
        }


    schedules.setOnClickListener(v->scheduleProfiles(context,layout));

    verylowalarm[2].setOnClickListener(v->{
        new tk.glucodata.RingTones(5).mkviews(context,context.getString(R.string.verylowglucosealarm),layout);
        });
    veryhighalarm[2].setOnClickListener(v->{
        new tk.glucodata.RingTones(6).mkviews(context,context.getString(R.string.veryhighglucosealarm),layout);
        });
    prelowalarm[2].setOnClickListener(v->{
        new tk.glucodata.RingTones(7).mkviews(context,context.getString(R.string.prelowglucosealarm),layout);
        });
    prehighalarm[2].setOnClickListener(v->{
        new tk.glucodata.RingTones(8).mkviews(context,context.getString(R.string.prehighglucosealarm),layout);
        });
Runnable saver=() -> {
         boolean hasverylow=((CheckDirectionBox) verylowalarm[0]).isChecked();
         boolean hasveryhigh=((CheckDirectionBox) veryhighalarm[0]).isChecked();
         boolean hasprelow=((CheckDirectionBox) prelowalarm[0]).isChecked();
         boolean hasprehigh=((CheckDirectionBox) prehighalarm[0]).isChecked();

         Natives.setAdvancedAlarms(str2float(((EditText)verylowalarm[1]).getText().toString()),
                    str2float(((EditText)veryhighalarm[1]).getText().toString()),hasverylow,hasveryhigh,hasprelow,hasprehigh,
str2float(((EditText)prelowalarm[1]).getText().toString()), str2float(((EditText)prehighalarm[1]).getText().toString()));
        };


    layout.setBackgroundColor(Applic.backgroundcolor);
    context.addMyContentView(layout, new ViewGroup.LayoutParams( MATCH_PARENT ,MATCH_PARENT));
    MainActivity.setonback(()-> {
        saver.run();
        removeContentView(layout);
        alarmsettings(context,parview);
        } );
    close.setOnClickListener(v->{MainActivity.doonback(); });
}

static private class ProfileScheduleHolder extends RecyclerView.ViewHolder {
    public ProfileScheduleHolder(View view,ProfileScheduleAdapter adapt,View parent) {
       super(view);
       view.setOnClickListener(v -> {
            int pos=getAbsoluteAdapterPosition();
            changeProfile((MainActivity)view.getContext(),pos,adapt,parent);
            });

    }

}
static private String profilename(int pos) {
    if(pos==0)
        return Applic.getContext().getString(R.string.defaultname);
    return Applic.getContext().getString(R.string.profile)+pos;
    }
static private class ProfileScheduleAdapter extends RecyclerView.Adapter<ProfileScheduleHolder> {
    View layout;

    ProfileScheduleAdapter(View layout) {
        this.layout=layout;
        }
    @NonNull
    @Override
    public ProfileScheduleHolder onCreateViewHolder(ViewGroup parent, int viewType) {
         var view=new TextView( parent.getContext());
          view.setTransformationMethod(null);
          if(isWearable) {
              view.setTextSize(TypedValue.COMPLEX_UNIT_PX, Applic.largefontsize);
              view.setLayoutParams(new ViewGroup.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
              view.setGravity(Gravity.CENTER);
              }
          else {
              view.setTextSize(TypedValue.COMPLEX_UNIT_SP,16.0f);
              view.setTextColor(ClinicalUi.primaryText(parent.getContext()));
              view.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
              view.setMinimumHeight(ClinicalUi.dp(parent.getContext(),64));
              view.setPaddingRelative(ClinicalUi.dp(parent.getContext(),16),
                      ClinicalUi.dp(parent.getContext(),8),
                      ClinicalUi.dp(parent.getContext(),16),
                      ClinicalUi.dp(parent.getContext(),8));
              view.setBackground(ClinicalUi.surface(parent.getContext(),false,true));
              RecyclerView.LayoutParams params=new RecyclerView.LayoutParams(
                      MATCH_PARENT,WRAP_CONTENT);
              params.topMargin=ClinicalUi.dp(parent.getContext(),5);
              params.bottomMargin=ClinicalUi.dp(parent.getContext(),5);
              view.setLayoutParams(params);
              }
           return new ProfileScheduleHolder(view,this,layout);
          }

    @Override
    public void onBindViewHolder(final ProfileScheduleHolder holder, int pos) {
    	TextView text=(TextView)holder.itemView;
        short[] minprofile=getScheduleProfile(pos);
        short min=minprofile[0];
        short profile=minprofile[1];
//        final String arrow = MainActivity.rtl ? "\u2190" : "\u27A1";
        final String arrow = MainActivity.rtl ? "\u2B05" : "\u27A1";

//     final String arrow = MainActivity.rtl ? 	"\u2B05":"\u27A1";
//        final String arrow = MainActivity.rtl ? "\u2190" : "\u2192";  // ← →
//        final String arrow = MainActivity.rtl ? "\u2B05" : "\u2B95";
//        final String arrow=MainActivity.rtl?"🡄 ":"🡆";
        text.setText(String.format(usedlocale,"%02d:%02d\t%s\t%s", min/60,min%60,arrow,profilename(profile)));
    	}

        @Override
        public int getItemCount() {
            return Natives.nrScheduledProfiles();
           }
    }

private static void clinicalChangeProfile(MainActivity context,int previousIndex,
        ProfileScheduleAdapter adapter,View parent) {
    int index=previousIndex<0?Natives.nrScheduledProfiles():previousIndex;
    if(index>=10) {
        Applic.argToaster(context,"Too many schedules",Toast.LENGTH_LONG);
        return;
        }
    EnableControls(parent,false);
    short[] minuteProfile=Natives.getScheduleProfile(index);
    Spinner profile=getProfileSpinner(context);
    profile.setSelection(minuteProfile[1]);
    clinicalStyleSpinner(profile);
    profile.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
        @Override public void onItemSelected(AdapterView<?> owner,View view,int position,long id) {
            minuteProfile[1]=(short)position;
            }
        @Override public void onNothingSelected(AdapterView<?> owner) {}
        });

    Button time=ClinicalUi.button(context,
            String.format(Locale.US,"%02d:%02d",minuteProfile[0]/60,minuteProfile[0]%60),
            ClinicalUi.ButtonRole.SECONDARY);
    time.setMinimumWidth(ClinicalUi.dp(context,112));
    time.setOnClickListener(view->context.getnumberview().gettimepicker(context,
            minuteProfile[0]/60,minuteProfile[0]%60,(hour,minute)-> {
                minuteProfile[0]=(short)(hour*60+minute);
                time.setText(String.format(Locale.US,"%02d:%02d",hour,minute));
                },()->{}));
    Button cancel=clinicalHeaderButton(context,R.string.cancel);
    Button save=ClinicalUi.button(context,context.getString(R.string.save),
            ClinicalUi.ButtonRole.PRIMARY);
    Button delete=ClinicalUi.button(context,context.getString(R.string.delete),
            ClinicalUi.ButtonRole.DANGER);
    delete.setVisibility(previousIndex<0?GONE:VISIBLE);

    LinearLayout content=clinicalScreenContent(context);
    content.addView(ClinicalUi.header(context,
            context.getString(previousIndex<0?R.string.settings_schedule_new_title:
                    R.string.settings_schedule_edit_title),cancel));
    TextView intro=ClinicalUi.body(context,
            context.getString(R.string.settings_schedule_editor_intro));
    intro.setPaddingRelative(ClinicalUi.dp(context,4),0,ClinicalUi.dp(context,4),
            ClinicalUi.dp(context,6));
    content.addView(intro);
    content.addView(ClinicalUi.sectionLabel(context,
            context.getString(R.string.settings_schedule_details_section)));
    content.addView(ClinicalUi.card(context,
            ClinicalUi.fieldRow(context,context.getString(R.string.settings_time),time),
            clinicalLabeledSpinner(context,R.string.profile,profile)));
    LinearLayout actions=new LinearLayout(context);
    actions.setOrientation(LinearLayout.HORIZONTAL);
    actions.setGravity(Gravity.CENTER_VERTICAL);
    actions.setPadding(0,ClinicalUi.dp(context,24),0,0);
    if(previousIndex>=0)
        actions.addView(delete,new LinearLayout.LayoutParams(0,WRAP_CONTENT,1.0f));
    if(previousIndex>=0) {
        Space gap=new Space(context);
        actions.addView(gap,new LinearLayout.LayoutParams(ClinicalUi.dp(context,12),1));
        }
    actions.addView(save,new LinearLayout.LayoutParams(0,WRAP_CONTENT,1.0f));
    content.addView(actions);
    ScrollView screen=ClinicalUi.scrollScreen(context,content);
    context.addMyContentView(screen,new ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT));
    MainActivity.setonback(()-> {
        removeContentView(screen);
        EnableControls(parent,true);
        });
    cancel.setOnClickListener(view->MainActivity.doonback());
    delete.setOnClickListener(view->{
        removeScheduleProfile(index);
        adapter.notifyItemRemoved(index);
        NumAlarm.handlealarm(Applic.app);
        MainActivity.doonback();
        });
    save.setOnClickListener(view->{
        int stored=Natives.setScheduleProfile(previousIndex,minuteProfile[0],minuteProfile[1]);
        if(stored<0) {
            Applic.argToaster(context,"Too many schedules",Toast.LENGTH_SHORT);
            return;
            }
        if(stored==index) {
            if(previousIndex<0)
                adapter.notifyItemInserted(stored);
            else
                adapter.notifyItemChanged(stored);
            }
        else
            adapter.notifyDataSetChanged();
        NumAlarm.handlealarm(Applic.app);
        MainActivity.doonback();
        });
    }

static private void changeProfile(MainActivity act,int wasindex, ProfileScheduleAdapter adapt,View parview) {
    if(useClinicalPhoneChild(isWearable)) {
        clinicalChangeProfile(act,wasindex,adapt,parview);
        return;
        }
    if(!isWearable)
        EnableControls(parview,false);
    int index;
    if(wasindex<0) {
        index=Natives.nrScheduledProfiles( );
        if(index>=10) {
                Applic.argToaster(act,"Too many schedules",Toast.LENGTH_LONG);
                return;
                }
          }
    else
        index=wasindex;

   short[] minpro=Natives.getScheduleProfile(index);
   var spin=getProfileSpinner(act) ;
   spin.setSelection(minpro[1]);
   spin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
        @Override
        public  void onItemSelected (AdapterView<?> parent, View view, int position, long id) {
             minpro[1]= (short) position;
            }
        @Override
        public  void onNothingSelected (AdapterView<?> parent) {

        } });
    Button timebut=new Button(act);
    timebut.setText(String.format(Locale.US,"%02d:%02d",minpro[0]/60, minpro[0]%60));

    timebut.setOnClickListener(
            v->  {
                act.getnumberview().gettimepicker(act,minpro[0]/60, minpro[0]%60,
                (hour,min) -> {
                        minpro[0]= (short) (hour*60+min);
                        timebut.setText(String.format(Locale.US,"%02d:%02d",hour,min));
                   },()->{;});
         });
    var save=getbutton(act,R.string.save);
    var cancel=getbutton(act,R.string.cancel);
    var delete=getbutton(act,R.string.delete);
    if(wasindex<0)
        delete.setVisibility(INVISIBLE);
     else {
        delete.setOnClickListener(v->{
            removeScheduleProfile(index);
            adapt.notifyItemRemoved(index);
    	    NumAlarm.handlealarm(Applic.app);
            MainActivity.doonback();
            });
         }
    cancel.setOnClickListener(v->{
    	MainActivity.doonback();
    	});
    save.setOnClickListener(v->{
        int induit=Natives.setScheduleProfile(wasindex,minpro[0], minpro[1]);
        if(induit>=0) {
    	    MainActivity.doonback();
            if(induit==index) {
                if(wasindex<0)
                    adapt.notifyItemInserted(induit);
                else
                    adapt.notifyItemChanged(induit);
                }
            else
                adapt.notifyDataSetChanged();
    	    NumAlarm.handlealarm(Applic.app);
            return;
            }
        else {
            Applic.argToaster(act,"Too many schedules",Toast.LENGTH_SHORT);
            }
    	});
//    Button help=getbutton(act,R.string.helpname);
    var height=GlucoseCurve.getheight();
   if(isWearable) {
       var width=GlucoseCurve.getheight();
        getMargins(cancel).bottomMargin=(int)(height*.1);
        getMargins(timebut).setMarginStart((int)(width*.01));
        var layout= new Layout(act, (l, w, h) -> {
    	int[] ret={w,h};
    	return ret;
    	},new View[]{cancel},new View[]{timebut,spin},new View[]{delete},new View[]{save});
        layout.setBackgroundColor(Applic.backgroundcolor);
        act.addMyContentView(layout,new ViewGroup.LayoutParams( MATCH_PARENT, MATCH_PARENT));
        MainActivity.setonback( () -> {
                removeContentView(layout);
                });
        }
     else {
        var layout= new Layout(act, (l, w, h) -> {
            int[] ret={w,h};
            return ret;
            },new View[]{timebut,spin},new View[]{cancel,delete,save});
            /*
        layout.measure(WRAP_CONTENT, WRAP_CONTENT);
        layout.setX( (GlucoseCurve.getwidth()-layout.getMeasuredWidth())*.5f);
        layout.setY( height-layout.getMeasuredHeight()-MainActivity.systembarBottom);
        */
         layout.setBackgroundResource(R.drawable.helpbackground);
        var  params =    new FrameLayout.LayoutParams( WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        params.bottomMargin=MainActivity.systembarBottom;

      act.addMyContentView(layout, params);
    //    act.addMyContentView(layout,new ViewGroup.LayoutParams(  WRAP_CONTENT,  WRAP_CONTENT));
        MainActivity.setonback( () -> {
                removeContentView(layout);
                EnableControls(parview,true);
                });
         }
    }

private static void clinicalScheduleProfiles(MainActivity context,View parent) {
    EnableControls(parent,false);
    Button close=clinicalHeaderButton(context,R.string.closename);
    Button add=ClinicalUi.button(context,context.getString(R.string.settings_add_schedule),
            ClinicalUi.ButtonRole.PRIMARY);
    LinearLayout scheduleHelp=ClinicalUi.actionRow(context,
            context.getString(R.string.helpname),context.getString(R.string.settings_schedule_help_hint));
    RecyclerView list=new RecyclerView(context);
    list.setLayoutManager(new LinearLayoutManager(context));
    list.setClipToPadding(false);
    list.setPadding(ClinicalUi.dp(context,2),ClinicalUi.dp(context,5),
            ClinicalUi.dp(context,2),ClinicalUi.dp(context,12));
    list.setBackgroundColor(ClinicalUi.window(context));

    LinearLayout content=clinicalScreenContent(context);
    content.setLayoutParams(new ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT));
    content.addView(ClinicalUi.header(context,
            context.getString(R.string.settings_schedules_title),close));
    TextView summary=ClinicalUi.body(context,context.getString(
            R.string.settings_schedules_count,Natives.nrScheduledProfiles()));
    summary.setPaddingRelative(ClinicalUi.dp(context,4),0,ClinicalUi.dp(context,4),
            ClinicalUi.dp(context,8));
    content.addView(summary);
    content.addView(list,new LinearLayout.LayoutParams(MATCH_PARENT,0,1.0f));
    content.addView(ClinicalUi.card(context,scheduleHelp));
    LinearLayout.LayoutParams addParams=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
    addParams.topMargin=ClinicalUi.dp(context,14);
    add.setLayoutParams(addParams);
    content.addView(add);
    context.addMyContentView(content,new ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT));
    ProfileScheduleAdapter adapter=new ProfileScheduleAdapter(content);
    list.setAdapter(adapter);
    MainActivity.setonback(()-> {
        removeContentView(content);
        EnableControls(parent,true);
        });
    close.setOnClickListener(view->MainActivity.doonback());
    add.setOnClickListener(view->changeProfile(context,-1,adapter,content));
    scheduleHelp.setOnClickListener(view->help(R.string.schedulehelp,context));
    }

static public void scheduleProfiles(MainActivity act,View parview) {
    if(useClinicalPhoneChild(isWearable)) {
        clinicalScheduleProfiles(act,parview);
        return;
        }
    if(!isWearable)
        EnableControls(parview,false);
    if(doLog) {Log.i(LOG_ID,"scheduleProfiles");};
    Button ok=getbutton(act,R.string.closename);
    Button newone=getbutton(act,R.string.newname);
    RecyclerView recycle = new RecyclerView(act);
    recycle.setLayoutParams(new ViewGroup.LayoutParams( WRAP_CONTENT , WRAP_CONTENT));
    LinearLayoutManager lin = new LinearLayoutManager(act);
    recycle.setLayoutManager(lin);
    View[][] views;
    if(isWearable) {
        views=new View[][]{new View[]{ok},new View[]{recycle},new View[]{newone}};
        }
    else {
        final   int pad=(int)(tk.glucodata.GlucoseCurve.metrics.density*9.0);
        recycle.setPaddingRelative(pad,0,0,0);
        Button help=getbutton(act,R.string.helpname);
        help.setOnClickListener(v->{
            help(R.string.schedulehelp,act);
            });
        views=new View[][]{new View[]{recycle},new View[]{help,newone,ok}};
        }
    var layout= new Layout(act, (l, w, h) -> {
    	int[] ret={w,h};
    	return ret;
    	},views);
    var numadapt = new ProfileScheduleAdapter(layout); 
    recycle.setAdapter(numadapt);
    ok.setOnClickListener(v->{
    	MainActivity.doonback();
    	});
    newone.setOnClickListener(v->{
         changeProfile(act, -1, numadapt,layout);
    	});
    ViewGroup.LayoutParams params;
    if(!isWearable) {
        var height=GlucoseCurve.getheight();
        recycle.setMinimumHeight(2*height/3);
        /*
        layout.measure(WRAP_CONTENT, WRAP_CONTENT);
        layout.setX( (GlucoseCurve.getwidth()-layout.getMeasuredWidth())*.5f);
        layout.setY( (height-layout.getMeasuredHeight())*.5f);
        */
          params =    new FrameLayout.LayoutParams( WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER|Gravity.CENTER_HORIZONTAL);
        }
     else {
       final var type=MATCH_PARENT;
       params=new ViewGroup.LayoutParams( type,type);
       }
     act.addMyContentView(layout,params);
     if(isWearable)
        layout.setBackgroundColor(Applic.backgroundcolor);
    else
        layout.setBackgroundResource(R.drawable.dialogbackground);
    MainActivity.setonback( () -> {
                removeContentView(layout);
            if(!isWearable)
                EnableControls(parview,true);
            });
    }
static public void setProfile(MainActivity act,int profile) {
       int oldtheme=Natives.getTheme();
       boolean oldisoval=Natives.getisOval();
       int oldradius=Natives.getradius();
       Natives.setProfile(profile);
       SuperGattCallback.initAlarmTalk();
       if(DynamicTheme) {
           if(oldtheme!=Natives.getTheme()||oldisoval!=Natives.getisOval()||oldradius!=Natives.getradius()) {
                act.recreate();
                }
           }
        }

private static LinearLayout clinicalAlarmThresholdRow(MainActivity context,
        EditText value) {
    value.setVisibility(VISIBLE);
    clinicalStyleInput(value);
    LinearLayout row=ClinicalUi.fieldRow(context,
            context.getString(R.string.settings_threshold_value),value);
    row.addView(clinicalUnitLabel(context));
    return row;
    }

private static void clinicalSyncExpandable(boolean enabled,View... details) {
    for(View detail:details)
        if(detail!=null)
            detail.setVisibility(enabled?VISIBLE:GONE);
    }

private static void clinicalAlarmSettings(MainActivity context,View parent) {
    clinicalNormalizeUnifiedAlarms();
    GlucoseAlertSettingsPage.show(context,()-> {
        clinicalNormalizeUnifiedAlarms();
        parent.setVisibility(VISIBLE);
        tk.glucodata.help.hidekeyboard(context);
        });
    }

/** Removes hidden legacy phone sources while preserving all thresholds and
 * every current-glucose or signal-loss setting. */
private static void clinicalNormalizeUnifiedAlarms() {
    Natives.setalarms(Natives.alarmlow(),Natives.alarmhigh(),
            Natives.hasalarmlow(),Natives.hasalarmhigh(),false,
            Natives.hasalarmloss());
    Natives.setAdvancedAlarms(Natives.alarmverylow(),Natives.alarmveryhigh(),
            false,false,false,false,Natives.alarmprelow(),
            Natives.alarmprehigh());
    }

static private void alarmsettings(MainActivity context,View parview) {
    parview.setVisibility(GONE);
    if(useClinicalPhoneChild(isWearable)) {
        clinicalAlarmSettings(context,parview);
        return;
        }
    TextView alarmlow,alarmhigh;
    View[] lowalarm= mkalarm(context,context.getString(R.string.lowglucosealarm),Natives.hasalarmlow(),Natives.alarmlow(),0);
    View[] highalarm=mkalarm(context,context.getString(R.string.highglucosealarm),Natives.hasalarmhigh(),Natives.alarmhigh(),1);
    alarmlow=(TextView)lowalarm[1];
    alarmhigh=(TextView)highalarm[1];
    alarmlow.setText( float2string(Natives.alarmlow()));
    alarmhigh.setText( float2string(Natives.alarmhigh()));
    CheckDirectionBox isvalue = new CheckDirectionBox(context);
    final boolean hasvalue=Natives.hasvaluealarm();
    isvalue.setChecked(hasvalue); //Value
    isvalue.setText(R.string.valueavailablenotification);
    Button ringisvalue=getbutton(context,R.string.ringtonename);
    Button help=getbutton(context,R.string.helpname);
    help.setOnClickListener(v->{help(R.string.alarmhelp,(MainActivity)(v.getContext())); });
    if(!hasvalue) ringisvalue.setVisibility(INVISIBLE);
    isvalue.setOnCheckedChangeListener(
             (buttonView,  isChecked) -> {
             if(isChecked) {
                ringisvalue.setVisibility(VISIBLE);
                }
            else {
                ringisvalue.setVisibility(INVISIBLE);
                }
            });


//    var usealarm=getcheckbox(context, R.string.USE_ALARM, Natives.getUSEALARM());

    var alarmis=getlabel(context,R.string.alarmis);
    var alarmtype=new RadioGroup(context);

    int id=0;
    alarmtype.addView(getradiobuttonId(context,R.string.alarm,id++));
    alarmtype.addView(getradiobuttonId(context,R.string.notification,id++));
    alarmtype.addView(getradiobuttonId(context,R.string.media,id++));
    alarmtype.check(getalarmSoundType());

    final boolean alarmloss= Natives.hasalarmloss();
        CheckDirectionBox lossalarm = new CheckDirectionBox(context);
        lossalarm.setChecked(alarmloss); //Value
        lossalarm.setText(R.string.lossofsignalalarm);
    Button ringlossalarm=getbutton(context,R.string.ringtonename);
        EditText losswait = new EditText(context);
        losswait.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
//    losswait.setImeOptions(editoptions);
        losswait.setImeOptions(editoptions);
        losswait.setMinEms(2);
        int waitloss=Natives.readalarmsuspension(4);
        losswait.setText(waitloss+"");

        var min=getlabel(context,R.string.minutes);
        lossalarm.setOnCheckedChangeListener(
                (buttonView,  isChecked) -> {
                    if(isChecked) {
                        ringlossalarm.setVisibility(VISIBLE);
                        losswait.setVisibility(VISIBLE);
                        min.setVisibility(VISIBLE);

                    }
                    else {
                        ringlossalarm.setVisibility(INVISIBLE);
                        losswait.setVisibility(INVISIBLE);
                        min.setVisibility(INVISIBLE);
                    }
                });
    if(!alarmloss) {
        ringlossalarm.setVisibility(INVISIBLE);
        losswait.setVisibility(INVISIBLE);
        min.setVisibility(INVISIBLE);
        }


    var Save=getbutton(context,R.string.closename);
//    var Cancel=getbutton(context,R.string.cancel);
     var advanced=getbutton(context,R.string.advanced);
//    var schedules=getbutton(context,R.string.schedules);
    View[][] views;
    var spin=getProfileSpinner(context);
   int pos=Natives.getProfile();
    spin.setSelection(pos);
    if(isWearable) {
        var ala=getlabel(context,R.string.alarms);
        final   int pad=(int)(tk.glucodata.GlucoseCurve.metrics.density*5.0);
           ala.setPadding(pad,pad,pad,0);
        final var width= GlucoseCurve.getwidth();
        int hormarg=(int)(width*.08);

        getMargins(lowalarm[1]).setMarginStart(hormarg);
        getMargins(highalarm[1]).setMarginStart(hormarg);
        getMargins(Save).topMargin=pad;
        views=new View[][]{new View[]{ala},new View[]{spin},new View[]{lowalarm[0]},new View[]{lowalarm[1],lowalarm[2]}, new View[]{highalarm[0]},new View[]{highalarm[1],highalarm[2]},
new View[]{lossalarm},new View[]{losswait,min,ringlossalarm},
new View[]{isvalue},new View[]{ringisvalue},new View[]{alarmis,alarmtype},new View[]{advanced},new View[]{Save}};
        }
    else {
         View[] lostrow={lossalarm,losswait,min,ringlossalarm};
         View[] row6={isvalue, ringisvalue,alarmis,alarmtype};
         View[] rowshow={help,spin,advanced,Save};
         var marg=(int)(GlucoseCurve.getwidth()*.05f);

        getMargins(help).setMarginStart(marg);
        getMargins(Save).setMarginEnd(marg);


        views=new View[][]{lowalarm,highalarm,lostrow,row6,rowshow};
        }    
    View lay;
        Layout layout = new Layout(context, (l, w, h) -> {
            hideSystemUI();
        int[] ret={w,h};
        return ret;
        },views);
   if(isWearable) {
//       layout.setPadding(0, (int) (GlucoseCurve.metrics.density*10),0,0);
      final int sidepad=(int)(GlucoseCurve.metrics.density*5);
       layout.setPaddingRelative((int)(GlucoseCurve.metrics.density*8), sidepad,(int)(GlucoseCurve.metrics.density*12),sidepad);
       }
     else {
        final int sidepad=(int)(GlucoseCurve.metrics.density*8);
        layout.setPadding(MainActivity.systembarLeft+sidepad,MainActivity.systembarTop*2/3,sidepad+MainActivity.systembarRight,sidepad+MainActivity.systembarBottom*9/10);
        }
    var scroll=new ScrollView(context);    
    scroll.addView(layout);
    scroll.setFillViewport(true);
    scroll.setSmoothScrollingEnabled(false);
   scroll.setScrollbarFadingEnabled(true);
   scroll.setVerticalScrollBarEnabled(Applic.scrollbar);
    lay=scroll;
    /*
    if(isWearable) {
        }
    else
        lay=layout; */
//    schedules.setOnClickListener(v->scheduleProfiles(context,lay));
        lay.setBackgroundColor(Applic.backgroundcolor);
    context.addMyContentView(lay, new ViewGroup.LayoutParams( MATCH_PARENT ,MATCH_PARENT));


    lowalarm[2].setOnClickListener(v->{
        new tk.glucodata.RingTones(0).mkviews(context,context.getString(R.string.lowglucosealarm),lay);
        });
    highalarm[2].setOnClickListener(v->{
        new tk.glucodata.RingTones(1).mkviews(context,context.getString(R.string.highglucosealarm),lay);
        });
/*
    context.setonback(() -> {
        parview.setVisibility(VISIBLE);
        tk.glucodata.help.hidekeyboard(context);
        removeContentView(lay) ;
        });
*/
   // usealarm.setOnCheckedChangeListener( (buttonView,  isChecked) -> Natives.setUSEALARM(isChecked));
      alarmtype.setOnCheckedChangeListener( (g,i)-> {
            Natives.setalarmSoundType(i);
         });

    BooleanSupplier saver=() -> {
      final boolean hasloss= lossalarm.isChecked();
        if(hasloss) {
            String str = losswait.getText().toString();
             try  {
                if(str != null) {
                     short wa = Short.parseShort(str);
                     if(wa!=Natives.readalarmsuspension(4)) {
                        Natives.writealarmsuspension(4, wa);
                        tk.glucodata.SuperGattCallback.glucosealarms.setLossAlarm();
                        }
                    }
                  else {
                        Applic.argToaster(context,context.getString(R.string.cantsetminutes)+" nothing",Toast.LENGTH_SHORT);
                        return false;
                        }
                } catch(Throwable e) {
                    Log.stack(LOG_ID,"parseShort",e);
                    Applic.argToaster(context,context.getString(R.string.cantsetminutes)+str,Toast.LENGTH_SHORT);
                    return false;
                }
            }
         boolean haslow=((CheckDirectionBox) lowalarm[0]).isChecked();
         boolean hashigh=((CheckDirectionBox) highalarm[0]).isChecked();
         Natives.setalarms(str2float(((EditText)lowalarm[1]).getText().toString()),
                    str2float(((EditText)highalarm[1]).getText().toString()),
                     haslow, hashigh, isvalue.isChecked(),hasloss);
         return true;
         };
    Save.setOnClickListener(v->{
        if(!saver.getAsBoolean()) {
            return;
            }
        context.poponback();
        removeContentView(lay) ;
        parview.setVisibility(VISIBLE);
        tk.glucodata.help.hidekeyboard(context);
        });
    context.setonback(() -> {
        saver.getAsBoolean();
        parview.setVisibility(VISIBLE);
        tk.glucodata.help.hidekeyboard(context);
        removeContentView(lay) ;
        });

   spin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
        @Override
        public  void onItemSelected (AdapterView<?> parent, View view, int position, long id) {
            if(position!=pos) {
               saver.getAsBoolean();
               removeContentView(lay) ;
               MainActivity.poponback();
               setProfile(context,position);
               alarmsettings(context,parview);
               }
            }
        @Override
        public  void onNothingSelected (AdapterView<?> parent) {

        } });

     advanced.setOnClickListener(v->{ 
        saver.getAsBoolean();
        context.poponback();
        removeContentView(lay) ;
        advancedalarm(context,parview); 
        });
    ringisvalue.setOnClickListener(v->{
        new tk.glucodata.RingTones(2).mkviews(context,context.getString(R.string.valuenotification), lay);
        });
    ringlossalarm.setOnClickListener(v->{
        new tk.glucodata.RingTones(4).mkviews(context,context.getString(R.string.lossofsignal),lay);
        });
}


final private static String  codestr=String.valueOf(BuildConfig.VERSION_CODE);


static private final List<String> supportedlanguages= Arrays.asList("Language","ar","be","de","en","es","fr","hi","it","ja","nl","pl","pt","ru","sv","tr","uk","uz","zh");

static public Spinner getGenSpin(Activity context) {
    var spin=  new Spinner(context,isWearable?MODE_DIALOG: MODE_DROPDOWN);
    avoidSpinnerDropdownFocus(spin);
    /*
   if(isWearable) {
      var width= GlucoseCurve.getwidth();
      spin.setDropDownWidth(width);
      spin.setDropDownHorizontalOffset(0);
      }
      */
     return spin;
     }

static private Spinner languagespinner(MainActivity context) {
   var spin=  getGenSpin(context);
//   spin.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
 //  spin.setTextDirection(View.TEXT_DIRECTION_LTR);
    var locales=AppCompatDelegate.getApplicationLocales();
    int prepos;
    if(locales.isEmpty()||(prepos=supportedlanguages.indexOf(locales.get(0).getLanguage()))<1)
        prepos=0;
    final int pos=prepos;
    spin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
        @Override
        public  void onItemSelected (AdapterView<?> parent, View view, int position, long id) {
            if(position!=pos) {
               var newlocale=(position==0)?getEmptyLocaleList():LocaleListCompat.forLanguageTags(supportedlanguages.get(position));
               AppCompatDelegate.setApplicationLocales(newlocale);
               }
            }
        @Override
        public  void onNothingSelected (AdapterView<?> parent) {

        } });

    supportedlanguages.set(0,context.getString(R.string.languagename));
   final var adapt=new LabelAdapter<String>(context,supportedlanguages,0);
    spin.setAdapter(adapt);
//    adapt.setDropDownViewResource(R.layout.spinner_dialog_item_center);

//    var pos=supportedlanguages.indexOf(getlocale().getLanguage()); if(pos<0) pos=0;
    spin.setSelection(pos);

//       spin.setPadding(0,0,0,0);
    return spin;
    }
static void mkrangelabel(TextView view,int res,float low, float high) {
     view.setText(view.getContext().getString(res)+" "+float2string(low)+"-"+float2string(high));
   }

private static void clinicalDisplaySettings(MainActivity context,Settings settings) {
    EditText graphLow=new EditText(context);
    EditText graphHigh=new EditText(context);
    EditText targetLow=new EditText(context);
    EditText targetHigh=new EditText(context);
    for(EditText input:new EditText[]{graphLow,graphHigh,targetLow,targetHigh}) {
        input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setImeOptions(editoptions);
        }
    graphLow.setText(float2string(Natives.graphlow()));
    graphHigh.setText(float2string(Natives.graphhigh()));
    final boolean targetMmol=Natives.getunit()==1;
    final float productTargetLow=targetMmol?4.2f:75.6f;
    final float productTargetHigh=targetMmol?9.0f:162.0f;
    targetLow.setText(float2string(productTargetLow));
    targetHigh.setText(float2string(productTargetHigh));
    targetLow.setEnabled(false);
    targetHigh.setEnabled(false);

    String oldThreshold=float2string(Natives.getthreshold());
    EditText threshold=getnumedit(context,oldThreshold);
    clinicalStyleInput(threshold);

    CheckDirectionBox manualTime=getcheckbox(context,R.string.time,!Natives.getfixatex());
    CheckDirectionBox axisLeft=getcheckbox(context,R.string.glucoseaxisleft,Natives.getlevelleft());
    axisLeft.setOnCheckedChangeListener((button,isChecked)->Natives.setlevelleft(isChecked));
    CheckDirectionBox predict=getcheckbox(context,R.string.dexfuture,Natives.getdexcomPredict());
    predict.setOnCheckedChangeListener((button,isChecked)->Natives.setdexcomPredict(isChecked));
    CheckDirectionBox clampNow=getcheckbox(context,R.string.clampnow,Natives.getcurrentRelative());
    clampNow.setOnCheckedChangeListener((button,isChecked)->Natives.setcurrentRelative(isChecked));
    CheckDirectionBox twelveHour=getcheckbox(context,R.string.hour12,!Natives.gethour24());
    twelveHour.setOnCheckedChangeListener((button,isChecked)->Applic.sethour24(!isChecked));

    CheckDirectionBox graphScans=getcheckbox(context,R.string.scansname,
            Natives.getshowscans());
    graphScans.setOnCheckedChangeListener((button,isChecked)-> {
        Natives.setshowscans(isChecked);
        context.requestRender();
        });
    CheckDirectionBox graphCalibratedScans=getcheckbox(context,R.string.calibrated,
            Natives.getshowcalibratedscans());
    graphCalibratedScans.setOnCheckedChangeListener((button,isChecked)-> {
        Natives.setshowcalibratedscans(isChecked);
        PredictiveAlertSettingsPage.onLocalCalibrationStateChanged(context);
        context.requestRender();
        });
    CheckDirectionBox graphStream=getcheckbox(context,R.string.streamname,
            Natives.getshowstream());
    graphStream.setOnCheckedChangeListener((button,isChecked)-> {
        Natives.setshowstream(isChecked);
        context.requestRender();
        });
    CheckDirectionBox graphCalibratedStream=getcheckbox(context,R.string.calibrated,
            Natives.getshowcalibratedstream());
    graphCalibratedStream.setOnCheckedChangeListener((button,isChecked)-> {
        Natives.setshowcalibratedstream(isChecked);
        PredictiveAlertSettingsPage.onLocalCalibrationStateChanged(context);
        context.requestRender();
        });
    CheckDirectionBox graphHistory=getcheckbox(context,R.string.historyname,
            Natives.getshowhistories());
    graphHistory.setOnCheckedChangeListener((button,isChecked)-> {
        Natives.setshowhistories(isChecked);
        context.requestRender();
        });
    CheckDirectionBox graphCalibratedHistory=getcheckbox(context,R.string.calibrated,
            Natives.getshowcalibratedhistories());
    graphCalibratedHistory.setOnCheckedChangeListener((button,isChecked)-> {
        Natives.setshowcalibratedhistories(isChecked);
        context.requestRender();
        });
    CheckDirectionBox graphAmounts=getcheckbox(context,R.string.amountsname,
            Natives.getshownumbers());
    graphAmounts.setOnCheckedChangeListener((button,isChecked)-> {
        Natives.setshownumbers(isChecked);
        context.requestRender();
        });
    CheckDirectionBox graphMeals=getcheckbox(context,R.string.mealsname,
            Natives.getshowmeals());
    graphMeals.setOnCheckedChangeListener((button,isChecked)-> {
        Natives.setshowmeals(isChecked);
        context.requestRender();
        });
    CheckDirectionBox graphFloating=getcheckbox(context,R.string.floatname,
            Natives.getfloatglucose());
    graphFloating.setOnCheckedChangeListener((button,isChecked)->
            Floating.setfloatglucose(context,isChecked));
    CheckDirectionBox graphDarkMode=getcheckbox(context,R.string.darkmode,
            Natives.getInvertColors());
    graphDarkMode.setOnCheckedChangeListener((button,isChecked)->
            Natives.setInvertColors(isChecked));
    CheckDirectionBox graphSystemUi=getcheckbox(context,R.string.system_ui,
            Natives.getsystemui());
    graphSystemUi.setOnCheckedChangeListener((button,isChecked)-> {
        Natives.setsystemui(isChecked);
        context.selectionSystemUI();
        });

    Spinner language=languagespinner(context);
    Button close=clinicalHeaderButton(context,R.string.closename);
    LinearLayout content=clinicalScreenContent(context);
    content.addView(ClinicalUi.header(context,
            context.getString(R.string.settings_display_title),close));
    TextView intro=ClinicalUi.body(context,context.getString(R.string.settings_display_intro));
    intro.setPaddingRelative(ClinicalUi.dp(context,4),0,ClinicalUi.dp(context,4),
            ClinicalUi.dp(context,6));
    content.addView(intro);

    content.addView(ClinicalUi.sectionLabel(context,
            context.getString(R.string.settings_graph_readings_section)));
    content.addView(ClinicalUi.card(context,
            clinicalToggleRow(context,graphScans,
                    context.getString(R.string.settings_graph_scans_hint)),
            clinicalToggleRow(context,graphCalibratedScans,
                    context.getString(R.string.settings_graph_calibrated_scans_hint)),
            clinicalToggleRow(context,graphStream,
                    context.getString(R.string.settings_graph_stream_hint)),
            clinicalToggleRow(context,graphCalibratedStream,
                    context.getString(R.string.settings_graph_calibrated_stream_hint)),
            clinicalToggleRow(context,graphHistory,
                    context.getString(R.string.settings_graph_history_hint)),
            clinicalToggleRow(context,graphCalibratedHistory,
                    context.getString(R.string.settings_graph_calibrated_history_hint))));

    content.addView(ClinicalUi.sectionLabel(context,
            context.getString(R.string.settings_graph_records_section)));
    content.addView(ClinicalUi.card(context,
            clinicalToggleRow(context,graphAmounts,
                    context.getString(R.string.settings_graph_amounts_hint)),
            clinicalToggleRow(context,graphMeals,
                    context.getString(R.string.settings_graph_meals_hint))));

    content.addView(ClinicalUi.sectionLabel(context,
            context.getString(R.string.settings_graph_presentation_section)));
    content.addView(ClinicalUi.card(context,
            clinicalToggleRow(context,graphFloating,
                    context.getString(R.string.settings_graph_floating_hint)),
            clinicalToggleRow(context,graphDarkMode,
                    context.getString(R.string.settings_graph_dark_hint)),
            clinicalToggleRow(context,graphSystemUi,
                    context.getString(R.string.settings_graph_system_ui_hint))));

    content.addView(ClinicalUi.sectionLabel(context,
            context.getString(R.string.settings_ranges_section)));
    LinearLayout graphRow=clinicalRangeRow(context,R.string.graphrange,graphLow,graphHigh);
    LinearLayout targetRow=clinicalRangeRow(context,R.string.targetrange,targetLow,targetHigh);
    LinearLayout thresholdRow=ClinicalUi.fieldRow(context,
            context.getString(R.string.threshold),threshold);
    content.addView(ClinicalUi.card(context,graphRow,targetRow,thresholdRow));

    content.addView(ClinicalUi.sectionLabel(context,
            context.getString(R.string.settings_chart_section)));
    content.addView(ClinicalUi.card(context,
            clinicalToggleRow(context,manualTime,
                    context.getString(R.string.settings_manual_time_hint)),
            clinicalToggleRow(context,axisLeft,null),
            clinicalToggleRow(context,predict,null),
            clinicalToggleRow(context,clampNow,null)));

    LinearLayout colors=ClinicalUi.actionRow(context,context.getString(R.string.colors),
            context.getString(R.string.settings_colors_hint));
    LinearLayout theme=ClinicalUi.actionRow(context,context.getString(R.string.theme),
            context.getString(R.string.settings_theme_hint));
    LinearLayout iob=ClinicalUi.actionRow(context,"IOB",
            context.getString(R.string.settings_iob_hint));
    content.addView(ClinicalUi.sectionLabel(context,
            context.getString(R.string.settings_appearance_section)));
    content.addView(ClinicalUi.card(context,
            colors,theme,clinicalLabeledSpinner(context,R.string.languagename,language),
            clinicalToggleRow(context,twelveHour,null),iob));

    LinearLayout displayHelp=ClinicalUi.actionRow(context,
            context.getString(R.string.helpname),context.getString(R.string.settings_display_help_hint));
    content.addView(ClinicalUi.sectionLabel(context,
            context.getString(R.string.settings_support_section)));
    content.addView(ClinicalUi.card(context,displayHelp));

    ScrollView screen=ClinicalUi.scrollScreen(context,content);
    context.addMyContentView(screen,new ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT));

    Runnable saver=()-> {
        String changedThreshold=threshold.getText().toString();
        if(!oldThreshold.equals(changedThreshold)) {
            float value=str2float(changedThreshold);
            if(!isDisplayThresholdValid(value))
                Applic.argToaster(context,"A threshold should 0.0 - 0.8",Toast.LENGTH_LONG);
            else
                setthreshold(value);
            }
        Natives.setfixatex(!manualTime.isChecked());
        Natives.setGraphRange(str2float(graphLow.getText().toString()),
                str2float(graphHigh.getText().toString()));
        // The dashboard, predictive alerts and target-band colors deliberately
        // share one product target. Clinical low/high alarms stay independent.
        Natives.setTargetRange(productTargetLow,productTargetHigh);
        removeContentView(screen);
        tk.glucodata.help.hidekeyboard(context);
        };
    MainActivity.setonback(saver);
    close.setOnClickListener(view->{
        context.poponback();
        saver.run();
        });
    colors.setOnClickListener(view->{
        MainActivity.doonback();
        settings.finish();
        SetColors.show(context);
        });
    theme.setOnClickListener(view->SelectTheme.show(context,screen));
    iob.setOnClickListener(view->tk.glucodata.IOB.mkview(context));
    displayHelp.setOnClickListener(view->help(R.string.displayhelp,context));
    }

static private void displaysettings(MainActivity context,Settings settings) {

    if(useClinicalPhoneChild(isWearable)) {
        clinicalDisplaySettings(context,settings);
        return;
        }

        TextView graphlabel = new TextView(context);
        graphlabel.setText(R.string.graphrange);
        var glow = new EditText(context);
        glow.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);

        glow.setImeOptions(editoptions);
        glow.setMinEms(1);


        TextView line = new TextView(context);
        line.setText("-");
        var ghigh = new EditText(context);

        ghigh.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        ghigh.setImeOptions(editoptions);
        ghigh.setMinEms(2);
        glow.setText(float2string(Natives.graphlow()));
        ghigh.setText(float2string(Natives.graphhigh()));
        Object[] graphrow = {graphlabel, new View[] {glow, line, ghigh}};

    TextView targetlabel = getlabel(context,R.string.targetrange);
        var tlow = new EditText(context);

        tlow.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        tlow.setMinEms(1);
        tlow.setImeOptions(editoptions);
        TextView line2=new TextView(context); line2.setText("-");
        var thigh = new EditText(context);

        thigh.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        thigh.setMinEms(2);
        thigh.setImeOptions(editoptions);
        Object[] targetrow = {targetlabel, new View[]{tlow, line2, thigh}};



        tlow.setText(float2string(Natives.targetlow()));
        thigh.setText(float2string(Natives.targethigh()));

    var colbut=getbutton(context,R.string.colors);
   var help=getbutton(context,R.string.helpname);
  help.setOnClickListener(v->{help(R.string.displayhelp,context); });

    var hour12=getcheckbox(context,R.string.hour12,!Natives.gethour24());
    hour12.setOnCheckedChangeListener( (buttonView,  isChecked) -> {
         Applic.sethour24(!isChecked);
         });
    var fixed=getcheckbox(context,R.string.clampnow,Natives.getcurrentRelative());
     fixed.setOnCheckedChangeListener( (buttonView,  isChecked) -> Natives.setcurrentRelative(isChecked));


    TextView scalelabel=getlabel(context,R.string.manuallyscale);
    CheckDirectionBox fixatex =new CheckDirectionBox(context);

    CheckDirectionBox fixatey =new CheckDirectionBox(context);


    fixatex.setText(R.string.time);
    fixatey.setText(R.string.glucose);
    fixatex.setChecked(!Natives.getfixatex());
    fixatey.setChecked(!Natives.getfixatey());

        final var threslabel=getlabel(context,R.string.threshold);

        final var thresstring=float2string(Natives.getthreshold());
        final var threshold=getnumedit(context,thresstring);

      
      var close=getbutton(context,R.string.closename);

    var langspin=languagespinner(context);
      Layout lay;
    if(isWearable)  {
      var iob=getcheckbox(context,"IOB",Natives.getIOB());
      iob.setOnCheckedChangeListener( (buttonView,  isChecked) -> {
                if(!Natives.setIOB(isChecked)) {
                    iob.setChecked(false);
                    tk.glucodata.help.help(R.string.IOBhelp,context);
                    }
                }
            ); 
    if(!useclose)
          close.setVisibility(GONE);
    targetlabel.setPaddingRelative((int)(tk.glucodata.GlucoseCurve.metrics.density*8.0),0,0,0);
    graphlabel.setPaddingRelative((int)(tk.glucodata.GlucoseCurve.metrics.density*8.0),0,0,0);
    //colbut.setPadding(0,0,0,0);
    threslabel.setPaddingRelative((int)(tk.glucodata.GlucoseCurve.metrics.density*7.0),0,0,0);
     var setuseclose=getcheckbox(context,R.string.useclose,useclose) ;
    setuseclose.setOnCheckedChangeListener( (buttonView,  isChecked) -> { 
         Specific.setclose(isChecked);
         Natives.setdontuseclose(!isChecked); 
         context.finish();
         context.startActivity(context.getIntent());
      });
   //      Button display=getbutton(context,context.getString(R.string.display));
   /*
     var Scans=getcheckbox(context,R.string.scansname,getshowscans()) ;
     var Calibrated=getcheckbox(context,R.string.calibrated,getshowcalibratedstream()) ;
     var History=getcheckbox(context,R.string.historyname,getshowhistories()) ;
     var Stream=getcheckbox(context,R.string.streamname,getshowstream()) ;
     var Amounts=getcheckbox(context,R.string.amountshort,getshownumbers()) ;

Calibrated.setOnCheckedChangeListener( (buttonView,  isChecked) -> { Natives.setshowcalibratedstream(isChecked); });
Scans.setOnCheckedChangeListener( (buttonView,  isChecked) -> { Natives.setshowscans(isChecked); });
    History.setOnCheckedChangeListener( (buttonView,  isChecked) -> { Natives.setshowhistories(isChecked); });
    Stream.setOnCheckedChangeListener( (buttonView,  isChecked) -> { Natives.setshowstream(isChecked); });
    Amounts.setOnCheckedChangeListener( (buttonView,  isChecked) -> { Natives.setshownumbers(isChecked); });
    */
       fixed.setPaddingRelative(0,0,0,(int)(tk.glucodata.GlucoseCurve.metrics.density*7.0));
        lay = new Layout(context, (l, w, h) -> {
                  int[] ret={w,h};
                 return ret;
               },new View[]{colbut},graphrow,targetrow,new View[] {hour12},new View[]{scalelabel}, new View[]{fixatex},new View[]{fixatey},new View[]{threslabel,threshold},new View[]{fixed},new View[]{iob}/*,new View[]{Scans},new View[]{History},new View[]{Stream},new View[]{Calibrated},new View[]{Amounts}*/,new View[]{setuseclose},new View[]{close},new View[]{langspin});
         }
      else {    
//      var iob=getcheckbox(context,"IOB",Natives.getIOB());
      var iob=getbutton(context,"IOB");
      iob.setOnClickListener(v-> {
        tk.glucodata.IOB.mkview(context);
        });
        var dexfuture=getcheckbox(context,R.string.dexfuture,Natives.getdexcomPredict());
         dexfuture.setOnCheckedChangeListener( (buttonView,  isChecked) -> Natives.setdexcomPredict(isChecked) );
    CheckDirectionBox levelleft= new CheckDirectionBox(context);
    levelleft.setText(R.string.glucoseaxisleft);
    levelleft.setChecked(Natives.getlevelleft());

    levelleft.setOnCheckedChangeListener( (buttonView,  isChecked) -> {
             Natives.setlevelleft(isChecked);
            });

        var amarg=(int)( .15f*GlucoseCurve.getwidth());

        Layout.getMargins(colbut).setMarginStart(amarg);

        Layout.getMargins(close).setMarginEnd(amarg);
        var themebut=getbutton(context,R.string.theme);
        lay = new Layout(context, (l, w, h) -> {
                  int[] ret={w,h};
                 return ret;
               },graphrow,new View[]{scalelabel,fixatex, fixatey},targetrow,new View[]{threslabel,threshold,dexfuture},new View[] {levelleft},new View[] {hour12,langspin,iob,fixed},new View[]{colbut,themebut,help,close});

       themebut.setOnClickListener(v-> {
            SelectTheme.show(context,lay);
            });
/*
        iob.setOnCheckedChangeListener( (buttonView,  isChecked) -> {
                if(!Natives.setIOB(isChecked)) {
                    iob.setChecked(false);
                    EnableControls(lay,false);
                    tk.glucodata.help.help(R.string.IOB,context,l->EnableControls(lay,true) );
                    }
                }
            ); */
         }

     lay.setBackgroundColor(Applic.backgroundcolor);
    if(isWearable) {
      final   int pad=(int)(tk.glucodata.GlucoseCurve.metrics.density*10.0);
       lay.setPaddingRelative((int)(tk.glucodata.GlucoseCurve.metrics.density*6.0),(int)(tk.glucodata.GlucoseCurve.metrics.density*11.0),(int)(tk.glucodata.GlucoseCurve.metrics.density*7.0),pad*2);
        }
     else {
      final   int pad=(int)(tk.glucodata.GlucoseCurve.metrics.density*8.0);
       lay.setPadding(MainActivity.systembarLeft+pad,MainActivity.systembarTop*3/4,pad+MainActivity.systembarRight,pad+MainActivity.systembarBottom*3/4);
      }

    var scroll=new ScrollView(context);
    scroll.addView(lay);
    scroll.setFillViewport(true);
    scroll.setSmoothScrollingEnabled(false);
   scroll.setScrollbarFadingEnabled(true);
   scroll.setVerticalScrollBarEnabled(Applic.scrollbar);
   context.addMyContentView(scroll, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
   colbut.setOnClickListener(v-> {
           MainActivity.doonback();
        settings.finish();
        SetColors.show(context);
        });

Runnable closerun= () -> {
      var newthreshold=threshold.getText().toString();
      if(!thresstring.equals(newthreshold)) {
        float thres=str2float(newthreshold);
        if(thres>0.8f||thres<0.0f) {
          Applic.argToaster(context, "A threshold should 0.0 - 0.8",Toast.LENGTH_LONG);
           }
         else
           setthreshold(thres);
        }
        Natives.setfixatex(!fixatex.isChecked());
        Natives.setfixatey(!fixatey.isChecked());
       Natives.setGraphRange(str2float(glow.getText().toString()), str2float(ghigh.getText().toString()));
      Natives.setTargetRange(str2float(tlow.getText().toString()), str2float(thigh.getText().toString()));
        removeContentView(scroll) ;
        };
    MainActivity.setonback(closerun);

      close.setOnClickListener(v->{
           context.poponback();
         closerun.run();
      });

   }
       /*graphrange.setLabelFormatter(f->{
            var str= Settings.float2string(f);
          {if(doLog) {Log.i(LOG_ID,"setLabelformatter "+str);};};
          return str;}); */
//       graphrange.setLabelBehavior(LabelFormatter.LABEL_WITHIN_BOUNDS);
//       graphrange.setLabelBehavior(LabelFormatter.LABEL_FLOATING);
 //      graphrange.setHaloRadius((int)(tk.glucodata.GlucoseCurve.metrics.density*15.0f));
private static int settingsDp(float value) {
    return Math.round(value*tk.glucodata.GlucoseCurve.metrics.density);
    }

/** Contract shared by the programmatic phone settings screens and their tests. */
static final int PHONE_SETTINGS_MIN_TOUCH_DP=48;
static final int PHONE_GRAPH_DISPLAY_TOGGLE_COUNT=11;
static final int PHONE_LEGACY_ACTION_COUNT=9;

static boolean useClinicalPhoneChild(boolean wearable) {
    return !wearable;
    }

static boolean isDisplayThresholdValid(float value) {
    return value>=0.0f&&value<=0.8f;
    }

private static Button clinicalHeaderButton(Context context,int label) {
    Button button=ClinicalUi.button(context,context.getString(label),
            ClinicalUi.ButtonRole.SECONDARY);
    button.setMinWidth(ClinicalUi.dp(context,64));
    button.setMinimumHeight(ClinicalUi.dp(context,PHONE_SETTINGS_MIN_TOUCH_DP));
    return button;
    }

private static LinearLayout clinicalScreenContent(MainActivity context) {
    LinearLayout content=ClinicalUi.verticalContent(context);
    content.setPaddingRelative(
            MainActivity.systembarLeft+ClinicalUi.dp(context,20),
            MainActivity.systembarTop+ClinicalUi.dp(context,8),
            MainActivity.systembarRight+ClinicalUi.dp(context,20),
            MainActivity.systembarBottom+ClinicalUi.dp(context,30));
    return content;
    }

private static void clinicalStyleInput(EditText input) {
    Context context=input.getContext();
    input.setSingleLine(true);
    input.setTextSize(TypedValue.COMPLEX_UNIT_SP,16.0f);
    input.setTextColor(ClinicalUi.primaryText(context));
    input.setHintTextColor(ClinicalUi.secondaryText(context));
    input.setGravity(Gravity.CENTER);
    input.setSelectAllOnFocus(true);
    input.setMinimumHeight(ClinicalUi.dp(context,PHONE_SETTINGS_MIN_TOUCH_DP));
    input.setMinWidth(ClinicalUi.dp(context,76));
    input.setPaddingRelative(ClinicalUi.dp(context,12),0,
            ClinicalUi.dp(context,12),0);
    input.setBackground(ClinicalUi.surface(context,false,true));
    }

private static void clinicalStyleSpinner(Spinner spinner) {
    Context context=spinner.getContext();
    spinner.setMinimumHeight(ClinicalUi.dp(context,52));
    spinner.setPaddingRelative(ClinicalUi.dp(context,12),0,
            ClinicalUi.dp(context,12),0);
    spinner.setBackground(ClinicalUi.surface(context,false,true));
    }

private static void clinicalStyleAction(Button button) {
    Context context=button.getContext();
    button.setAllCaps(false);
    button.setTextSize(TypedValue.COMPLEX_UNIT_SP,16.0f);
    button.setTextColor(ClinicalUi.primaryText(context));
    button.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
    button.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
    button.setMinimumHeight(ClinicalUi.dp(context,58));
    button.setPaddingRelative(ClinicalUi.dp(context,16),ClinicalUi.dp(context,8),
            ClinicalUi.dp(context,16),ClinicalUi.dp(context,8));
    button.setBackground(ClinicalUi.surface(context,false,true));
    button.setStateListAnimator(null);
    }

private static LinearLayout clinicalToggleRow(MainActivity context,
        CheckDirectionBox legacy,CharSequence subtitle) {
    CharSequence title=legacy.getText();
    LinearLayout row=new LinearLayout(context);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(ClinicalUi.dp(context,subtitle==null?60:72));
    row.setPaddingRelative(ClinicalUi.dp(context,16),ClinicalUi.dp(context,8),
            ClinicalUi.dp(context,8),ClinicalUi.dp(context,8));
    row.setBackground(ClinicalUi.surface(context,false,true));

    LinearLayout copy=new LinearLayout(context);
    copy.setOrientation(LinearLayout.VERTICAL);
    copy.setGravity(Gravity.CENTER_VERTICAL);
    TextView label=new TextView(context);
    label.setText(title);
    label.setTextSize(TypedValue.COMPLEX_UNIT_SP,16.0f);
    label.setTextColor(ClinicalUi.primaryText(context));
    copy.addView(label);
    if(subtitle!=null&&subtitle.length()>0) {
        TextView detail=ClinicalUi.body(context,subtitle);
        detail.setTextSize(TypedValue.COMPLEX_UNIT_SP,13.0f);
        detail.setPadding(0,ClinicalUi.dp(context,2),0,0);
        copy.addView(detail);
        }
    row.addView(copy,new LinearLayout.LayoutParams(0,WRAP_CONTENT,1.0f));

    SwitchCompat toggle=new SwitchCompat(context);
    toggle.setShowText(false);
    toggle.setSwitchMinWidth(ClinicalUi.dp(context,42));
    toggle.setMinimumWidth(ClinicalUi.dp(context,56));
    toggle.setMinimumHeight(ClinicalUi.dp(context,PHONE_SETTINGS_MIN_TOUCH_DP));
    toggle.setPadding(ClinicalUi.dp(context,7),0,ClinicalUi.dp(context,7),0);
    toggle.setContentDescription(title);
    toggle.setThumbTintList(ContextCompat.getColorStateList(context,
            R.color.modern_settings_switch_thumb));
    toggle.setTrackTintList(ContextCompat.getColorStateList(context,
            R.color.modern_settings_switch_track));
    toggle.setEnabled(legacy.isEnabled());
    toggle.setChecked(legacy.isChecked());
    toggle.setOnCheckedChangeListener((button,isChecked)-> {
        if(legacy.isChecked()!=isChecked)
            legacy.setChecked(isChecked);
        });
    row.setEnabled(legacy.isEnabled());
    row.setVisibility(legacy.getVisibility());
    row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    row.setOnClickListener(view->toggle.toggle());
    row.addView(toggle,new LinearLayout.LayoutParams(WRAP_CONTENT,
            ClinicalUi.dp(context,PHONE_SETTINGS_MIN_TOUCH_DP)));
    return row;
    }

private static CheckDirectionBox clinicalDirectToggle(MainActivity context,
        CheckDirectionBox toggle) {
    toggle.setMinimumHeight(ClinicalUi.dp(context,64));
    toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP,16.0f);
    toggle.setTextColor(ClinicalUi.primaryText(context));
    toggle.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
    toggle.setPaddingRelative(ClinicalUi.dp(context,16),ClinicalUi.dp(context,7),
            ClinicalUi.dp(context,12),ClinicalUi.dp(context,7));
    toggle.setBackground(ClinicalUi.surface(context,false,true));
    if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.LOLLIPOP)
        toggle.setButtonTintList(ContextCompat.getColorStateList(context,
                R.color.modern_settings_choice_control));
    return toggle;
    }

private static LinearLayout clinicalExpandableCard(Context context,View... rows) {
    LinearLayout card=new LinearLayout(context);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setBackground(ClinicalUi.surface(context,false,false));
    card.setPadding(ClinicalUi.dp(context,6),ClinicalUi.dp(context,6),
            ClinicalUi.dp(context,6),ClinicalUi.dp(context,6));
    boolean first=true;
    for(View row:rows) {
        if(row==null)
            continue;
        if(!first)
            card.addView(ClinicalUi.divider(context));
        ViewGroup.LayoutParams current=row.getLayoutParams();
        row.setLayoutParams(new LinearLayout.LayoutParams(MATCH_PARENT,
                current==null?WRAP_CONTENT:current.height));
        card.addView(row);
        first=false;
        }
    return card;
    }

private static TextView clinicalUnitLabel(Context context) {
    TextView unit=ClinicalUi.body(context,Natives.getunit()==1?"mmol/L":"mg/dL");
    unit.setGravity(Gravity.CENTER);
    unit.setMinWidth(ClinicalUi.dp(context,66));
    unit.setPaddingRelative(ClinicalUi.dp(context,8),0,ClinicalUi.dp(context,4),0);
    return unit;
    }

private static LinearLayout clinicalRangeRow(MainActivity context,int label,
        EditText low,EditText high) {
    clinicalStyleInput(low);
    clinicalStyleInput(high);
    TextView dash=ClinicalUi.body(context,"\u2013");
    dash.setGravity(Gravity.CENTER);
    dash.setPaddingRelative(ClinicalUi.dp(context,5),0,ClinicalUi.dp(context,5),0);
    return ClinicalUi.fieldRow(context,context.getString(label),low,dash,high);
    }

private static LinearLayout clinicalLabeledSpinner(MainActivity context,int label,
        Spinner spinner) {
    clinicalStyleSpinner(spinner);
    return ClinicalUi.fieldRow(context,context.getString(label),spinner);
    }

private static TextView phoneSettingsTitle(Context context) {
    TextView title=new TextView(context);
    title.setText(R.string.settings);
    title.setTextColor(Color.rgb(242,244,243));
    title.setTextSize(TypedValue.COMPLEX_UNIT_SP,30.0f);
    title.setTypeface(title.getTypeface(),android.graphics.Typeface.BOLD);
    title.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
    title.setMinHeight(settingsDp(64.0f));
    title.setIncludeFontPadding(false);
    title.setLayoutParams(new LinearLayout.LayoutParams(0,WRAP_CONTENT,1.0f));
    return title;
    }

private static TextView phoneSettingsSection(Context context,int text) {
    TextView section=new TextView(context);
    section.setText(text);
    section.setAllCaps(false);
    section.setTextColor(Color.rgb(137,146,149));
    section.setTextSize(TypedValue.COMPLEX_UNIT_SP,13.0f);
    section.setTypeface(section.getTypeface(),android.graphics.Typeface.BOLD);
    section.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
    section.setPaddingRelative(settingsDp(4.0f),settingsDp(20.0f),settingsDp(4.0f),settingsDp(8.0f));
    section.setLayoutParams(new ViewGroup.MarginLayoutParams(MATCH_PARENT,WRAP_CONTENT));
    return section;
    }

private static void stylePhoneSettingsAction(View view,boolean fullWidth) {
    if(view==null)
        return;
    ViewGroup.MarginLayoutParams params=new ViewGroup.MarginLayoutParams(fullWidth?MATCH_PARENT:WRAP_CONTENT,WRAP_CONTENT);
    if(!fullWidth) {
        params.setMarginStart(settingsDp(2.0f));
        params.setMarginEnd(settingsDp(2.0f));
        }
    view.setLayoutParams(params);
    view.setMinimumHeight(settingsDp(fullWidth?56.0f:48.0f));
    view.setBackgroundResource(fullWidth
            ?R.drawable.modern_settings_item:R.drawable.modern_settings_close);
    view.setPaddingRelative(settingsDp(fullWidth?16.0f:14.0f),settingsDp(6.0f),settingsDp(fullWidth?14.0f:14.0f),settingsDp(6.0f));
    if(view instanceof TextView text) {
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP,fullWidth?16.0f:14.0f);
        text.setGravity((fullWidth?Gravity.START:Gravity.CENTER)|Gravity.CENTER_VERTICAL);
        text.setTextAlignment(fullWidth
                ?View.TEXT_ALIGNMENT_VIEW_START:View.TEXT_ALIGNMENT_CENTER);
        text.setAllCaps(false);
        text.setSingleLine(true);
        text.setTextColor(Color.rgb(236,239,238));
        if(fullWidth)
            text.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    0,0,R.drawable.modern_settings_chevron,0);
        }
    if(view instanceof Button button)
        button.setStateListAnimator(null);
    }

private static void stylePhoneSettingsUnit(View view) {
    if(view==null)
        return;
    view.setMinimumHeight(settingsDp(44.0f));
    view.setBackgroundResource(R.drawable.modern_settings_choice);
    view.setPaddingRelative(settingsDp(12.0f),settingsDp(4.0f),settingsDp(12.0f),settingsDp(4.0f));
    ViewGroup.MarginLayoutParams params=new ViewGroup.MarginLayoutParams(WRAP_CONTENT,WRAP_CONTENT);
    params.setMarginStart(settingsDp(2.0f));
    params.setMarginEnd(settingsDp(2.0f));
    view.setLayoutParams(params);
    if(view instanceof TextView text) {
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP,14.0f);
        text.setGravity(Gravity.CENTER_VERTICAL);
        text.setTextColor(ContextCompat.getColorStateList(view.getContext(),
                R.color.modern_settings_choice_text));
        }
    if(view instanceof androidx.appcompat.widget.AppCompatRadioButton choice)
        choice.setSupportButtonTintList(ContextCompat.getColorStateList(view.getContext(),
                R.color.modern_settings_choice_control));
    }

private static LinearLayout phoneSettingsHeader(Context context,TextView title,
        Button close) {
    LinearLayout header=new LinearLayout(context);
    header.setOrientation(LinearLayout.HORIZONTAL);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setMinimumHeight(settingsDp(68.0f));
    header.addView(title);
    stylePhoneSettingsAction(close,false);
    close.setTextColor(Color.rgb(199,205,203));
    close.setMinWidth(settingsDp(64.0f));
    header.addView(close);
    return header;
    }

private static LinearLayout phoneSettingsUnitRow(Context context,TextView label,
        View first,View second) {
    LinearLayout row=new LinearLayout(context);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(settingsDp(60.0f));
    row.setPaddingRelative(settingsDp(16.0f),settingsDp(6.0f),settingsDp(6.0f),settingsDp(6.0f));
    row.setBackgroundResource(R.drawable.modern_settings_item);
    label.setPadding(0,0,settingsDp(8.0f),0);
    label.setLayoutParams(new LinearLayout.LayoutParams(0,WRAP_CONTENT,1.0f));
    row.addView(label);
    stylePhoneSettingsUnit(first);
    stylePhoneSettingsUnit(second);
    row.addView(first);
    row.addView(second);
    return row;
    }

private static LinearLayout phoneSettingsToggleRow(Context context,
        CheckDirectionBox control) {
    CharSequence labelText=control.getText();
    TextView label=new TextView(context);
    label.setText(labelText);
    label.setTextColor(Color.rgb(236,239,238));
    label.setTextSize(TypedValue.COMPLEX_UNIT_SP,16.0f);
    label.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
    label.setSingleLine(false);
    label.setLayoutParams(new LinearLayout.LayoutParams(0,MATCH_PARENT,1.0f));

    SwitchCompat toggle=new SwitchCompat(context);
    toggle.setShowText(false);
    toggle.setSwitchMinWidth(settingsDp(42.0f));
    toggle.setMinimumWidth(settingsDp(52.0f));
    toggle.setMinimumHeight(settingsDp(48.0f));
    toggle.setPadding(settingsDp(6.0f),0,settingsDp(6.0f),0);
    toggle.setContentDescription(labelText);
    toggle.setThumbTintList(ContextCompat.getColorStateList(context,
            R.color.modern_settings_switch_thumb));
    toggle.setTrackTintList(ContextCompat.getColorStateList(context,
            R.color.modern_settings_switch_track));
    toggle.setEnabled(control.isEnabled());
    toggle.setChecked(control.isChecked());
    toggle.setOnCheckedChangeListener((button,isChecked)-> {
        if(control.isChecked()!=isChecked)
            control.setChecked(isChecked);
        });
    toggle.setLayoutParams(new LinearLayout.LayoutParams(
            WRAP_CONTENT,settingsDp(48.0f)));

    LinearLayout row=new LinearLayout(context);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(settingsDp(56.0f));
    row.setPaddingRelative(settingsDp(16.0f),0,settingsDp(6.0f),0);
    row.setBackgroundResource(R.drawable.modern_settings_item);
    row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    row.setVisibility(control.getVisibility());
    row.setOnClickListener(view->toggle.toggle());
    row.addView(label);
    row.addView(toggle);
    return row;
    }

private static View phoneSettingsDivider(Context context) {
    View divider=new View(context);
    divider.setBackgroundColor(Color.rgb(41,47,49));
    LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(
            MATCH_PARENT,settingsDp(1.0f));
    params.setMarginStart(settingsDp(16.0f));
    params.setMarginEnd(settingsDp(16.0f));
    divider.setLayoutParams(params);
    divider.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    return divider;
    }

private static LinearLayout phoneSettingsGroup(Context context,View... rows) {
    LinearLayout group=new LinearLayout(context);
    group.setOrientation(LinearLayout.VERTICAL);
    group.setBackgroundResource(R.drawable.modern_settings_group);
    group.setPadding(settingsDp(4.0f),settingsDp(4.0f),settingsDp(4.0f),settingsDp(4.0f));
    group.setLayoutParams(new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    boolean first=true;
    for(View row:rows) {
        if(row==null||row.getVisibility()==GONE)
            continue;
        if(!first)
            group.addView(phoneSettingsDivider(context));
        ViewGroup.LayoutParams params=row.getLayoutParams();
        row.setLayoutParams(new LinearLayout.LayoutParams(MATCH_PARENT,
                params==null?WRAP_CONTENT:params.height));
        group.addView(row);
        first=false;
        }
    return group;
    }

private static void restorePhoneSettingsButtons(ViewGroup group) {
    for(int index=0;index<group.getChildCount();index++) {
        View child=group.getChildAt(index);
        if(child instanceof Button&&!(child instanceof android.widget.CompoundButton)) {
            ViewGroup.LayoutParams params=child.getLayoutParams();
            stylePhoneSettingsAction(child,params!=null&&params.width==MATCH_PARENT);
            }
        else if(child instanceof ViewGroup nested)
            restorePhoneSettingsButtons(nested);
        }
    }

private static void clinicalLegacySettings(MainActivity context,Settings settings,
        View parent) {
    parent.setVisibility(GONE);
    Button close=clinicalHeaderButton(context,R.string.closename);
    LinearLayout list=ClinicalUi.actionRow(context,context.getString(R.string.list),
            context.getString(R.string.settings_legacy_list_hint));
    LinearLayout statistics=ClinicalUi.actionRow(context,
            context.getString(R.string.statistics),
            context.getString(R.string.settings_legacy_statistics_hint));
    LinearLayout lastScan=ClinicalUi.actionRow(context,
            context.getString(R.string.last_scan),
            context.getString(R.string.settings_legacy_last_scan_hint));
    LinearLayout export=ClinicalUi.actionRow(context,context.getString(R.string.export),
            context.getString(R.string.settings_legacy_export_hint));
    LinearLayout watch=ClinicalUi.actionRow(context,context.getString(R.string.watches),
            context.getString(R.string.settings_legacy_watch_hint));
    LinearLayout talk=ClinicalUi.actionRow(context,context.getString(R.string.talk),
            context.getString(R.string.settings_legacy_talk_hint));
    LinearLayout floating=ClinicalUi.actionRow(context,
            context.getString(R.string.floatglucose),
            context.getString(R.string.settings_legacy_floating_hint));
    LinearLayout reminders=ClinicalUi.actionRow(context,
            context.getString(R.string.remindersname),
            context.getString(R.string.settings_legacy_reminders_hint));
    LinearLayout labels=ClinicalUi.actionRow(context,
            context.getString(R.string.numberlabels),
            context.getString(R.string.settings_legacy_labels_hint));

    LinearLayout content=clinicalScreenContent(context);
    content.addView(ClinicalUi.header(context,
            context.getString(R.string.settings_legacy_title),close));
    TextView intro=ClinicalUi.body(context,
            context.getString(R.string.settings_legacy_intro));
    intro.setPaddingRelative(ClinicalUi.dp(context,4),0,ClinicalUi.dp(context,4),
            ClinicalUi.dp(context,6));
    content.addView(intro);
    content.addView(ClinicalUi.sectionLabel(context,
            context.getString(R.string.settings_legacy_records_section)));
    content.addView(ClinicalUi.card(context,list,statistics,lastScan,export));
    content.addView(ClinicalUi.sectionLabel(context,
            context.getString(R.string.settings_legacy_utilities_section)));
    content.addView(ClinicalUi.card(context,watch,talk,floating,reminders,labels));

    ScrollView screen=ClinicalUi.scrollScreen(context,content);
    context.addMyContentView(screen,new ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT));
    Runnable closeLegacy=()-> {
        parent.setVisibility(VISIBLE);
        removeContentView(screen);
        };
    context.setonback(closeLegacy);
    close.setOnClickListener(view->context.doonback());

    // Native graph destinations must be opened only after both Settings layers
    // are removed. Otherwise their renderer is active behind an invisible overlay
    // and the next Back press consumes the wrong callback.
    Runnable dismissSettings=()-> {
        context.poponback();
        closeLegacy.run();
        context.poponback();
        settings.hidekeyboard();
        settings.finish();
        };
    list.setOnClickListener(view->
            LegacySettingsRoutes.showList(context,dismissSettings));
    statistics.setOnClickListener(view->
            LegacySettingsRoutes.showStatistics(context,dismissSettings));
    lastScan.setOnClickListener(view->
            LegacySettingsRoutes.showLastScan(context,dismissSettings));
    watch.setOnClickListener(view->LegacySettingsRoutes.showWatch(context));
    talk.setOnClickListener(view->tk.glucodata.Talker.config(context,false));
    export.setOnClickListener(view-> {
        GlucoseCurve curve=Applic.app.curve;
        if(curve!=null)
            curve.dialogs.showexport(context,curve.getWidth(),curve.getHeight(),screen);
        });
    floating.setOnClickListener(view->
            tk.glucodata.FloatingConfig.show(context,screen));
    reminders.setOnClickListener(view->
            new tk.glucodata.setNumAlarm().mkviews(context,screen));
    labels.setOnClickListener(view->
            new LabelsClass(context).mklabellayout(screen));
    }

private    void mksettings(MainActivity context) {

    if(settinglayout==null) {
        mmolL = new CheckDirectionRadio(context);

        mmolL.setOnClickListener(v-> {
         ((Applic) context.getApplication()).setunit(1);
                  mgdl.setChecked(false);
                 recreate();
                });

            mmolL.setText(R.string.mmolL);
         mgdl = new CheckDirectionRadio(context);
        mgdl.setOnClickListener(v-> {
            ((Applic) context.getApplication()).setunit(2);
            mmolL.setChecked(false);
              recreate();
           });

        mgdl.setText(R.string.mgdL);

         final int padmg=0;
        mgdl.setPaddingRelative(0,0,padmg,0);
        mmolL.setPadding(0,0,0,0);
        var leftspace=new Space(context);
       View[] row0;

        Button changelabels=new Button(context);
        Button help =new Button(context);
        help.setText(R.string.helpname);
        help.setOnClickListener(v->{help(R.string.settinghelp,(MainActivity)(v.getContext())); });

      var close=getbutton(context,R.string.closename);
        if(isWearable) {
             row0=new View[]{leftspace,mmolL, mgdl,new Space(context)};
            }
        else {
            TextView unitlabel = new TextView(context);
            unitlabel.setText(R.string.unit);
            int hormarg= (int)(tk.glucodata.GlucoseCurve.metrics.density*15.0);
            getMargins(unitlabel).setMarginStart(hormarg);
            getMargins(mgdl).setMarginEnd(hormarg);
             row0 = new View[]{unitlabel, mmolL, mgdl};
            getMargins(help).setMarginStart(hormarg);
            getMargins(close).setMarginEnd(hormarg);
             }


      if(!useclose)
     close.setVisibility(GONE);
//    CheckDirectionBox bluetooth= new CheckDirectionBox(context);
   CheckDirectionBox globalscan = new CheckDirectionBox(context);
    globalscan.setText(R.string.startsapp);

    final var hasnfc=MainActivity.hasnfc;
      final  CheckDirectionBox nfcsound=hasnfc?new CheckDirectionBox(context):null;
    if(hasnfc)  {
        nfcsound.setText(R.string.nfcsound);
        nfcsound.setChecked(Natives.nfcsound());
        scanenabled=IntentscanEnabled();
        globalscan.setChecked(scanenabled) ; //Value
        globalscan.setOnCheckedChangeListener( (buttonView,  isChecked) -> 
                EnableIntentScanning(isChecked));
           nfcsound.setOnCheckedChangeListener((buttonView,  isChecked) -> {
                Natives.setnfcsound(isChecked);
                context.setnfc();
                });
        }
    CheckDirectionBox camera=!isWearable?new CheckDirectionBox(context):null;
    if(!isWearable) {
        final int diskey=!isWearable?Natives.camerakey():0;
        if(diskey>0)  {
            camera.setText(R.string.disablecamerakey);
            camera.setChecked(diskey==1);
            camera.setOnCheckedChangeListener((buttonView,  isChecked) -> {
                 final int setto = isChecked ? 1 : 2;
                 Natives.setcamerakey(setto);
                 });

            }
            
        else
            camera.setVisibility(GONE);

        }

   var exchanges=getbutton(context,R.string.exchanges);
    final boolean blueused=Natives.getusebluetooth();

    //bluetooth.setChecked(blueused);
    var alarmbut=getbutton(context,R.string.alarms);
        alarmbut.setOnClickListener(v->{
            int unit=mmolL.isChecked()?1:(mgdl.isChecked()?2:0);
            if(unit==0) {
                Applic.argToaster(context, R.string.setunitfirst,Toast.LENGTH_SHORT);
               return;
               }
            alarmsettings(context,settinglayout);
            });



    close.setOnClickListener(v->{
            int unit=mmolL.isChecked()?1:(mgdl.isChecked()?2:0);
            if(unit==0) {
            Applic.argToaster(context, R.string.setunitfirst,Toast.LENGTH_SHORT);
               return;
            }
           context.poponback();
            hidekeyboard();
            finish();
     context.lightBars(!getInvertColors( ));
         if(tk.glucodata.Menus.on) tk.glucodata.Menus.show(context);

            });
   var displayview=getbutton(context,R.string.display);
    ViewGroup[] thelayout=new ViewGroup[1];
    if(!isWearable) {
        changelabels.setText(R.string.numberlabels);
        changelabels.setOnClickListener(v-> {
                hidekeyboard();
                new LabelsClass(context).mklabellayout(thelayout[0]);});
              }
    Button numalarm=getbutton(context,R.string.remindersname);
    Button advanced=null;


    View[][] views;
    LinearLayout phoneSettingsLayout=null;
    final String advhelp=isWearable?null:Natives.advanced();

        var calibration=  getbutton(context,R.string.calibration);
        calibration.setOnClickListener(v-> {
            Calibration.show(context,thelayout[0]);
        });
    if(isWearable) {
        Button talk;
        if(!tk.glucodata.Applic.DontTalk) {
                talk=getbutton(context,R.string.talk);
                talk.setOnClickListener(v ->{tk.glucodata.Talker.config(context);});
                getMargins(talk).bottomMargin= (int)(tk.glucodata.GlucoseCurve.metrics.density*4.0);

                }
        else talk=null;
        Button complications;
        if(BuildConfig.minSDK>=26) {
            complications = getbutton(context, R.string.complications);
            complications.setOnClickListener(v -> tk.glucodata.glucosecomplication.ColorConfig.show(context, thelayout[0]));
                        final var margins=getMargins(complications);
                        margins.topMargin= (int)(tk.glucodata.GlucoseCurve.metrics.density*3.0);
                        margins.bottomMargin= (int)(tk.glucodata.GlucoseCurve.metrics.density*4.0);

        }

//      alarmbut.setMinimumWidth(0); alarmbut.setMinWidth(0);
        var uppad=(int)(tk.glucodata.GlucoseCurve.metrics.density*9.0);
      alarmbut.setPadding(uppad,alarmbut.getPaddingTop(),uppad,alarmbut.getPaddingBottom());

      var floatconfig=getbutton(context,R.string.floatglucoseshort);

        CheckDirectionBox floatglucose=new CheckDirectionBox(context);
      floatconfig.setOnClickListener(v-> {
         tk.glucodata.FloatingConfig.show(context,thelayout[0]);
         });
        floatglucose.setText("   " );
     View[] talkrow;
        if(doLog) {
                Button logview=getbutton(context,R.string.logging);
                logview.setOnClickListener(v->LogConfig.make(context,thelayout[0]));
                talkrow=new View[]{talk,logview};
                }
         else {
                talkrow=new View[]{talk};
            }


        floatglucose.setChecked(Natives.getfloatglucose());
        floatglucose.setOnCheckedChangeListener( (buttonView,  isChecked) -> Floating.setfloatglucose(context,isChecked) ) ;
        View[] camornum=new View[] {alarmbut,numalarm};
        if(BuildConfig.minSDK>=26) {
            views = new View[][]{new View[]{displayview},row0, hasnfc ? (new View[]{globalscan, nfcsound}) : null,new View[]{floatconfig, floatglucose},new View[]{complications},talkrow, new View[]{exchanges },new View[]{calibration},   camornum, new View[]{close}, new View[]{getlabel(context, BuildConfig.BUILD_TIME)}, new View[]{getlabel(context, BuildConfig.VERSION_NAME)}, new View[]{getlabel(context, codestr)}};
            ;
        }
        else{
            views = new View[][]{new View[]{displayview},row0,    hasnfc ? (new View[]{globalscan, nfcsound}) : null, new View[]{floatconfig, floatglucose},talkrow,  new View[]{exchanges },new View[]{calibration},     camornum,new View[]{close},  new View[]{getlabel(context, BuildConfig.BUILD_TIME)}, new View[]{getlabel(context, BuildConfig.VERSION_NAME)}, new View[]{getlabel(context, codestr)}};
            ;
        }
        }
    else {
        var about=getbutton(context,R.string.aboutname);
        about.setOnClickListener(v-> tk.glucodata.GlucoseCurve.doabout(context));
        var intro=getbutton(context,"Intro");
        intro.setOnClickListener(v-> help(R.string.introhelp,context));
        if(advhelp!=null) {
            advanced=new Button(context);
            advanced.setText(R.string.advanced);
            }

//      var oldxdrip=getbutton(context,"send old"); oldxdrip.setOnClickListener(v-> tk.glucodata.Natives.sendxdripold());
        CheckDirectionBox glucosenotify=new CheckDirectionBox(context);
        glucosenotify.setText(R.string.glucosestatusbar);
        glucosenotify.setChecked(Natives.getshowalways()) ;
        glucosenotify.setOnCheckedChangeListener( (buttonView,  isChecked) -> Notify.glucosestatus(isChecked) );
        var googlescan=getcheckbox(context, R.string.googlescan, Natives.getGoogleScan());
        googlescan.setOnCheckedChangeListener( (buttonView,  isChecked) -> Natives.setGoogleScan(isChecked) );
        Button mirror=getbutton(context,R.string.mirror);
        mirror.setOnClickListener(view->new Backup().realmkbackupview(context,false));
        Button intakeBackend=getbutton(context,R.string.intake_backend_settings_title);
        intakeBackend.setOnClickListener(view->IntakeBackendSettings.show(context));
        Button legacy=getbutton(context,R.string.settings_legacy_title);
        legacy.setOnClickListener(view->
                clinicalLegacySettings(context,this,thelayout[0]));

        Button logview=null;
        if(doLog) {
            logview=getbutton(context,R.string.logging);
            logview.setOnClickListener(v->LogConfig.make(context,thelayout[0]));
                }

        TextView title=phoneSettingsTitle(context);
        displayview.setText(R.string.settings_display_title);
        TextView glucoseSection=phoneSettingsSection(context,R.string.settings_section_glucose);
        TextView alertsSection=phoneSettingsSection(context,R.string.settings_section_alerts);
        TextView connectionsSection=phoneSettingsSection(context,
                R.string.settings_section_connections);
        TextView preferencesSection=phoneSettingsSection(context,R.string.settings_section_preferences);
        TextView technicalSection=phoneSettingsSection(context,
                R.string.settings_section_technical);
        TextView legacySection=phoneSettingsSection(context,R.string.settings_legacy_title);

        TextView unitLabel=(TextView)row0[0];
        unitLabel.setTextColor(Color.rgb(170,177,179));
        unitLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP,14.0f);
        unitLabel.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);

        for(View action:new View[]{calibration,displayview,alarmbut,exchanges,
                intakeBackend,mirror,legacy})
            stylePhoneSettingsAction(action,true);
        if(logview!=null)
            stylePhoneSettingsAction(logview,true);

        LinearLayout unitRow=phoneSettingsUnitRow(context,unitLabel,mmolL,mgdl);
        LinearLayout glucoseGroup=phoneSettingsGroup(context,
                unitRow,
                calibration,
                phoneSettingsToggleRow(context,glucosenotify),
                displayview);
        LinearLayout alertsGroup=phoneSettingsGroup(context,alarmbut);
        LinearLayout connectionsGroup=phoneSettingsGroup(context,
                intakeBackend,exchanges,mirror);

        java.util.ArrayList<View> preferenceRows=new java.util.ArrayList<>();
        preferenceRows.add(phoneSettingsToggleRow(context,googlescan));
        if(hasnfc) {
            preferenceRows.add(phoneSettingsToggleRow(context,nfcsound));
            preferenceRows.add(phoneSettingsToggleRow(context,globalscan));
            preferenceRows.add(phoneSettingsToggleRow(context,camera));
            }
        LinearLayout preferencesGroup=phoneSettingsGroup(context,
                preferenceRows.toArray(new View[0]));
        LinearLayout technicalGroup=logview==null?null:
                phoneSettingsGroup(context,logview);
        LinearLayout legacyGroup=phoneSettingsGroup(context,legacy);

        phoneSettingsLayout=new LinearLayout(context);
        phoneSettingsLayout.setOrientation(LinearLayout.VERTICAL);
        phoneSettingsLayout.setBackgroundColor(Color.rgb(11,13,14));
        phoneSettingsLayout.addView(phoneSettingsHeader(context,title,close),
                new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
        phoneSettingsLayout.addView(glucoseSection);
        phoneSettingsLayout.addView(glucoseGroup);
        phoneSettingsLayout.addView(alertsSection);
        phoneSettingsLayout.addView(alertsGroup);
        phoneSettingsLayout.addView(connectionsSection);
        phoneSettingsLayout.addView(connectionsGroup);
        phoneSettingsLayout.addView(preferencesSection);
        phoneSettingsLayout.addView(preferencesGroup);
        if(technicalGroup!=null) {
            phoneSettingsLayout.addView(technicalSection);
            phoneSettingsLayout.addView(technicalGroup);
            }
        phoneSettingsLayout.addView(legacySection);
        phoneSettingsLayout.addView(legacyGroup);
        views=null;
        }

    View initialFocus=isWearable?help:close;
    initialFocus.setFocusableInTouchMode(true);
    initialFocus.setFocusable(true);
    initialFocus.requestFocus();
    initialFocus.requestFocusFromTouch();

        final ViewGroup lay;
        if(isWearable) {
            lay=new Layout(context, (l, w, h) -> {
                hideSystemUI(); int[] ret={w,h};
                return ret;
                },views);
            }
        else {
            lay=phoneSettingsLayout;
            }

     exchanges.setOnClickListener(v->{
        exchanges(context,lay);
        });
    thelayout[0]=lay;
        if(advhelp!=null) {
            advanced.setOnClickListener(v -> {
                EnableControls(thelayout[0],false);
                help(advhelp, (MainActivity) (v.getContext()),l->
                    EnableControls(thelayout[0],true)
                    
                    );
            });
            }


        lay.setBackgroundColor(isWearable
                ?colorwindowbackground:Color.rgb(11,13,14));
/*var    horlayout= new HorizontalScrollView(context);
    horlayout.addView(lay);
    horlayout.setHorizontalScrollBarEnabled(false);
    horlayout.setFillViewport(true);
   horlayout.setPadding(0,0,0,0); */

    ScrollView scroller=new ScrollView(context);
    scroller.addView(lay);
    scroller.setSmoothScrollingEnabled(false);
    scroller.setVerticalScrollBarEnabled(Applic.scrollbar);
    scroller.setScrollbarFadingEnabled(true);//Crash with NestedScrollView
    scroller.setFillViewport(isWearable);
    scroller.setClipToPadding(isWearable?false:true);
    scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
    if(isWearable)
        scroller.setPadding(0,0,0,0);
    else {
        // Keep every scrolled row inside the safe content area. Insets on the
        // child itself scroll away and let controls collide with the status or
        // navigation bars at the ends of the list.
        scroller.setBackgroundColor(Color.rgb(11,13,14));
        scroller.setPadding(MainActivity.systembarLeft,MainActivity.systembarTop,
                MainActivity.systembarRight,MainActivity.systembarBottom);
        }

   settinglayout=scroller;


       final   int pad=(int)(tk.glucodata.GlucoseCurve.metrics.density*7.0);
    if(isWearable) {
   //   lay.setPadding((int)(tk.glucodata.GlucoseCurve.metrics.density*14.0),(int)(tk.glucodata.GlucoseCurve.metrics.density*11.0),(int)(tk.glucodata.GlucoseCurve.metrics.density*14.0),pad);
      lay.setPaddingRelative((int)(tk.glucodata.GlucoseCurve.metrics.density*5.5),(int)(tk.glucodata.GlucoseCurve.metrics.density*11.0),(int)(tk.glucodata.GlucoseCurve.metrics.density*14.0),pad);
        }
     else {
       final int phoneHorizontal=settingsDp(18.0f);
       final int phoneVertical=settingsDp(12.0f);
       lay.setPadding(phoneHorizontal,phoneVertical,phoneHorizontal,phoneVertical);
      }

    final    int laywidth=MATCH_PARENT;
     context.addMyContentView(settinglayout, new ViewGroup.LayoutParams( laywidth ,MATCH_PARENT));
    numalarm.setOnClickListener(v-> {
        new tk.glucodata.setNumAlarm().mkviews(context,settinglayout);
        });
    displayview.setOnClickListener(v-> {
        int unit=mmolL.isChecked()?1:(mgdl.isChecked()?2:0);
        if(unit==0) {
            Applic.argToaster(context, R.string.setunitfirst,Toast.LENGTH_SHORT);
           return;
           }
         displaysettings(context,this);
         });
        }
    else {
        settinglayout.setVisibility(VISIBLE);
        settinglayout.bringToFront();
    }

setvalues();
}

private static void clinicalExchanges(MainActivity context,View parent) {
    CheckDirectionBox xdrip=new CheckDirectionBox(context);
    xdrip.setText(R.string.xdripbroadcast);
    xdrip.setChecked(Natives.getxbroadcast());
    CheckDirectionBox juggluco=new CheckDirectionBox(context);
    juggluco.setText(R.string.settings_juggluco_broadcast);
    juggluco.setChecked(Natives.getJugglucobroadcast());
    CheckDirectionBox patchedLibre=new CheckDirectionBox(context);
    patchedLibre.setText(R.string.patchedlibrebroadcast);
    patchedLibre.setChecked(Natives.getlibrelinkused());
    CheckDirectionBox everSense=new CheckDirectionBox(context);
    everSense.setText(R.string.everSensebroadcast);
    everSense.setChecked(Natives.geteverSensebroadcast());
    CheckDirectionBox libreView=new CheckDirectionBox(context);
    libreView.setText(R.string.libreviewname);
    libreView.setChecked(Natives.getuselibreview());
    CheckDirectionBox healthConnect=Build.VERSION.SDK_INT<28?null:
            getcheckbox(context,"Health Connect",Natives.gethealthConnect());

    Button close=clinicalHeaderButton(context,R.string.closename);
    LinearLayout webServer=ClinicalUi.actionRow(context,context.getString(R.string.webserver),
            context.getString(R.string.settings_webserver_hint));
    LinearLayout uploader=ClinicalUi.actionRow(context,context.getString(R.string.uploader),
            context.getString(R.string.settings_uploader_hint));
    LinearLayout meters=ClinicalUi.actionRow(context,context.getString(R.string.meterlist),
            context.getString(R.string.settings_meters_hint));
    LinearLayout exchangeHelp=ClinicalUi.actionRow(context,
            context.getString(R.string.helpname),context.getString(R.string.settings_exchange_help_hint));

    LinearLayout content=clinicalScreenContent(context);
    content.addView(ClinicalUi.header(context,
            context.getString(R.string.settings_exchange_title),close));
    TextView intro=ClinicalUi.body(context,context.getString(R.string.settings_exchange_intro));
    intro.setPaddingRelative(ClinicalUi.dp(context,4),0,ClinicalUi.dp(context,4),
            ClinicalUi.dp(context,6));
    content.addView(intro);
    content.addView(ClinicalUi.sectionLabel(context,
            context.getString(R.string.settings_broadcasts_section)));
    content.addView(ClinicalUi.card(context,
            clinicalDirectToggle(context,patchedLibre),
            clinicalDirectToggle(context,everSense),
            clinicalDirectToggle(context,xdrip),
            clinicalDirectToggle(context,juggluco)));
    content.addView(ClinicalUi.sectionLabel(context,
            context.getString(R.string.settings_services_section)));
    if(healthConnect!=null)
        content.addView(ClinicalUi.card(context,webServer,uploader,
                clinicalDirectToggle(context,libreView),
                clinicalToggleRow(context,healthConnect,
                        context.getString(R.string.settings_health_connect_hint))));
    else
        content.addView(ClinicalUi.card(context,webServer,uploader,
                clinicalDirectToggle(context,libreView)));
    content.addView(ClinicalUi.sectionLabel(context,
            context.getString(R.string.settings_data_section)));
    content.addView(ClinicalUi.card(context,meters));
    content.addView(ClinicalUi.sectionLabel(context,
            context.getString(R.string.settings_support_section)));
    content.addView(ClinicalUi.card(context,exchangeHelp));
    ScrollView screen=ClinicalUi.scrollScreen(context,content);
    context.addMyContentView(screen,new ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT));

    boolean[] xdripBusy={false};
    xdrip.setOnCheckedChangeListener((button,isChecked)-> {
        if(!xdripBusy[0]) {
            xdripBusy[0]=true;
            xdrip.setChecked(!isChecked);
            Broadcasts.setxdripreceivers(context,screen,xdrip,xdripBusy);
            }
        });
    boolean[] jugglucoBusy={false};
    juggluco.setOnCheckedChangeListener((button,isChecked)-> {
        if(!jugglucoBusy[0]) {
            jugglucoBusy[0]=true;
            juggluco.setChecked(!isChecked);
            Broadcasts.setglucodatareceivers(context,screen,juggluco,jugglucoBusy);
            }
        });
    boolean[] patchedBusy={false};
    patchedLibre.setOnCheckedChangeListener((button,isChecked)-> {
        if(!patchedBusy[0]) {
            patchedBusy[0]=true;
            patchedLibre.setChecked(!isChecked);
            Broadcasts.setlibrereceivers(context,screen,patchedLibre,patchedBusy);
            }
        });
    boolean[] everSenseBusy={false};
    everSense.setOnCheckedChangeListener((button,isChecked)-> {
        if(!everSenseBusy[0]) {
            everSenseBusy[0]=true;
            everSense.setChecked(!isChecked);
            Broadcasts.seteverSensereceivers(context,screen,everSense,everSenseBusy);
            }
        });
    boolean[] libreViewBusy={false};
    libreView.setOnCheckedChangeListener((button,isChecked)-> {
        if(!libreViewBusy[0]) {
            libreViewBusy[0]=true;
            libreView.setChecked(!isChecked);
            Libreview.config(context,screen,libreView,libreViewBusy);
            }
        });
    if(healthConnect!=null)
        healthConnect.setOnCheckedChangeListener((button,isChecked)-> {
            Natives.sethealthConnect(isChecked);
            if(isChecked) {
                MainActivity.tryHealth=5;
                HealthConnection.Companion.init(context);
                }
            else {
                MainActivity.tryHealth=0;
                HealthConnection.Companion.stop();
                }
            });

    webServer.setOnClickListener(view->tk.glucodata.Nightscout.show(context,screen));
    uploader.setOnClickListener(view->tk.glucodata.NightPost.config(context,screen));
    meters.setOnClickListener(view->MeterList.show(context,screen));
    exchangeHelp.setOnClickListener(view->help(R.string.exchangehelp,context));
    close.setOnClickListener(view->context.doonback());
    context.setonback(()-> {
        parent.setVisibility(VISIBLE);
        removeContentView(screen);
        });
    }

@SuppressLint("UseCompatLoadingForDrawables")
static private void exchanges(MainActivity context, View parent) {
  parent.setVisibility(GONE);
    if(useClinicalPhoneChild(isWearable)) {
        clinicalExchanges(context,parent);
        return;
        }
    final CheckDirectionBox xdripbroadcast = new CheckDirectionBox(context);
    final CheckDirectionBox jugglucobroadcast = new CheckDirectionBox(context);

   if(isWearable)
      xdripbroadcast.setText("xDrip broadcast");
   else
      xdripbroadcast.setText(R.string.xdripbroadcast);
    xdripbroadcast.setChecked(Natives.getxbroadcast());
   if(isWearable)
      jugglucobroadcast.setText("Glucodata");
   else
      jugglucobroadcast.setText("Glucodata broadcast");
    jugglucobroadcast.setChecked(Natives.getJugglucobroadcast());
   var mirrorview=getbutton(context,R.string.mirror);
   mirrorview.setOnClickListener(v ->{ (new Backup()).realmkbackupview(context,false); });
    Layout[] thelayout = {null};
        final boolean[] xbroadnothing = {false};
        xdripbroadcast.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (!xbroadnothing[0]) {
                        xbroadnothing[0] = true;
                        xdripbroadcast.setChecked(!isChecked);
                        Broadcasts.setxdripreceivers(context, thelayout[0], xdripbroadcast, xbroadnothing);
                    }
                }
        );

        final boolean[] juggluconothing = {false};
        jugglucobroadcast.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (!juggluconothing[0]) {
                        juggluconothing[0] = true;
                        jugglucobroadcast.setChecked(!isChecked);
                        Broadcasts.setglucodatareceivers(context, thelayout[0], jugglucobroadcast, juggluconothing);
                    }
                }
        );
   var ok = getbutton(context, R.string.closename);
    if(!useclose)
        ok.setVisibility(GONE);
    ok.setOnClickListener(
        v->{
          context.doonback();
        });
   Layout lay;
    if (isWearable) {
        var uploader = getbutton(context, R.string.upload);
        uploader.setOnClickListener(v -> tk.glucodata.NightPost.config(context, thelayout[0]));

        lay = new Layout(context, (l, w, h) -> {
            int[] ret = {w, h};
            return ret;
        },new View[]{xdripbroadcast},new View[]{uploader,mirrorview}  ,new View[]{jugglucobroadcast}, new View[]{ok});

   final var density=tk.glucodata.GlucoseCurve.metrics.density;
        lay.setPadding((int)(density*8.0),(int)(density*25.0),(int)(density*8.0),(int)(density*2.0));
    } else {
        var uploader = getbutton(context, R.string.uploader);
        uploader.setOnClickListener(v -> tk.glucodata.NightPost.config(context, thelayout[0]));
        final CheckDirectionBox librelinkbroadcast = new CheckDirectionBox(context);
        final CheckDirectionBox libreview = new CheckDirectionBox(context);
        final CheckDirectionBox everSensebroadcast = new CheckDirectionBox(context);
        final var healthconnect = (isWearable || Build.VERSION.SDK_INT < 28) ? null : getcheckbox(context, "Health Connect", Natives.gethealthConnect());
        final boolean wasxdrip = Natives.getuselibreview();
        final boolean usedlibrebroad = Natives.getlibrelinkused();
        libreview.setText(R.string.libreviewname);
        libreview.setChecked(wasxdrip);
        librelinkbroadcast.setText(R.string.patchedlibrebroadcast);

        librelinkbroadcast.setChecked(usedlibrebroad);
        everSensebroadcast.setText(R.string.everSensebroadcast);
        everSensebroadcast.setChecked(Natives.geteverSensebroadcast());
        if (Build.VERSION.SDK_INT >= 28) {
            healthconnect.setOnCheckedChangeListener((buttonView, isChecked) -> {
                        Natives.sethealthConnect(isChecked);
                        if (isChecked) {
                            MainActivity.tryHealth = 5;
                            HealthConnection.Companion.init(context);
                        } else {
                            MainActivity.tryHealth = 0;
                            HealthConnection.Companion.stop();
                        }
                    }
            );
        }
        if (librelinkbroadcast.isChecked() != usedlibrebroad) {
            if (!usedlibrebroad) {
                final var starttime = Natives.laststarttime();
                if (starttime != 0L) {
                    tk.glucodata.XInfuus.sendSensorActivateBroadcast(context, Natives.lastsensorname(), starttime);
                }
            }
        }
        var webserver = getbutton(context, R.string.webserver);
        webserver.setOnClickListener(v -> tk.glucodata.Nightscout.show(context, thelayout[0]));
        uploader.setOnClickListener(v -> tk.glucodata.NightPost.config(context, thelayout[0]));
        final boolean[] donothing = {false};
        libreview.setOnCheckedChangeListener(
                (buttonView, isChecked) -> {
                    if (!donothing[0]) {
                        donothing[0] = true;
                        libreview.setChecked(!isChecked);
                        Libreview.config(context, thelayout[0], libreview, donothing);
                    }
                });
        final boolean[] xdripdonthing = {false};
        librelinkbroadcast.setOnCheckedChangeListener(
                (buttonView, isChecked) -> {
                    if (!xdripdonthing[0]) {
                        xdripdonthing[0] = true;
                        librelinkbroadcast.setChecked(!isChecked);
        //                Applic.argToaster(context,R.string.nolibrelink,Toast.LENGTH_LONG);
                        Broadcasts.setlibrereceivers(context, thelayout[0], librelinkbroadcast, xdripdonthing);
                    }
                });

        final boolean[] everSensenothing = {false};
        everSensebroadcast.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (!everSensenothing[0]) {
                        everSensenothing[0] = true;
                        everSensebroadcast.setChecked(!isChecked);
                        Broadcasts.seteverSensereceivers(context, thelayout[0], everSensebroadcast, everSensenothing);
                    }
                }
        );
        var help = getbutton(context, R.string.helpname);
      help.setOnClickListener(v->{help(R.string.exchangehelp,context); });
      var exportview=getbutton(context,R.string.export);
        var hormarg=(int)(tk.glucodata.GlucoseCurve.metrics.density*15.0);

      getMargins(help).setMarginStart(hormarg);
      getMargins(webserver).setMarginStart(hormarg);
      getMargins(ok).setMarginEnd(hormarg);


        var meters = getbutton(context, R.string.meterlist);
        lay = new Layout(context, (l, w, h) -> {
            int[] ret = {w, h};
            return ret;
        }, new View[]{everSensebroadcast,librelinkbroadcast},new View[]{xdripbroadcast, jugglucobroadcast}, new View[]{webserver, uploader, libreview}, (Build.VERSION.SDK_INT >= 28) ? new View[]{healthconnect,exportview,mirrorview} :new View[]{exportview,mirrorview},
                new View[]{help,meters, ok});

    final   int pad=(int)(tk.glucodata.GlucoseCurve.metrics.density*10.0);
        lay.setPadding(MainActivity.systembarLeft,MainActivity.systembarTop*3/4,MainActivity.systembarRight+pad,MainActivity.systembarBottom*7/8+(int)(tk.glucodata.GlucoseCurve.metrics.density*5.0));
        exportview.setOnClickListener(v ->{
            var c=Applic.app.curve;
            if(c!=null) {
                c.dialogs.showexport(context,c.getWidth(),c.getHeight(),lay);
            }
        });
        meters.setOnClickListener(v->{
            MeterList.show(context,lay); });
      }

    thelayout[0] = lay;
    lay.setBackgroundColor(Applic.backgroundcolor);
       context.addMyContentView(lay, new ViewGroup.LayoutParams( MATCH_PARENT ,MATCH_PARENT));

    context.setonback(() -> {
     parent.setVisibility(VISIBLE);
        removeContentView(lay) ;
        });
}


//ViewGroup labellayout=null;



public static void   removeContentView(View view) {
    ViewGroup parent= (ViewGroup)view.getParent();
    if(parent!=null)
        parent.removeView(view);
    }

//@Override

}
