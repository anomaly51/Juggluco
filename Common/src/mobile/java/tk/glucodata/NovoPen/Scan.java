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
/*      Sun Apr 16 20:56:20 CEST 2023                                                 */


package tk.glucodata.NovoPen;

import static android.graphics.Typeface.BOLD;
import static android.graphics.Typeface.DEFAULT_BOLD;
import static android.view.View.INVISIBLE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static tk.glucodata.Dialogs.fdatename;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.Natives.novopentype;
import static tk.glucodata.Natives.savenovopen;
import static tk.glucodata.Natives.setnovopenttimeandtype;
import static tk.glucodata.Log.showbytes;
import static tk.glucodata.NumberView.avoidSpinnerDropdownFocus;
import static tk.glucodata.ScanNfcV.failure;
import static tk.glucodata.ScanNfcV.getvibrator;
import static tk.glucodata.ScanNfcV.startvibration;
import static tk.glucodata.settings.Settings.removeContentView;
import static tk.glucodata.util.getbutton;
import static tk.glucodata.util.getlabel;

import android.view.Gravity;
import android.widget.FrameLayout;
import androidx.appcompat.app.AlertDialog;
import android.content.DialogInterface;
import android.nfc.Tag;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.Spinner;

import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

import tk.glucodata.NovoPen.opennov.OpContext;
import tk.glucodata.NovoPen.opennov.OpenNov;

import tk.glucodata.Applic;
import tk.glucodata.ClinicalUi;
import tk.glucodata.GlucoseCurve;
import tk.glucodata.LabelAdapter;
import tk.glucodata.Layout;
import tk.glucodata.Log;
import tk.glucodata.MainActivity;
import tk.glucodata.Natives;
import tk.glucodata.R;
import tk.glucodata.help;

//import android.nfc.Tag;

public class Scan {
    static final private String LOG_ID="Scan";
    static public void onTag(MainActivity act, Tag tag) {
        {if(doLog){showbytes("onTag", tag.getId());};}
        var vibrator = getvibrator(act);
        startvibration(vibrator);
        var openNov = new OpenNov();
        var op = openNov.processTag(tag);
        vibrator.cancel();
        if (op == null) {
            failure(vibrator);
            final var failread = act.getString(R.string.penfailed);
            Log.e(LOG_ID, "processTag failed");
        } else {
            if (op.specification == null) {
                Log.e(LOG_ID, "op.specification==null");
            } else {
                    if (op.doses == null) {
                        Log.e(LOG_ID, "op.eventReport.doses==null");
                    } else {
                        Applic.RunOnUiThread(() -> setInsulin(act, op));
                        return;
                }
            }
        }

        final var failread =act.getString(R.string.penfailed);
        Applic.Toaster(failread);
    }
    private static void earlytimeconfirmation(MainActivity act) {
        AlertDialog.Builder builder = new AlertDialog.Builder(act);
        builder.setTitle("To get older values you have to scan again").
           setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                    }
                }) .show().setCanceledOnTouchOutside(false);
    }
private static void changeTypeconfirmation(MainActivity act,String type,Runnable save) {
        AlertDialog.Builder builder = new AlertDialog.Builder(act);
        builder.setTitle(act.getString(R.string.changeinsulin)+type+"?").
           setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                save.run();
                    }
                }) .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
            }
        }).show().setCanceledOnTouchOutside(false);
    }
//static  public final DateFormat fhourmin=             new SimpleDateFormat("HH:mm", Locale.US);

static void setInsulin(MainActivity context, OpContext op) {
    if(Natives.staticnum()) {
            help.help(R.string.staticnum,context);
            return;
           }
 
    String serial=op.specification.getSerial();
    final long typetime =novopentype(serial);
    int type;
    long lasttime;
    if(typetime!=-1L) {
        type=(int)(typetime>>32);
        long sectime=(typetime&0xFFFFFFFFL);
        lasttime=sectime*1000L;
        final String datestr=fdatename.format(lasttime)      ;
        {if(doLog) {Log.i(LOG_ID,"type= "+type+" Last time: "+String.format("typetime=%X seconds=%X %d",typetime,sectime,sectime)+ " "+datestr);};};
        }
    else {
        type=-1;
        lasttime=0L;
        }

    Spinner spinner=new Spinner(context);
//    final int minheight= GlucoseCurve.dpToPx(48);
//    spinner.setMinimumHeight(minheight);
    avoidSpinnerDropdownFocus(spinner);
    int[] selected={type<0?0:type};
    final var labels=Natives.getLabels();
    LabelAdapter<String> numspinadapt=new LabelAdapter<String>(context,labels,1);
        spinner.setAdapter(numspinadapt);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
        @Override
        public  void onItemSelected (AdapterView<?> parent, View view, int position, long id) {
            if(position>=0&&position<labels.size()) {
                selected[0]=position;
                {if(doLog) {Log.i(LOG_ID, "selected " + labels.get(position));};};
                }
            else
                {if(doLog) {Log.i(LOG_ID,"selected "+position);};};
            }
        @Override
        public  void onNothingSelected (AdapterView<?> parent) {

        } });
    spinner.setSelection(selected[0]);
    context.lightBars(false);
    var after=ClinicalUi.body(context,context.getString(R.string.dosesprior));
    after.setPadding(ClinicalUi.dp(context,4),0,ClinicalUi.dp(context,4),
            ClinicalUi.dp(context,8));
    var device=ClinicalUi.body(context,"PEN "+serial);
    device.setTextColor(ClinicalUi.accent(context));
    device.setTypeface(DEFAULT_BOLD,BOLD);
    var cancel=ClinicalUi.button(context,context.getString(R.string.cancel),
            ClinicalUi.ButtonRole.SECONDARY);
    var ok=ClinicalUi.button(context,context.getString(R.string.save),
            ClinicalUi.ButtonRole.PRIMARY);
    long[] newtime={lasttime};
    final var datebutton=ClinicalUi.button(context,
            DateFormat.getDateInstance(DateFormat.DEFAULT).format(lasttime),
            ClinicalUi.ButtonRole.SECONDARY);
    var cal = Calendar.getInstance();
        datebutton.setOnClickListener(
                v -> { 
            context.getnumberview().getdateviewal(context,newtime[0], (year,month,day)-> {
             cal.set(Calendar.YEAR,year);
             cal.set(Calendar.MONTH,month);
             cal.set(Calendar.DAY_OF_MONTH,day);
             long newmsec= cal.getTimeInMillis();
             newtime[0]=newmsec;
            datebutton.setText(DateFormat.getDateInstance(DateFormat.DEFAULT).format(newmsec));
            });

        });    

    cal.setTimeInMillis(newtime[0]);
    int[] hour={cal.get(Calendar.HOUR_OF_DAY)};
    int[]  min={cal.get(Calendar.MINUTE)};
    var timebutton=ClinicalUi.button(context,
            String.format(Locale.US,"%02d:%02d",hour[0],min[0]),
            ClinicalUi.ButtonRole.SECONDARY);
    spinner.setMinimumHeight(ClinicalUi.dp(context,52));
    spinner.setMinimumWidth(ClinicalUi.dp(context,150));
    LinearLayout content=ClinicalUi.verticalContent(context);
    content.setPadding(ClinicalUi.dp(context,20),
            MainActivity.systembarTop+ClinicalUi.dp(context,8),
            ClinicalUi.dp(context,20),ClinicalUi.dp(context,30));
    content.addView(ClinicalUi.header(context,
            context.getString(R.string.hardware_novopen_title),cancel));
    content.addView(device);
    content.addView(after);
    content.addView(ClinicalUi.sectionLabel(context,
            context.getString(R.string.hardware_import_window_section)));
    content.addView(ClinicalUi.card(context,
            ClinicalUi.fieldRow(context,
                    context.getString(R.string.hardware_import_after_date),datebutton),
            ClinicalUi.fieldRow(context,
                    context.getString(R.string.hardware_import_after_time),timebutton)));
    content.addView(ClinicalUi.sectionLabel(context,
            context.getString(R.string.hardware_record_type_section)));
    content.addView(ClinicalUi.card(context,
            ClinicalUi.fieldRow(context,context.getString(R.string.type),spinner)));
    LinearLayout.LayoutParams saveParams=new LinearLayout.LayoutParams(
            MATCH_PARENT,WRAP_CONTENT);
    saveParams.topMargin=ClinicalUi.dp(context,20);
    content.addView(ok,saveParams);
    ScrollView screen=ClinicalUi.scrollScreen(context,content);
    timebutton.setOnClickListener(v-> {
        screen.setVisibility(INVISIBLE);
            context.getnumberview().gettimepicker(context,hour[0], min[0], (h,m) -> {
                hour[0]=h;
                min[0]=m;
                cal.set(Calendar.HOUR_OF_DAY,h);
                cal.set(Calendar.MINUTE,m);
                     newtime[0]= cal.getTimeInMillis();
                timebutton.setText(String.format(Locale.US,"%02d:%02d",h,m));
               },()-> screen.setVisibility(View.VISIBLE));});

    context.setonback(() -> removeContentView(screen));
    ok.setOnClickListener(v -> {
            var doses=op.doses;
            int ty=selected[0];
            Runnable saveall=()-> {
                int nr=0;
                int lastdose=doses.size()-1;
                for(int d=0;d<=lastdose;++d) {
                    var dose=doses.get(d);
                    int back=savenovopen(dose.referencetime,serial,ty,dose.rawdoses,d==lastdose);
                    if(back<0)  {
                        Applic.Toaster(context.getString(R.string.wentwrong));
                        context.doonback();
                        return;
                        }
                    else
                        nr+=back;
                    }
                Applic.Toaster(nr+(nr==1?context.getString(R.string.dosis):context.getString(R.string.doses))+context.getString(R.string.saved));
                if(nr>0) context.requestRender();
                context.doonback();
                };
            Runnable testtype=() -> {
                if(ty==type) {
                    saveall.run();
                    }
                else {
                    changeTypeconfirmation(context,labels.get(ty), saveall);
                    }
                };
            if(lasttime==newtime[0]) {
                testtype.run();
                }
            else {
                setnovopenttimeandtype(newtime[0],ty,serial);
                if(lasttime<newtime[0]) {
                    testtype.run();
                    }
                else {
                    context.doonback();
                    earlytimeconfirmation(context);
                    }
                }
            }
            );
    cancel.setOnClickListener(v -> context.doonback());
    context.addMyContentView(screen,
            new FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT));
    }
}
