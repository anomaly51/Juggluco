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
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.opengl.GLSurfaceView;
import android.text.InputType;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;

import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.Calendar;

import androidx.annotation.Keep;
import androidx.annotation.UiThread;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import tk.glucodata.nums.numio;
import tk.glucodata.settings.Settings;

import static android.util.TypedValue.COMPLEX_UNIT_PX;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static java.lang.System.currentTimeMillis;
import static tk.glucodata.Applic.isWearable;
import static tk.glucodata.Applic.usedlocale;
import static tk.glucodata.BuildConfig.SiBionics;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.MainActivity.REQUEST_BARCODE;
import static tk.glucodata.MainActivity.systembarBottom;
import static tk.glucodata.MainActivity.systembarLeft;
import static tk.glucodata.MainActivity.systembarRight;
import static tk.glucodata.MainActivity.systembarTop;
import static tk.glucodata.Natives.getInvertColors;
import static tk.glucodata.Natives.turnoffalarm;
import static tk.glucodata.NumberView.smallScreen;
import static tk.glucodata.RingTones.EnableControls;
import static tk.glucodata.settings.Settings.editoptions;
import static tk.glucodata.settings.Settings.removeContentView;
import static tk.glucodata.util.getlabel;

public class GlucoseCurve extends GLSurfaceView {
Button summarybutton=null;
boolean statspresent=false;
@Keep
void summaryready() {
    statspresent=true;
    if(doLog) {Log.i(LOG_ID,"summaryready");};;
    Applic.RunOnUiThread(()-> {
       if(doLog) {
           Log.i(LOG_ID,"UIThread summaryready");
           }
        Button tmp= summarybutton;
        summarybutton=null;
        if(tmp!=null) {
            {if(doLog) {Log.i(LOG_ID,"set Visible");};};
            tmp.setVisibility(VISIBLE);
            tmp.bringToFront();
            }
        });
   if(doLog) {
           Log.i(LOG_ID,"end summaryready");
           }
    
    }

@Keep
void showsensorinfo(String text,long sensorptr) {
    Applic.RunOnUiThread(()-> {
//        bluediag.showsensorinfo(text,(MainActivity )getContext());
        Sensors.show((MainActivity )getContext(),text,sensorptr);
        });
    }
static View[] reopen=new View[8]; //6 needed
static int reopennr=0;
    //    SearchLayout search;
    ViewGroup search;
    public Dialogs dialogs;
    private static final String LOG_ID = "GlucoseCurve";
static   public float smallfontsize;
    Calendar cal = Calendar.getInstance();
 final   private ScaleGestureDetector mScaleDetector;
 final   private GestureDetector mGestureDetector;
 final   private GestureListener mGestureListener;
 final   private GraphGestureState graphGestureState;
    static final int STEPBACK = 1;
    boolean waitnfc = false;
    MyRenderer render = new MyRenderer();
    static int height,width;



NumberView  numberview= new NumberView();

ViewGroup numcontrol=null;
void startsearch() {
if(!isWearable) {
    MainActivity activity = (MainActivity) getContext();
    if(searchcontrol!=null) {
        Natives.stopsearch();
        searchcontrol.setVisibility(View.GONE);
    }
    if (search == null) {
        search = getsearchlayout(activity);
    } else {
        var labels=Natives.getLabels();
        if(!searchspinadap.getarray().equals(labels))  {
            searchspinadap.setarray(labels);
            searchspinner.setAdapter(searchspinadap);
        }
        search.setVisibility(View.VISIBLE);
        search.bringToFront();
        if(labelsel==Natives.getmealvar())
            mkmealsearch(activity);
    }
    search.setFocusableInTouchMode(true);
    search.requestFocus();

    activity.setonback(()-> {
        activity.showui=false;
          activity.hideSystemUI();
        tk.glucodata.help.hidekeyboard(activity);
        search.setVisibility(View.GONE);
        hidemealsearch();
        hidekeyboard();
        reopener();
        if(Menus.on)
            Menus.show(activity);
    } );
    }
    }



private final static void hidesave(View v) {
    if(v.getVisibility()==VISIBLE) {
        reopen[reopennr++]=v;
        v.setVisibility(INVISIBLE);
        }
    }

void setminheight(View[] views,int minheight) {
    for(View v:views)
        v.setMinimumHeight(minheight);
    }

private static Button recordsPagerButton(MainActivity activity,int text,
        int description) {
    Button button=new Button(activity);
    button.setText(text);
    button.setContentDescription(activity.getString(description));
    button.setAllCaps(false);
    button.setTextColor(Color.rgb(148,163,184));
    button.setTextSize(TypedValue.COMPLEX_UNIT_SP,12.0f);
    button.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
    button.setMinWidth(0);
    button.setMinimumWidth(0);
    button.setPaddingRelative((int)(metrics.density*6.0f),0,
            (int)(metrics.density*6.0f),0);
    if(android.os.Build.VERSION.SDK_INT>=21)
        button.setBackgroundTintList(null);
    button.setBackgroundResource(R.drawable.records_toolbar_pager);
    return button;
    }
//void getnumcontrol(MainActivity activity,int width,int height) {
void getnumcontrol(MainActivity activity) {
   {if(doLog) {Log.i(LOG_ID,"getnumcontrol start");};};

   final int height=getHeight();
    if(numcontrol==null||(isWearable&&numcontrol.getHeight()!=(height-systembarTop))) {
           ImageButton first=new ImageButton(activity);
           first.setImageResource( R.drawable.baseline_first_page_24);
           first.setOnClickListener(v-> {
        Natives.firstpage();
                  requestRender();
           });
        first.setContentDescription(activity.getString(
                R.string.records_first_description));

           ImageButton back=new ImageButton(activity);
           back.setImageResource( R.drawable.baseline_arrow_back_24);
           back.setOnClickListener(v-> {
        Natives.backwardnumlist();
                  requestRender();

           });
        back.setContentDescription(activity.getString(
                R.string.records_previous_description));
            


     ImageButton search=new ImageButton(activity);
     search.setImageResource( android.R.drawable.ic_menu_search);
        search.setContentDescription(activity.getString(
                R.string.records_search_description));
//     search.setImageResource( android.R.attr.actionModeWebSearchDrawable);

       search.setOnClickListener(v-> {
           hidesave(numcontrol);
        startsearch();
        selectnumbers();
        hidesave(scansearch);
        hidesave(streamsearch);
        hidesave(streamcalibratedsearch);
        hidesave(historycalibratedsearch);
        hidesave(historysearch);
           });
//    s/\(first[^6]*.6\)/(int)(\1)/g
    search.setPadding((int)(first.getPaddingLeft()*.69),(int)(first.getPaddingTop()*.69),(int)(first.getPaddingRight()*.69),(int)(first.getPaddingBottom()*.69));
     ImageButton closecontrol=new ImageButton(activity);

     closecontrol.setImageResource(isWearable?
             android.R.drawable.ic_menu_close_clear_cancel:
             R.drawable.baseline_arrow_back_24);
           closecontrol.setOnClickListener(v-> {
           activity.doonback();
//           activity.poponback();
           });

    closecontrol.setContentDescription(activity.getString(R.string.closename));
    closecontrol.setPadding((int)(first.getPaddingLeft()*.69),(int)(first.getPaddingTop()*.69),(int)(first.getPaddingRight()*.69),(int)(first.getPaddingBottom()*.69));

           ImageButton next=new ImageButton(activity);

           next.setImageResource( R.drawable.baseline_arrow_forward_24);
        next.setContentDescription(activity.getString(
                R.string.records_next_description));
           next.setOnClickListener(v-> {
            Natives.forwardnumlist();
                 requestRender();

       });
           ImageButton last=new ImageButton(activity);
       last.setContentDescription(activity.getString(
               R.string.records_latest_description));
           last.setImageResource( R.drawable.baseline_last_page_24);

           last.setOnClickListener(v-> {

        Natives.lastpage();
                  requestRender();
           });


         final View[] controls={first,
                 back,
                 search,
                 closecontrol,
                 next,
                 last};
         final    int minheight=(int)(metrics.density*48.0f);
         setminheight(controls,minheight);
        if(!isWearable) {
            final int iconPadding=(int)(metrics.density*14.0f);
            final int iconTint=Color.rgb(148,163,184);
            search.setBackgroundResource(R.drawable.records_toolbar_icon);
            search.setImageTintList(ColorStateList.valueOf(iconTint));
            search.setPadding(iconPadding,iconPadding,iconPadding,iconPadding);
            closecontrol.setBackgroundResource(R.drawable.records_toolbar_icon);
            closecontrol.setImageTintList(ColorStateList.valueOf(iconTint));
            closecontrol.setPadding(iconPadding,iconPadding,iconPadding,iconPadding);

            LinearLayout heading=new LinearLayout(activity);
            heading.setOrientation(LinearLayout.VERTICAL);
            heading.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView title=new TextView(activity);
            title.setText(R.string.dashboard_records);
            title.setTextColor(Color.rgb(241,245,249));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP,19.0f);
            title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            TextView subtitle=new TextView(activity);
            subtitle.setText(R.string.records_subtitle);
            subtitle.setTextColor(Color.rgb(100,116,139));
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP,11.0f);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            heading.addView(title,new LinearLayout.LayoutParams(
                    MATCH_PARENT,WRAP_CONTENT));
            heading.addView(subtitle,new LinearLayout.LayoutParams(
                    MATCH_PARENT,WRAP_CONTENT));

            LinearLayout topRow=new LinearLayout(activity);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            topRow.setBaselineAligned(false);
            topRow.addView(closecontrol,new LinearLayout.LayoutParams(
                    minheight,minheight));
            LinearLayout.LayoutParams headingParams=new LinearLayout.LayoutParams(
                    0,minheight,1.0f);
            headingParams.setMarginStart((int)(metrics.density*10.0f));
            headingParams.setMarginEnd((int)(metrics.density*10.0f));
            topRow.addView(heading,headingParams);
            topRow.addView(search,new LinearLayout.LayoutParams(
                    minheight,minheight));
            Button firstPage=recordsPagerButton(activity,R.string.records_first,
                    R.string.records_first_description);
            firstPage.setOnClickListener(v -> {
                Natives.firstpage();
                requestRender();
                });
            Button previousPage=recordsPagerButton(activity,R.string.records_previous,
                    R.string.records_previous_description);
            previousPage.setOnClickListener(v -> {
                Natives.backwardnumlist();
                requestRender();
                });
            Button nextPage=recordsPagerButton(activity,R.string.records_next,
                    R.string.records_next_description);
            nextPage.setOnClickListener(v -> {
                Natives.forwardnumlist();
                requestRender();
                });
            Button latestPage=recordsPagerButton(activity,R.string.records_latest,
                    R.string.records_latest_description);
            latestPage.setOnClickListener(v -> {
                Natives.lastpage();
                requestRender();
                });
            LinearLayout pagerRow=new LinearLayout(activity);
            pagerRow.setOrientation(LinearLayout.HORIZONTAL);
            pagerRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            final int pagerGap=(int)(metrics.density*8.0f);
            for(Button pager:new Button[]{firstPage,previousPage,nextPage,latestPage}) {
                LinearLayout.LayoutParams pagerParams=new LinearLayout.LayoutParams(
                        0,minheight,1.0f);
                if(pagerRow.getChildCount()>0)
                    pagerParams.setMarginStart(pagerGap);
                pagerRow.addView(pager,pagerParams);
                }

            LinearLayout toolbarRoot=new LinearLayout(activity);
            toolbarRoot.setOrientation(LinearLayout.VERTICAL);
            toolbarRoot.setClickable(true);
            toolbarRoot.addView(topRow,new LinearLayout.LayoutParams(
                    MATCH_PARENT,minheight));
            LinearLayout.LayoutParams pagerRowParams=new LinearLayout.LayoutParams(
                    MATCH_PARENT,minheight);
            pagerRowParams.topMargin=(int)(metrics.density*8.0f);
            toolbarRoot.addView(pagerRow,pagerRowParams);
            toolbarRoot.addOnLayoutChangeListener((v,left,top,right,bottom,
                    oldLeft,oldTop,oldRight,oldBottom) -> {
                if ((right-left)!=(oldRight-oldLeft)
                        || (bottom-top)!=(oldBottom-oldTop)) {
                    Natives.numcontrol(0,bottom-top);
                    v.setX(0.0f);
                    v.setY(systembarTop);
                    requestRender();
                    }
                });
            numcontrol=toolbarRoot;
            numcontrol.setBackgroundResource(R.drawable.records_toolbar_surface);
            numcontrol.setElevation(metrics.density*3.0f);
            activity.addMyContentView(numcontrol,
                    new ViewGroup.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
            }
        else {
            numcontrol= new Layout(activity,(v,w,h) -> {
                final int width=getWidth();
                int columns=Natives.numcontrol(w,h);
                int bar=systembarTop;
                int over;
                if(bar>0) {
                    v.setY(bar);
                    over=height-bar;
                    if(over>h)
                       over=h;
                    }
                else  {
                    if(height>h)
                       v.setY((height-h)/2.0f);
                    over=h;
                    }
                if(width>w) {
                    if(columns==1)  {
                        v.setX(width-w-systembarRight);
                        }
                    else {
                        v.setX(((width-w-systembarRight+systembarLeft)/2.0f));
                        }
                    }
                requestRender();
                over-=systembarBottom;
                return new int[] {w,over};
                },new View[]{first}, new View[]{back}, new View[]{search},
                    new View[]{closecontrol}, new View[]{next}, new View[]{last});
            activity.addMyContentView(numcontrol,
                    new ViewGroup.LayoutParams(WRAP_CONTENT,MATCH_PARENT));
            }
        numcontrol.post(numcontrol::requestLayout);
       }
      else
         numcontrol.setVisibility(VISIBLE);
    if(!isWearable) {
        final int horizontalPadding=(int)(metrics.density*14.0f);
        final int verticalPadding=(int)(metrics.density*8.0f);
        numcontrol.setPadding(horizontalPadding+systembarLeft,verticalPadding,
                horizontalPadding+systembarRight,verticalPadding);
        }
    activity.setonback(()-> {
        numcontrol.setVisibility(GONE);
        Natives.endnumlist();
         if(Menus.on) {
             Menus.show(activity);
             }
        else
             requestRender();
         });
   {if(doLog) {Log.i(LOG_ID,"getnumcontrol end");};};
    }

    void showkeyboard(MainActivity context) {
       numberview.showkeyboard(context);
        }
    void hidekeyboard() {
        numberview.hidekeyboard();
            }
        
    /*OnBackPressedCallback callback = new OnBackPressedCallback(true) {
        @Override
        public void handleOnBackPressed() {
            if ((render.stepresult & STEPBACK) == STEPBACK) {
                {if(doLog) {Log.d(LOG_ID,"GlucoseCurve: back");};};
                render.stepresult = 0;
//                ((MainActivity)getContext()).hideSystem=true;
                ((MainActivity)getContext()).hideSystemUI();
//                Natives.hidescanresults();
                requestRender();
            } else
                ((Activity) getContext()).finish();
        }
    };*/
static public DisplayMetrics metrics;
static public float getDensity() {
    if(metrics==null||metrics.density<=0.0f) {
        metrics= Applic.app.getResources().getDisplayMetrics();
        }
    return metrics.density;
    }
public GlucoseCurve(MainActivity context) {
    super(context);
    {if(doLog) {Log.i(LOG_ID,"GlucoseCurve "+MainActivity.openglversion);};};
    graphGestureState = new GraphGestureState(
            ViewConfiguration.get(context).getScaledTouchSlop());
    mScaleDetector = new ScaleGestureDetector(context, mScaleListener);
    mGestureListener = new GestureListener();
    mGestureDetector = new GestureDetector(context, mGestureListener);
    mGestureDetector.setOnDoubleTapListener(mGestureListener);
    setEGLContextClientVersion(MainActivity.openglversion);
    setEGLConfigChooser(8, 8, 8, 8, 16, 1);
    setRenderer(render);
    setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    metrics= getResources().getDisplayMetrics();
    dialogs=new Dialogs(metrics.density);
    }
    public static int dpToPx(float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, metrics);
        }

    static boolean isCentralDashboardGraphPoint(float x, float y,
            float width, float height, float edgeInset) {
        return edgeInset >= 0.0f && width > edgeInset * 2.0f
                && height > edgeInset * 2.0f
                && x >= edgeInset && x <= width - edgeInset
                && y >= edgeInset && y <= height - edgeInset;
        }

    /** New amount entry stays available to the separate wearable UI only. */
    static boolean allowsLegacyNewRecordCreation(boolean wearable) {
        return wearable;
        }

public static int getheight() {
    return height;
    }
public static int getwidth() {
    return width;
    }
static void setgeo(int w,int h) {
    // Java overlays use these values as the current window geometry.  The old
    // graph was landscape-only and intentionally ignored portrait sizes,
    // leaving every dialog with stale coordinates after a rotation.
    width=w;
    height=h;
    }
long multitime=0L;
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if(turnoffalarm())
        Notify.stopalarm();
        if((render.stepresult&STEPBACK)!=0) {
            final float x = event.getX();
            final float y = event.getY();
    
            if(Natives.isbutton(x, y)) {
                render.badscan=0;
                if(Menus.on)
                    Menus.show((MainActivity)getContext());
                else
                    requestRender();
            }
            return false;
        }

        final int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN) {
            graphGestureState.beginSingleFinger(event.getX(),event.getY());
            }
        else if(action==MotionEvent.ACTION_POINTER_DOWN) {
            multitime=System.currentTimeMillis();
            graphGestureState.beginPinch();
            mGestureListener.cancelForScale();
            }

        final boolean scaleHandled=mScaleDetector.onTouchEvent(event);
        if(graphGestureState.isPinchSequence()||event.getPointerCount()>1
                ||mScaleDetector.isInProgress()) {
            if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)
                mGestureListener.finishTouchSequence();
            return true;
            }

        boolean handled=mGestureDetector.onTouchEvent(event)||scaleHandled;
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL) {
            handled=mGestureListener.finishDeferredLongPress(event)||handled;
            mGestureListener.finishTouchSequence();
            }
        return handled;
    }

    boolean down = false;
final    private ScaleGestureDetector.SimpleOnScaleGestureListener mScaleListener = new ScaleGestureDetector.SimpleOnScaleGestureListener() {
        float focusx;

        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            graphGestureState.beginPinch();
            mGestureListener.cancelForScale();
            focusx = detector.getFocusX();
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {


        }

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float scalex = detector.getCurrentSpanX() / detector.getPreviousSpanX();
      {if(doLog) {Log.i(LOG_ID,"onScale SpanX="+detector.getCurrentSpanX()+" PreviousSpanX="+ detector.getPreviousSpanX()+" scalex="+scalex);};};
            Natives.xscale(scalex, focusx);
            requestRender();
            ((MainActivity)getContext()).refreshDashboardData();
            down = false;
            return true;
        }
    };

    long reldate;
     void startdatepick(long tim) {
        reldate=tim;
        numberview.getdateviewal((MainActivity)getContext(),tim,    (year,month,day)-> {
            Natives.movedate(reldate, year, month, day);
            requestRender();
        });

    }
class GestureListener implements GestureDetector.OnGestureListener,
        GestureDetector.OnDoubleTapListener {
        private boolean suppressSingleTapForDoubleTap;
        private boolean deferredLongPress;
        private boolean scrubMoved;
        private float deferredLongPressX;
        private float deferredLongPressY;
        private long lastScrubTime;

        private void cancelForScale() {
            cancelDeferredLongPress();
            down=false;
        }

        private void finishTouchSequence() {
            down=false;
            graphGestureState.endSequence();
        }

        private void cancelDeferredLongPress() {
            deferredLongPress=false;
            scrubMoved=false;
            lastScrubTime=0L;
        }

        private boolean finishDeferredLongPress(MotionEvent event) {
            if(!deferredLongPress)
                return false;
            final boolean runLegacy=event.getActionMasked()==MotionEvent.ACTION_UP
                    &&!scrubMoved;
            final float x=deferredLongPressX;
            final float y=deferredLongPressY;
            cancelDeferredLongPress();
            if(runLegacy)
                handleLegacyLongPress(x,y);
            down=false;
            return true;
        }

        private boolean beginDashboardScrub(float x,float y) {
            if(isWearable||!(getContext() instanceof MainActivity))
                return false;
            MainActivity activity=(MainActivity)getContext();
            final float edgeInset=dpToPx(48.0f);
            if(!activity.acceptsDashboardGraphGestures()
                    ||!isCentralDashboardGraphPoint(x,y,getWidth(),getHeight(),edgeInset))
                return false;
            final long selectedTime=Natives.graphscrub(x,y);
            if(selectedTime==0L)
                return false;
            deferredLongPress=true;
            scrubMoved=false;
            deferredLongPressX=x;
            deferredLongPressY=y;
            lastScrubTime=selectedTime;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            requestRender();
            return true;
        }

        private void updateDashboardScrub(MotionEvent event) {
            final float deltaX=event.getX()-deferredLongPressX;
            final float deltaY=event.getY()-deferredLongPressY;
            final float slop=dpToPx(7.0f);
            if(deltaX*deltaX+deltaY*deltaY>=slop*slop)
                scrubMoved=true;
            final long selectedTime=Natives.graphscrub(event.getX(),event.getY());
            if(selectedTime!=0L) {
                if(selectedTime!=lastScrubTime) {
                    lastScrubTime=selectedTime;
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                requestRender();
                }
        }

        private boolean handleDashboardDoubleTap(MotionEvent event) {
            if (isWearable || !(getContext() instanceof MainActivity)) {
                return false;
            }
            MainActivity activity = (MainActivity) getContext();
            final float edgeInset = dpToPx(48.0f);
            if (!activity.acceptsDashboardGraphGestures()
                    || !isCentralDashboardGraphPoint(event.getX(), event.getY(),
                            getWidth(), getHeight(), edgeInset)) {
                return false;
            }
            suppressSingleTapForDoubleTap = true;
            Natives.settonow();
            requestRender();
            activity.refreshDashboardData();
            return true;
        }

        @Override
        public boolean onDown(MotionEvent e) {
            cancelDeferredLongPress();
            down = true;
            return true;
        }

        @Override
        public void onShowPress(MotionEvent e) {

        }


      /*
void startlibrelink(String lang) {
    Activity act = (Activity) getContext();
    ComponentName cn = new ComponentName("com.freestylelibre.app."+lang, "com.librelink.app.ui.SplashActivity");
    //                            ComponentName cn = new ComponentName("com.freestylelibre.app.nl","com.librelink.app.ui.common.ScanSensorActivity");
    Intent infoIntent = new Intent();
    infoIntent.setComponent(cn);
    infoIntent.setAction("android.intent.action.MAIN");
    act.startActivity(infoIntent);
}
*/
//GarminStatus status=null;
//bluediag bluestatus=null;
        @UiThread
        @Override
        public boolean onSingleTapUp(MotionEvent event) {
            {if(doLog) {Log.d(LOG_ID,"onSingleTapUp");};};
            if (suppressSingleTapForDoubleTap) {
                return true;
            }
            if (down ) {
                final float x=event.getX();
                final float y=event.getY();
                long choice = Natives.tap(x, y);
                if(choice==-2L) 
                    return true;
                if(choice!=-1L) {
                    int menu = (int) (choice & 0xf);
                    int item = (int) (choice >> 4);
                    {if(doLog) {Log.i(LOG_ID,"menu="+menu+" item="+item);};};
                switch(menu) {
                     case 0:
                        switch (item) {
                            case 0: ((MainActivity) getContext()).selectionSystemUI(); break;
                            case 1: Menus.show((MainActivity) getContext());break;
                            case 2: {
                            MainActivity activity = (MainActivity) getContext();
                            if(!isWearable) {
                                tk.glucodata.Watch.show(activity);
                                }
                            else {
                                }
                                tk.glucodata.Display.show(activity);
                                }

                                break;
                              case 3: bluediag.start((MainActivity)getContext()); 
                                  break;
                              case 4: {
                                MainActivity activity = (MainActivity) getContext(); 
                                Settings.set(activity);
                                };break;

                            case 5: {
                                if(!isWearable) {
                                    MainActivity activity = (MainActivity) getContext();
                                    if(SiBionics==1)
                                        PhotoScan.scan(activity,REQUEST_BARCODE);
                                    else
                                        doabout(activity);
                                    }


                                break;
                                }
                                            case 6: ((Activity) getContext()).moveTaskToBack(true);break; //keeps current state 
                                            case 7:  Notify.stopalarm();break;
                                            default:
                                    }

                            break;
             case 1: {
                switch(item&0xF) {
                                    case 0: dialogs.showexport(( MainActivity)getContext(),getWidth(),getHeight(),null); break;


                   case 1: (new Backup()).mkbackupview(( MainActivity)getContext());break;
                case 2: {
                       MainActivity activity = (MainActivity) getContext();
                    if(!allowsLegacyNewRecordCreation(isWearable))
                        return true;
                    if(Natives.staticnum()) {
                  if(isWearable)
                     Specific.blockedNum(activity);
                  else   {
                           activity.themeLightBars();
                            help.help(R.string.staticnum,activity,l->activity.lightBars(!Natives.getInvertColors( ))); 
                            }
                        }
                    else {
                        numberview.addnumberview(activity);
                        if(!smallScreen)
                            showkeyboard(activity);
                        }
                    }; break;
                case 3: getnumcontrol((MainActivity) getContext());return true;
                case 4: Stats.mkstats((MainActivity) getContext());break;
                case 5: tk.glucodata.Talker.config((MainActivity) getContext());break;
                case 6:  Floating.setfloatglucose((MainActivity) getContext(),!Natives.getfloatglucose()) ;break;
                };
                };break;
            case 2: {
                var light=item==0;
                var main=(MainActivity) getContext();
                main.lightBars(light);
                };break;
            case 3:
                switch (item) {
                    case 1: startsearch();
                                    break;
                    case 2:
                                startdatepick(Natives.getstarttime());
                                    break;
                            };break;
                    case 0xe: {
              if(reopennr>0)
                  return true;
                MainActivity act = (MainActivity) getContext();
                               int pos=(int)(choice>>16);
                            int base =(int)((choice>>8)&0xF);
                            {if(doLog) {Log.i(LOG_ID,"tap pos="+pos+" base="+base);};};
                if(numcontrol!=null) hidesave(numcontrol);
                numberview.addnumberview(act, base, pos) ;
                if(!Natives.staticnum()) {
                    if(!smallScreen)
                        numberview.showkeyboard(act);
                    }
                };
                return true;
                        default:
                    }
                }
            requestRender();
            return true;
        }
            return false;
}



        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
//          {if(doLog) {Log.i(LOG_ID,"onScroll dX="+distanceX+" dY="+distanceY);};};
        if(down) {
            if(deferredLongPress) {
                updateDashboardScrub(e2);
                return true;
                }
            if((render.stepresult&STEPBACK)==0)  {
                if(e1.isFromSource(InputDevice.SOURCE_MOUSE) && e1.isButtonPressed(MotionEvent.BUTTON_PRIMARY) && (e1.getMetaState() & KeyEvent.META_CTRL_ON)==KeyEvent.META_CTRL_ON){
                   if(Natives.mouseScale(distanceX,e1.getRawX(), e2.getRawX())!=0)
                        requestRender();
                   }
                else if(isWearable||e1.isFromSource(InputDevice.SOURCE_MOUSE)) {
                    if(Natives.translate(distanceX,distanceY,e1.getRawY(),e2.getRawY())!=0)
                        requestRender();
                    }
                else {
                    final GraphGestureState.Axis axis=
                            graphGestureState.updateSingleFinger(e2.getX(),e2.getY());
                    if(axis==GraphGestureState.Axis.UNDECIDED)
                        return true;
                    final float currentRawY=e2.getRawY();
                    final int translated;
                    if(axis==GraphGestureState.Axis.HORIZONTAL_PAN) {
                        if(distanceX==0.0f)
                            translated=0;
                        else {
                            Natives.lockGraphYRangeForPan();
                            translated=Natives.translate(
                                    distanceX,0.0f,currentRawY,currentRawY);
                            }
                        }
                    else {
                        translated=0;
                        }
                    if(translated!=0)
                        requestRender();
                        }
                    }
            return true;
            }
        return false;
        }


        @Override
        public void onLongPress(MotionEvent event) {
            {if(doLog) {Log.d(LOG_ID,"OnLongPress" + (down?"":" not") + " down");};};
        if(down) {
        long nutime=System.currentTimeMillis();
        if((nutime-multitime)<1000)
            return;
                final float wgrens=smallfontsize*3;
                final float rgrens=getWidth()-wgrens;
                final float x=event.getX();
                final float y=event.getY();
            if(x<wgrens) {
                Natives.prevday(1);
                }
            else if(x>rgrens) {
                Natives.nextday(1);
                }
            else if(beginDashboardScrub(x,y)) {
                return;
                }
            else {
                handleLegacyLongPress(x,y);
                return;
                }
            requestRender();
            }
        }

        private void handleLegacyLongPress(float x,float y) {
            long hitptr=Natives.longpress(x,y);
            if(hitptr!=0) {
                if((hitptr&3)!=0)
                    return;
                if(hitptr==numio.newhit
                        &&!allowsLegacyNewRecordCreation(isWearable))
                    return;
                MainActivity activity=(MainActivity)getContext();
                if(Natives.staticnum()&&hitptr==numio.newhit) {
                    help.help(R.string.staticnum,activity);
                    }
                else {
                    numberview.addnumberview(activity,hitptr);
                    if(!Natives.staticnum()&&!smallScreen)
                        showkeyboard(activity);
                    }
                }
            requestRender();
            }
        @Override
        public boolean    onDoubleTap(MotionEvent e) {
            return handleDashboardDoubleTap(e);
        }

        @Override
        public boolean    onDoubleTapEvent(MotionEvent e) {
            final boolean handled=suppressSingleTapForDoubleTap;
            final int action=e.getActionMasked();
            if(handled&&(action==MotionEvent.ACTION_UP
                    ||action==MotionEvent.ACTION_CANCEL)) {
                post(()->suppressSingleTapForDoubleTap=false);
                }
            return handled;

        }

        @Override
        public   boolean    onSingleTapConfirmed(MotionEvent e) {
            if(!isWearable&&getContext() instanceof MainActivity) {
                MainActivity activity=(MainActivity)getContext();
                if(activity.acceptsDashboardGraphGestures()) {
                    try {
                        int[] eventKeys=Natives.timelineEventsAt(
                                e.getX(),e.getY());
                        if(eventKeys!=null&&eventKeys.length>1) {
                            activity.showIntakeEventCluster(eventKeys);
                            return true;
                        }
                        if(eventKeys!=null&&eventKeys.length==1
                                &&eventKeys[0]!=0) {
                            activity.showIntakeEvent(eventKeys[0]);
                            return true;
                        }
                    } catch(UnsatisfiedLinkError ignored) {
                        // An incremental Java install can briefly run with an
                        // older native library. Keep the established single
                        // marker path available for that safe transition.
                        int eventKey=Natives.timelineEventAt(e.getX(),e.getY());
                        if(eventKey!=0) {
                            activity.showIntakeEvent(eventKey);
                            return true;
                        }
                    }
                }
            }
            return false;
        }
        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {{if(doLog) {Log.d(LOG_ID,"onFling");};};
            // {if(doLog) {Log.i(LOG_ID,"onFling volX="+velocityX+"volY="+velocityY);};};
            if(down) {
                if(deferredLongPress)
                    return true;
                final boolean legacyFling=isWearable
                        ||e1.isFromSource(InputDevice.SOURCE_MOUSE);
                final boolean fling=legacyFling
                        ?Math.abs(velocityX)>2000.0f
                                &&Math.abs(velocityX)>Math.abs(velocityY)
                        :graphGestureState.allowsHorizontalFling(
                                velocityX,velocityY,2000.0f);
                if(fling) {
                    if(!legacyFling)
                        Natives.lockGraphYRangeForPan();
                    Natives.flingX(velocityX);
                    requestRender();
                }
                return true;
            }
    return false;
        }
    };//end class GestureListener 



private int[] minutes={-1,-1};


static String mktime(int hour,int min) {
     if(Applic.hour24)  {
                return String.format(usedlocale,"%02d:%02d", hour, min);
        }
    else {
            var daypart = (hour >= 12)?"pm":"am";
            var hour12 = hour % 12;
            if(hour12 == 0) hour12 = 12;
            return   String.format(usedlocale,"%d:%02d%s", hour12, min,daypart);
        }
    }

private void mktimedialog( Button but,final int num ,View parent) {
        but.setOnClickListener(
                v->  {
        parent.setVisibility(GONE);
        var keys=numberview.keyboard;
        if(keys!=null) {
            keys.setVisibility(INVISIBLE);
            }
        int starthour,startmin;
        if(minutes[num]>=0) {
            starthour=minutes[num]/60;
            startmin=minutes[num]%60;
            }
        else {
            cal.setTimeInMillis(currentTimeMillis());
             starthour=cal.get(Calendar.HOUR_OF_DAY);
             startmin=cal.get(Calendar.MINUTE);
             }
        numberview.gettimepicker((MainActivity)getContext(),starthour, startmin,
        (hour,min) -> {
            TextView text=((TextView) v);
                        text.setText(mktime( hour, min));
//            v.setBackgroundColor(Color.RED);
            text.setTextColor(ClinicalUi.accent(getContext()));
            text.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
            text.setTextSize(COMPLEX_UNIT_PX,oldsize);
                          minutes[num] = hour * 60 + min;
           },()-> {
            parent.setVisibility(VISIBLE);
                        if(keys!=null) {
                            keys.setVisibility(VISIBLE);
                        }


                    });
        });
    }


    EditText under,above;
    Button prev=null,next=null;
    TextView searchValidation=null;
    TextView searchRangeUnitStart=null,searchRangeUnitEnd=null;
    RadioButton searchEarlier=null,searchLater=null;
    ScrollView searchScroll=null;

    static final int SEARCH_INPUT_VALID=0;
    static final int SEARCH_INPUT_EMPTY_LIMIT=1;
    static final int SEARCH_INPUT_INVALID_LIMIT=2;
    static final int SEARCH_INPUT_REVERSED_RANGE=3;
    static final int SEARCH_INPUT_INVALID_AMOUNT=4;
    static final int SEARCH_INPUT_NEGATIVE_AMOUNT=5;
    static final int SEARCH_INPUT_INGREDIENT_REQUIRED=6;
    private static final int SEARCH_INPUT_NO_RESULTS=7;

    private static String normalizedSearchNumber(String value) {
        return value==null?"":value.trim().replace(',','.');
    }

    private static boolean finiteSearchNumber(String value) {
        try {
            float parsed=Float.parseFloat(normalizedSearchNumber(value));
            return !Float.isNaN(parsed)&&!Float.isInfinite(parsed);
        }
        catch(Exception ignored) {
            return false;
        }
    }

    static int validateSearchInput(String below,String above,
            String ingredient,String minimumAmount) {
        String lower=normalizedSearchNumber(below);
        String upper=normalizedSearchNumber(above);
        if(lower.isEmpty()||upper.isEmpty())
            return SEARCH_INPUT_EMPTY_LIMIT;
        if(!finiteSearchNumber(lower)||!finiteSearchNumber(upper))
            return SEARCH_INPUT_INVALID_LIMIT;
        if(Float.parseFloat(lower)>Float.parseFloat(upper))
            return SEARCH_INPUT_REVERSED_RANGE;

        String amount=normalizedSearchNumber(minimumAmount);
        if(!amount.isEmpty()) {
            if(!finiteSearchNumber(amount))
                return SEARCH_INPUT_INVALID_AMOUNT;
            if(Float.parseFloat(amount)<0.0f)
                return SEARCH_INPUT_NEGATIVE_AMOUNT;
            if(ingredient==null||ingredient.trim().isEmpty())
                return SEARCH_INPUT_INGREDIENT_REQUIRED;
        }
        return SEARCH_INPUT_VALID;
    }

    private int searchValidationText(int state) {
        switch(state) {
            case SEARCH_INPUT_EMPTY_LIMIT:
                return R.string.clinical_search_error_limits_required;
            case SEARCH_INPUT_INVALID_LIMIT:
                return R.string.clinical_search_error_limits_number;
            case SEARCH_INPUT_REVERSED_RANGE:
                return R.string.clinical_search_error_range_order;
            case SEARCH_INPUT_INVALID_AMOUNT:
                return R.string.clinical_search_error_amount_number;
            case SEARCH_INPUT_NEGATIVE_AMOUNT:
                return R.string.clinical_search_error_amount_negative;
            case SEARCH_INPUT_INGREDIENT_REQUIRED:
                return R.string.clinical_search_error_ingredient_required;
            case SEARCH_INPUT_NO_RESULTS:
                return R.string.clinical_search_no_results;
            default:
                return 0;
        }
    }

    private void showSearchValidation(int state) {
        if(searchValidation==null)
            return;
        under.setError(null);
        above.setError(null);
        if(mealingredient!=null)
            mealingredient.setError(null);
        if(mealquantity!=null)
            mealquantity.setError(null);
        if(state==SEARCH_INPUT_VALID) {
            searchValidation.setText("");
            searchValidation.setVisibility(GONE);
            return;
        }
        int text=searchValidationText(state);
        searchValidation.setText(text);
        searchValidation.setVisibility(VISIBLE);
        if(state==SEARCH_INPUT_EMPTY_LIMIT||state==SEARCH_INPUT_INVALID_LIMIT
                ||state==SEARCH_INPUT_REVERSED_RANGE) {
            under.setError(getContext().getString(text));
            above.setError(getContext().getString(text));
        }
        else if(state==SEARCH_INPUT_INVALID_AMOUNT||state==SEARCH_INPUT_NEGATIVE_AMOUNT) {
            if(mealquantity!=null)
                mealquantity.setError(getContext().getString(text));
        }
        else if(state==SEARCH_INPUT_INGREDIENT_REQUIRED&&mealingredient!=null)
            mealingredient.setError(getContext().getString(text));
        searchValidation.announceForAccessibility(searchValidation.getText());
        if(searchScroll!=null)
            searchScroll.post(()->searchScroll.smoothScrollTo(
                    0,searchValidation.getBottom()));
    }

    private void updateSearchRangeUnits() {
        if(searchRangeUnitStart==null||searchRangeUnitEnd==null)
            return;
        boolean glucoseSource=(historysearch!=null&&historysearch.isChecked())
                ||(scansearch!=null&&scansearch.isChecked())
                ||(streamsearch!=null&&streamsearch.isChecked())
                ||(streamcalibratedsearch!=null&&streamcalibratedsearch.isChecked())
                ||(historycalibratedsearch!=null&&historycalibratedsearch.isChecked());
        String unit=glucoseSource
                ?(Natives.getunit()==1?"mmol/L":"mg/dL")
                :getContext().getString(R.string.clinical_search_record_amount_unit);
        searchRangeUnitStart.setText(unit);
        searchRangeUnitEnd.setText(unit);
    }

    void searchaway() {
        if(search!=null) {
            search.setVisibility(GONE);
       hidemealsearch();
           hidekeyboard();
           help.hidekeyboard((MainActivity)getContext());
     if(searchcontrol!=null)
         searchcontrol.setVisibility(GONE);
        reopener();
       Natives.stopsearch();
       requestRender();
       }
   }
static void reopener() {
    for(int i=0;i<reopennr;i++)
        reopen[i].setVisibility(VISIBLE);
    reopennr=0;
    }
int labelsel=-1;
void clearsearch(View view) {
if(!isWearable) {
    if(mealquantity!=null) {
         mealquantity.setText("");
         mealingredient.setText("");
         }
      under.setText("0");
        above.setText("999");
        labelsel=searchspinner.getCount()-1;
        searchspinner.setSelection(labelsel);
        scansearch.setChecked(false);
        streamsearch.setChecked(true);
        streamcalibratedsearch.setChecked(false);
        historycalibratedsearch.setChecked(false);
        historysearch.setChecked(false);
        fromtime.setText(R.string.clinical_search_any_time);
        totime.setText(R.string.clinical_search_any_time);
    fromtime.setTextColor(oldColors);
    totime.setTextColor(oldColors);
    totime.setTextSize(COMPLEX_UNIT_PX,oldsize);
    fromtime.setTextSize(COMPLEX_UNIT_PX,oldsize);
        fromtime.setTypeface(null,Typeface.NORMAL);
        totime.setTypeface(null,Typeface.NORMAL);
        minutes[0]=-1;
        minutes[1]=-1;
        if(searchEarlier!=null)
            searchEarlier.setChecked(true);
        updateSearchRangeUnits();
        showSearchValidation(SEARCH_INPUT_VALID);

    under.clearFocus();
    above.clearFocus();
    }
    }
View searchcontrol=null;
//    void search(View view) {
void search(boolean forward) {
    ((MainActivity)getContext()).hideSystemUI();
    if(smallScreen) {
        help.hidekeyboard((MainActivity)getContext());
        }
        boolean useMealConditions=meallayout!=null
                &&meallayout.getVisibility()==VISIBLE;
        String ingredient=!useMealConditions||mealingredient==null?null:
                mealingredient.getText().toString().trim();
        String amount=!useMealConditions||mealquantity==null?null:
                mealquantity.getText().toString();
        int validation=validateSearchInput(under.getText().toString(),
                above.getText().toString(),ingredient,amount);
        if(validation!=SEARCH_INPUT_VALID) {
            showSearchValidation(validation);
            return;
        }
        showSearchValidation(SEARCH_INPUT_VALID);
        float funder=Float.parseFloat(normalizedSearchNumber(under.getText().toString()));
        float fabove=Float.parseFloat(normalizedSearchNumber(above.getText().toString()));
        float ingamount=amount==null||amount.trim().isEmpty()?0.0f:
                Float.parseFloat(normalizedSearchNumber(amount));
        String ingsearch=ingredient==null||ingredient.isEmpty()?null:ingredient;
        int glsearch=((historysearch.isChecked()?0x40000002:0)| (scansearch.isChecked()?0x40000001:0))|(streamsearch.isChecked()?0x40000004:0)| (streamcalibratedsearch.isChecked()?0x40000008:0)|(historycalibratedsearch.isChecked()?0x40000010:0);


       if(Natives.search(glsearch==0?labelsel:glsearch,funder,fabove,minutes[0],minutes[1],forward,ingsearch,ingamount)==0) {

           search.setVisibility(GONE);
           hidemealsearch();
           hidekeyboard();
           help.hidekeyboard((MainActivity)getContext());
           requestRender();
            MainActivity activity=(MainActivity)getContext();
        activity.poponback();
           if(searchcontrol==null) {
               prev=ClinicalUi.button(activity,"\u2039",ClinicalUi.ButtonRole.SECONDARY);
               prev.setContentDescription(activity.getString(
                       R.string.clinical_search_previous_result));
               prev.setOnClickListener(v-> {
                   if (0 == Natives.earliersearch()) {
                       next.setVisibility(VISIBLE);
                        requestRender();
                     }
                    else {
                       if (next.getVisibility() != VISIBLE)
                           searchaway();
                       else
                           v.setVisibility(INVISIBLE);
                    }
            });

               next=ClinicalUi.button(activity,"\u203a",ClinicalUi.ButtonRole.SECONDARY);
               next.setContentDescription(activity.getString(
                       R.string.clinical_search_next_result));
               next.setOnClickListener(v-> {if(0== Natives.latersearch()) {
                   prev.setVisibility(VISIBLE);
                   requestRender();
               }else
                   {
                    if(prev.getVisibility()!=VISIBLE)
                        searchaway();
                    else
                                v.setVisibility(INVISIBLE);
                   }
               });
         Button closecontrol=ClinicalUi.button(activity,"\u00d7",
                 ClinicalUi.ButtonRole.SECONDARY);
         closecontrol.setContentDescription(activity.getString(
                 R.string.clinical_search_close_results));
              closecontrol.setOnClickListener(v-> {
              activity.poponback();
                    searchaway();
                      });
        int resultTarget=ClinicalUi.dp(activity,52);
        prev.setMinWidth(resultTarget);
        next.setMinWidth(resultTarget);
        closecontrol.setMinWidth(resultTarget);
        LinearLayout bar=new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(ClinicalUi.dp(activity,6),ClinicalUi.dp(activity,6),
                ClinicalUi.dp(activity,6),ClinicalUi.dp(activity,6));
        bar.setBackground(ClinicalUi.surface(activity,true,false));
        bar.addView(prev,new LinearLayout.LayoutParams(resultTarget,resultTarget));
        TextView resultTitle=ClinicalUi.body(activity,
                activity.getString(R.string.clinical_search_result));
        resultTitle.setTextColor(ClinicalUi.primaryText(activity));
        resultTitle.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        resultTitle.setGravity(Gravity.CENTER);
        bar.addView(resultTitle,new LinearLayout.LayoutParams(
                0,resultTarget,1.0f));
        bar.addView(next,new LinearLayout.LayoutParams(resultTarget,resultTarget));
        LinearLayout.LayoutParams closeParams=new LinearLayout.LayoutParams(
                resultTarget,resultTarget);
        closeParams.setMarginStart(ClinicalUi.dp(activity,6));
        bar.addView(closecontrol,closeParams);

        FrameLayout resultOverlay=new FrameLayout(activity);
        resultOverlay.setClipChildren(false);
        resultOverlay.setClipToPadding(false);
        resultOverlay.setPadding(systembarLeft+ClinicalUi.dp(activity,16),
                systembarTop+ClinicalUi.dp(activity,10),
                systembarRight+ClinicalUi.dp(activity,16),0);
        FrameLayout.LayoutParams barParams=new FrameLayout.LayoutParams(
                MATCH_PARENT,WRAP_CONTENT,Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        resultOverlay.addView(bar,barParams);
        searchcontrol=resultOverlay;
        activity.addMyContentView(searchcontrol,
                new ViewGroup.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
           searchcontrol.post(searchcontrol::requestLayout);
            }

           prev.setVisibility(VISIBLE);
           next.setVisibility(VISIBLE);
           searchcontrol.setVisibility(VISIBLE);
           activity.setonback(this::searchaway);
           }
           else
               showSearchValidation(SEARCH_INPUT_NO_RESULTS);
        //((MainActivity)getContext()).curve.requestRender();
}

 
// Disable spell check (hex strings look like words to Android)

//CheckDirectionRadio numbers;

    CheckDirectionBox scansearch,historysearch,streamsearch,streamcalibratedsearch, historycalibratedsearch;

 

    Button fromtime, totime;

//https://gist.github.com/kakajika/a236ba721a5c0ad3c1446e16a7423a63
    /*
void radiolisten( CheckDirectionRadio one,CheckDirectionRadio other) {
         one.setOnClickListener(v-> {
             ((CheckDirectionRadio) v).setChecked(true);
             other.setChecked(false);
             if(numbers.isChecked())
                 spinner.setVisibility(VISIBLE);
             else
                 spinner.setVisibility(GONE);

         });
}
*/
void selectnumbers() {
            scansearch.setChecked(false);
            historysearch.setChecked(false);
            streamsearch.setChecked(false);
            streamcalibratedsearch.setChecked(false);
            historycalibratedsearch.setChecked(false);
       //     spinner.setVisibility(VISIBLE);
        }
void glucoselisten(CompoundButton one) {
    one.setOnClickListener(v -> {
        if(historysearch.isChecked()||scansearch.isChecked()||streamsearch.isChecked()||streamcalibratedsearch.isChecked() ||historycalibratedsearch.isChecked()) {

            labelsel=searchspinner.getCount()-1;
            searchspinner.setSelection(labelsel);
            }
        updateSearchRangeUnits();
        showSearchValidation(SEARCH_INPUT_VALID);
    });
}
Spinner searchspinner;
LabelAdapter<String> searchspinadap;
Spinner getsearchspinner(MainActivity context) {
if(searchspinner==null) {
    /*
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        searchspinner=new Spinner(context, null,0,R.style.MySpinnerStyle , -1);
    }
    else */
        searchspinner=new Spinner(context);
//        searchspinner=new Spinner(context, null,R.style.MySpinnerStyle );
//        searchspinner=new Spinner(context,R.style.spinner_style);
//        searchspinner=new Spinner(context,R.style.MySpinnerStyle2);
/*
    int minheight=GlucoseCurve.dpToPx(48);
    searchspinner.setMinimumHeight(minheight);
*/
    searchspinner.setContentDescription(context.getString(
            R.string.clinical_search_record_type));
    searchspinner.setMinimumHeight(ClinicalUi.dp(context,52));
    searchspinner.setPaddingRelative(ClinicalUi.dp(context,12),0,
            ClinicalUi.dp(context,12),0);
    searchspinner.setBackground(ClinicalUi.surface(context,false,true));
   NumberView.avoidSpinnerDropdownFocus(searchspinner);
    searchspinadap= new LabelAdapter<String>(context,Natives.getLabels(),0);
        searchspinner.setAdapter(searchspinadap);
    searchspinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
        @Override
        public  void onItemSelected (AdapterView<?> parent, View view, int position, long id) {
       {if(doLog) {Log.i(LOG_ID,"onItemSelected "+position);};};
            if(position!=(searchspinner.getCount()-1)) {
                selectnumbers();
        if(position==Natives.getmealvar()) {
            mkmealsearch(context);
            }
        else
            hidemealsearch();
        }
        else
        hidemealsearch();
         labelsel=position;
         updateSearchRangeUnits();
         showSearchValidation(SEARCH_INPUT_VALID);
        }
        @Override
        public  void onNothingSelected (AdapterView<?> parent) {
            labelsel=searchspinner.getCount()-1;

        } });
    searchspinner.clearAnimation();
    }
return searchspinner;

}
ColorStateList oldColors;
float oldsize;
LinearLayout meallayout=null;
void hidemealsearch() {
    if(meallayout!=null) 
        meallayout.setVisibility(GONE);
    }
EditText mealingredient=null,mealquantity=null;

private void styleSearchEdit(MainActivity context,EditText edit,int minWidthDp) {
    edit.setSingleLine(true);
    edit.setTextColor(ClinicalUi.primaryText(context));
    edit.setHintTextColor(ClinicalUi.secondaryText(context));
    edit.setTextSize(TypedValue.COMPLEX_UNIT_SP,16);
    edit.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);
    edit.setMinWidth(ClinicalUi.dp(context,minWidthDp));
    edit.setMinimumHeight(ClinicalUi.dp(context,52));
    edit.setPaddingRelative(ClinicalUi.dp(context,12),0,
            ClinicalUi.dp(context,12),0);
    edit.setBackground(ClinicalUi.surface(context,false,true));
}

private TextView searchUnit(MainActivity context,CharSequence text) {
    TextView unit=ClinicalUi.body(context,text);
    unit.setTextColor(ClinicalUi.secondaryText(context));
    unit.setGravity(Gravity.CENTER_VERTICAL);
    unit.setPaddingRelative(ClinicalUi.dp(context,9),0,
            ClinicalUi.dp(context,4),0);
    unit.setMinWidth(ClinicalUi.dp(context,58));
    return unit;
}

private void styleSearchSource(MainActivity context,CheckDirectionBox source) {
    source.setTextColor(ClinicalUi.primaryText(context));
    source.setTextSize(TypedValue.COMPLEX_UNIT_SP,15);
    source.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);
    source.setMinimumHeight(ClinicalUi.dp(context,58));
    source.setPaddingRelative(ClinicalUi.dp(context,14),ClinicalUi.dp(context,6),
            ClinicalUi.dp(context,12),ClinicalUi.dp(context,6));
    source.setButtonTintList(new ColorStateList(
            new int[][]{
                    new int[]{android.R.attr.state_checked},
                    new int[]{}
            },
            new int[]{ClinicalUi.accent(context),ClinicalUi.secondaryText(context)}));
}

void mkmealsearch(MainActivity act) {
    if(meallayout==null) {
        mealingredient=new EditText(act);
        mealingredient.setInputType(InputType.TYPE_CLASS_TEXT
                |InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        mealingredient.setImeOptions(editoptions);
        mealingredient.setOnFocusChangeListener(new editUIfocus());
        mealingredient.setHint(R.string.clinical_search_ingredient_hint);
        styleSearchEdit(act,mealingredient,156);

        mealquantity=new EditText(act);
        mealquantity.setInputType(InputType.TYPE_CLASS_NUMBER
                |InputType.TYPE_NUMBER_FLAG_DECIMAL);
        mealquantity.setImeOptions(editoptions);
        mealquantity.setHint(R.string.clinical_search_optional);
        styleSearchEdit(act,mealquantity,92);

        TextView amountUnit=searchUnit(act,
                act.getString(R.string.clinical_search_ingredient_unit));
        LinearLayout ingredientRow=ClinicalUi.fieldRow(act,
                act.getString(R.string.clinical_search_ingredient),mealingredient);
        LinearLayout quantityRow=ClinicalUi.fieldRow(act,
                act.getString(R.string.clinical_search_minimum_amount),
                mealquantity,amountUnit);

        meallayout=new LinearLayout(act);
        meallayout.setOrientation(LinearLayout.VERTICAL);
        meallayout.addView(ClinicalUi.sectionLabel(act,
                act.getString(R.string.clinical_search_meal_section)));
        meallayout.addView(ClinicalUi.card(act,ingredientRow,quantityRow));
        }
    else {
        meallayout.setVisibility(VISIBLE);
        }
    showSearchValidation(SEARCH_INPUT_VALID);
    }

private ViewGroup getsearchlayout(MainActivity context) {
    if(isWearable)
        return null;

    editfocus focus=new editfocus();
    under=new EditText(context);
    above=new EditText(context);
    under.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
    above.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
    under.setImeOptions(editoptions);
    above.setImeOptions(editoptions);
    under.setOnFocusChangeListener(focus);
    above.setOnFocusChangeListener(focus);
    under.setHint(R.string.clinical_search_lower_hint);
    above.setHint(R.string.clinical_search_upper_hint);
    styleSearchEdit(context,under,92);
    styleSearchEdit(context,above,92);

    scansearch=new CheckDirectionBox(context);
    scansearch.setText(R.string.scansname);
    final String historystr=context.getString(R.string.historyname);
    final String streamstr=context.getString(R.string.streamname);
    final String calibrated=context.getString(R.string.calibrated);
    historysearch=new CheckDirectionBox(context);
    historysearch.setText(historystr);
    streamsearch=new CheckDirectionBox(context);
    streamsearch.setText(streamstr);
    streamcalibratedsearch=new CheckDirectionBox(context);
    streamcalibratedsearch.setText(streamstr+" "+calibrated);
    historycalibratedsearch=new CheckDirectionBox(context);
    historycalibratedsearch.setText(historystr+" "+calibrated);
    CheckDirectionBox[] sourceChecks={scansearch,historysearch,
            historycalibratedsearch,streamsearch,streamcalibratedsearch};
    for(CheckDirectionBox source:sourceChecks) {
        styleSearchSource(context,source);
        glucoselisten(source);
    }

    Spinner type=getsearchspinner(context);
    type.setMinimumWidth(ClinicalUi.dp(context,184));

    searchRangeUnitStart=searchUnit(context,"");
    searchRangeUnitEnd=searchUnit(context,"");
    LinearLayout lowerRow=ClinicalUi.fieldRow(context,
            context.getString(R.string.clinical_search_lower_value),
            under,searchRangeUnitStart);
    LinearLayout upperRow=ClinicalUi.fieldRow(context,
            context.getString(R.string.clinical_search_upper_value),
            above,searchRangeUnitEnd);

    fromtime=ClinicalUi.button(context,
            context.getString(R.string.clinical_search_any_time),
            ClinicalUi.ButtonRole.SECONDARY);
    totime=ClinicalUi.button(context,
            context.getString(R.string.clinical_search_any_time),
            ClinicalUi.ButtonRole.SECONDARY);
    fromtime.setMinWidth(ClinicalUi.dp(context,120));
    totime.setMinWidth(ClinicalUi.dp(context,120));
    oldColors=totime.getTextColors();
    oldsize=totime.getTextSize();
    LinearLayout fromRow=ClinicalUi.fieldRow(context,
            context.getString(R.string.clinical_search_from_time),fromtime);
    LinearLayout toRow=ClinicalUi.fieldRow(context,
            context.getString(R.string.clinical_search_to_time),totime);

    mkmealsearch(context);
    hidemealsearch();

    searchEarlier=new RadioButton(context);
    searchEarlier.setId(View.generateViewId());
    searchEarlier.setText(R.string.clinical_search_earlier);
    searchLater=new RadioButton(context);
    searchLater.setId(View.generateViewId());
    searchLater.setText(R.string.clinical_search_later);
    RadioButton[] directions={searchEarlier,searchLater};
    for(RadioButton direction:directions) {
        direction.setTextColor(ClinicalUi.primaryText(context));
        direction.setTextSize(TypedValue.COMPLEX_UNIT_SP,15);
        direction.setGravity(Gravity.CENTER);
        direction.setMinimumHeight(ClinicalUi.dp(context,52));
        direction.setButtonTintList(new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{}
                },
                new int[]{ClinicalUi.accent(context),
                        ClinicalUi.secondaryText(context)}));
    }
    RadioGroup directionGroup=new RadioGroup(context);
    directionGroup.setOrientation(RadioGroup.HORIZONTAL);
    directionGroup.setGravity(Gravity.CENTER_VERTICAL);
    directionGroup.setPaddingRelative(ClinicalUi.dp(context,8),
            ClinicalUi.dp(context,3),ClinicalUi.dp(context,8),
            ClinicalUi.dp(context,3));
    directionGroup.addView(searchEarlier,new RadioGroup.LayoutParams(
            0,WRAP_CONTENT,1.0f));
    directionGroup.addView(searchLater,new RadioGroup.LayoutParams(
            0,WRAP_CONTENT,1.0f));

    searchValidation=ClinicalUi.body(context,"");
    searchValidation.setTextColor(ClinicalUi.danger(context));
    searchValidation.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
    searchValidation.setPadding(ClinicalUi.dp(context,14),
            ClinicalUi.dp(context,11),ClinicalUi.dp(context,14),
            ClinicalUi.dp(context,11));
    searchValidation.setBackground(ClinicalUi.surface(context,false,false));
    searchValidation.setVisibility(GONE);
    searchValidation.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);

    LinearLayout content=ClinicalUi.verticalContent(context);
    content.setPadding(0,0,0,ClinicalUi.dp(context,24));
    TextView intro=ClinicalUi.body(context,
            context.getString(R.string.clinical_search_intro));
    intro.setPaddingRelative(ClinicalUi.dp(context,4),0,
            ClinicalUi.dp(context,4),ClinicalUi.dp(context,5));
    content.addView(intro);

    content.addView(ClinicalUi.sectionLabel(context,
            context.getString(R.string.clinical_search_type_section)));
    content.addView(ClinicalUi.card(context,ClinicalUi.fieldRow(context,
            context.getString(R.string.clinical_search_record_type),type)));

    content.addView(ClinicalUi.sectionLabel(context,
            context.getString(R.string.clinical_search_sources_section)));
    TextView sourcesHint=ClinicalUi.body(context,
            context.getString(R.string.clinical_search_sources_hint));
    sourcesHint.setPaddingRelative(ClinicalUi.dp(context,4),0,
            ClinicalUi.dp(context,4),ClinicalUi.dp(context,9));
    content.addView(sourcesHint);
    content.addView(ClinicalUi.card(context,scansearch,historysearch,
            historycalibratedsearch,streamsearch,streamcalibratedsearch));

    content.addView(ClinicalUi.sectionLabel(context,
            context.getString(R.string.clinical_search_range_section)));
    content.addView(ClinicalUi.card(context,lowerRow,upperRow));

    content.addView(ClinicalUi.sectionLabel(context,
            context.getString(R.string.clinical_search_time_section)));
    content.addView(ClinicalUi.card(context,fromRow,toRow));
    content.addView(meallayout);

    content.addView(ClinicalUi.sectionLabel(context,
            context.getString(R.string.clinical_search_direction_section)));
    TextView directionHint=ClinicalUi.body(context,
            context.getString(R.string.clinical_search_direction_hint));
    directionHint.setPaddingRelative(ClinicalUi.dp(context,4),0,
            ClinicalUi.dp(context,4),ClinicalUi.dp(context,9));
    content.addView(directionHint);
    content.addView(ClinicalUi.card(context,directionGroup));
    LinearLayout.LayoutParams validationParams=new LinearLayout.LayoutParams(
            MATCH_PARENT,WRAP_CONTENT);
    validationParams.topMargin=ClinicalUi.dp(context,12);
    content.addView(searchValidation,validationParams);

    ScrollView scroll=ClinicalUi.scrollScreen(context,content);
    searchScroll=scroll;
    LinearLayout.LayoutParams scrollParams=new LinearLayout.LayoutParams(
            MATCH_PARENT,0,1.0f);

    Button back=ClinicalUi.button(context,"\u2039",
            ClinicalUi.ButtonRole.SECONDARY);
    back.setContentDescription(context.getString(R.string.clinical_search_back));
    back.setOnClickListener(v->context.doonback());
    Button helpButton=ClinicalUi.button(context,"?",
            ClinicalUi.ButtonRole.SECONDARY);
    helpButton.setContentDescription(context.getString(
            R.string.clinical_search_help));
    helpButton.setOnClickListener(v->{
        context.themeLightBars();
        help.help(R.string.searchhelp,context,
                l->context.lightBars(!getInvertColors()));
    });
    LinearLayout header=new LinearLayout(context);
    header.setOrientation(LinearLayout.HORIZONTAL);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setMinimumHeight(ClinicalUi.dp(context,72));
    int headerTarget=ClinicalUi.dp(context,52);
    header.addView(back,new LinearLayout.LayoutParams(
            headerTarget,headerTarget));
    TextView title=ClinicalUi.title(context,
            context.getString(R.string.clinical_search_title));
    LinearLayout.LayoutParams titleParams=new LinearLayout.LayoutParams(
            0,WRAP_CONTENT,1.0f);
    titleParams.setMarginStart(ClinicalUi.dp(context,12));
    header.addView(title,titleParams);
    header.addView(helpButton,new LinearLayout.LayoutParams(
            headerTarget,headerTarget));

    Button clear=ClinicalUi.button(context,
            context.getString(R.string.clinical_search_reset),
            ClinicalUi.ButtonRole.SECONDARY);
    Button searchAction=ClinicalUi.button(context,
            context.getString(R.string.clinical_search_action),
            ClinicalUi.ButtonRole.PRIMARY);
    searchAction.setContentDescription(context.getString(
            R.string.clinical_search_action));
    LinearLayout footer=new LinearLayout(context);
    footer.setOrientation(LinearLayout.HORIZONTAL);
    footer.setGravity(Gravity.CENTER_VERTICAL);
    footer.setPadding(0,ClinicalUi.dp(context,10),0,
            ClinicalUi.dp(context,4));
    footer.addView(clear,new LinearLayout.LayoutParams(
            0,WRAP_CONTENT,0.9f));
    LinearLayout.LayoutParams actionParams=new LinearLayout.LayoutParams(
            0,WRAP_CONTENT,1.1f);
    actionParams.setMarginStart(ClinicalUi.dp(context,12));
    footer.addView(searchAction,actionParams);

    LinearLayout screen=new LinearLayout(context);
    screen.setOrientation(LinearLayout.VERTICAL);
    screen.setBackgroundColor(ClinicalUi.window(context));
    screen.setPadding(systembarLeft+ClinicalUi.dp(context,18),
            systembarTop+ClinicalUi.dp(context,6),
            systembarRight+ClinicalUi.dp(context,18),
            systembarBottom+ClinicalUi.dp(context,8));
    screen.addView(header,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
    screen.addView(scroll,scrollParams);
    screen.addView(footer,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));

    mktimedialog(fromtime,0,screen);
    mktimedialog(totime,1,screen);
    clear.setOnClickListener(this::clearsearch);
    searchAction.setOnClickListener(v->search(searchLater.isChecked()));

    context.addMyContentView(screen,
            new ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT));
    clearsearch(clear);
    hidemealsearch();
    screen.post(screen::requestLayout);
    return screen;
}


//Editable edit;



@Override
public void onResume() {
    {if(doLog) {Log.i(LOG_ID,"onResume()");};};
    super.onResume();
    Applic app = Applic.app;

    app.setcurve(this);
    app.setmintime();

    if(!isWearable) {
       if(SiBionics==1)  {
            if(MainActivity.tocalendarapp) {
                final String name=Natives.getUsedSensorName();
                if(name!=null) {
                    ScanNfcV.calendar((MainActivity)getContext(), 0, name);
                    MainActivity.tocalendarapp=false;
                    }
                }
            }
       }
    }

@Override
public void onPause() {
    {if(doLog) {Log.i(LOG_ID,"onPause()");};};
     Applic app = Applic.app;
     app.cancelmintime();
     app.setcurve(null);
     super.onPause();
    }
@Override
public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
    {if(doLog) {Log.i(LOG_ID,"surfaceChanged format="+format+", width="+w+", height="+h);};};
    super.surfaceChanged(holder,format,w,h);
    }
@Override
public void surfaceCreated(SurfaceHolder holder) {
    {if(doLog) {Log.i(LOG_ID,"surfaceCreated(SurfaceHolder holder)");};};
    super.surfaceCreated(holder);
    }
@Override
public void surfaceDestroyed(SurfaceHolder holder) {
   {if(doLog) {Log.i(LOG_ID,"surfaceDestroyed(SurfaceHolder holder)");};};
   super.surfaceDestroyed(holder);
    }
static public void    doabout(MainActivity activity) {
if(!isWearable) {
    String about=activity.getString(R.string.about)+"<p>Version Code: "+ BuildConfig.VERSION_CODE+"<br>Version Name: "+ 
        BuildConfig.VERSION_NAME +"<br>"+Natives.getCPUarch()+"<br>Build time: "+ BuildConfig.BUILD_TIME +"</p>";
    
    help.help(about, activity,l->{});
    }
    }
void removeviews() {
        numberview.deleteviews();    
        searchspinner=null;
        if(search!=null) {
            removeContentView(search);
            search=null;
            meallayout=null;
            mealingredient=null;
            mealquantity=null;
            searchValidation=null;
            searchRangeUnitStart=null;
            searchRangeUnitEnd=null;
            searchEarlier=null;
            searchLater=null;
            searchScroll=null;
            }
        if(searchcontrol!=null) {
            removeContentView(searchcontrol);
            searchcontrol=null;
            }
       Applic.setremoveviews=false;
       }
}
