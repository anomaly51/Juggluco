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
/*      Fri Jan 27 15:31:05 CET 2023                                                 */


package tk.glucodata;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.InputType;
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
import android.widget.Toast;

import androidx.appcompat.widget.SwitchCompat;

import static tk.glucodata.Layout.getMargins;
import static android.view.View.GONE;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.Log.stack;
import static tk.glucodata.NumberView.geteditview;
import static tk.glucodata.NumberView.geteditwearos;
import static tk.glucodata.NumberView.smallScreen;
import static tk.glucodata.settings.Settings.removeContentView;
import static tk.glucodata.util.getbutton;
import static tk.glucodata.util.getcheckbox;
import static tk.glucodata.util.getlabel;

class Stats {
private static final String LOG_ID="Stats";
static final int STATS_DAYS_VALID=0;
static final int STATS_DAYS_EMPTY=1;
static final int STATS_DAYS_NOT_NUMBER=2;
static final int STATS_DAYS_NOT_POSITIVE=3;
static final int STATS_DAYS_TOO_LARGE=4;

static int validateStatisticsDays(String raw) {
   if(raw==null||raw.trim().isEmpty())
      return STATS_DAYS_EMPTY;
   final long value;
   try {
      value=Long.parseLong(raw.trim());
      }
   catch(NumberFormatException ex) {
      return STATS_DAYS_NOT_NUMBER;
      }
   if(value<=0)
      return STATS_DAYS_NOT_POSITIVE;
   if(value>Integer.MAX_VALUE)
      return STATS_DAYS_TOO_LARGE;
   return STATS_DAYS_VALID;
   }

static int parseStatisticsDays(String raw) {
   return Integer.parseInt(raw.trim());
   }

private static void askPhoneDays(MainActivity act,boolean history) {
   act.lightBars(false);
   int previousDays=Natives.getAnalysedays();
   boolean previousHistory=Natives.getAnalysehistory();
   LinearLayout content=ClinicalUi.verticalContent(act);
   content.setPadding(ClinicalUi.dp(act,20),
           MainActivity.systembarTop+ClinicalUi.dp(act,8),
           ClinicalUi.dp(act,20),ClinicalUi.dp(act,30));
   Button headerClose=ClinicalUi.button(act,act.getString(R.string.cancel),
           ClinicalUi.ButtonRole.SECONDARY);
   content.addView(ClinicalUi.header(act,
           act.getString(R.string.stats_modern_period_title),headerClose));
   TextView intro=ClinicalUi.body(act,act.getString(R.string.stats_modern_period_intro));
   intro.setPadding(ClinicalUi.dp(act,4),0,ClinicalUi.dp(act,4),ClinicalUi.dp(act,4));
   content.addView(intro);
   content.addView(ClinicalUi.sectionLabel(act,
           act.getString(R.string.stats_modern_period_section)));

   EditText days=new EditText(act);
   days.setSingleLine(true);
   days.setInputType(InputType.TYPE_CLASS_NUMBER);
   days.setText(String.valueOf(previousDays));
   days.setTextColor(ClinicalUi.primaryText(act));
   days.setHintTextColor(ClinicalUi.secondaryText(act));
   days.setTextSize(TypedValue.COMPLEX_UNIT_SP,17);
   days.setGravity(Gravity.CENTER);
   days.setMinWidth(ClinicalUi.dp(act,112));
   days.setMinimumHeight(ClinicalUi.dp(act,50));
   days.setPadding(ClinicalUi.dp(act,12),0,ClinicalUi.dp(act,12),0);
   days.setBackground(ClinicalUi.surface(act,false,true));
   TextView unit=ClinicalUi.body(act,act.getString(R.string.stats_modern_days_unit));
   unit.setPadding(ClinicalUi.dp(act,10),0,ClinicalUi.dp(act,4),0);
   content.addView(ClinicalUi.card(act,
           ClinicalUi.fieldRow(act,act.getString(R.string.stats_modern_period_label),days,unit)));

   LinearLayout quickPeriods=new LinearLayout(act);
   quickPeriods.setOrientation(LinearLayout.HORIZONTAL);
   quickPeriods.setPadding(0,ClinicalUi.dp(act,12),0,0);
   int[] options={7,14,30,90};
   for(int index=0;index<options.length;index++) {
      int option=options[index];
      Button quick=ClinicalUi.button(act,
              act.getString(R.string.stats_modern_quick_period,option),
              ClinicalUi.ButtonRole.SECONDARY);
      quick.setOnClickListener(v->days.setText(String.valueOf(option)));
      LinearLayout.LayoutParams quickParams=new LinearLayout.LayoutParams(0,WRAP_CONTENT,1f);
      if(index>0)
         quickParams.setMarginStart(ClinicalUi.dp(act,8));
      quickPeriods.addView(quick,quickParams);
      }
   content.addView(quickPeriods);

   TextView error=ClinicalUi.body(act,"");
   error.setTextColor(ClinicalUi.danger(act));
   error.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
   error.setPadding(ClinicalUi.dp(act,16),ClinicalUi.dp(act,12),
           ClinicalUi.dp(act,16),ClinicalUi.dp(act,12));
   error.setBackground(ClinicalUi.surface(act,false,false));
   error.setVisibility(GONE);
   LinearLayout.LayoutParams errorParams=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
   errorParams.topMargin=ClinicalUi.dp(act,12);
   content.addView(error,errorParams);

   content.addView(ClinicalUi.sectionLabel(act,
           act.getString(R.string.stats_modern_actions_section)));
   Button apply=ClinicalUi.button(act,act.getString(R.string.stats_modern_apply_period),
           ClinicalUi.ButtonRole.PRIMARY);
   Button cancel=ClinicalUi.button(act,act.getString(R.string.cancel),
           ClinicalUi.ButtonRole.SECONDARY);
   content.addView(apply,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
   LinearLayout.LayoutParams cancelParams=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
   cancelParams.topMargin=ClinicalUi.dp(act,10);
   content.addView(cancel,cancelParams);

   ScrollView screen=ClinicalUi.scrollScreen(act,content);
   screen.setVerticalScrollBarEnabled(false);
   act.addMyContentView(screen,new ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT));
   Runnable closeOnBack=()->{
      help.hidekeyboard(act);
      removeContentView(screen);
      mkstats(act);
      };
   headerClose.setOnClickListener(v->act.doonback());
   cancel.setOnClickListener(v->{
      act.poponback();
      closeOnBack.run();
      });
   apply.setOnClickListener(v->{
      int validation=validateStatisticsDays(days.getText().toString());
      if(validation!=STATS_DAYS_VALID) {
         showPhoneDaysError(act,error,validation);
         return;
         }
      int selectedDays=parseStatisticsDays(days.getText().toString());
      boolean available=Natives.analysedays(selectedDays,history);
      if(!available) {
         Natives.analysedays(previousDays,previousHistory);
         error.setText(R.string.stats_modern_no_period_data);
         error.setVisibility(VISIBLE);
         error.announceForAccessibility(error.getText());
         act.requestRender();
         return;
         }
      act.poponback();
      act.curve.statspresent=false;
      act.curve.summarybutton=null;
      help.hidekeyboard(act);
      removeContentView(screen);
      act.requestRender();
      mkstats(act);
      });
   act.setonback(closeOnBack);
   }

private static void showPhoneDaysError(MainActivity act,TextView error,int validation) {
   int message;
   if(validation==STATS_DAYS_EMPTY)
      message=R.string.stats_modern_error_empty;
   else if(validation==STATS_DAYS_NOT_POSITIVE)
      message=R.string.stats_modern_error_positive;
   else if(validation==STATS_DAYS_TOO_LARGE)
      message=R.string.stats_modern_error_large;
   else
      message=R.string.stats_modern_error_number;
   error.setText(message);
   error.setVisibility(VISIBLE);
   error.announceForAccessibility(error.getText());
   }

static private void askdays(MainActivity act,boolean history) {
   if(!smallScreen) {
      askPhoneDays(act,history);
      return;
      }
   var label=getlabel(act,act.getString(R.string.days));

   int pad= (int)(tk.glucodata.GlucoseCurve.metrics.density*8);
   label.setPadding(pad,pad,pad,pad);
   Button Ok = getbutton(act, R.string.ok);
   Button Cancel = getbutton(act, R.string.cancel);
   EditText days= smallScreen?geteditwearos(act):geteditview(act,new editfocus()) ;
   days.setMinEms(4);
   days.setText(""+Natives.getAnalysedays());
   Layout layout = new Layout(act, (l, w, h) -> {
      int wid = GlucoseCurve.getwidth();
      if(!smallScreen) {
         int hei = GlucoseCurve.getheight();
         if(hei>h&&wid>w) {
                int half= wid / 2;
                int af=(half-w)/4;
             l.setX(half - w-af);
             l.setY((hei - h) / 2);
             }
            else {
             l.setX(0);
             l.setY(0);
               }
            }
      else {
             l.setX((wid-w)/2);
             l.setY(0);
         }
      return new int[]{w, h};
      },new View[]{label,days},new View[]{Cancel,Ok});
        layout.setPadding(pad,pad,pad,pad);
   act.addMyContentView(layout,new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
     layout.post(layout::requestLayout);
        layout.setBackgroundResource(R.drawable.dialogbackground);
   days.requestFocus();
   if(!smallScreen) {
      act.curve.numberview.showkeyboard(act);
      }
   else  {
      help.showkeyboard(act,days);
      }
final Runnable closeonback=()-> {
      removeContentView(layout);
      if(smallScreen) {
         help.hidekeyboard(act);
         }
      else
         act.curve.numberview.hidekeyboard() ;
      mkstats(act);
      };
   Cancel.setOnClickListener(v -> {
      act.poponback();   
      closeonback.run();
      });
   Ok.setOnClickListener(v -> {
      int get=0;
      String str=days.getText().toString();
      try {
         get=Integer.parseInt(str);  
         }
      catch(Throwable e) {
         stack(LOG_ID, e);
         };
      if(get<=0) {
              Applic.argToaster(act, "'"+str+act.getString(R.string.invaliddays), Toast.LENGTH_SHORT);
         return;
         }
      act.poponback();   
      act.curve.statspresent=false;
      act.curve.summarybutton=null;
      Natives.analysedays(get,history);
      removeContentView(layout);
      if(smallScreen) {
         help.hidekeyboard(act);
         }
      else
         act.curve.numberview.hidekeyboard() ;
      act.requestRender();
      mkstats(act);
      });
   act.setonback(closeonback);
   }

private static void mkPhoneStats(MainActivity act) {
   act.lightBars(false);
   boolean showHistory=Natives.getAnalysehistory();
   MainActivity.clearonback();

   FrameLayout overlay=new FrameLayout(act);
   overlay.setBackgroundColor(Color.TRANSPARENT);
   overlay.setClipChildren(false);

   Button close=ClinicalUi.button(act,act.getString(R.string.closename),
           ClinicalUi.ButtonRole.SECONDARY);
   LinearLayout header=ClinicalUi.header(act,
           act.getString(R.string.stats_modern_title),close);
   header.setPadding(ClinicalUi.dp(act,14),0,ClinicalUi.dp(act,10),0);
   header.setBackground(ClinicalUi.surface(act,true,false));
   FrameLayout.LayoutParams headerParams=new FrameLayout.LayoutParams(
           MATCH_PARENT,WRAP_CONTENT,Gravity.TOP);
   headerParams.setMargins(ClinicalUi.dp(act,16),
           MainActivity.systembarTop+ClinicalUi.dp(act,8),
           ClinicalUi.dp(act,16),0);
   overlay.addView(header,headerParams);

   LinearLayout controls=new LinearLayout(act);
   controls.setOrientation(LinearLayout.VERTICAL);
   controls.setPadding(ClinicalUi.dp(act,16),ClinicalUi.dp(act,12),
           ClinicalUi.dp(act,16),ClinicalUi.dp(act,16));
   controls.setBackground(ClinicalUi.surface(act,true,false));

   TextView controlsLabel=ClinicalUi.sectionLabel(act,
           act.getString(R.string.stats_modern_controls_section));
   controlsLabel.setPadding(ClinicalUi.dp(act,4),0,ClinicalUi.dp(act,4),
           ClinicalUi.dp(act,8));
   controls.addView(controlsLabel);
   Button period=ClinicalUi.button(act,
           act.getString(R.string.stats_modern_period_value,Natives.getAnalysedays()),
           ClinicalUi.ButtonRole.SECONDARY);
   controls.addView(period,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));

   CheckDirectionBox historySource=new CheckDirectionBox(act);
   historySource.setText(R.string.historyname);
   historySource.setChecked(showHistory);
   LinearLayout historyRow=ClinicalUi.toggleRow(act,historySource,
           act.getString(R.string.stats_modern_history_helper));
   LinearLayout historyCard=ClinicalUi.card(act,historyRow);
   LinearLayout.LayoutParams historyParams=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
   historyParams.topMargin=ClinicalUi.dp(act,10);
   controls.addView(historyCard,historyParams);
   SwitchCompat historySwitch=(SwitchCompat)historyRow.getChildAt(
           historyRow.getChildCount()-1);

   TextView status=ClinicalUi.body(act,act.curve.statspresent
           ?act.getString(R.string.stats_modern_ready)
           :act.getString(R.string.stats_modern_loading));
   status.setTextColor(ClinicalUi.accent(act));
   status.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
   status.setPadding(ClinicalUi.dp(act,14),ClinicalUi.dp(act,11),
           ClinicalUi.dp(act,14),ClinicalUi.dp(act,11));
   status.setBackground(ClinicalUi.surface(act,false,false));
   LinearLayout.LayoutParams statusParams=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
   statusParams.topMargin=ClinicalUi.dp(act,10);
   controls.addView(status,statusParams);

   Button summary=ClinicalUi.button(act,
           act.getString(R.string.summarygraph),ClinicalUi.ButtonRole.PRIMARY);
   Button report=ClinicalUi.button(act,
           act.getString(R.string.stats_modern_report),ClinicalUi.ButtonRole.SECONDARY);
   LinearLayout actionRow=new LinearLayout(act);
   actionRow.setOrientation(LinearLayout.HORIZONTAL);
   actionRow.addView(summary,new LinearLayout.LayoutParams(0,WRAP_CONTENT,1f));
   LinearLayout.LayoutParams reportParams=new LinearLayout.LayoutParams(0,WRAP_CONTENT,1f);
   reportParams.setMarginStart(ClinicalUi.dp(act,10));
   actionRow.addView(report,reportParams);
   LinearLayout.LayoutParams actionsParams=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
   actionsParams.topMargin=ClinicalUi.dp(act,10);
   controls.addView(actionRow,actionsParams);
   Button helpButton=ClinicalUi.button(act,act.getString(R.string.helpname),
           ClinicalUi.ButtonRole.SECONDARY);
   LinearLayout.LayoutParams helpParams=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
   helpParams.topMargin=ClinicalUi.dp(act,10);
   controls.addView(helpButton,helpParams);

   if(!act.curve.statspresent)
      summary.setVisibility(INVISIBLE);
   act.curve.summarybutton=summary;
   FrameLayout.LayoutParams controlsParams=new FrameLayout.LayoutParams(
           MATCH_PARENT,WRAP_CONTENT,Gravity.BOTTOM);
   controlsParams.setMargins(ClinicalUi.dp(act,16),0,ClinicalUi.dp(act,16),
           MainActivity.systembarBottom+ClinicalUi.dp(act,12));
   overlay.addView(controls,controlsParams);
   act.addMyContentView(overlay,new ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT));

   Runnable closeOnBack=()->{
      act.curve.statspresent=false;
      act.curve.summarybutton=null;
      removeContentView(overlay);
      Natives.endstats();
      act.themeLightBars();
      if(Menus.on)
         Menus.show(act);
      else
         act.requestRender();
      };
   close.setOnClickListener(v->{
      act.poponback();
      closeOnBack.run();
      });
   act.setonback(closeOnBack);
   helpButton.setOnClickListener(v->{
      act.themeLightBars();
      help.help(R.string.stathelp,act,l->act.lightBars(false));
      });
   period.setOnClickListener(v->{
      act.poponback();
      askdays(act,historySource.isChecked());
      removeContentView(overlay);
      });

   boolean[] ignoreSourceChange={false};
   final Runnable[] readyWatcher=new Runnable[1];
   readyWatcher[0]=()->{
      if(overlay.getParent()==null)
         return;
      if(summary.getVisibility()==VISIBLE) {
         status.setText(R.string.stats_modern_ready);
         status.setTextColor(ClinicalUi.accent(act));
         }
      else
         overlay.postDelayed(readyWatcher[0],250);
      };
   overlay.post(readyWatcher[0]);

   historySource.setOnCheckedChangeListener((button,isChecked)->{
      if(ignoreSourceChange[0])
         return;
      act.curve.summarybutton=summary;
      summary.setVisibility(INVISIBLE);
      status.setText(R.string.stats_modern_loading);
      status.setTextColor(ClinicalUi.accent(act));
      act.curve.statspresent=false;
      boolean available=Natives.analysedays(-1,isChecked);
      boolean actualHistory=Natives.getAnalysehistory();
      if(!available) {
         status.setText(R.string.stats_modern_no_period_data);
         status.setTextColor(ClinicalUi.danger(act));
         }
      else if(actualHistory!=isChecked) {
         status.setText(actualHistory
                 ?R.string.stats_modern_no_stream_data
                 :R.string.stats_modern_no_history_data);
         status.setTextColor(ClinicalUi.danger(act));
         ignoreSourceChange[0]=true;
         historySource.setChecked(actualHistory);
         historySwitch.setChecked(actualHistory);
         ignoreSourceChange[0]=false;
         Applic.argToaster(act,actualHistory?R.string.nostreamvalues:R.string.nohistoryvalues,
                 Toast.LENGTH_SHORT);
         }
      overlay.post(readyWatcher[0]);
      act.requestRender();
      });

   summary.setOnClickListener(v->{
      act.poponback();
      act.setonback(()->{
         Natives.summarygraph(false);
         Stats.mkstats(act);
         act.requestRender();
         });
      Natives.summarygraph(true);
      removeContentView(overlay);
      act.requestRender();
      });
   report.setOnClickListener(v->{
      if(Natives.getusexdripwebserver())
         webPercentiles(act,Natives.getAnalysedays(),historySource.isChecked());
      else
         Confirm.message(act,act.getString(R.string.titlewebserverneed),
                 act.getString(R.string.messagewebserverneed),()->{});
      });
   }

 private static void webPercentiles(Context context, int days,boolean history) {
    final long endtime=Natives.percentileEndtime(days);
	final String key=Natives.getApiSecret();
    final String addkey=(key!=null&&!key.isEmpty())?key+"/":"";
    final String type=(Natives.getDoCalibrate()?"&calibrated":"&")+(history?"history":"stream");
    final String url="http://127.0.0.1:17580/"+addkey+"x/report?amounts&days="+days+"&endtime="+endtime+type+"&hl="+Applic.curlang;
    var intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
    context.startActivity(intent);
    }

static void mkstats(MainActivity act) {
     if(!smallScreen) {
        mkPhoneStats(act);
        return;
        }
     boolean showhistory=Natives.getAnalysehistory();
      MainActivity.clearonback();

        var stats=getbutton(act,R.string.save);


      Button Help = getbutton(act, R.string.helpname);
      Button Close = getbutton(act, R.string.closename);
      Button Days = getbutton(act, R.string.days);
      var history = getcheckbox(act, R.string.historyname,showhistory);
      getMargins(history).rightMargin=(int)(GlucoseCurve.metrics.density*5.0);
      Button Curve = getbutton(act, R.string.summarygraph);
      Layout layout = new Layout(act, (l, w, h) -> {
      /*
         int height = GlucoseCurve.getheight();
         int width = GlucoseCurve.getwidth();
         if(width>w) l.setX(width - w-MainActivity.systembarRight);

         if(height>h) l.setY((height - h -MainActivity. systembarBottom));
         */
         return new int[]{w, h};
      }, new View[]{history,Days, Help},new View[]{Close,stats, Curve});

      layout.setBackgroundColor(Applic.backgroundcolor);
      if(!act.curve.statspresent)
         Curve.setVisibility(INVISIBLE);
      act.curve.summarybutton=Curve;

    var  params =    new FrameLayout.LayoutParams( WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM| Gravity.RIGHT);
    params.bottomMargin=MainActivity.systembarBottom;
    params.rightMargin=MainActivity.systembarRight;
      act.addMyContentView(layout, params);
    //  act.addMyContentView(layout, new ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
    final Runnable closeonback=()-> {
             act.curve.statspresent=false;
             act.curve.summarybutton=null;
             removeContentView(layout);
             Natives.endstats();
             {if(doLog) {Log.i(LOG_ID,"closeonback");};};

             if(Menus.on)  {
                Menus.show(act);
                }
             else
                act.requestRender();
             };
    Close.setOnClickListener(v -> {
       act.poponback();
       closeonback.run();
       });
    act.setonback(closeonback);
      Help.setOnClickListener(v ->  {
                    act.themeLightBars();
                    help.help(R.string.stathelp, act,l->act.lightBars(!Natives.getInvertColors( ))); 
                    });
      Days.setOnClickListener(v -> {
         act.poponback();
         askdays(act,history.isChecked());
         removeContentView(layout);
      });
     boolean[] dontswitch={false};
      history.setOnCheckedChangeListener( (buttonView,  isChecked)->  {
             // act.poponback();
             // removeContentView(layout);
             if(dontswitch[0])
                return;
              act.curve.summarybutton=Curve;
              Curve.setVisibility(INVISIBLE);
              act.curve.statspresent=false;
//              act.curve.summarybutton=null;
              Natives.analysedays(-1,isChecked);
              final boolean usehistory=Natives.getAnalysehistory();
              if(usehistory!=isChecked) {
                    Applic.argToaster(act,usehistory?R.string.nostreamvalues:R.string.nohistoryvalues, Toast.LENGTH_SHORT);
                    dontswitch[0]=true;
                    history.setChecked(usehistory);
                    dontswitch[0]=false;
                    }
              act.requestRender();
             // mkstats(act);
               }
             );
      Curve.setOnClickListener(v -> {
         boolean hist= history.isChecked();
         Log.i(LOG_ID,"history.isChecked()="+hist);

         act.poponback();
         act.setonback(()-> {
            Natives.summarygraph(false);
            Stats.mkstats(act);
            act.requestRender();
            });
         Natives.summarygraph(true);
         removeContentView(layout);
         act.requestRender();
      });

        stats.setOnClickListener(v->  {
            if(Natives.getusexdripwebserver() ) {
                webPercentiles(act,Natives.getAnalysedays(),history.isChecked());
                }
            else {
                    Confirm.message(act,
                        act.getString(R.string.titlewebserverneed),
                        act.getString(R.string.messagewebserverneed)
                        ,()->{}); 
                }
            });
   }
}
