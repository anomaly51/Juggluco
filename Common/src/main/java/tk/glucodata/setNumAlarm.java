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
import android.app.Activity;
import androidx.appcompat.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import static android.view.View.GONE;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static android.widget.LinearLayout.VERTICAL;
import static android.widget.Spinner.MODE_DIALOG;
import static android.widget.Spinner.MODE_DROPDOWN;
import static tk.glucodata.Applic.isWearable;
import static tk.glucodata.Applic.usedlocale;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.Natives.getInvertColors;
import static tk.glucodata.Natives.getNumAlarm;
import static tk.glucodata.NumberView.avoidSpinnerDropdownFocus;
import static tk.glucodata.RingTones.EnableControls;
import static tk.glucodata.Specific.useclose;
import static tk.glucodata.help.helplight;
import static tk.glucodata.help.hidekeyboard;
import static tk.glucodata.settings.Settings.editoptions;
import static tk.glucodata.settings.Settings.float2string;
import static tk.glucodata.settings.Settings.getGenSpin;
import static tk.glucodata.settings.Settings.removeContentView;
import static tk.glucodata.util.getbutton;

public class setNumAlarm {
    Layout genlayout=null;
    private ViewGroup phoneScreen;
    private RecyclerView phoneRecycler;
    private View phoneEmptyState;
    private TextView phoneReminderCount;
    private TextView phoneFormTitle;
    private TextView phoneFormError;
NumAlarmAdapter numadapt;
private final static String LOG_ID="setNumAlarm";
    //static final private String LOG_ID="setNumAlarm";
public static boolean issaved;
/*class ScrollListener extends GestureDetector.SimpleOnGestureListener {
@Override
   public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
      {if(doLog) {Log.i(LOG_ID,"onScroll dX="+distanceX+" dY="+distanceY);};};
      return false;
      }
@Override
      public boolean onFling (MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
      {if(doLog) {Log.i(LOG_ID,"onFling volX="+velocityX+"volY="+velocityY);};};
      return false;
      }
};*/
@SuppressLint("ClickableViewAccessibility")
public void mkviews(MainActivity act, View set) {
 issaved=false;
 if(!isWearable) {
     mkPhoneViews(act,set);
     return;
     }
 {if(doLog) {Log.i(LOG_ID,"mkviews");};};
set.setVisibility(GONE);
if(genlayout==null) {
    Button ok=getbutton(act,R.string.closename);
    Button newone=getbutton(act,R.string.newname);
        Button help=new Button(act);
        help.setText(R.string.helpname);
        Button ring=getbutton(act,isWearable?R.string.ringshort:R.string.ringtonename);
//    recycle.setLayoutParams(new ViewGroup.LayoutParams(  MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    View[][] views;
    if(isWearable) {
      var list=getbutton(act,R.string.list);
       views=new View[][]{new View[]{list},new View[]{ring,newone},new View[]{ok}};
      genlayout= new Layout(act, (l, w, h) -> { int[] ret={w,h}; return ret; },views);
      numadapt = new NumAlarmAdapter(genlayout); //USE recycle.setAdapter(numadapt);
      list.setOnClickListener(v->{
            var listclose=getbutton(act,R.string.closename);
    	    var recycle = new RecyclerView(act);
            LinearLayoutManager lin = new LinearLayoutManager(act);
            recycle.setLayoutManager(lin);
            var height=GlucoseCurve.getheight();
            recycle.setLayoutParams(new ViewGroup.LayoutParams(  WRAP_CONTENT,height));
            recycle.setPadding(0,0,0,(int)(tk.glucodata.GlucoseCurve.metrics.density*10.0f)); 
          if(!useclose) {
              recycle.setPadding(0,(int)(tk.glucodata.GlucoseCurve.metrics.density*10.0f),0,(int)(tk.glucodata.GlucoseCurve.metrics.density*10.0f));
             listclose.setVisibility(GONE);
                }
         else  {
                  recycle.setPadding(0,0,0,(int)(tk.glucodata.GlucoseCurve.metrics.density*10.0f)); 
              }
          var listlay= new Layout(act, (l, w, h) -> { int[] ret={w,h}; return ret; },new  View[]{listclose},new View[]{recycle});
           listlay.setPadding(0,(int)(tk.glucodata.GlucoseCurve.metrics.density*1.0f),0,0);
           act.addMyContentView(listlay, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
           listlay.setBackgroundColor(Applic.backgroundcolor);
            act.setonback( () -> {
    		         removeContentView(listlay);
                  });

             listclose.setOnClickListener(v3->{ MainActivity.doonback();});
               recycle.setAdapter(numadapt);
               });

       }
    else {
    	RecyclerView recycle = new RecyclerView(act);
    	    recycle.setLayoutParams(new ViewGroup.LayoutParams(  MATCH_PARENT, WRAP_CONTENT));
         recycle.setPaddingRelative((int)(tk.glucodata.GlucoseCurve.metrics.density*15.0f),0,0,0);
            LinearLayoutManager lin = new LinearLayoutManager(act);
            recycle.setLayoutManager(lin);
     views=new View[][]{new View[]{recycle},new View[]{ring,help,newone,ok}};
    genlayout= new Layout(act, (l, w, h) -> {
    /*
    	if(!isWearable) {
    		var height=GlucoseCurve.getheight();
    		if(height>h)
    			l.setY(height*.9f-h);
    		var width=GlucoseCurve.getwidth();
    		if(width>w)
    			l.setX((width-w)/2);
    		}
            */
    	int[] ret={w,h};
    	return ret;
    	},views);
         numadapt = new NumAlarmAdapter(genlayout); //USE recycle.setAdapter(numadapt);
         recycle.setAdapter(numadapt);
    }
    act.lightBars(!getInvertColors( ));
    ring.setOnClickListener(v->{
    	new tk.glucodata.RingTones(3).mkviews(act,null,genlayout);
    	});
    ok.setOnClickListener(v->{
    	act.doonback();
    	});
    help.setOnClickListener(v->{
    	helplight(R.string.reminders,act);	
    	});
    newone.setOnClickListener(v->{
    	mkitemlayout(act,genlayout);
    	emptyitemlayout();
    	});
    ViewGroup.LayoutParams layparm;
    if(isWearable) {
         final int pad=(int)(tk.glucodata.GlucoseCurve.metrics.density*2.0f);
         genlayout.setPadding(pad,pad,pad,pad);
    	layparm = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
/*        var  gestureListener= new ScrollListener();
        var mGestureDetector = new GestureDetector(act, gestureListener);
        genlayout.setOnTouchListener((v,e) -> mGestureDetector.onTouchEvent(e)); */

    	}
    else {
    	layparm=  new FrameLayout.LayoutParams( WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM| Gravity.CENTER_HORIZONTAL);
        var height=GlucoseCurve.getheight();
        ((FrameLayout.LayoutParams) layparm).bottomMargin=(int)(height*.1f);
    	}

        act.addMyContentView(genlayout,layparm);

        genlayout.setBackgroundColor(Applic.backgroundcolor);
    }
else {
    genlayout.setVisibility(VISIBLE); 
    genlayout.bringToFront();
    
    }
act.setonback( () -> {
    	set.setVisibility(VISIBLE);
    	if(itemlayout!=null) {
    		removeContentView(itemlayout);
    		}
    	removeContentView(genlayout);
    	NumAlarm.handlealarm(act.getApplication());
       act.themeLightBars();
    	});
}

private void mkPhoneViews(MainActivity act,View set) {
    set.setVisibility(GONE);
    if(phoneScreen==null) {
        LinearLayout content=new LinearLayout(act);
        content.setOrientation(VERTICAL);
        content.setBackgroundColor(ClinicalUi.window(act));
        content.setPadding(ClinicalUi.dp(act,20),MainActivity.systembarTop+ClinicalUi.dp(act,8),
                ClinicalUi.dp(act,20),
                MainActivity.systembarBottom+ClinicalUi.dp(act,24));

        Button close=ClinicalUi.button(act,act.getString(R.string.closename),
                ClinicalUi.ButtonRole.SECONDARY);
        close.setContentDescription(act.getString(R.string.closename));
        content.addView(ClinicalUi.header(act,
                act.getString(R.string.reminder_modern_title),close));

        TextView intro=ClinicalUi.body(act,
                act.getString(R.string.reminder_modern_subtitle));
        intro.setPadding(ClinicalUi.dp(act,4),0,ClinicalUi.dp(act,4),
                ClinicalUi.dp(act,8));
        content.addView(intro);

        LinearLayout sectionHeader=new LinearLayout(act);
        sectionHeader.setOrientation(LinearLayout.HORIZONTAL);
        sectionHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView section=ClinicalUi.sectionLabel(act,
                act.getString(R.string.reminder_modern_scheduled));
        sectionHeader.addView(section,new LinearLayout.LayoutParams(
                0,WRAP_CONTENT,1f));
        phoneReminderCount=ClinicalUi.body(act,"");
        phoneReminderCount.setTextSize(TypedValue.COMPLEX_UNIT_SP,13);
        phoneReminderCount.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        sectionHeader.addView(phoneReminderCount,new LinearLayout.LayoutParams(
                WRAP_CONTENT,MATCH_PARENT));
        content.addView(sectionHeader);

        FrameLayout listHost=new FrameLayout(act);
        listHost.setMinimumHeight(ClinicalUi.dp(act,420));
        LinearLayout.LayoutParams listHostParams=new LinearLayout.LayoutParams(
                MATCH_PARENT,WRAP_CONTENT);
        listHostParams.bottomMargin=ClinicalUi.dp(act,14);
        content.addView(listHost,listHostParams);

        phoneRecycler=new RecyclerView(act);
        phoneRecycler.setLayoutManager(new LinearLayoutManager(act));
        phoneRecycler.setNestedScrollingEnabled(false);
        phoneRecycler.setClipToPadding(false);
        phoneRecycler.setOverScrollMode(View.OVER_SCROLL_NEVER);
        phoneRecycler.setPadding(0,ClinicalUi.dp(act,4),0,ClinicalUi.dp(act,10));
        listHost.addView(phoneRecycler,new FrameLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));

        phoneEmptyState=makePhoneEmptyState(act);
        FrameLayout.LayoutParams emptyParams=new FrameLayout.LayoutParams(
                MATCH_PARENT,WRAP_CONTENT,Gravity.CENTER);
        listHost.addView(phoneEmptyState,emptyParams);

        Button add=ClinicalUi.button(act,
                act.getString(R.string.reminder_modern_add),ClinicalUi.ButtonRole.PRIMARY);
        add.setContentDescription(act.getString(R.string.reminder_modern_add));
        content.addView(add,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));

        Button sound=ClinicalUi.button(act,
                act.getString(R.string.reminder_modern_sound),ClinicalUi.ButtonRole.SECONDARY);
        LinearLayout.LayoutParams secondaryParams=new LinearLayout.LayoutParams(
                MATCH_PARENT,WRAP_CONTENT);
        secondaryParams.topMargin=ClinicalUi.dp(act,10);
        content.addView(sound,secondaryParams);

        Button helpButton=ClinicalUi.button(act,
                act.getString(R.string.helpname),ClinicalUi.ButtonRole.SECONDARY);
        LinearLayout.LayoutParams helpParams=new LinearLayout.LayoutParams(
                MATCH_PARENT,WRAP_CONTENT);
        helpParams.topMargin=ClinicalUi.dp(act,10);
        content.addView(helpButton,helpParams);

        ScrollView screen=ClinicalUi.scrollScreen(act,content);
        phoneScreen=screen;
        numadapt=new NumAlarmAdapter(phoneScreen);
        phoneRecycler.setAdapter(numadapt);
        close.setOnClickListener(v->act.doonback());
        add.setOnClickListener(v->{
            mkitemlayout(act,phoneScreen);
            emptyitemlayout();
            });
        sound.setOnClickListener(v->new RingTones(3).mkviews(act,null,phoneScreen));
        helpButton.setOnClickListener(v->helplight(R.string.reminders,act));
        }
    if(phoneScreen.getParent()==null)
        act.addMyContentView(phoneScreen,new ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT));
    phoneScreen.setVisibility(VISIBLE);
    phoneScreen.bringToFront();
    updatePhoneListState();
    act.lightBars(false);
    act.setonback(()->{
        set.setVisibility(VISIBLE);
        if(itemlayout!=null&&itemlayout.getParent()!=null)
            removeContentView(itemlayout);
        removeContentView(phoneScreen);
        NumAlarm.handlealarm(act.getApplication());
        act.themeLightBars();
        });
    }

private View makePhoneEmptyState(MainActivity act) {
    LinearLayout empty=new LinearLayout(act);
    empty.setOrientation(VERTICAL);
    empty.setGravity(Gravity.CENTER);
    empty.setPadding(ClinicalUi.dp(act,24),ClinicalUi.dp(act,30),
            ClinicalUi.dp(act,24),ClinicalUi.dp(act,30));
    empty.setBackground(ClinicalUi.surface(act,false,false));
    TextView title=new TextView(act);
    title.setText(R.string.reminder_modern_empty_title);
    title.setTextColor(ClinicalUi.primaryText(act));
    title.setTextSize(TypedValue.COMPLEX_UNIT_SP,20);
    title.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));
    title.setGravity(Gravity.CENTER);
    empty.addView(title);
    TextView body=ClinicalUi.body(act,act.getString(R.string.reminder_modern_empty_body));
    body.setGravity(Gravity.CENTER);
    body.setPadding(0,ClinicalUi.dp(act,8),0,0);
    empty.addView(body);
    return empty;
    }

private void updatePhoneListState() {
    if(phoneRecycler==null||phoneEmptyState==null)
        return;
    int count=Natives.getNumAlarmCount();
    phoneRecycler.setVisibility(count==0?GONE:VISIBLE);
    phoneEmptyState.setVisibility(count==0?VISIBLE:GONE);
    if(phoneReminderCount!=null)
        phoneReminderCount.setText(phoneRecycler.getContext().getString(
                R.string.reminder_modern_count,count));
    }

static final int REMINDER_VALID=0;
static final int REMINDER_INVALID_LABEL=1;
static final int REMINDER_EMPTY_VALUE=2;
static final int REMINDER_INVALID_VALUE=3;
static final int REMINDER_SAME_TIME=4;

static int validateReminderInput(String rawValue,int labelIndex,int start,int end) {
    if(labelIndex<0)
        return REMINDER_INVALID_LABEL;
    if(rawValue==null||rawValue.trim().isEmpty())
        return REMINDER_EMPTY_VALUE;
    try {
        float parsed=parseReminderValue(rawValue);
        if(!Float.isFinite(parsed))
            return REMINDER_INVALID_VALUE;
        }
    catch(NumberFormatException ex) {
        return REMINDER_INVALID_VALUE;
        }
    if(start<0||start>=24*60||end<0||end>=24*60||start==end)
        return REMINDER_SAME_TIME;
    return REMINDER_VALID;
    }

static float parseReminderValue(String rawValue) {
    return Float.parseFloat(rawValue.trim().replace(',','.'));
    }

static String formatTimeRange(int start,int end) {
    return String.format(Locale.US,"%02d:%02d – %02d:%02d",
            start/60,start%60,end/60,end%60);
    }

private CharSequence phoneReminderError(MainActivity act,int validation) {
    switch(validation) {
        case REMINDER_INVALID_LABEL:
            return act.getString(R.string.reminder_modern_error_label);
        case REMINDER_EMPTY_VALUE:
            return act.getString(R.string.reminder_modern_error_empty_value);
        case REMINDER_INVALID_VALUE:
            return act.getString(R.string.reminder_modern_error_value);
        case REMINDER_SAME_TIME:
            return act.getString(R.string.reminder_modern_error_time);
        default:
            return "";
        }
    }

private void showPhoneFormError(MainActivity act,int validation) {
    if(phoneFormError==null)
        return;
    if(validation==REMINDER_VALID) {
        phoneFormError.setText("");
        phoneFormError.setVisibility(GONE);
        }
    else {
        phoneFormError.setText(phoneReminderError(act,validation));
        phoneFormError.setVisibility(VISIBLE);
        phoneFormError.announceForAccessibility(phoneFormError.getText());
        }
    }


    int alarmpos=-1;
public class NumAlarmHolder extends RecyclerView.ViewHolder {

    public NumAlarmHolder(View view,View ok) {
       super(view);
       view.setOnClickListener(v -> {
            int pos=getAbsoluteAdapterPosition();
            mkitemlayout((MainActivity)v.getContext(),ok);
            fillitemlayout(pos) ;
            alarmpos=pos;
            });

    }

}

private static final class ReminderCardView extends LinearLayout {
    private final TextView labelView;
    private final TextView valueView;
    private final TextView timeView;

    ReminderCardView(android.content.Context context) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.START);
        setPadding(ClinicalUi.dp(context,18),ClinicalUi.dp(context,15),
                ClinicalUi.dp(context,18),ClinicalUi.dp(context,15));
        setMinimumHeight(ClinicalUi.dp(context,96));
        setBackground(ClinicalUi.surface(context,true,true));
        setFocusable(true);
        setClickable(true);

        labelView=new TextView(context);
        labelView.setTextColor(ClinicalUi.primaryText(context));
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP,18);
        labelView.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));
        labelView.setGravity(Gravity.START);
        addView(labelView,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));

        LinearLayout details=new LinearLayout(context);
        details.setOrientation(LinearLayout.HORIZONTAL);
        details.setGravity(Gravity.CENTER_VERTICAL);
        details.setPadding(0,ClinicalUi.dp(context,9),0,0);
        valueView=ClinicalUi.body(context,"");
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP,14);
        details.addView(valueView,new LinearLayout.LayoutParams(0,WRAP_CONTENT,1f));
        timeView=new TextView(context);
        timeView.setTextColor(ClinicalUi.accent(context));
        timeView.setTextSize(TypedValue.COMPLEX_UNIT_SP,14);
        timeView.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        timeView.setGravity(Gravity.END);
        details.addView(timeView,new LinearLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT));
        addView(details,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
        }

    void bind(String label,String value,int start,int end) {
        labelView.setText(label);
        valueView.setText(getContext().getString(R.string.reminder_modern_value_summary,value));
        timeView.setText(formatTimeRange(start,end));
        setContentDescription(getContext().getString(
                R.string.reminder_modern_card_description,label,value,formatTimeRange(start,end)));
        }
    }

public class NumAlarmAdapter extends RecyclerView.Adapter<NumAlarmHolder> {
   final private ArrayList<String> labels;
    final private View ok;
    NumAlarmAdapter(View ok) {
        this.ok=ok;
    labels=Natives.getLabels();
    }
    @NonNull
    @Override
    public NumAlarmHolder onCreateViewHolder(ViewGroup parent, int viewType) {
          if(!isWearable) {
              ReminderCardView card=new ReminderCardView(parent.getContext());
              RecyclerView.LayoutParams params=new RecyclerView.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
              params.setMargins(0,0,0,ClinicalUi.dp(parent.getContext(),10));
              card.setLayoutParams(params);
              return new NumAlarmHolder(card,ok);
              }
          var view=new TextView( parent.getContext());
          view.setTransformationMethod(null);
          view.setTextSize(TypedValue.COMPLEX_UNIT_PX, Applic.largefontsize);
          view.setLayoutParams(new ViewGroup.LayoutParams(  MATCH_PARENT, WRAP_CONTENT));
          if(isWearable)
              view.setGravity(Gravity.CENTER);
          else
              view.setGravity(Gravity.LEFT);
           return new NumAlarmHolder(view,ok);
          }

    @Override
    public void onBindViewHolder(final NumAlarmHolder holder, int pos) {
    	 Object[] alarmobj=getNumAlarm(pos);
    	 float value=(Float)alarmobj[0];
    	 short[] rest=(short[])alarmobj[1];
    	final short type= rest[3];
      final String lab=(type>=0&&type<labels.size())?labels.get(type):
              (isWearable?"UNLABELED":holder.itemView.getContext().getString(
                      R.string.reminder_modern_unknown_label));
      if(!isWearable) {
         short start=rest[0];
         short alarm=rest[1];
         ((ReminderCardView)holder.itemView).bind(lab,float2string(value),start,alarm);
         return;
         }
      TextView text=(TextView)holder.itemView;
      if(isWearable)  {
    	      text.setText(String.format(usedlocale,"%s  %s", float2string(value),lab) );
            /*
            if(pos==0)
              text.setPadding(0,(int)(tk.glucodata.GlucoseCurve.metrics.density*10.0f),0,(int)(tk.glucodata.GlucoseCurve.metrics.density*10.0f));
         else
              text.setPadding(0,0,0,0); */
            }
      else {
         short start=rest[0];
         short alarm=rest[1];
         text.setText(String.format(usedlocale,"%s  %s %02d:%02d-%02d:%02d", float2string(value),lab , (start/60), (start%60), (alarm/60), (alarm%60))); 
         }
    	}
        @Override
        public int getItemCount() {
    	return Natives.getNumAlarmCount();

        }

}

static void settime(TextView but,int min) {
    but.setText(String.format(usedlocale,"%02d:%02d",min/60,min%60));
    }
int[] minutes=new int[2];
static Button gettimeview(MainActivity act,int[] minutes,int ind,View[] parent) {
    Button but=new Button(act);
    but.setOnClickListener(
            v->  {
                parent[0].setVisibility(INVISIBLE);
                hidekeyboard(act);
                act.getnumberview().gettimepicker(act,minutes[ind]/60, minutes[ind]%60,
                (hour,min) -> {
                        minutes[ind]=hour*60+min;

                        but.setText(String.format(Locale.US,"%02d:%02d",hour,min));
                   },()-> parent[0].setVisibility(VISIBLE));
         });
    return but;
    }


int labelsel=-1;
ViewGroup itemlayout=null;
EditText value;
Spinner spinner;
Button startbut,alarmbut;
Button Delete;


void dodelete(View parent,int alarmpos) {
    	int nr=Natives.getNumAlarmCount();
    	Natives.delNumAlarm(alarmpos);
    	if(nr>0&&alarmpos<nr) { 
    		{if(doLog) {Log.i(LOG_ID,"alarmpos="+alarmpos+ " nr="+nr+" new nr="+Natives.getNumAlarmCount());};};
    		numadapt.notifyItemRemoved(alarmpos);
    		numadapt.notifyDataSetChanged();
    		}
    	this.alarmpos=-1;
    	itemlayout.setVisibility(GONE); 
      if(!isWearable)
         {
         EnableControls(parent,true);
         updatePhoneListState();
         }
      MainActivity.poponback();
    	}
private void askdelete( View parent,int alarmpos) {
    if(!isWearable) {
        askPhoneDelete(parent,alarmpos);
        return;
        }
     Object[] alarmobj=getNumAlarm(alarmpos);
     float flvalue=(Float)alarmobj[0];
     short[] rest=(short[])alarmobj[1];
    short type=rest[3];
    spinner.setSelection(type);
    var value=float2string(flvalue);
    var label=Natives.getLabels().get(type);
    var act=parent.getContext();
        AlertDialog.Builder builder = new AlertDialog.Builder(act);
        builder.setTitle(R.string.deletereminder).
     setMessage(label+" "+value).
        setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
    	 		dodelete(parent,alarmpos);
                    }
                }) .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
            }
        }).show().setCanceledOnTouchOutside(false);
    }

private void askPhoneDelete(View parent,int alarmIndex) {
    Object[] alarmobj=getNumAlarm(alarmIndex);
    float flvalue=(Float)alarmobj[0];
    short[] rest=(short[])alarmobj[1];
    short type=rest[3];
    ArrayList<String> labels=Natives.getLabels();
    String label=(type>=0&&type<labels.size())?labels.get(type):
            parent.getContext().getString(R.string.reminder_modern_unknown_label);
    String valueText=float2string(flvalue);
    String timeText=formatTimeRange(rest[0],rest[1]);
    android.content.Context dialogContext=parent.getContext();
    AlertDialog dialog=new AlertDialog.Builder(dialogContext)
            .setTitle(R.string.reminder_modern_delete_title)
            .setMessage(dialogContext.getString(R.string.reminder_modern_delete_message,
                    label,valueText,timeText))
            .setNegativeButton(R.string.cancel,null)
            .setPositiveButton(R.string.delete,(which,id)->dodelete(parent,alarmIndex))
            .create();
    dialog.setCanceledOnTouchOutside(false);
    dialog.setOnShowListener(ignored->{
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ClinicalUi.danger(dialogContext));
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ClinicalUi.primaryText(dialogContext));
        if(dialog.getWindow()!=null)
            dialog.getWindow().setBackgroundDrawable(ClinicalUi.surface(dialogContext,true,false));
        });
    dialog.show();
    }

private void mkPhoneItemLayout(MainActivity act,View parent) {
    if(itemlayout==null) {
        LinearLayout content=ClinicalUi.verticalContent(act);
        content.setPadding(ClinicalUi.dp(act,20),MainActivity.systembarTop+ClinicalUi.dp(act,8),
                ClinicalUi.dp(act,20),ClinicalUi.dp(act,30));

        Button headerClose=ClinicalUi.button(act,act.getString(R.string.cancel),
                ClinicalUi.ButtonRole.SECONDARY);
        LinearLayout header=ClinicalUi.header(act,"",headerClose);
        phoneFormTitle=(TextView)header.getChildAt(0);
        content.addView(header);

        TextView intro=ClinicalUi.body(act,
                act.getString(R.string.reminder_modern_form_subtitle));
        intro.setPadding(ClinicalUi.dp(act,4),0,ClinicalUi.dp(act,4),ClinicalUi.dp(act,4));
        content.addView(intro);
        content.addView(ClinicalUi.sectionLabel(act,
                act.getString(R.string.reminder_modern_details)));

        spinner=getGenSpin(act);
        LabelAdapter<String> labelAdapter=new LabelAdapter<>(act,Natives.getLabels(),1);
        spinner.setAdapter(labelAdapter);
        spinner.setMinimumHeight(ClinicalUi.dp(act,50));
        spinner.setMinimumWidth(ClinicalUi.dp(act,150));
        spinner.setPaddingRelative(ClinicalUi.dp(act,12),0,ClinicalUi.dp(act,12),0);
        spinner.setBackground(ClinicalUi.surface(act,false,true));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> selectedParent,View view,int position,long id) {
                labelsel=position;
                showPhoneFormError(act,REMINDER_VALID);
                }
            @Override
            public void onNothingSelected(AdapterView<?> selectedParent) {
                labelsel=-1;
                }
            });
        spinner.setOnTouchListener((view,event)->{
            hidekeyboard(act);
            return false;
            });

        value=makePhoneNumberInput(act);
        View[] layoutReference=new View[1];
        startbut=getPhoneTimeView(act,minutes,0,layoutReference);
        alarmbut=getPhoneTimeView(act,minutes,1,layoutReference);
        LinearLayout details=ClinicalUi.card(act,
                ClinicalUi.fieldRow(act,act.getString(R.string.reminder_modern_label),spinner),
                ClinicalUi.fieldRow(act,act.getString(R.string.reminder_modern_value),value),
                ClinicalUi.fieldRow(act,act.getString(R.string.reminder_modern_start),startbut),
                ClinicalUi.fieldRow(act,act.getString(R.string.reminder_modern_end),alarmbut));
        content.addView(details);

        phoneFormError=ClinicalUi.body(act,"");
        phoneFormError.setTextColor(ClinicalUi.danger(act));
        phoneFormError.setTextSize(TypedValue.COMPLEX_UNIT_SP,14);
        phoneFormError.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        phoneFormError.setPadding(ClinicalUi.dp(act,16),ClinicalUi.dp(act,13),
                ClinicalUi.dp(act,16),ClinicalUi.dp(act,13));
        phoneFormError.setBackground(ClinicalUi.surface(act,false,false));
        phoneFormError.setVisibility(GONE);
        LinearLayout.LayoutParams errorParams=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
        errorParams.topMargin=ClinicalUi.dp(act,12);
        content.addView(phoneFormError,errorParams);

        content.addView(ClinicalUi.sectionLabel(act,
                act.getString(R.string.reminder_modern_actions)));
        Button save=ClinicalUi.button(act,act.getString(R.string.reminder_modern_save),
                ClinicalUi.ButtonRole.PRIMARY);
        Button cancel=ClinicalUi.button(act,act.getString(R.string.cancel),
                ClinicalUi.ButtonRole.SECONDARY);
        Delete=ClinicalUi.button(act,act.getString(R.string.reminder_modern_delete),
                ClinicalUi.ButtonRole.DANGER);
        content.addView(save,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
        LinearLayout.LayoutParams actionParams=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
        actionParams.topMargin=ClinicalUi.dp(act,10);
        content.addView(cancel,actionParams);
        LinearLayout.LayoutParams deleteParams=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
        deleteParams.topMargin=ClinicalUi.dp(act,10);
        content.addView(Delete,deleteParams);

        ScrollView screen=ClinicalUi.scrollScreen(act,content);
        itemlayout=screen;
        layoutReference[0]=itemlayout;
        headerClose.setOnClickListener(v->act.doonback());
        cancel.setOnClickListener(v->act.doonback());
        Delete.setOnClickListener(v->{
            hidekeyboard(act);
            if(alarmpos>=0)
                askdelete(parent,alarmpos);
            });
        save.setOnClickListener(v->savePhoneReminder(act,parent));
        }
    if(itemlayout.getParent()==null)
        act.addMyContentView(itemlayout,new ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT));
    itemlayout.setVisibility(VISIBLE);
    itemlayout.bringToFront();
    showPhoneFormError(act,REMINDER_VALID);
    MainActivity.setonback(()->{
        hidekeyboard(act);
        itemlayout.setVisibility(GONE);
        EnableControls(parent,true);
        });
    EnableControls(parent,false);
    }

private EditText makePhoneNumberInput(MainActivity act) {
    EditText input=new EditText(act);
    input.setSingleLine(true);
    input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
    input.setImeOptions(editoptions);
    input.setTextColor(ClinicalUi.primaryText(act));
    input.setHintTextColor(ClinicalUi.secondaryText(act));
    input.setTextSize(TypedValue.COMPLEX_UNIT_SP,17);
    input.setGravity(Gravity.CENTER);
    input.setMinWidth(ClinicalUi.dp(act,112));
    input.setMinimumHeight(ClinicalUi.dp(act,50));
    input.setPadding(ClinicalUi.dp(act,12),0,ClinicalUi.dp(act,12),0);
    input.setBackground(ClinicalUi.surface(act,false,true));
    return input;
    }

private static Button getPhoneTimeView(MainActivity act,int[] minutes,int index,View[] parent) {
    Button button=ClinicalUi.button(act,"00:00",ClinicalUi.ButtonRole.SECONDARY);
    button.setMinWidth(ClinicalUi.dp(act,112));
    button.setOnClickListener(v->{
        parent[0].setVisibility(INVISIBLE);
        hidekeyboard(act);
        act.getnumberview().gettimepicker(act,minutes[index]/60,minutes[index]%60,
                (hour,minute)->{
                    minutes[index]=hour*60+minute;
                    button.setText(String.format(Locale.US,"%02d:%02d",hour,minute));
                    },()->parent[0].setVisibility(VISIBLE));
        });
    return button;
    }

private void savePhoneReminder(MainActivity act,View parent) {
    int validation=validateReminderInput(value.getText().toString(),labelsel,
            minutes[0],minutes[1]);
    if(validation!=REMINDER_VALID) {
        showPhoneFormError(act,validation);
        return;
        }
    hidekeyboard(act);
    float parsed=parseReminderValue(value.getText().toString());
    issaved=true;
    if(alarmpos>=0) {
        Natives.delNumAlarm(alarmpos);
        alarmpos=-1;
        }
    Natives.setNumAlarm(labelsel,parsed,minutes[0],minutes[1]);
    numadapt.notifyDataSetChanged();
    updatePhoneListState();
    itemlayout.setVisibility(GONE);
    EnableControls(parent,true);
    MainActivity.poponback();
    }

void  mkitemlayout(MainActivity act,View parent) {
  if(!isWearable) {
      mkPhoneItemLayout(act,parent);
      return;
      }
  if(itemlayout==null) {
        spinner=getGenSpin(act);
//        if(isWearable) spinner.setDropDownVerticalOffset((int)(GlucoseCurve.getheight()*.54));
       LabelAdapter<String> labelspinadapt=new LabelAdapter<String>(act,Natives.getLabels(),1);
       spinner.setAdapter(labelspinadapt);
       spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
              @Override
              public  void onItemSelected (AdapterView<?> parent, View view, int position, long id) {
                  labelsel=position;
              }
              @Override
              public  void onNothingSelected (AdapterView<?> parent) {
                  labelsel=-1;

              } });
//         spinner.clearAnimation();
         spinner.setOnTouchListener(new View.OnTouchListener() {
             @Override
             public boolean onTouch(View view, MotionEvent motionEvent) {
               hidekeyboard(act);
            return false;
             }
         });
    value=new EditText(act);
    value.setInputType( InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
    value.setMinEms(isWearable?1:2);
    value.setImeOptions(editoptions);
    View[] layoutar=new View[1];
    startbut=gettimeview(act,minutes,0,layoutar);
    alarmbut=gettimeview(act,minutes,1,layoutar);
    Delete=getbutton(act,R.string.delete);
    Button Cancel=getbutton(act,R.string.cancel);
    Button Save=getbutton(act,R.string.save);
    View[][] views=null;
    if(isWearable) {
      var space1=new Space(act);
      var space2=new Space(act);
      if(useclose)
    	views=new View[][] {new View[]{space1,startbut,alarmbut,space2},new View[] {spinner,value},new View[]{Cancel,Save},new View[]{Delete}};
      else {
//         var space3=new Space(act);
 //        var space4=new Space(act);
         //views=new View[][] {new View[]{space1,startbut,alarmbut,space2},new View[] {spinner,value},new View[]{space3,Delete,Save,space4}};
         views=new View[][] {new View[]{startbut,alarmbut},new View[] {spinner,value},new View[]{Delete,Save}};
         }
      }
    else
    	views=new View[][] {new View[] {spinner,value},new View[]{startbut,alarmbut},new View[]{Delete,Cancel,Save}};
    itemlayout= new Layout(act, (l, w, h) -> {
    /*
    	var height=GlucoseCurve.getheight();
    	if(!isWearable)  {
         l.setY(MainActivity.systembarTop);
         }
       else {
            if(!useclose) {
               if(height>h)
                  l.setY((height-h)/2);
               }
         }
    	var width=GlucoseCurve.getwidth();
    	if(width>w) l.setX((width-w)/2);
        */
    	int[] ret={w,h};
    	return ret;
    	}, views);
  ViewGroup.LayoutParams layparm;
   if(isWearable) {
      if(useclose) {
         var scroll=new ScrollView(act);
         scroll.setFillViewport(true);
         scroll.setSmoothScrollingEnabled(false);
         scroll.setScrollbarFadingEnabled(true);
         scroll.setVerticalScrollBarEnabled(true);
    	 var itempar=  new FrameLayout.LayoutParams( WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER| Gravity.CENTER_HORIZONTAL);
//         scroll.addView(itemlayout,new ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
         scroll.addView(itemlayout,itempar);
         itemlayout.setPadding(0,(int)(tk.glucodata.GlucoseCurve.metrics.density*15.0f),0,0);
         itemlayout=scroll;
      }
    else {
         var frame =new FrameLayout(act);
         frame.addView(itemlayout,new ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
         itemlayout=frame;
         itemlayout.setBackgroundColor(Applic.backgroundcolor);
         }

        layparm=new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT);
//    	layparm=  new FrameLayout.LayoutParams( WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP| Gravity.CENTER_HORIZONTAL);
//    	layparm=  new FrameLayout.LayoutParams( WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER| Gravity.CENTER_HORIZONTAL);
      }
    else  {
    	layparm=  new FrameLayout.LayoutParams( WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP| Gravity.CENTER_HORIZONTAL);
        var height=GlucoseCurve.getheight();
        ((FrameLayout.LayoutParams) layparm).topMargin=MainActivity.systembarTop;
        }
    layoutar[0]=itemlayout;
        //itemlayout.setBackgroundColor(Applic.backgroundcolor);
        if(!isWearable)
           itemlayout.setBackgroundResource(R.drawable.dialogbackground);
         else
            itemlayout.setBackgroundColor(Applic.backgroundcolor);
       int pad=(int)(tk.glucodata.GlucoseCurve.metrics.density*4.5);
       itemlayout.setPadding(pad,0,pad,0);
    Cancel.setOnClickListener(v->{ 
      MainActivity.doonback();
//    	genlayout.setVisibility(VISIBLE); 
    	});



    Delete.setOnClickListener(v->{ 
    	if(alarmpos>=0) {
    		askdelete(parent,alarmpos);
    		}

        	hidekeyboard(act);
    	});
    Save.setOnClickListener( v-> {

      issaved=true;
       hidekeyboard((MainActivity)v.getContext());
      if(labelsel<0) {
         Log.e(LOG_ID,"labelsel="+labelsel);
         return;
         }
      float val;
      try {
         val=Float.parseFloat(value.getText().toString());
         }
         catch(Exception e) {
         {if(doLog) {Log.i(LOG_ID,"parsefloat exception "+value.getText().toString());};};
         return;
         };
      if(minutes[0]==minutes[1])
         return;
      if(alarmpos>=0) {
         Natives.delNumAlarm(alarmpos);
         alarmpos=-1;
         }
      
      {if(doLog) {Log.i(LOG_ID,"save "+labelsel+" "+val+" "+tstring(minutes[0])+ " "+tstring(minutes[1]));};};
      Natives.setNumAlarm( labelsel,val,minutes[0],minutes[1]);

      numadapt.notifyDataSetChanged();
      itemlayout.setVisibility(GONE); 
   //    genlayout.setVisibility(VISIBLE); 
   //        parok.setVisibility(VISIBLE);
      if(!isWearable)
            EnableControls(parent,true);
      MainActivity.poponback();
      }

    );
        //act.addMyContentView(itemlayout,isWearable?new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT):new ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));


        act.addMyContentView(itemlayout,layparm);
;

    }
else  {
    itemlayout.setVisibility(VISIBLE); 
    itemlayout.bringToFront();
    }
MainActivity.setonback(()-> {
          hidekeyboard(act);
         itemlayout.setVisibility(GONE); 
   //    	parok.setVisibility(VISIBLE);
         if(!isWearable) EnableControls(parent,true);
         });
//    	genlayout.setVisibility(VISIBLE); 
//    parok.setVisibility(INVISIBLE);
   if(!isWearable)
      EnableControls(parent,false);
    }	

String tstring(int min) {
      return String.format(usedlocale,"%02d:%02d",min/60,min%60);
      }
    /*
struct amountalarm {
        float value;
        uint16_t start,alarm,end;
        uint16_t type;
        };
*/
void emptyitemlayout() {
    value.setText("");
    minutes[0]=0;
    minutes[1]=0;
    settime(startbut,0);
    settime(alarmbut,0);
    alarmpos=-1;
    spinner.setSelection(0);
    labelsel=0;
    //Delete.setVisibility(isWearable?GONE:INVISIBLE); 
    Delete.setVisibility(isWearable?INVISIBLE:GONE);
    if(!isWearable&&phoneFormTitle!=null) {
        phoneFormTitle.setText(R.string.reminder_modern_add_title);
        if(phoneFormError!=null)
            phoneFormError.setVisibility(GONE);
        }
    }
void fillitemlayout(int pos) {
    Object[] alarmobj=getNumAlarm(pos);
    float flvalue=(Float)alarmobj[0];
    short[] rest=(short[])alarmobj[1];
    short start=rest[0];
    short alarm=rest[1];
    short type=rest[3];
    spinner.setSelection(type);
    labelsel=type;
    value.setText(float2string(flvalue));
    minutes[0]=start;
    minutes[1]=alarm;
    settime(startbut,start);
    settime(alarmbut,alarm);
    Delete.setVisibility(VISIBLE); 
    if(!isWearable&&phoneFormTitle!=null) {
        phoneFormTitle.setText(R.string.reminder_modern_edit_title);
        if(phoneFormError!=null)
            phoneFormError.setVisibility(GONE);
        }
    }
}
