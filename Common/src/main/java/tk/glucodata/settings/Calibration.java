
package tk.glucodata.settings;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static tk.glucodata.Applic.backgroundcolor;
import static tk.glucodata.Applic.isWearable;
import static tk.glucodata.Natives.setshowcalibratedstream;
import static tk.glucodata.Natives.setshowstream;
import static tk.glucodata.NumberView.avoidSpinnerDropdownFocus;
import static tk.glucodata.settings.Settings.removeContentView;
import static tk.glucodata.util.getbutton;
import static tk.glucodata.util.getcheckbox;
import static tk.glucodata.util.getlabel;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;

import tk.glucodata.Applic;
import tk.glucodata.ClinicalUi;
import tk.glucodata.GlucoseCurve;
import tk.glucodata.LabelAdapter;
import tk.glucodata.Layout;
import tk.glucodata.MainActivity;
import tk.glucodata.Natives;
import tk.glucodata.R;

public class Calibration  {


static public void show(MainActivity act,View parent) {
    if(parent!=null)  {
           parent.setVisibility(GONE);
           if(!isWearable)
                    act.lightBars(!Natives.getInvertColors());
            }
    Spinner spinner=new Spinner(act);
    avoidSpinnerDropdownFocus(spinner);
    LabelAdapter<String> numspinadapt=new LabelAdapter<String>(act, Natives.getLabels(),0);
    spinner.setAdapter(numspinadapt);
    spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
        @Override
        public  void onItemSelected (AdapterView<?> parent, View view, int position, long id) {
            Natives.setbloodvar((byte)position);
            }
        @Override
        public  void onNothingSelected (AdapterView<?> parent) {

        } });
   spinner.setSelection(Natives.getbloodvar());
   var bloodvar=getlabel(act,R.string.bloodvar);
    float density=GlucoseCurve.metrics.density;
   bloodvar.setPaddingRelative((int)(density*10),0,(int)(4*density),0);
   var close=isWearable?getbutton(act, R.string.closename):ClinicalUi.button(
           act,act.getString(R.string.closename),ClinicalUi.ButtonRole.SECONDARY);
   var allvalues=getcheckbox(act,act.getString(R.string.allvalues),Natives.getAllValues());
   allvalues.setOnCheckedChangeListener( (buttonView,  isChecked)-> {
          Natives.setAllValues(isChecked);
          act.requestRender();
          });
   var docalibrate=getcheckbox(act,act.getString(R.string.active),Natives.getDoCalibrate());
   docalibrate.setOnCheckedChangeListener( (buttonView,  isChecked)-> {
          Natives.setDoCalibrate(isChecked);
          if(isChecked) {
            setshowcalibratedstream(true);
            }
          act.requestRender();
          });
   var calibratepast=getcheckbox(act,act.getString(R.string.calibratepast),Natives.getCalibratePast());
   calibratepast.setOnCheckedChangeListener( (buttonView,  isChecked)-> {
          Natives.setCalibratePast(isChecked);
          act.requestRender();
          });
   var calibrateA=getcheckbox(act,act.getString(R.string.calibrate_a),Natives.getCalibrateA());
   calibrateA.setOnCheckedChangeListener( (buttonView,  isChecked)-> {
          Natives.setCalibrateA(isChecked);
          });
  if(!isWearable) {
        LinearLayout content=ClinicalUi.verticalContent(act);
        content.setPadding(
                MainActivity.systembarLeft+ClinicalUi.dp(act,20),
                MainActivity.systembarTop+ClinicalUi.dp(act,8),
                MainActivity.systembarRight+ClinicalUi.dp(act,20),
                MainActivity.systembarBottom+ClinicalUi.dp(act,28));
        content.addView(ClinicalUi.header(act,
                act.getString(R.string.calibration),close));

        content.addView(ClinicalUi.sectionLabel(act,
                act.getString(R.string.bloodvar)));
        content.addView(ClinicalUi.card(act,
                ClinicalUi.fieldRow(act,act.getString(R.string.bloodvar),spinner)));

        content.addView(ClinicalUi.sectionLabel(act,
                act.getString(R.string.calibration)));
        content.addView(ClinicalUi.card(act,
                ClinicalUi.toggleRow(act,docalibrate,null),
                ClinicalUi.toggleRow(act,calibratepast,null),
                ClinicalUi.toggleRow(act,allvalues,null),
                ClinicalUi.toggleRow(act,calibrateA,null)));

        LinearLayout help=ClinicalUi.actionRow(act,
                act.getString(R.string.helpname),null);
        help.setOnClickListener(v->tk.glucodata.help.help(
                R.string.calibrationhelp,act));
        content.addView(ClinicalUi.sectionLabel(act,
                act.getString(R.string.helpname)));
        content.addView(ClinicalUi.card(act,help));

        ScrollView screen=ClinicalUi.scrollScreen(act,content);
        act.addMyContentView(screen,new ViewGroup.LayoutParams(
                MATCH_PARENT,MATCH_PARENT));
        MainActivity.setonback(()-> {
            if(parent!=null) {
                parent.setVisibility(VISIBLE);
                act.themeLightBars();
                }
            removeContentView(screen);
            });
        close.setOnClickListener(v->MainActivity.doonback());
        return;
        }

  View[][] views;
  {
        var buttons=new View[]{docalibrate,close};
        views=new View[][]{new View[]{calibrateA},
       new View[]{bloodvar,spinner},buttons,new View[]{calibratepast},new View[]{allvalues}} ;
    }
    ViewGroup  layout=new Layout(act, (lay, w, h) -> { return new int[] {w,h};},views );
  if(isWearable) {
    layout.setBackgroundColor(backgroundcolor);
    var height=    GlucoseCurve.getheight();
//    layout.setPadding((int)(height*.02f),(int)(height*.11f), (int)(height*.05f), (int)(height*.11f));
    layout.setPaddingRelative((int)(height*.02f),(int)(height*.17f), (int)(height*.05f), 0);
    //Layout.getMargins(allvalues);
    //pasmarg.leftMargin=(int)(height*.07f);
    var pasmarg=Layout.getMargins(allvalues);
    pasmarg.bottomMargin=(int)(height*.14f);
        var scroll=new ScrollView(act);
        scroll.addView(layout);
        scroll.setFillViewport(true);
        scroll.setSmoothScrollingEnabled(false);
       scroll.setScrollbarFadingEnabled(true);
       scroll.setVerticalScrollBarEnabled(Applic.scrollbar);


    act.addMyContentView(scroll, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
    layout=scroll;

  }
  else {
     layout.setBackgroundResource(R.drawable.dialogbackground);
    var  params =    new FrameLayout.LayoutParams( WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER| Gravity.CENTER_HORIZONTAL);

    act.addMyContentView(layout, params);
    int pad=(int)(density*5.0f);
    layout.setPadding(pad,pad,pad,pad);

    }
    final ViewGroup flayout=layout;
    MainActivity.setonback( () -> {
      if(parent!=null)  {
           parent.setVisibility(VISIBLE);
           if(!isWearable)
                    act.themeLightBars();
            }
        removeContentView(flayout);
        });

    close.setOnClickListener(v->{
    	MainActivity.doonback();
    	});
   }


};
