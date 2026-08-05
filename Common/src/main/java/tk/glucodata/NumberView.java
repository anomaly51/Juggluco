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

import android.view.Gravity;
import android.widget.LinearLayout;
import android.app.Activity;
import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.Selection;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.Button;

import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ListPopupWindow;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.TimePicker;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;


import tk.glucodata.nums.AllData;
import tk.glucodata.nums.numio;

import static android.widget.LinearLayout.VERTICAL;
import static android.view.View.GONE;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static android.widget.Spinner.MODE_DIALOG;
import static android.widget.Spinner.MODE_DROPDOWN;
import static java.lang.System.currentTimeMillis;
//import static tk.glucodata.Applic.smallScreen;
import static tk.glucodata.Applic.isWearable;
import static tk.glucodata.Applic.systemtimeformat;
import static tk.glucodata.GlucoseCurve.mktime;
import static tk.glucodata.Layout.getMargins;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.MainActivity.systembarLeft;
import static tk.glucodata.MainActivity.systembarRight;
import static tk.glucodata.RingTones.EnableControls;
import static tk.glucodata.Specific.useclose;
import static tk.glucodata.settings.Settings.editoptions;
import static tk.glucodata.settings.Settings.getGenSpin;
import static tk.glucodata.settings.Settings.removeContentView;
import static tk.glucodata.util.getbutton;
import static tk.glucodata.util.getcheckbox;
import static tk.glucodata.util.getlabel;


public class NumberView {
private static final boolean SmallShowKeyboard=false;
public static  boolean smallScreen=false;
final private static String LOG_ID="NumberView";
Calendar cal = Calendar.getInstance();
Layout datepicker=null;
TextView dateview;
DatePicker datepick;
View newnumview=null;
Button deletebutton, savebutton,cancelbutton ;
long currentnum=0L;
Spinner spinner;
EditText valueedit;
TextView source=null;
Button timebutton,datebutton;
void deleteviews() {
    Log.i(LOG_ID,"deleteviews()");
    closenumview();
    spinner=null;
    if(newnumview!=null) {
        removeContentView(newnumview);
        newnumview=null;
        }
    if(datepicker!=null) {
        removeContentView(datepicker);
        datepicker=null;
        }
    if(timepicker!=null) {
        removeContentView(timepicker);
        timepicker=null;
        Log.i(LOG_ID,"timepicker=null");
        }
    if(keyboard!=null) {
        removeContentView(keyboard);
        keyboard=null;
        }
   phoneAddBody=null;
   phoneAddForm=null;
   phoneAddStage=null;
   phoneAddKeyColumn=null;
   phoneAddBottomBar=null;
   phoneAddScreen=null;
   phoneAddScroll=null;
   phoneAddKeyPortraitIndex=-1;
   phoneEditorTitle=null;
   phoneEditorSubtitle=null;
   phoneDeleteSummary=null;
   phoneDeleteConfirm=null;
   phoneDeleteConfirmButton=null;
   phoneDeleteKeepButton=null;
   keyboardEmbedded=false;
   cal = Calendar.getInstance();
    }
/*
/*
void rotatekey(float deg) {
    keyboard.setRotation(deg);
//    keyboard.bringToFront();
    newnumview.setRotation(deg);

//    newnumview.bringToFront();

    }*/
int labelsel=-1;
void closenumview() {
    if (newnumview != null) { 
        newnumview.setVisibility(GONE);
        hidekeyboard();
         }
    }

Button mealbutton;
CheckDirectionBox excludebox;
public void  addnumberview(MainActivity activity, long hitptr) {
    final boolean oldnum=hitptr!=numio.newhit;
    if(currentnum!=0L&&currentnum!=numio.newhit)  {
            Natives.freehitptr(currentnum);
            }
    currentnum=0L;
    long time= Natives.hittime(hitptr)*1000L;
    lasttime=time;
    int bron= Natives.gethitindex(hitptr);
    var type=Natives.hittype(hitptr);
    var exclude=Natives.hitexclude(hitptr);
    addnumberview(activity, bron,time,Natives.hitvalue(hitptr),type,-1);
    if(oldnum) {
        boolean staticnum=Natives.staticnum();
       // Log.i(LOG_ID,"addnumberview oldnum staticnum="+staticnum);
        if(!staticnum) {
            seedelete();
            }
        else {
            nodelete();
            }
        currentnum = hitptr;
        setmealbutton(type,bron, Natives.hitmeal(hitptr),exclude) ;
        }
    else {
        //Log.i(LOG_ID,"addnumberview new num");
        nodelete();
        setmealbutton(type,bron, 0,shouldexclude) ;
        currentnum=0L;
        }
    updateEditorMode(oldnum);
           if(dateview!=null) {
               thedate = time;
              }

            if(timeview!=null) {
                cal.setTimeInMillis(time);
                int hour = cal.get(Calendar.HOUR_OF_DAY);
                int min = cal.get(Calendar.MINUTE);
                thetime=hour * 60 + min;
                }
        }
float roundto(float get,float ro) {
    return Math.round(get/ro)*ro;
    }
final private int[] newmealptr={0};
final private Layout[] mealview={null};
private long lasttime=0L;
private TextView messagetext;
private TextView phoneEditorTitle;
private TextView phoneEditorSubtitle;
private TextView phoneDeleteSummary;
private LinearLayout phoneDeleteConfirm;
private Button phoneDeleteConfirmButton;
private Button phoneDeleteKeepButton;
boolean shouldexclude=false;
void  setExcludeTime(long time){
      shouldexclude = Natives.shouldExclude(time);
      EnableControls(excludebox, !shouldexclude);
      excludebox.setChecked(shouldexclude);
       }
//public static String minhourstr(Date dat) {
public static String minhourstr(long mmsec) {
   if(systemtimeformat())
      return Notify.timef.format(new Date(mmsec));
   else {
      Calendar cal = Calendar.getInstance();
      cal.setTimeInMillis(mmsec);
      return mktime(cal.get(Calendar.HOUR_OF_DAY),cal.get(Calendar.MINUTE));
      }
   }
public   View addnumberview(MainActivity context,final int bron,final long time,final float value,final int type,final int tmpmealptr) {
    if(newnumview==null) {
       // var mat = new MaterialButton(context); mat.setCornerRadius(GlucoseCurve.dpToPx(30)); datebutton=mat; 
      datebutton = new Button(context);
        datebutton.setOnClickListener(v -> {
            hidekeyboard();
            getdateview(context);
        });
    source=new TextView(context);
        dateview=datebutton;
        timebutton = new Button(context);

//        var mat2 = new MaterialButton(context); timebutton=mat2; mat2.setCornerRadius(GlucoseCurve.dpToPx(30));

        timeview=timebutton;
    mealbutton=getbutton(context,R.string.mealname);
    excludebox=getcheckbox(context,R.string.exclude,true);
    source.setMinWidth(mealbutton.getMinWidth());
    messagetext=getlabel(context,R.string.dontchangeamounts);
    mealbutton.setVisibility(GONE);
    excludebox.setVisibility(GONE);
    if(smallScreen) {
        valueedit=geteditwearos(context);
        }
    else  {
        valueedit = geteditview(context,new editfocus());
        }
    valueedit.setMinEms(isWearable?2:4);

        deletebutton = new Button(context);

        deletebutton.setText(R.string.delete);

        cancelbutton = new Button(context);
        cancelbutton.setText(R.string.cancel);
        savebutton = new Button(context);
        savebutton.setText(R.string.save);

    Button helpbutton;
    if(!isWearable) {
        helpbutton=getbutton(context,R.string.helpname);
        helpbutton.setOnClickListener(v-> help.helplight(R.string.newamount,context));
        }
    else {
       if(!useclose) cancelbutton.setVisibility(GONE);
        }
      ViewGroup layout;

    if(isWearable) {
        int height=GlucoseCurve.getheight();
        int width=GlucoseCurve.getwidth();
        int hormarg= (int)(width*0.08f);
        getMargins(timebutton).setMarginEnd(hormarg);
       // getMargins(savebutton).setMarginStart(hormarg); 
        getMargins(datebutton).setMarginStart(hormarg); 
//        getMargins(deletebutton).setMarginEnd(hormarg);
        //Log.i(LOG_ID,"addNumberView width="+width+" hormarg="+hormarg);
      if(useclose)  {
          layout = new Layout(context, (lay,w,h) -> { 
          return new int[]{w,h}; }, new View[]{datebutton,timebutton} ,new View[]{getspinner(context), valueedit}, new View[]{excludebox},new View[]{messagetext,savebutton,deletebutton},new View[]{cancelbutton});
      }
      else {
       layout = new Layout(context, (lay,w,h) -> {
   return new int[]{w,h};
   }, new View[]{datebutton,timebutton} ,new View[]{getspinner(context), valueedit},new View[]{excludebox}, new View[]{messagetext,savebutton,deletebutton});
   }


        if(true) {
           layout.setPaddingRelative((int)(width*0.01f),(int)(height*.15f),(int)(width*0.01f),(int)(height*.01f));
           ScrollView scroll=new ScrollView(context);
           scroll.setFillViewport(true);
           scroll.setSmoothScrollingEnabled(false);
           scroll.setScrollbarFadingEnabled(true);
           scroll.setVerticalScrollBarEnabled(true);
           scroll.addView(layout, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
           newnumview=scroll;
           }
         else {
           var frame=new FrameLayout(context);
           frame.addView(layout, new ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
           newnumview=frame;
             }
      }
  else {
    final float density=GlucoseCurve.metrics.density;
    final int pagePadding=(int)(density*18.0f);
    final int sectionGap=(int)(density*20.0f);
    final int rowGap=(int)(density*12.0f);
    final int controlHeight=(int)(density*56.0f);
    final int amountHeight=(int)(density*68.0f);

    LinearLayout formColumn=new LinearLayout(context);
    formColumn.setOrientation(VERTICAL);
    formColumn.setPadding(0,(int)(density*8.0f),0,(int)(density*24.0f));

    LinearLayout headerRow=new LinearLayout(context);
    headerRow.setOrientation(LinearLayout.HORIZONTAL);
    headerRow.setGravity(Gravity.CENTER_VERTICAL);
    styleTopActionButton(cancelbutton,density);
    headerRow.addView(cancelbutton,new LinearLayout.LayoutParams(
            WRAP_CONTENT,controlHeight));
    phoneEditorTitle=new TextView(context);
    phoneEditorTitle.setText(R.string.record_editor_new_title);
    phoneEditorTitle.setTextColor(Color.rgb(242,244,243));
    phoneEditorTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP,20.0f);
    phoneEditorTitle.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
    phoneEditorTitle.setGravity(Gravity.CENTER);
    phoneEditorTitle.setSingleLine(true);
    LinearLayout.LayoutParams titleParams=new LinearLayout.LayoutParams(
            0,controlHeight,1.0f);
    titleParams.setMarginStart(rowGap);
    titleParams.setMarginEnd(rowGap);
    headerRow.addView(phoneEditorTitle,titleParams);
    styleTopActionButton(helpbutton,density);
    helpbutton.setContentDescription(context.getString(R.string.helpname));
    LinearLayout.LayoutParams helpParams=new LinearLayout.LayoutParams(
            WRAP_CONTENT,controlHeight);
    headerRow.addView(helpbutton,helpParams);

    phoneEditorSubtitle=new TextView(context);
    phoneEditorSubtitle.setText(R.string.record_editor_new_subtitle);
    phoneEditorSubtitle.setTextColor(Color.rgb(155,161,159));
    phoneEditorSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP,15.0f);
    phoneEditorSubtitle.setLineSpacing(0,1.08f);
    formColumn.addView(phoneEditorSubtitle,new LinearLayout.LayoutParams(
            MATCH_PARENT,WRAP_CONTENT));

    Spinner typeSpinner=getspinner(context);
    typeSpinner.setId(View.generateViewId());
    typeSpinner.setContentDescription(context.getString(R.string.add_record_category));
    styleField(typeSpinner,density);
    valueedit.setId(View.generateViewId());
    valueedit.setHint(R.string.add_record_amount);
    valueedit.setContentDescription(context.getString(R.string.add_record_amount));
    styleAmountField(valueedit,density);
    TextView whatTitle=sectionTitle(context,R.string.record_editor_what);
    LinearLayout.LayoutParams whatTitleParams=new LinearLayout.LayoutParams(
            MATCH_PARENT,WRAP_CONTENT);
    whatTitleParams.topMargin=sectionGap;
    formColumn.addView(whatTitle,whatTitleParams);
    LinearLayout whatCard=sectionCard(context,density);
    whatCard.addView(labelledField(context,R.string.add_record_category,
            typeSpinner,controlHeight),new LinearLayout.LayoutParams(
            MATCH_PARENT,WRAP_CONTENT));
    LinearLayout.LayoutParams valueGroupParams=new LinearLayout.LayoutParams(
            MATCH_PARENT,WRAP_CONTENT);
    valueGroupParams.topMargin=rowGap;
    whatCard.addView(labelledField(context,R.string.add_record_amount,
            valueedit,amountHeight),valueGroupParams);
    LinearLayout.LayoutParams whatCardParams=new LinearLayout.LayoutParams(
            MATCH_PARENT,WRAP_CONTENT);
    whatCardParams.topMargin=(int)(density*8.0f);
    formColumn.addView(whatCard,whatCardParams);

    datebutton.setId(View.generateViewId());
    datebutton.setContentDescription(context.getString(R.string.date));
    styleFieldButton(datebutton,density);
    timebutton.setId(View.generateViewId());
    timebutton.setContentDescription(context.getString(R.string.time));
    styleFieldButton(timebutton,density);
    LinearLayout whenRow=new LinearLayout(context);
    whenRow.setOrientation(LinearLayout.HORIZONTAL);
    whenRow.addView(labelledField(context,R.string.date,datebutton,controlHeight),
            new LinearLayout.LayoutParams(0,WRAP_CONTENT,1.0f));
    LinearLayout.LayoutParams timeGroupParams=new LinearLayout.LayoutParams(
            0,WRAP_CONTENT,1.0f);
    timeGroupParams.setMarginStart(rowGap);
    whenRow.addView(labelledField(context,R.string.time,timebutton,controlHeight),
            timeGroupParams);
    TextView whenTitle=sectionTitle(context,R.string.record_editor_when);
    LinearLayout.LayoutParams whenTitleParams=new LinearLayout.LayoutParams(
            MATCH_PARENT,WRAP_CONTENT);
    whenTitleParams.topMargin=sectionGap;
    formColumn.addView(whenTitle,whenTitleParams);
    LinearLayout whenCard=sectionCard(context,density);
    whenCard.addView(whenRow,new LinearLayout.LayoutParams(
            MATCH_PARENT,WRAP_CONTENT));
    LinearLayout.LayoutParams whenCardParams=new LinearLayout.LayoutParams(
            MATCH_PARENT,WRAP_CONTENT);
    whenCardParams.topMargin=(int)(density*8.0f);
    formColumn.addView(whenCard,whenCardParams);

    LinearLayout detailRow=new LinearLayout(context);
    detailRow.setOrientation(VERTICAL);
    detailRow.setGravity(Gravity.CENTER_VERTICAL);
    mealbutton.setAllCaps(false);
    mealbutton.setTextColor(Color.rgb(226,229,227));
    mealbutton.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);
    mealbutton.setBackgroundResource(R.drawable.add_record_field);
    mealbutton.setPaddingRelative((int)(density*14.0f),0,
            (int)(density*14.0f),0);
    detailRow.addView(excludebox,new LinearLayout.LayoutParams(
            MATCH_PARENT,controlHeight));
    detailRow.addView(mealbutton,new LinearLayout.LayoutParams(
            MATCH_PARENT,controlHeight));
    source.setTextColor(Color.rgb(155,161,159));
    source.setTextSize(TypedValue.COMPLEX_UNIT_SP,14.0f);
    source.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);
    source.setBackgroundResource(R.drawable.add_record_field);
    source.setPaddingRelative((int)(density*14.0f),0,
            (int)(density*14.0f),0);
    detailRow.addView(source,new LinearLayout.LayoutParams(
            MATCH_PARENT,controlHeight));
    TextView detailTitle=sectionTitle(context,R.string.add_record_details);
    LinearLayout.LayoutParams detailTitleParams=new LinearLayout.LayoutParams(
            MATCH_PARENT,WRAP_CONTENT);
    detailTitleParams.topMargin=sectionGap;
    formColumn.addView(detailTitle,detailTitleParams);
    LinearLayout detailCard=sectionCard(context,density);
    detailCard.addView(detailRow,new LinearLayout.LayoutParams(
            MATCH_PARENT,WRAP_CONTENT));
    LinearLayout.LayoutParams detailCardParams=new LinearLayout.LayoutParams(
            MATCH_PARENT,WRAP_CONTENT);
    detailCardParams.topMargin=(int)(density*8.0f);
    formColumn.addView(detailCard,detailCardParams);

    styleDangerButton(deletebutton,density);
    stylePrimaryButton(savebutton,density);
    messagetext.setTextColor(Color.rgb(155,161,159));
    messagetext.setTextSize(TypedValue.COMPLEX_UNIT_SP,14.0f);
    messagetext.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);
    messagetext.setBackgroundResource(R.drawable.add_record_notice);
    messagetext.setPadding((int)(density*16.0f),(int)(density*14.0f),
            (int)(density*16.0f),(int)(density*14.0f));
    LinearLayout.LayoutParams messageParams=new LinearLayout.LayoutParams(
            MATCH_PARENT,WRAP_CONTENT);
    messageParams.topMargin=sectionGap;
    formColumn.addView(messagetext,messageParams);
    deletebutton.setText(R.string.record_editor_delete);
    LinearLayout.LayoutParams deleteParams=new LinearLayout.LayoutParams(
            MATCH_PARENT,controlHeight);
    deleteParams.topMargin=sectionGap;
    formColumn.addView(deletebutton,deleteParams);

    phoneDeleteConfirm=new LinearLayout(context);
    phoneDeleteConfirm.setOrientation(VERTICAL);
    phoneDeleteConfirm.setPadding((int)(density*16.0f),(int)(density*16.0f),
            (int)(density*16.0f),(int)(density*16.0f));
    phoneDeleteConfirm.setBackgroundResource(R.drawable.add_record_delete_panel);
    phoneDeleteConfirm.setVisibility(GONE);
    TextView deleteTitle=new TextView(context);
    deleteTitle.setText(R.string.record_editor_delete_title);
    deleteTitle.setTextColor(Color.rgb(241,181,177));
    deleteTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP,16.0f);
    deleteTitle.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
    phoneDeleteConfirm.addView(deleteTitle,new LinearLayout.LayoutParams(
            MATCH_PARENT,WRAP_CONTENT));
    phoneDeleteSummary=new TextView(context);
    phoneDeleteSummary.setTextColor(Color.rgb(185,137,134));
    phoneDeleteSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP,13.0f);
    LinearLayout.LayoutParams summaryParams=new LinearLayout.LayoutParams(
            MATCH_PARENT,WRAP_CONTENT);
    summaryParams.topMargin=(int)(density*6.0f);
    phoneDeleteConfirm.addView(phoneDeleteSummary,summaryParams);
    LinearLayout deleteActions=new LinearLayout(context);
    deleteActions.setOrientation(LinearLayout.HORIZONTAL);
    phoneDeleteKeepButton=new Button(context);
    phoneDeleteKeepButton.setText(R.string.record_editor_keep_editing);
    styleSecondaryButton(phoneDeleteKeepButton,density);
    phoneDeleteConfirmButton=new Button(context);
    phoneDeleteConfirmButton.setText(R.string.record_editor_delete_confirm);
    styleDangerButton(phoneDeleteConfirmButton,density);
    deleteActions.addView(phoneDeleteKeepButton,new LinearLayout.LayoutParams(
            0,controlHeight,1.0f));
    LinearLayout.LayoutParams confirmParams=new LinearLayout.LayoutParams(
            0,controlHeight,1.0f);
    confirmParams.setMarginStart(rowGap);
    deleteActions.addView(phoneDeleteConfirmButton,confirmParams);
    LinearLayout.LayoutParams deleteActionsParams=new LinearLayout.LayoutParams(
            MATCH_PARENT,WRAP_CONTENT);
    deleteActionsParams.topMargin=(int)(density*12.0f);
    phoneDeleteConfirm.addView(deleteActions,deleteActionsParams);
    LinearLayout.LayoutParams confirmPanelParams=new LinearLayout.LayoutParams(
            MATCH_PARENT,WRAP_CONTENT);
    confirmPanelParams.topMargin=sectionGap;
    formColumn.addView(phoneDeleteConfirm,confirmPanelParams);

    keyboard=getkeyboard(context);
    keyboard.setVisibility(GONE);
    keyboardEmbedded=true;
    LinearLayout keyColumn=new LinearLayout(context);
    keyColumn.setOrientation(VERTICAL);
    keyColumn.setPadding((int)(density*16.0f),(int)(density*16.0f),
            (int)(density*16.0f),(int)(density*16.0f));
    keyColumn.setBackgroundResource(R.drawable.add_record_section);
    TextView keypadTitle=sectionTitle(context,R.string.record_editor_keypad);
    keyColumn.addView(keypadTitle,new LinearLayout.LayoutParams(
            MATCH_PARENT,WRAP_CONTENT));
    LinearLayout.LayoutParams keyboardParams=new LinearLayout.LayoutParams(
            MATCH_PARENT,WRAP_CONTENT);
    keyboardParams.topMargin=(int)(density*10.0f);
    keyColumn.addView(keyboard,keyboardParams);
    keyColumn.setVisibility(GONE);

    LinearLayout.LayoutParams keypadParams=new LinearLayout.LayoutParams(
            MATCH_PARENT,WRAP_CONTENT);
    keypadParams.topMargin=sectionGap;
    final int keypadPortraitIndex=formColumn.indexOfChild(whatCard)+1;
    formColumn.addView(keyColumn,keypadPortraitIndex,keypadParams);

    LinearLayout body=new LinearLayout(context);
    body.setOrientation(VERTICAL);
    body.addView(formColumn,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));

    LinearLayout stage=new LinearLayout(context);
    stage.setOrientation(VERTICAL);
    stage.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL);
    stage.addView(body,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    ScrollView scroll=new ScrollView(context);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    scroll.setVerticalScrollBarEnabled(false);
    scroll.addView(stage,new ScrollView.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    LinearLayout bottomBar=new LinearLayout(context);
    bottomBar.setOrientation(VERTICAL);
    bottomBar.setPadding(0,(int)(density*10.0f),0,0);
    bottomBar.setBackgroundResource(R.drawable.add_record_action_bar);
    bottomBar.addView(savebutton,new LinearLayout.LayoutParams(
            MATCH_PARENT,controlHeight));

    LinearLayout screen=new LinearLayout(context);
    screen.setOrientation(VERTICAL);
    screen.setBackgroundColor(Color.rgb(11,13,14));
    screen.setPadding(systembarLeft+pagePadding,
            MainActivity.systembarTop+(int)(density*6.0f),
            systembarRight+pagePadding,
            MainActivity.systembarBottom+(int)(density*10.0f));
    screen.addView(headerRow,new LinearLayout.LayoutParams(
            MATCH_PARENT,controlHeight));
    LinearLayout.LayoutParams scrollParams=new LinearLayout.LayoutParams(
            MATCH_PARENT,0,1.0f);
    scrollParams.topMargin=(int)(density*8.0f);
    screen.addView(scroll,scrollParams);
    screen.addView(bottomBar,new LinearLayout.LayoutParams(
            MATCH_PARENT,WRAP_CONTENT));

    phoneAddBody=body;
    phoneAddForm=formColumn;
    phoneAddStage=stage;
    phoneAddKeyColumn=keyColumn;
    phoneAddBottomBar=bottomBar;
    phoneAddScreen=screen;
    phoneAddScroll=scroll;
    phoneAddKeyPortraitIndex=keypadPortraitIndex;
    phoneAddDensity=density;
    screen.addOnLayoutChangeListener((v,left,top,right,bottom,
            oldLeft,oldTop,oldRight,oldBottom) ->
            updatePhoneAddLayout(right-left,bottom-top));
    layout=screen;
    updatePhoneAddLayout(GlucoseCurve.getwidth(),GlucoseCurve.getheight());
         }

        timebutton.setOnClickListener(
                v -> {
                  layout.setVisibility(GONE);
                  gettimeview(context,()-> {
                     layout.setVisibility(VISIBLE);
                     showkeyboard(context);
                  });
                });





        deletebutton.setOnClickListener( v-> {
            if(mealview[0]!=null) {
                removeContentView(mealview[0]);
                mealview[0]=null;
                ((MainActivity)v.getContext()).poponback();
                }
            if(isWearable)
                deletedialog(v,newmealptr);
            else
                showPhoneDeleteConfirmation(v);

            });
    if(phoneDeleteKeepButton!=null)
        phoneDeleteKeepButton.setOnClickListener(v ->
                hidePhoneDeleteConfirmation(true));
    if(phoneDeleteConfirmButton!=null)
        phoneDeleteConfirmButton.setOnClickListener(v ->
                deleteCurrentRecord(v,newmealptr));
    if(isWearable) {

        }
    else  {
        newnumview=layout;
        } 
    newnumview.setBackgroundColor(isWearable?Applic.backgroundcolor:Color.rgb(11,13,14));
  savebutton.setOnClickListener(v -> {
        MainActivity act=(MainActivity)v.getContext();
        GlucoseCurve.reopener();
        if(saveamount(act,timeview,  valueedit,newmealptr[0],lasttime)) {
            if(mealview[0]!=null) {
                removeContentView(mealview[0]);
                mealview[0]=null;
                    act.poponback();
                }
                newmealptr[0]=0;
        //        Natives.closemeal(newmealptr[0]);
             final var nview=newnumview;
             if(nview!=null)
                nview.setVisibility(GONE);
            if(!isWearable)
                hidekeyboard();
            if(smallScreen)
                help.hidekeyboard(act);
                
            ((Applic) act.getApplication()). redraw();
              MainActivity.poponback();

             if(Menus.on) {
                if(deletebutton.getVisibility()==GONE) {
                        Menus.show(context);
                        }
                }
            } 
            //            act.clearonback();
        });

    ViewGroup.LayoutParams overlayParams=new ViewGroup.LayoutParams(
            MATCH_PARENT,MATCH_PARENT);
    if(isWearable)
        context.addMyContentView(newnumview,overlayParams);
    else
        context.addMyContentView(newnumview,overlayParams,false);
        }
    else  {
        numspinadapt.setarray(editorLabels(context));
        newnumview.setVisibility(VISIBLE);
       }
    valueedit.requestFocus();
    editfocus.setedittext(valueedit);

    View.OnClickListener menucall= v -> {

        if(!isWearable) {
            hidekeyboard();
        if(mealview[0]==null) {
            EnableControls(newnumview,false);
//            newnumview.setVisibility(GONE);
            int mptr=newmealptr[0]==0?((currentnum!=0L)?Natives.hitmeal(currentnum):0):newmealptr[0];
            if(mptr==0)
                mptr=Natives.getnewmealptr();
            mealview[0]=tk.glucodata.Meal.menuview(NumberView.this, context,mptr, (carb,mealptr)->{
                float roundt=Natives.getroundto();
                if(roundt>0.0f)
                    valueedit.setText(Float.toString(roundto(carb,roundt)));
                else
                    valueedit.setText(Float.toString(carb));
                newmealptr[0]=mealptr;
            },()->{
                EnableControls(newnumview,true);
                valueedit.requestFocus();

                if(!smallScreen) {
                    showkeyboard(context);
                    editfocus.setedittext(valueedit);
                    }
               else {
                    tk.glucodata.help.showkeyboard(context,valueedit);
                  }

            //    newnumview.setVisibility(VISIBLE);
                mealview[0]=null;

                }    );
            }
    }

    };
    mealbutton.setOnClickListener(menucall);
    lasttime=time;
    Date dat = new Date(time);
   if(isWearable)
      datebutton.setText(DateFormat.getDateInstance(DateFormat.SHORT).format(dat));
   else
       datebutton.setText(DateFormat.getDateInstance(DateFormat.DEFAULT).format(dat));
    timebutton.setText(minhourstr(time));
    if(value< Float.MAX_VALUE)
        valueedit.setText(String.valueOf(value));
    else
        valueedit.setText("");
    spinner.setSelection(type);
    editfocus.setedittext(valueedit);
    source.setText(bron==1?R.string.record_editor_source_local:
            R.string.record_editor_source_synced);
    source.setContentDescription(source.getText());
    source.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
    if(!Natives.staticnum()) {
        messagetext.setVisibility(GONE);
        savebutton.setVisibility(VISIBLE);
        if(phoneAddBottomBar!=null)
            phoneAddBottomBar.setVisibility(VISIBLE);
        }
    else  {
        savebutton.setVisibility(GONE);
        messagetext.setVisibility(VISIBLE);
        if(phoneAddBottomBar!=null)
            phoneAddBottomBar.setVisibility(GONE);
        }
     final Runnable closerRun= () -> {
                if(newmealptr[0]!=0) {
                if(currentnum!=0&&(currentnum!=numio.newhit)) {
                    Natives.hitsetmealptr(currentnum,newmealptr[0]);
                    }
                else
                    Natives.deletemeal(newmealptr[0]);
                newmealptr[0]=0;
                }
            if(mealview[0]!=null) {
                removeContentView(mealview[0]);
                mealview[0]=null;
                }

        if(currentnum!=0) {
            if(currentnum!=numio.newhit) 
                    Natives.freehitptr(currentnum);

            currentnum=0L;
            }
        else {
             if(Menus.on) {
                Menus.show(context);
                }
            }

           GlucoseCurve.reopener();
           final var nview=newnumview;
           if(nview!=null)
               nview.setVisibility(GONE);
           if(!isWearable)  {
               hidekeyboard();
            if(smallScreen)
                help.hidekeyboard(context);
              }
            };
        cancelbutton.setOnClickListener(v -> {
            MainActivity.poponback();
            closerRun.run();
            });

     context.setonback(closerRun);

    if(tmpmealptr>=0) {
        timebutton.setTextColor( Color.YELLOW);
        datebutton.setTextColor( Color.YELLOW);
         newmealptr[0]=tmpmealptr;
        mealview[0]=null;
        {if(doLog) {Log.i(LOG_ID,"onClick");};};
        menucall.onClick(mealbutton);
        }
    else {
        final int fieldTextColor=isWearable?savebutton.getCurrentTextColor()
                :Color.rgb(226,229,227);
        timebutton.setTextColor(fieldTextColor);
        datebutton.setTextColor(fieldTextColor);
    }

    setExcludeTime(time);
    return newnumview;
    }
private String currentRecordSummary(View v) {
    if(currentnum==0L)
        return "";
    MainActivity context=(MainActivity)v.getContext();
    long time=Natives.hittime(currentnum)*1000L;
    float value=Natives.hitvalue(currentnum);
    int type=Natives.hittype(currentnum);
    ArrayList<String> labels=editorLabels(context);
    return DateFormat.getDateTimeInstance(DateFormat.DEFAULT,DateFormat.SHORT)
            .format(time)+"  \u00b7  "+labels.get(type)+"  \u00b7  "+value;
    }

private void showPhoneDeleteConfirmation(View v) {
    if(currentnum==0L) {
        newnumview.setVisibility(GONE);
        hidekeyboard();
        return;
        }
    if(phoneDeleteSummary!=null)
        phoneDeleteSummary.setText(currentRecordSummary(v));
    deletebutton.setVisibility(GONE);
    if(phoneDeleteConfirm!=null) {
        phoneDeleteConfirm.setVisibility(VISIBLE);
        phoneDeleteConfirm.requestFocus();
        phoneDeleteConfirm.announceForAccessibility(
                v.getContext().getString(R.string.record_editor_delete_title));
        }
    }

private void deleteCurrentRecord(View v,int[] mealptr) {
    if(currentnum==0L) {
        newnumview.setVisibility(GONE);
        hidekeyboard();
        return;
        }
    MainActivity context=(MainActivity)v.getContext();
    if(mealptr[0]!=0)
        Natives.deletemeal(mealptr[0]);
    mealptr[0]=0;
    if(currentnum!=0) {
        if(currentnum!=numio.newhit) {
            int index=Natives.gethitindex(currentnum);
            int waslast=numio.getlastnum(index);
            int pos=Natives.hitremove(currentnum);
            int last=numio.getlastnum(index);
            if(!isWearable) {
                AllData alldata=((Applic)((Activity)v.getContext())
                        .getApplication()).numdata;
                alldata.deletelast(index,last,waslast);
                if(pos<last)
                    alldata.changedback(index);
                }
            Natives.freehitptr(currentnum);
            ((Applic)((Activity)v.getContext()).getApplication()).redraw();
            }
        currentnum=0L;
        }
    hidePhoneDeleteConfirmation(false);
    newnumview.setVisibility(GONE);
    hidekeyboard();
    GlucoseCurve.reopener();
    context.poponback();
    }

void deletedialog(View v,int[] mealptr) {
    if(currentnum==0L) {
        newnumview.setVisibility(GONE);
        hidekeyboard();
        return;
        }
    MainActivity context=(MainActivity)v.getContext();
    AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(R.string.deletequestion).setMessage(currentRecordSummary(v)).
           setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        deleteCurrentRecord(v,mealptr);
                    }
                }) .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
            }
        }).show().setCanceledOnTouchOutside(false);
    }

static void setMarginStart(ViewGroup.MarginLayoutParams params,int start) {
        if(MainActivity.rtl) 
            params.rightMargin=start;
        else
            params.leftMargin=start;
    }
static void setMarginEnd(ViewGroup.MarginLayoutParams params,int end) {
        if(MainActivity.rtl) 
            params.leftMargin=end;
        else
            params.rightMargin=end;
    }

private static TextView fieldLabel(Context context,int textResource,View control) {
    TextView label=new TextView(context);
    label.setText(textResource);
    label.setTextColor(Color.rgb(132,139,136));
    label.setTextSize(TypedValue.COMPLEX_UNIT_SP,11.0f);
    label.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
    label.setLetterSpacing(.045f);
    if(control.getId()==View.NO_ID)
        control.setId(View.generateViewId());
    label.setLabelFor(control.getId());
    return label;
    }

private static LinearLayout labelledField(Context context,int textResource,
        View control,int controlHeight) {
    LinearLayout group=new LinearLayout(context);
    group.setOrientation(VERTICAL);
    TextView label=fieldLabel(context,textResource,control);
    group.addView(label,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    LinearLayout.LayoutParams controlParams=new LinearLayout.LayoutParams(
            MATCH_PARENT,controlHeight);
    controlParams.topMargin=(int)(GlucoseCurve.metrics.density*4.0f);
    group.addView(control,controlParams);
    return group;
    }

private static TextView sectionTitle(Context context,int textResource) {
    TextView title=new TextView(context);
    title.setText(textResource);
    title.setTextColor(Color.rgb(196,202,199));
    title.setTextSize(TypedValue.COMPLEX_UNIT_SP,14.0f);
    title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
    return title;
    }

private static LinearLayout sectionCard(Context context,float density) {
    LinearLayout card=new LinearLayout(context);
    card.setOrientation(VERTICAL);
    final int padding=(int)(density*16.0f);
    card.setPadding(padding,padding,padding,padding);
    card.setBackgroundResource(R.drawable.add_record_section);
    return card;
    }

private static void styleTopActionButton(Button button,float density) {
    button.setAllCaps(false);
    button.setTextColor(Color.rgb(155,161,159));
    button.setTextSize(TypedValue.COMPLEX_UNIT_SP,13.0f);
    button.setMinWidth(0);
    button.setMinimumWidth((int)(density*64.0f));
    button.setPaddingRelative((int)(density*12.0f),0,
            (int)(density*12.0f),0);
    if(Build.VERSION.SDK_INT>=21)
        button.setBackgroundTintList(null);
    button.setBackgroundResource(R.drawable.add_record_top_action);
    }

private static void styleField(View view,float density) {
    view.setBackgroundResource(R.drawable.add_record_field);
    view.setPaddingRelative((int)(density*15.0f),0,(int)(density*15.0f),0);
    view.setMinimumHeight((int)(density*56.0f));
    }

private static void styleAmountField(EditText edit,float density) {
    styleField(edit,density);
    edit.setTextColor(Color.rgb(242,244,243));
    edit.setHintTextColor(Color.rgb(113,121,117));
    edit.setTextSize(TypedValue.COMPLEX_UNIT_SP,26.0f);
    edit.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
    edit.setGravity(Gravity.CENTER_VERTICAL);
    edit.setSingleLine(true);
    edit.setBackgroundResource(R.drawable.add_record_field);
    }

private static void styleFieldButton(Button button,float density) {
    styleField(button,density);
    button.setAllCaps(false);
    button.setTextColor(Color.rgb(226,229,227));
    button.setTextSize(TypedValue.COMPLEX_UNIT_SP,15.0f);
    button.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);
    }

private static void styleSecondaryButton(Button button,float density) {
    button.setAllCaps(false);
    button.setTextColor(Color.rgb(196,202,199));
    button.setTextSize(TypedValue.COMPLEX_UNIT_SP,13.0f);
    if(Build.VERSION.SDK_INT>=21)
        button.setBackgroundTintList(null);
    button.setBackgroundResource(R.drawable.add_record_secondary);
    button.setPaddingRelative((int)(density*14.0f),0,(int)(density*14.0f),0);
    }

private static void stylePrimaryButton(Button button,float density) {
    button.setAllCaps(false);
    button.setTextColor(Color.rgb(10,34,20));
    button.setTextSize(TypedValue.COMPLEX_UNIT_SP,14.0f);
    button.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
    if(Build.VERSION.SDK_INT>=21)
        button.setBackgroundTintList(null);
    button.setBackgroundResource(R.drawable.add_record_primary);
    }

private static void styleDangerButton(Button button,float density) {
    button.setAllCaps(false);
    button.setTextColor(Color.rgb(235,142,137));
    button.setTextSize(TypedValue.COMPLEX_UNIT_SP,13.0f);
    if(Build.VERSION.SDK_INT>=21)
        button.setBackgroundTintList(null);
    button.setBackgroundResource(R.drawable.add_record_danger);
    button.setPaddingRelative((int)(density*10.0f),0,(int)(density*10.0f),0);
    }

private void updateEditorMode(boolean editing) {
    if(phoneEditorTitle!=null)
        phoneEditorTitle.setText(editing?R.string.record_editor_edit_title:
                R.string.record_editor_new_title);
    if(phoneEditorSubtitle!=null)
        phoneEditorSubtitle.setText(editing?R.string.record_editor_edit_subtitle:
                R.string.record_editor_new_subtitle);
    hidePhoneDeleteConfirmation(false);
    }

private void hidePhoneDeleteConfirmation(boolean showDeleteAction) {
    if(phoneDeleteConfirm!=null)
        phoneDeleteConfirm.setVisibility(GONE);
    if(showDeleteAction&&deletebutton!=null)
        deletebutton.setVisibility(VISIBLE);
    }

private void nodelete() {
    hidePhoneDeleteConfirmation(false);
    deletebutton.setVisibility(GONE);
    if(isWearable) {
//        Log.i(LOG_ID,"nodelete");
        setMarginStart(getMargins(savebutton),0);

       // getMargins(savebutton).setMarginStart(0); // works only the first time
        }
    }

private void seedelete() {
    hidePhoneDeleteConfirmation(false);
    deletebutton.setVisibility(VISIBLE);
    if(isWearable) {
 //       Log.i(LOG_ID,"seedelete");
        int width=GlucoseCurve.getwidth();
        int hormarg=useclose?(int)(width*0.05f):(int)(width*0.12f);

//        getMargins(deletebutton).setMarginEnd(hormarg);
        setMarginEnd(getMargins(deletebutton),hormarg);
       // getMargins(savebutton).setMarginStart(hormarg);

        setMarginStart(getMargins(savebutton),hormarg);
        }
    }
public void addnumberwithmenu(MainActivity context,int mealptr) {
    if(currentnum!=0L)  {
        if(currentnum!=numio.newhit) 
            Natives.freehitptr(currentnum);
        currentnum=0L;
        }
    var type=Natives.getmealvar();
     addnumberview(context,1,currentTimeMillis(),Float.MAX_VALUE,type,mealptr);
    updateEditorMode(false);
    setmealbutton(type,1, 0,shouldexclude) ;
    nodelete();
    thetime=-1;
    thedate=0L;
    }
public View addnumberview(MainActivity context) {
    if(currentnum!=0L)  {
        if(currentnum!=numio.newhit) 
            Natives.freehitptr(currentnum);
        currentnum=0L;
        }
    View lay=  addnumberview(context,1,currentTimeMillis(),Float.MAX_VALUE,0,-1);
    updateEditorMode(false);
    setmealbutton(0,1, 0,shouldexclude) ;
    
    if(SmallShowKeyboard&&smallScreen) {
        valueedit.requestFocus();
        tk.glucodata.help.showkeyboard(context,valueedit);
      }
    else if(isWearable)
        spinner.performClick();
    nodelete();
    thetime=-1;
    thedate=0L;
    return lay;
    }


public void  addnumberview(MainActivity activity, int bron, int pos) {
     addnumberview(activity, Natives.mkhitptr(numio.numptrs[bron],pos)); 
    }


long thedate=0;
int thetime=-1;
private boolean saveamount(Activity activity,TextView timeview,TextView value,int mealptr,long lasttime) {
    final String strval= value.getText().toString();
    float val=0.0f;
    try {
        val=(strval.length()==0)?0:Float.parseFloat(strval);
        }
    catch(Throwable e) {
        Log.stack(LOG_ID,"parseFloat "+strval,e);
        };

    if(labelsel==Natives.getbloodvar()) { 
        mealptr=excludebox.isChecked()?1:0;
        }
    if(currentnum!=0&&currentnum!=numio.newhit) {
//        long dat=thedate==0L?Natives.hittime(currentnum)*1000L:thedate;
        long dat=thedate==0L?lasttime:thedate;
/*        if(timeview!=null) {
            cal.setTimeInMillis(dat);
            int minutes = thetime;
            if(minutes>=0) {
                cal.set(Calendar.HOUR_OF_DAY, minutes / 60);
                cal.set(Calendar.MINUTE, minutes % 60);
                cal.set(Calendar.SECOND,0);
                }
            dat= cal.getTimeInMillis();
            } */
        Natives.hitchange(currentnum,dat/1000L,val,labelsel,mealptr);
        int index=Natives.gethitindex(currentnum);
        if(!isWearable) {
            tk.glucodata.nums.AllData  alldata=Applic.app.numdata;
            alldata.changedback(index);
            }
        Natives.freehitptr(currentnum);
        }

    else {
        long dat=thedate==0L?lasttime:thedate;
        /* cal.setTimeInMillis(dat);
        if(timeview!=null) {
            int minutes = thetime;
            if(minutes>=0) {
                cal.set(Calendar.HOUR_OF_DAY, minutes / 60);
                cal.set(Calendar.MINUTE, minutes % 60);
                cal.set(Calendar.SECOND,0);
                }
            }
        dat= cal.getTimeInMillis(); */
        final int index=1;
        Natives.saveNum(numio.numptrs[index],dat/1000,val,labelsel,mealptr);
        if(!isWearable) {
           tk.glucodata.nums.AllData  alldata=Applic.app.numdata;
            alldata.changedback(index);
            }
        }

    currentnum=0L;
    return true;
    }
Dater dater=null;

Dater numdater=(year,month,day)-> {
         cal.set(Calendar.YEAR,year);
         cal.set(Calendar.MONTH,month);
         cal.set(Calendar.DAY_OF_MONTH,day);
         long dat= cal.getTimeInMillis();
        thedate=dat;
        setExcludeTime(dat);
        dateview.setText( DateFormat.getDateInstance( DateFormat.DEFAULT) .format(dat)); } ;

Layout getdateview(MainActivity activity) {
    long tim=(thedate==0L)?currentTimeMillis():thedate;
    return getdateviewal(activity,tim,numdater);
    }


public Layout getdateviewal(MainActivity activity, long date, Dater erdate) {
{if(doLog) {Log.i(LOG_ID, "getdateviewal");};};
    dater=erdate;
    if(datepicker==null) {
    {if(doLog) {Log.i(LOG_ID, " new");};};
    datepick =new DatePicker(activity);
    datepick.setCalendarViewShown(false);
        Button cancel=isWearable?new Button(activity):ClinicalUi.button(activity,
                activity.getString(R.string.cancel),ClinicalUi.ButtonRole.SECONDARY);
        if(isWearable) cancel.setText(R.string.cancel);
        cancel.setOnClickListener(vi -> { 
        activity.doonback();
        });
        Button ok=isWearable?new Button(activity):ClinicalUi.button(activity,
                activity.getString(R.string.ok),ClinicalUi.ButtonRole.PRIMARY);
        if(isWearable) ok.setText(R.string.ok);
 //      ok.setBackgroundResource(R.drawable.button_selector2);
        ok.setOnClickListener(vi -> {
        activity.doonback();
        if(keyboard!=null)
            EnableControls(keyboard,true);
        datepicker.setVisibility(GONE);
        if(newnumview!=null) EnableControls(newnumview,true);
        int day=datepick.getDayOfMonth();
        int month=datepick.getMonth();
        int year=datepick.getYear();
        dater.date(year,month,day);

        });
    if(!isWearable) {
        final float density=GlucoseCurve.metrics.density;
        styleSecondaryButton(cancel,density);
        stylePrimaryButton(ok,density);
        cancel.setMinHeight((int)(density*56.0f));
        ok.setMinHeight((int)(density*56.0f));
        }
   ViewGroup.LayoutParams datparams;
    if(isWearable) {
         if(!useclose)
            cancel.setVisibility(GONE);
        datepicker=new Layout(activity,
                (lay, w, h)->{
            return new int[] {w,h};
                },new View[]{cancel},new View[] {datepick},new View[] {ok});
        int laypar= MATCH_PARENT;
       datepicker.setPaddingRelative(0,(int)(GlucoseCurve.metrics.density*5.0),0,(int)(GlucoseCurve.metrics.density*2.0));
       datparams=new ViewGroup.LayoutParams(laypar,laypar);
        }
    else {
        TextView title=ClinicalUi.title(activity,
                activity.getString(R.string.clinical_choose_date));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP,26);
        title.setMinHeight(ClinicalUi.dp(activity,54));
        title.setLayoutParams(new ViewGroup.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
        boolean landscape=GlucoseCurve.getwidth()>GlucoseCurve.getheight();
        if(landscape) {
            LinearLayout actions=new LinearLayout(activity);
            actions.setOrientation(VERTICAL);
            actions.setPaddingRelative(ClinicalUi.dp(activity,12),0,0,0);
            actions.addView(ok,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
            actions.addView(cancel,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
            datepicker=new Layout(activity,(lay,w,h)->new int[]{w,h},
                    new View[]{title},new View[]{datepick,actions});
            }
        else {
            datepicker=new Layout(activity,(lay,w,h)->new int[]{w,h},
                    new View[]{title},new View[]{datepick},new View[]{cancel,ok});
            }
        datepicker.setDistributeExtraSpace(false);
        datparams = new FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT);
        }

    if(isWearable)
        datepicker.setBackgroundColor(Applic.app.backgroundcolor);
    else {
        final int padding=ClinicalUi.dp(activity,20);
        datepicker.setPaddingRelative(MainActivity.systembarLeft+padding,
                MainActivity.systembarTop+ClinicalUi.dp(activity,8),
                MainActivity.systembarRight+padding,
                MainActivity.systembarBottom+ClinicalUi.dp(activity,18));
        datepicker.setBackgroundColor(ClinicalUi.window(activity));
        }
    activity.addMyContentView(datepicker, datparams);
    }
    else {
    {if(doLog) {Log.i(LOG_ID, " old");};};
        datepicker.setVisibility(VISIBLE);
    datepicker.bringToFront();
    }

cal.setTimeInMillis(date);

datepick.updateDate( cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
activity.setonback(()->{ 
    if(keyboard!=null)
        EnableControls(keyboard,true);
    datepicker.setVisibility(GONE);

    if(newnumview!=null) {
        EnableControls(newnumview,true);
        if(!isWearable)
            showkeyboard(activity);
        }
    else {
        if(Menus.on)
                    Menus.show(activity);
        }
        });

if(newnumview!=null)
    EnableControls(newnumview,false);
if(keyboard!=null)
    EnableControls(keyboard,false);
return datepicker;
}

Layout timepicker=null;
TextView timeview=null;
TimePicker pick=null;

ObjIntConsumer<Integer> settime=null;
ObjIntConsumer<Integer>  numsettime=(hour,min)-> {
    thetime= hour*60+min;
    cal.setTimeInMillis(thedate==0?lasttime:thedate);
    int minutes = thetime;
    if(minutes>=0) {
        cal.set(Calendar.HOUR_OF_DAY, minutes / 60);
        cal.set(Calendar.MINUTE, minutes % 60);
        cal.set(Calendar.SECOND,0);
    }
    thedate= cal.getTimeInMillis();
     setExcludeTime(thedate);
    timeview.setText(String.format(Locale.US,"%02d:%02d",hour,min ));
    };
void gettimeview(MainActivity activity,Runnable parent) {
    int id=thetime; 
    int h,m;
    if(id>=0)  {
        h=id/60;
        m=id%60;
        }
    else {
        cal.setTimeInMillis(currentTimeMillis());
         h=cal.get(Calendar.HOUR_OF_DAY);
         m=cal.get(Calendar.MINUTE);
        }
    if(keyboard!=null) {
        keyboard.setVisibility(GONE);
        if(phoneAddKeyColumn!=null)
            phoneAddKeyColumn.setVisibility(GONE);
        }
    gettimepicker(activity,h,m,numsettime,parent);
    }
    @SuppressWarnings("deprecation")
//Layout buttonlay;
public void gettimepicker(MainActivity activity,int hourin, int minin, ObjIntConsumer<Integer> timeset,Runnable onclose) {
final boolean buttonsunder=!isWearable&&GlucoseCurve.getheight()>=GlucoseCurve.getwidth();
   settime=timeset;
    if(timepicker==null) {

    //    Log.i(LOG_ID,"new gettimepicker");
        pick =new TimePicker(activity);
//        pick.setIs24HourView( android.text.format.DateFormat.is24HourFormat(activity));
        Button cancel=isWearable?new Button(activity):ClinicalUi.button(activity,
                activity.getString(R.string.cancel),ClinicalUi.ButtonRole.SECONDARY);
        if(isWearable) cancel.setText(R.string.cancel);
        cancel.setOnClickListener(vi -> { 
        activity.doonback();

        });
        Button ok=isWearable?new Button(activity):ClinicalUi.button(activity,
                activity.getString(R.string.ok),ClinicalUi.ButtonRole.PRIMARY);
        if(isWearable) ok.setText(R.string.ok);
        ok.setOnClickListener(vi -> {
        activity.doonback();
            int hour,min;
            if(Build.VERSION.SDK_INT < 23) {
                 hour=pick.getCurrentHour();
                 min=pick.getCurrentMinute(); }
            else {
                 hour=pick.getHour();
                 min=pick.getMinute();
                }
        settime.accept(hour,min);

        });
    if(!isWearable) {
        final float density=GlucoseCurve.metrics.density;
        styleSecondaryButton(cancel,density);
        stylePrimaryButton(ok,density);
        cancel.setMinHeight((int)(density*56.0f));
        ok.setMinHeight((int)(density*56.0f));
        }
    View[][] views;
     int layparwidth,layparheight;
if(isWearable) {
      if(!useclose) cancel.setVisibility(GONE);
           views=new View[][]{new View[]{cancel},new View[]{pick},new View[]{ok}};
         layparheight=layparwidth=MATCH_PARENT;

         }
else {
       layparheight=MATCH_PARENT;
       layparwidth=MATCH_PARENT;
       TextView title=ClinicalUi.title(activity,
               activity.getString(R.string.clinical_choose_time));
       title.setTextSize(TypedValue.COMPLEX_UNIT_SP,26);
       title.setMinHeight(ClinicalUi.dp(activity,54));
       title.setLayoutParams(new ViewGroup.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
       if(buttonsunder) {
           views=new View[][]{new View[]{title},new View[]{pick},new View[]{cancel,ok}};
           }
       else {
         var buttons=new LinearLayout(activity);
         buttons.setOrientation(VERTICAL);
         buttons.setPaddingRelative(ClinicalUi.dp(activity,12),0,0,0);
         buttons.addView(ok,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
         buttons.addView(cancel,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
         views=new View[][] {new View[]{title},new View[] {pick,buttons}};
        };
       };
//        buttonlay.setBackgroundColor( RED);
//     var laypar=smallScreen?WRAP_CONTENT:MATCH_PARENT;
    pick.setLayoutParams(new ViewGroup.LayoutParams(
            isWearable?layparwidth:(buttonsunder?MATCH_PARENT:WRAP_CONTENT),WRAP_CONTENT));
//    pick.setLayoutParams(new ViewGroup.LayoutParams(WRAP_CONTENT , ViewGroup.LayoutParams.WRAP_CONTENT));
        Layout layout=new Layout(activity,
                (lay, w, h)-> {
                    activity.hideSystemUI();
                    /*
                    int wid = GlucoseCurve.getwidth();
                    if(w>=wid) {
                        lay.setX(0);
                        }
                    else {
                        int x=(wid-w)/2;
                        lay.setX(x);
                        {if(doLog) {Log.i(LOG_ID,"screen width="+wid+" w="+w+" x="+x);};};
                        } 
                    if(isWearable) {
                        int height = GlucoseCurve.getheight();
                        if(height>h) {
                                lay.setY((height-h)/2);
                        }
    
                    }
                    */
                    return new int[]{w, h};
                }, views);


        if(isWearable)
            layout.setBackgroundColor(Applic.backgroundcolor);
        else {
            final int padding=ClinicalUi.dp(activity,20);
            layout.setPaddingRelative(MainActivity.systembarLeft+padding,
                    MainActivity.systembarTop+ClinicalUi.dp(activity,8),
                    MainActivity.systembarRight+padding,
                    MainActivity.systembarBottom+ClinicalUi.dp(activity,18));
            layout.setBackgroundColor(ClinicalUi.window(activity));
            layout.setDistributeExtraSpace(false);
            }
    //    activity.addMyContentView(layout,  new ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
    var params=new FrameLayout.LayoutParams(layparwidth,layparheight,
            isWearable?Gravity.CENTER_HORIZONTAL:Gravity.NO_GRAVITY);

    //l act.addMyContentView(layout, params);
        activity.addMyContentView(layout,  params);
        timepicker=layout;
   if(isWearable)
          layout.setPaddingRelative(0,(int)(GlucoseCurve.metrics.density*5.0),0,(int)(GlucoseCurve.metrics.density*2.0));
    }
    else {
        //Log.i(LOG_ID,"old gettimepicker");
    timepicker.requestLayout();
    timepicker.setVisibility(VISIBLE);
    timepicker.bringToFront();
    }

  //    timepicker.setPaddingRelative(systembarLeft,MainActivity.systembarTop, systembarRight,MainActivity.systembarBottom);
     pick.setIs24HourView(Applic.hour24);
activity.setonback(
        () -> {
            onclose.run();
            activity.hideSystemUI();
            timepicker.setVisibility(GONE);
            if(newnumview!=null)
                EnableControls(newnumview,true);

        }
    );

if(newnumview!=null)
    EnableControls(newnumview,false);
pick.setCurrentHour(hourin);
pick.setCurrentMinute(minin);
}
LabelAdapter<String> numspinadapt;

void setmealbutton(int labelsel,int bron,int mealptr,boolean exclude) {
//       if(doLog) {Log.i(LOG_ID,"bron="+bron+" mealptr="+mealptr);};
        if(!isWearable&&labelsel==Natives.getmealvar() &&(bron==1|| mealptr>0)) {
            mealbutton.setVisibility(VISIBLE);
            source.setVisibility(GONE);
            excludebox.setVisibility(GONE);
            }

    else {
       if(labelsel==Natives.getbloodvar()) {
            mealbutton.setVisibility(GONE);
            source.setVisibility(GONE);
            excludebox.setVisibility(VISIBLE);
            if(shouldexclude)
                excludebox.setChecked(true);
            else
                excludebox.setChecked(exclude);
            }
        else  {
            mealbutton.setVisibility(GONE);
            source.setVisibility(VISIBLE);
            excludebox.setVisibility(GONE);
            }
            }
  
      }
void setmealbutton(int labelsel,long hitptr) {
    boolean here=(hitptr==0L||hitptr==numio.newhit);
    setmealbutton(labelsel, here?1:Natives.gethitindex(hitptr),here?1:Natives.hitmeal(hitptr),here?shouldexclude:Natives.hitexclude(hitptr));
    }

private static ArrayList<String> editorLabels(Context context) {
    ArrayList<String> labels=new ArrayList<>(Natives.getLabels());
    for(int index=0;index<labels.size();index++) {
        String label=labels.get(index);
        if("Carbohydra".equals(label))
            labels.set(index,context.getString(R.string.carbo));
        else if("Fast Insuli".equals(label))
            labels.set(index,context.getString(R.string.rapidinsulin));
        else if("Long Insuli".equals(label))
            labels.set(index,context.getString(R.string.longinsulin));
        }
    return labels;
    }

Spinner getspinner(Activity context) {
if(spinner==null) {
    spinner=getGenSpin(context);
    numspinadapt=new LabelAdapter<String>(context,editorLabels(context),1);
    spinner.setAdapter(numspinadapt);
    spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
        @Override
        public  void onItemSelected (AdapterView<?> parent, View view, int position, long id) {
            if(view instanceof TextView) {
                TextView selected=(TextView)view;
                selected.setSingleLine(true);
                selected.setEllipsize(android.text.TextUtils.TruncateAt.END);
                selected.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);
                }
            labelsel=position;
            setmealbutton(position,currentnum);
            }
        @Override
        public  void onNothingSelected (AdapterView<?> parent) {
            labelsel=-1;

        } });
    }
return spinner;

}

static EditText geteditview(Context context,View.OnFocusChangeListener focus) {
    EditText  under=new EditText(context);// under.setText(str);
    under.setInputType( InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
    under.setMinEms(2);
    under.setOnFocusChangeListener(focus);
    under.setSelectAllOnFocus(true);
    under.setMinHeight(GlucoseCurve.dpToPx(50));
    closekeyboard(under) ;
    return under;
}

static EditText geteditwearos(Context context) {
    var valedit=new EditText(context);
    valedit.setInputType(InputType.TYPE_CLASS_NUMBER |InputType.TYPE_NUMBER_FLAG_DECIMAL);//| InputType.IME_FLAG_NO_FULLSCREEN);
    valedit.setImeOptions(editoptions);
    return valedit;
    }
/*
static View.OnTouchListener ontouchedit= new View.OnTouchListener() {

        @Override
        public boolean onTouch(View v, MotionEvent event) {
    {if(doLog) {Log.v(LOG_ID,"ontouchedit");};};
            v.onTouchEvent(event);
//      EditText ed= (EditText)v; editfocus.setedit(ed.getText());

    MainActivity act=(MainActivity)v.getContext();
            InputMethodManager imm = (InputMethodManager)act.getSystemService(Context.INPUT_METHOD_SERVICE);

            if (imm != null) {
                imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
            }
        
        act.hideSystemUI();

            return true;
        }
    };
*/
    LinearLayout keyboard;
    private LinearLayout phoneAddBody;
    private LinearLayout phoneAddForm;
    private LinearLayout phoneAddStage;
    private LinearLayout phoneAddKeyColumn;
    private LinearLayout phoneAddBottomBar;
    private LinearLayout phoneAddScreen;
    private ScrollView phoneAddScroll;
    private int phoneAddKeyPortraitIndex=-1;
    private float phoneAddDensity;
    private boolean keyboardEmbedded=false;

private void updatePhoneAddLayout(int width,int height) {
    if(isWearable||phoneAddBody==null||phoneAddForm==null||keyboard==null)
        return;
    final float density=phoneAddDensity>0.0f?phoneAddDensity:GlucoseCurve.metrics.density;
    final int horizontalInsets=systembarLeft+systembarRight+(int)(density*36.0f);
    final int usableWidth=Math.max((int)(density*280.0f),width-horizontalInsets);
    final boolean twoPane=useTwoPaneEditor(width,height,density);
    final int gap=(int)(density*(twoPane?20.0f:16.0f));
    if(phoneAddScreen!=null) {
        final int sidePadding=(int)(density*(twoPane?24.0f:18.0f));
        phoneAddScreen.setPadding(systembarLeft+sidePadding,
                MainActivity.systembarTop+(int)(density*6.0f),
                systembarRight+sidePadding,
                MainActivity.systembarBottom+(int)(density*10.0f));
        }
    LinearLayout.LayoutParams bodyParams=(LinearLayout.LayoutParams)
            phoneAddBody.getLayoutParams();
    bodyParams.width=Math.min(usableWidth,(int)(density*(twoPane?900.0f:620.0f)));
    bodyParams.height=WRAP_CONTENT;
    phoneAddBody.setLayoutParams(bodyParams);
    phoneAddBody.setOrientation(twoPane?LinearLayout.HORIZONTAL:VERTICAL);
    phoneAddForm.setLayoutParams(new LinearLayout.LayoutParams(
            twoPane?0:MATCH_PARENT,WRAP_CONTENT,twoPane?1.0f:0.0f));
    final ViewGroup desiredKeypadParent=twoPane?phoneAddBody:phoneAddForm;
    if(phoneAddKeyColumn.getParent()!=desiredKeypadParent) {
        final ViewGroup oldParent=(ViewGroup)phoneAddKeyColumn.getParent();
        if(oldParent!=null)
            oldParent.removeView(phoneAddKeyColumn);
        if(twoPane)
            phoneAddBody.addView(phoneAddKeyColumn);
        else
            phoneAddForm.addView(phoneAddKeyColumn,
                    Math.min(phoneAddKeyPortraitIndex,phoneAddForm.getChildCount()));
        }
    LinearLayout.LayoutParams keypadParams=new LinearLayout.LayoutParams(
            twoPane?(int)(density*300.0f):MATCH_PARENT,WRAP_CONTENT);
    if(twoPane)
        keypadParams.setMarginStart(gap);
    else
        keypadParams.topMargin=gap;
    phoneAddKeyColumn.setLayoutParams(keypadParams);
    keyboard.setLayoutParams(new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    if(phoneAddStage!=null)
        phoneAddStage.requestLayout();
    phoneAddBody.requestLayout();
    }

static boolean useTwoPaneEditor(int width,int height,float density) {
    if(density<=0.0f)
        return false;
    final float widthDp=width/density;
    final float heightDp=height/density;
    return widthDp>=720.0f&&widthDp>heightDp*1.08f;
    }

static class numlisten implements View.OnClickListener {


    @Override
    public void onClick(View v) {
    final Editable edit= editfocus.getedit();
    if(edit!=null) {
        try {
            int start= Selection.getSelectionStart(edit);
            int end=Selection.getSelectionEnd(edit);
            Button but=(Button)v;
            edit.replace(start, end, but.getText());
            Selection.setSelection(edit, start + 1);
            } catch(Throwable e) {
                Log.stack(LOG_ID,e);
                }
    }

    }
}
boolean noroom=false;
LinearLayout getkeyboard(Context context) {
    final float density=GlucoseCurve.metrics.density;
    final int rowHeight=(int)(density*48.0f);
    final int gap=(int)(density*6.0f);
    numlisten click=new numlisten();
    LinearLayout layout=new LinearLayout(context);
    layout.setOrientation(VERTICAL);
    layout.setContentDescription(context.getString(R.string.add_record_keypad));
    final String[][] keys={{"7","8","9"},{"4","5","6"},{"1","2","3"}};
    for(String[] keyRow:keys) {
        LinearLayout row=new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for(int i=0;i<keyRow.length;i++) {
            Button button=makeKey(context,keyRow[i]);
            button.setContentDescription(keyRow[i]);
            button.setOnClickListener(click);
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(
                    0,rowHeight,1.0f);
            if(i>0)
                params.setMarginStart(gap);
            row.addView(button,params);
            }
        LinearLayout.LayoutParams rowParams=new LinearLayout.LayoutParams(
                MATCH_PARENT,rowHeight);
        if(layout.getChildCount()>0)
            rowParams.topMargin=gap;
        layout.addView(row,rowParams);
        }
    LinearLayout bottomRow=new LinearLayout(context);
    bottomRow.setOrientation(LinearLayout.HORIZONTAL);
    Button zero=makeKey(context,"0");
    zero.setContentDescription("0");
    zero.setOnClickListener(click);
    bottomRow.addView(zero,new LinearLayout.LayoutParams(0,rowHeight,1.0f));
    Button backspace=makeKey(context,Build.VERSION.SDK_INT>=22?"\u232B":"\u2190");
    backspace.setContentDescription(context.getString(R.string.add_record_backspace));
    backspace.setOnClickListener(v->{
        final Editable edit=editfocus.getedit();
        if(edit==null)
            return;
        int start=Selection.getSelectionStart(edit);
        int end=Selection.getSelectionEnd(edit);
        if(start<0||end<0)
            return;
        if(end>start)
            edit.replace(start,end,"");
        else if(start>0)
            edit.replace(--start,end,"");
        Selection.setSelection(edit,start);
        });
    LinearLayout.LayoutParams backspaceParams=new LinearLayout.LayoutParams(
            0,rowHeight,1.0f);
    backspaceParams.setMarginStart(gap);
    bottomRow.addView(backspace,backspaceParams);
    Button decimal=makeKey(context,".");
    decimal.setContentDescription(context.getString(R.string.add_record_decimal));
    decimal.setOnClickListener(click);
    LinearLayout.LayoutParams decimalParams=new LinearLayout.LayoutParams(
            0,rowHeight,1.0f);
    decimalParams.setMarginStart(gap);
    bottomRow.addView(decimal,decimalParams);
    LinearLayout.LayoutParams bottomParams=new LinearLayout.LayoutParams(
            MATCH_PARENT,rowHeight);
    bottomParams.topMargin=gap;
    layout.addView(bottomRow,bottomParams);
    return layout;
    }

private static Button makeKey(Context context,String text) {
    Button button=new Button(context);
    button.setText(text);
    button.setAllCaps(false);
    button.setTextColor(Color.rgb(226,229,227));
    button.setTextSize(TypedValue.COMPLEX_UNIT_SP,18.0f);
    button.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
    button.setGravity(Gravity.CENTER);
    button.setMinWidth(0);
    button.setMinimumWidth(0);
    button.setPadding(0,0,0,0);
    if(Build.VERSION.SDK_INT>=21)
        button.setBackgroundTintList(null);
    button.setBackgroundResource(R.drawable.add_record_key);
    return button;
    }

Layout getkeyboardLegacy(Context context) {

   numlisten click=new numlisten();
//    Layout layout=new Layout(context,row0,row1,row2,row3);
    View [][] views=new View[4][];
    for(int i=2,num=1;i>=0;i--) {
        views[i]=new View[3];
        for(int j=0;j<3;j++) {
            Button but=new Button(context);
            views[i][j]=but;
            but.setText(String.valueOf(num++));
            but.setOnClickListener(click);
            }
        }
    View[] tmp=views[3]=new View[3];
    Button but= new Button(context);
    but.setOnClickListener(click);
    tmp[0]=but;
    but.setText("0");
    tmp[1]=but=new Button(context);
//    but.setText(Build.VERSION.SDK_INT>=22?"⌫":"Del");
    but.setText(Build.VERSION.SDK_INT>=22?"\u232B":"\u2190");
//    but.setText(Build.VERSION.SDK_INT>=22?"\u232B":"\u21e6");
//    but.setText(Build.VERSION.SDK_INT>=22?"\u232B":"\u27f5");

    but.setContentDescription("Backspace");
    but.setOnClickListener(v->{
        int start= Selection.getSelectionStart(editfocus.getedit());
        int end=Selection.getSelectionEnd(editfocus.getedit());
        if(end>start) {
            editfocus.getedit().replace(start, end, "");
            }
        else {
            if(start>0)
                editfocus.getedit().replace(--start, end, "");
            }
        Selection.setSelection(editfocus.getedit(), start);
    } );
    tmp[2]=but=new Button(context);
    but.setText(".");
    but.setContentDescription("Decimal point");
    but.setOnClickListener(click);
    Layout layout=new Layout(context, (lay, w, h)->{
            int hei=GlucoseCurve.getheight();
            int wid=GlucoseCurve.getwidth();
            if(wid>hei) {
              lay.setY((int)((hei-h)*.65f));
              int mostright=wid-w-systembarRight;
                if(noroom)
                    lay.setX(mostright);
                else {
                    int half= (wid-systembarRight)/2;
                    int bij=(half-w)/4;
                 int xpos=half+bij;
                 if(xpos>mostright)
                    xpos=mostright;
                 lay.setX(xpos);

                }

        //        lay.setX(wid-w);
                }
            else {
                int half=hei/2;
                int bij=(half-h)/4;
                lay.setY(half+bij);
                lay.setX((wid-w)/2); 

            }

                    return new int[] {w,h};
        }, views) ;
        
    layout.setBackgroundColor( Applic.backgroundcolor);
    layout.post(layout::requestLayout);
    return layout;
    }

public    void showkeyboard(MainActivity context) {
if(!isWearable) {
    if(keyboard==null) {
    keyboard=getkeyboard(context);
    keyboardEmbedded=false;
    context.addMyContentView(keyboard, new ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
    }
    else {
        keyboard.setVisibility(VISIBLE);
        if(phoneAddKeyColumn!=null)
            phoneAddKeyColumn.setVisibility(VISIBLE);
        if(!keyboardEmbedded)
            keyboard.bringToFront();
    }
    updatePhoneAddLayout(GlucoseCurve.getwidth(),GlucoseCurve.getheight());
    revealPhoneKeypad();
    }
    }

private void revealPhoneKeypad() {
    if(phoneAddScroll==null||phoneAddKeyColumn==null
            ||phoneAddKeyColumn.getVisibility()!=VISIBLE)
        return;
    phoneAddKeyColumn.post(() -> {
        if(phoneAddKeyColumn==null||phoneAddKeyColumn.getVisibility()!=VISIBLE)
            return;
        Rect keypadBounds=new Rect(0,0,phoneAddKeyColumn.getWidth(),
                phoneAddKeyColumn.getHeight());
        phoneAddKeyColumn.requestRectangleOnScreen(keypadBounds,true);
        });
    }
public    void hidekeyboard() {
if(!isWearable) {
    if(keyboard!=null) {
        keyboard.setVisibility(GONE);
        if(phoneAddKeyColumn!=null)
            phoneAddKeyColumn.setVisibility(GONE);
        }
        }
    }
    /*
private static void setMode(TimePicker timepicker,int mode) {
        try {
        Field mModeField = timepicker.getClass().getDeclaredField("mMode");
//        Field mModeField = timepicker.getClass().getField("mMode");
        mModeField.setAccessible(true);
          Field modifiersField = Field.class.getDeclaredField("modifiers");
          modifiersField.setAccessible(true);
          modifiersField.setInt(mModeField, mModeField.getModifiers() & ~Modifier.FINAL);
        mModeField.setInt(timepicker, mode);
        } catch (Throwable e) {
        Log.stack(LOG_ID,e);
        }
     } */

public static void avoidSpinnerDropdownFocus(Spinner spinner) {
    try {
        Field listPopupField = Spinner.class.getDeclaredField("mPopup");
        listPopupField.setAccessible(true);
        Object listPopup = listPopupField.get(spinner);
        if (listPopup instanceof ListPopupWindow) {
    /*        {if(doLog) {Log.i("SPINNER","listpopupwin="+ ((ListPopupWindow) listPopup).getAnimationStyle());};};
            ((ListPopupWindow) listPopup).setAnimationStyle(0);*/
            Field popupField = ListPopupWindow.class.getDeclaredField("mPopup");
            popupField.setAccessible(true);
            Object popup = popupField.get((ListPopupWindow) listPopup);
            if (popup instanceof PopupWindow) { {
          PopupWindow popupwin=(PopupWindow) popup;
                  popupwin.setFocusable(false);
//          {if(doLog) {Log.i("SPINNER","popanim="+popupwin.getAnimationStyle());};};
        }
            }
        }
    } catch (Throwable e) {
        Log.stack(LOG_ID,"avoidSpinnerDropdownFocus",e);
    }
}

//https://www.programmersought.com/article/75522638732/
static public void closekeyboard(EditText view) {
        ((Activity)view.getContext()).getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        try {
            Class<EditText> cls = EditText.class;
            Method setSoftInputShownOnFocus;
            setSoftInputShownOnFocus = cls.getMethod("setShowSoftInputOnFocus", boolean.class);
            setSoftInputShownOnFocus.setAccessible(true);
            setSoftInputShownOnFocus.invoke(view, false);
        } catch(Throwable e) {
            Log.stack(LOG_ID,e);
        }
    }
}
