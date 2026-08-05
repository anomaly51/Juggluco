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

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.text.LineBreakConfig;
import android.graphics.text.LineBreaker;
import android.net.Uri;
import android.os.Build;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import static android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE;
import static android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY;
import static android.view.View.GONE;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import static java.lang.System.currentTimeMillis;

import static tk.glucodata.Log.doLog;
import static tk.glucodata.NumberView.geteditview;
import static tk.glucodata.NumberView.geteditwearos;
import static tk.glucodata.NumberView.smallScreen;
import static tk.glucodata.util.getbutton;
import static tk.glucodata.util.getcheckbox;
import static tk.glucodata.util.getlabel;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Locale;

import tk.glucodata.settings.Settings;

//import org.w3c.dom.Text;

public class Dialogs {
private    final static String LOG_ID="Dialogs";
private float density;
private ViewGroup exportscreen=null;
 TextView exportlabel=null;
 private boolean isCalibrated=Natives.getDoCalibrate();
Dialogs(float density) {
    this.density=density;
    }

static final int EXPORT_DAYS_VALID=0;
static final int EXPORT_DAYS_EMPTY=1;
static final int EXPORT_DAYS_NOT_NUMBER=2;
static final int EXPORT_DAYS_NOT_POSITIVE=3;

static int validateExportDays(String raw) {
    if(raw==null||raw.trim().isEmpty())
        return EXPORT_DAYS_EMPTY;
    try {
        float value=parseExportDays(raw);
        if(!Float.isFinite(value))
            return EXPORT_DAYS_NOT_NUMBER;
        if(value<=0f)
            return EXPORT_DAYS_NOT_POSITIVE;
        }
    catch(NumberFormatException ex) {
        return EXPORT_DAYS_NOT_NUMBER;
        }
    return EXPORT_DAYS_VALID;
    }

static float parseExportDays(String raw) {
    return Float.parseFloat(raw.trim().replace(',','.'));
    }

static int exportTypeWithCalibration(int baseType,boolean calibrated) {
    if(baseType==4)
        return baseType;
    return baseType|(calibrated?8:0);
    }

static String extensionForExportType(int baseType) {
    if(baseType==4)
        return ".html";
    if(baseType==5)
        return ".csv";
    return ".tsv";
    }
private Button exportbutton(MainActivity activity,String label, int type) {
    Button but=new Button(activity);
    but.setText(label);
    but.setOnClickListener(
        v-> {
            float daynr;
            try {
                daynr=Float.parseFloat(String.valueOf(days.getText()));
                } catch(Throwable th) {

                    exportlabel.setText("I don't understand \'"+days.getText()+"\'");
                    return;
                };
            switch(type) {
                case 4: algexporter(activity,   type,label,".html",daynr);break;
                case 5: algexporter(activity,type|(isCalibrated?8:0),label,".csv",daynr);break;
                default: exporter( activity,  type|(isCalibrated?8:0),label,daynr);
                };
            });
    return but;
    }
         




EditText days;

private void showPhoneExport(MainActivity activity,View parent) {
    activity.lightBars(false);
    if(parent!=null) {
        parent.setVisibility(GONE);
        }
    if(exportscreen==null) {
        LinearLayout content=ClinicalUi.verticalContent(activity);
        content.setPadding(ClinicalUi.dp(activity,20),
                MainActivity.systembarTop+ClinicalUi.dp(activity,8),
                ClinicalUi.dp(activity,20),ClinicalUi.dp(activity,30));
        Button close=ClinicalUi.button(activity,activity.getString(R.string.closename),
                ClinicalUi.ButtonRole.SECONDARY);
        content.addView(ClinicalUi.header(activity,
                activity.getString(R.string.export_modern_title),close));
        TextView intro=ClinicalUi.body(activity,
                activity.getString(R.string.export_modern_intro));
        intro.setPadding(ClinicalUi.dp(activity,4),0,ClinicalUi.dp(activity,4),
                ClinicalUi.dp(activity,4));
        content.addView(intro);

        content.addView(ClinicalUi.sectionLabel(activity,
                activity.getString(R.string.export_modern_period_section)));
        days=new EditText(activity);
        days.setSingleLine(true);
        days.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        days.setTextColor(ClinicalUi.primaryText(activity));
        days.setHintTextColor(ClinicalUi.secondaryText(activity));
        days.setTextSize(TypedValue.COMPLEX_UNIT_SP,17);
        days.setGravity(Gravity.CENTER);
        days.setMinWidth(ClinicalUi.dp(activity,104));
        days.setMinimumHeight(ClinicalUi.dp(activity,50));
        days.setPadding(ClinicalUi.dp(activity,12),0,ClinicalUi.dp(activity,12),0);
        days.setBackground(ClinicalUi.surface(activity,false,true));
        TextView unit=new TextView(activity);
        unit.setText(R.string.export_modern_days_unit);
        unit.setTextColor(ClinicalUi.secondaryText(activity));
        unit.setTextSize(TypedValue.COMPLEX_UNIT_SP,15);
        unit.setPaddingRelative(ClinicalUi.dp(activity,10),0,ClinicalUi.dp(activity,6),0);
        LinearLayout periodCard=ClinicalUi.card(activity,
                ClinicalUi.fieldRow(activity,
                        activity.getString(R.string.export_modern_period_label),days,unit));
        content.addView(periodCard);
        TextView periodHelp=ClinicalUi.body(activity,
                activity.getString(R.string.export_modern_period_helper));
        periodHelp.setTextSize(TypedValue.COMPLEX_UNIT_SP,13);
        periodHelp.setPadding(ClinicalUi.dp(activity,4),ClinicalUi.dp(activity,9),
                ClinicalUi.dp(activity,4),0);
        content.addView(periodHelp);

        CheckDirectionBox calibratedSource=new CheckDirectionBox(activity);
        calibratedSource.setText(R.string.calibrated);
        calibratedSource.setChecked(isCalibrated);
        LinearLayout calibratedRow=ClinicalUi.toggleRow(activity,calibratedSource,
                activity.getString(R.string.export_modern_calibrated_helper));
        LinearLayout.LayoutParams calibratedParams=new LinearLayout.LayoutParams(
                MATCH_PARENT,WRAP_CONTENT);
        calibratedParams.topMargin=ClinicalUi.dp(activity,12);
        content.addView(ClinicalUi.card(activity,calibratedRow),calibratedParams);
        calibratedSource.setOnCheckedChangeListener((button,checked)->isCalibrated=checked);

        exportlabel=ClinicalUi.body(activity,"");
        exportlabel.setTextColor(ClinicalUi.danger(activity));
        exportlabel.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        exportlabel.setPadding(ClinicalUi.dp(activity,16),ClinicalUi.dp(activity,12),
                ClinicalUi.dp(activity,16),ClinicalUi.dp(activity,12));
        exportlabel.setBackground(ClinicalUi.surface(activity,false,false));
        exportlabel.setVisibility(GONE);
        LinearLayout.LayoutParams errorParams=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
        errorParams.topMargin=ClinicalUi.dp(activity,12);
        content.addView(exportlabel,errorParams);

        content.addView(ClinicalUi.sectionLabel(activity,
                activity.getString(R.string.export_modern_type_section)));
        addPhoneExportCard(content,activity,activity.getString(R.string.amountsname),0,
                R.string.export_modern_amounts_helper);
        addPhoneExportCard(content,activity,activity.getString(R.string.scansname),1,
                R.string.export_modern_scans_helper);
        addPhoneExportCard(content,activity,activity.getString(R.string.streamname),2,
                R.string.export_modern_stream_helper);
        addPhoneExportCard(content,activity,activity.getString(R.string.historyname),3,
                R.string.export_modern_history_helper);
        addPhoneExportCard(content,activity,activity.getString(R.string.mealsname),4,
                R.string.export_modern_meals_helper);
        addPhoneExportCard(content,activity,activity.getString(R.string.libreviewname),5,
                R.string.export_modern_libreview_helper);

        Button helpButton=ClinicalUi.button(activity,activity.getString(R.string.helpname),
                ClinicalUi.ButtonRole.SECONDARY);
        LinearLayout.LayoutParams helpParams=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
        helpParams.topMargin=ClinicalUi.dp(activity,18);
        content.addView(helpButton,helpParams);
        helpButton.setOnClickListener(v->tk.glucodata.help.helplight(
                R.string.helpexport,activity));
        close.setOnClickListener(v->activity.doonback());

        ScrollView screen=ClinicalUi.scrollScreen(activity,content);
        screen.setVerticalScrollBarEnabled(false);
        exportscreen=screen;
        long hour24=1000L*60L*60L*24L;
        long endtime=Natives.getendtime();
        long dayCount=(endtime-Natives.oldestdatatime()+hour24-1)/hour24;
        days.setText(Long.toString(Math.max(1L,dayCount)));
        }
    if(exportscreen.getParent()==null)
        activity.addMyContentView(exportscreen,new ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT));
    exportscreen.setVisibility(VISIBLE);
    exportscreen.bringToFront();
    showPhoneExportError(activity,EXPORT_DAYS_VALID);
    days.clearFocus();
    activity.setonback(()->{
        help.hidekeyboard(activity);
        if(activity.curve!=null&&activity.curve.numberview!=null)
            activity.curve.numberview.hidekeyboard();
        exportscreen.setVisibility(GONE);
        activity.themeLightBars();
        if(parent!=null) {
            parent.setVisibility(VISIBLE);
            }
        else if(Menus.on)
            Menus.show(activity);
        });
    }

private void addPhoneExportCard(
        LinearLayout content,MainActivity activity,String title,int type,int helperRes) {
    LinearLayout card=new LinearLayout(activity);
    card.setOrientation(LinearLayout.HORIZONTAL);
    card.setGravity(Gravity.CENTER_VERTICAL);
    card.setMinimumHeight(ClinicalUi.dp(activity,76));
    card.setPaddingRelative(ClinicalUi.dp(activity,18),ClinicalUi.dp(activity,11),
            ClinicalUi.dp(activity,14),ClinicalUi.dp(activity,11));
    card.setBackground(ClinicalUi.surface(activity,true,true));
    card.setClickable(true);
    card.setFocusable(true);
    LinearLayout copy=new LinearLayout(activity);
    copy.setOrientation(LinearLayout.VERTICAL);
    TextView label=new TextView(activity);
    label.setText(title);
    label.setTextColor(ClinicalUi.primaryText(activity));
    label.setTextSize(TypedValue.COMPLEX_UNIT_SP,17);
    label.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));
    copy.addView(label);
    TextView helper=ClinicalUi.body(activity,activity.getString(helperRes));
    helper.setTextSize(TypedValue.COMPLEX_UNIT_SP,13);
    helper.setPadding(0,ClinicalUi.dp(activity,3),ClinicalUi.dp(activity,10),0);
    copy.addView(helper);
    card.addView(copy,new LinearLayout.LayoutParams(0,WRAP_CONTENT,1f));
    TextView format=new TextView(activity);
    format.setText(extensionForExportType(type).substring(1).toUpperCase(Locale.US));
    format.setTextColor(ClinicalUi.accent(activity));
    format.setTextSize(TypedValue.COMPLEX_UNIT_SP,13);
    format.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));
    format.setGravity(Gravity.CENTER);
    format.setPadding(ClinicalUi.dp(activity,10),ClinicalUi.dp(activity,7),
            ClinicalUi.dp(activity,10),ClinicalUi.dp(activity,7));
    format.setBackground(ClinicalUi.surface(activity,false,false));
    card.addView(format);
    card.setContentDescription(activity.getString(
            R.string.export_modern_card_description,title,format.getText()));
    card.setOnClickListener(v->startPhoneExport(activity,title,type));
    LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
    params.bottomMargin=ClinicalUi.dp(activity,10);
    content.addView(card,params);
    }

private void startPhoneExport(MainActivity activity,String label,int baseType) {
    int validation=validateExportDays(days.getText().toString());
    if(validation!=EXPORT_DAYS_VALID) {
        showPhoneExportError(activity,validation);
        return;
        }
    showPhoneExportError(activity,EXPORT_DAYS_VALID);
    help.hidekeyboard(activity);
    float dayCount=parseExportDays(days.getText().toString());
    int requestType=exportTypeWithCalibration(baseType,isCalibrated);
    String extension=extensionForExportType(baseType);
    if(baseType==4||baseType==5)
        algexporter(activity,requestType,label,extension,dayCount);
    else
        exporter(activity,requestType,label,dayCount);
    }

private void showPhoneExportError(MainActivity activity,int validation) {
    if(exportlabel==null)
        return;
    if(validation==EXPORT_DAYS_VALID) {
        exportlabel.setText("");
        exportlabel.setVisibility(GONE);
        return;
        }
    int message=validation==EXPORT_DAYS_EMPTY
            ?R.string.export_modern_error_empty
            :validation==EXPORT_DAYS_NOT_POSITIVE
                    ?R.string.export_modern_error_positive
                    :R.string.export_modern_error_number;
    exportlabel.setText(message);
    exportlabel.setVisibility(VISIBLE);
    exportlabel.announceForAccessibility(exportlabel.getText());
    }


public void showexport(MainActivity activity,int width,int height,View parent) {
   if(!smallScreen) {
       showPhoneExport(activity,parent);
       return;
       }
   if(parent!=null) {
       activity.lightBars(!Natives.getInvertColors());
       parent.setVisibility(GONE);
       {if(doLog) {Log.i(LOG_ID, "parent.setVisibility(GONE)");};};
       }
    if(exportscreen==null) {
        Button num=exportbutton(activity,activity.getString(R.string.amountsname),0);
        Button scan=exportbutton(activity,activity.getString(R.string.scansname),1);
        Button stream=exportbutton(activity,activity.getString(R.string.streamname),2);
        Button hist=exportbutton(activity,activity.getString(R.string.historyname),3);
        Button meals=exportbutton(activity,activity.getString(R.string.mealsname),4);
        Button libreview=exportbutton(activity,activity.getString(R.string.libreviewname),5);
        days= smallScreen?geteditwearos(activity):geteditview(activity,new editfocus()) ;
        days.setMinEms(3);
        long hour24=1000*60*60*24;
        long endtime=Natives.getendtime();
        long daysnr= (endtime- Natives.oldestdatatime()+hour24-1)/hour24;
        days.setText(Long.toString(daysnr));
        var daylabel=getlabel(activity, R.string.days);


        var help=getbutton(activity,R.string.helpname);
        help.setOnClickListener(v->  {
                tk.glucodata.help.helplight(R.string.helpexport,activity); 
                }
                );

        exportlabel=new TextView(activity);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            exportlabel.setElegantTextHeight(true);
        }
        exportlabel.setInputType(InputType.TYPE_TEXT_FLAG_MULTI_LINE);

        
   //     exportlabel.setLayoutParams(new ViewGroup.LayoutParams(  (int)(width*(smallScreen?0.6f:.33f)), WRAP_CONTENT));
        exportlabel.setLayoutParams(new ViewGroup.LayoutParams(  MATCH_PARENT, WRAP_CONTENT));
        exportlabel.setSingleLine(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportlabel.setBreakStrategy(BREAK_STRATEGY_SIMPLE);
        }
        final int rand=Math.round(5*density);
        final int leftright=Math.round(2*density);
        exportlabel.setPadding(leftright,rand,0,rand);
        Button close=new Button(activity);
        close.setText(R.string.closename);
        close.setOnClickListener(v-> activity.doonback());
        var calibrated=getcheckbox(activity,R.string.calibrated, isCalibrated);
        calibrated.setOnCheckedChangeListener( (buttonView,  isChecked) ->  { 
            isCalibrated=isChecked; 
            });
        exportscreen=new Layout(activity, (l, w, h) -> {
            int wid = GlucoseCurve.getwidth();
            if(!smallScreen) {
                int hei = GlucoseCurve.getheight();
                if(hei>h&&wid>w) {
                    int half= wid / 2;
                    int af=(half-w)/4;
                    l.setX(half - w-af +MainActivity.systembarLeft);
                    l.setY((hei - h) / 2);
                    }
                   else {
                    l.setX(MainActivity.systembarLeft);
                    l.setY(MainActivity.systembarTop*3/4);
                    }
                   }
            else {
                 l.setX((int)(MainActivity.systembarLeft+(wid-w-MainActivity.systembarLeft-MainActivity.systembarRight)*.5f));
                l.setY(MainActivity.systembarTop*3/4);
                 }
               return new int[] {w,h};
            }, 
            new View[] {calibrated,daylabel,days},
             new View[] {scan,hist,stream},
             new View[] {num,meals,libreview},
            new View[]{exportlabel},
             new View[] {help,close}
            );
        ((Layout)exportscreen).useMatch=true;
        exportscreen.setPadding(rand,rand,rand,rand);
        exportscreen.setBackgroundColor( Applic.backgroundcolor);
        activity.addMyContentView(exportscreen, new ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
//        exportlabel.requestFocus();

         exportscreen.post(exportscreen::requestLayout);
        }
    else {
            exportscreen.setVisibility(VISIBLE);
         exportscreen.bringToFront();
        }

    exportlabel.setText(R.string.exporthelp);
    days.requestFocus();
    if(!smallScreen) {
        activity.curve.numberview.showkeyboard(activity);
        }
    else  {
        help.showkeyboard(activity,days);
        }
    {if(doLog) {Log.i(LOG_ID, "parent==null");};};
     activity.setonback(() -> {
       if(smallScreen) {
          help.hidekeyboard(activity);
          }
       else
          activity.curve.numberview.hidekeyboard() ;
       exportscreen.setVisibility(GONE);
       if(parent!=null) {
            activity.themeLightBars();
            {if(doLog) {Log.i(LOG_ID, "parent.setVisibility(VISIBLE)");};};
            parent.setVisibility(VISIBLE);
          }
       else {
          if(Menus.on)
             Menus.show(activity);
          } });
    }
static    public final DateFormat fdatename=             new SimpleDateFormat("yyyy-MM-dd-HH:mm:ss", Locale.US);
static void algexporter(MainActivity context,int type,String prefix,String ext,float days) {
    final long time=Natives.getendtime();
    final String datestr=fdatename.format(time)      ;
        final String filename = prefix+datestr+ext;
        exportdata(context,type,filename,days);
    }
static void exporter(MainActivity context,int type,String prefix,float days) {
        algexporter(context,type,prefix,".tsv",days);
    }


static float showdays=0;
static private void exportdata(MainActivity     context,int type,String name,float days) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_TITLE, name);
    intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
          int request= MainActivity.REQUEST_EXPORT|type;
    try {
        showdays=days;
        context.startActivityForResult(intent, request);
        } catch(Throwable th) {
            Log.stack(LOG_ID,"ACTION_CREATE_DOCUMENT",th);
            }
    }

}
