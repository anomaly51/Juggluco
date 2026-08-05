/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2, Libre 3, Dexcom G7/ONE+ and           */
/*      Sibionics GS1Sb sensors.                                                     */
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
/*      Fri Feb 07 12:28:12 CET 2025                                                 */



package tk.glucodata;

import static android.text.Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE;
import static android.text.Html.fromHtml;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static tk.glucodata.Layout.getMargins;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.NumberView.avoidSpinnerDropdownFocus;
import static tk.glucodata.help.helplight;
import static tk.glucodata.settings.Settings.removeContentView;
import static tk.glucodata.util.getbutton;
import static tk.glucodata.util.getcheckbox;

import android.os.Build;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;

class MirrorSensors {
final private static String LOG_ID="MirrorSensors";
/*
public static native long[] activeSensorPtrs( );
public static native String namefromSensorptr(long sensorptr);
public static native String sensortextfromSensorptr(long sensorptr);
public static native void finishfromSensorptr(long sensorptr);
*/

private static void confirmFinish(MainActivity act,View parent,long ptr) {
   var serial=Natives.namefromSensorptr(ptr);
   ConnectionUi.confirmSheet(act,parent,serial,
         act.getString(R.string.connection_sensor_finish_message),
         act.getString(R.string.finish),ClinicalUi.ButtonRole.DANGER,()-> {
            Log.i(LOG_ID,"confirmFinish");
            Natives.finishfromSensorptr(ptr);
            act.requestRender();
            MainActivity.doonback();
            bluediag.start(act);
            });
    }
private static boolean isVisible=false;
static void show(MainActivity act) {
    if(isVisible)
        return;
    long[] ptrs=Natives.activeSensorPtrs();
    if(ptrs.length==0) {
        bluediag.nosensors(act);
        return;
        }
    isVisible=true;
    var sensors=new Sensors(act,false,false);
    Button close=ConnectionUi.headerButton(act,R.string.closename);
    LinearLayout sensorHelp=ClinicalUi.actionRow(act,act.getString(R.string.helpname),
          act.getString(R.string.connection_sensor_mirror_help_hint));
    sensorHelp.setOnClickListener(v-> helplight(R.string.sensormirror,act));
    Button finish=ClinicalUi.button(act,act.getString(R.string.finish),
          ClinicalUi.ButtonRole.DANGER);
    var list=new ArrayList<Long>(ptrs.length);
    for(var p : ptrs) {
        list.add(p);
        }
    var spin=new Spinner(act);
    avoidSpinnerDropdownFocus(spin);    
    spin.setMinimumHeight(ClinicalUi.dp(act,52));
    spin.setPaddingRelative(ClinicalUi.dp(act,12),0,ClinicalUi.dp(act,12),0);
    spin.setBackground(ClinicalUi.surface(act,false,true));
    var adap = new RangeAdapter<>(list, act, ptr -> {
        if(ptr != 0L) {
            var name=Natives.namefromSensorptr(ptr);
            if(name!=null)
                return name;
            }
        return "Error";
        });
    spin.setAdapter(adap);
    int[] waspos={0};
    sensors.setSensorptrText(ptrs[0]);
     final boolean wasused= Natives.getusebluetooth();
     var usebluetooth=getcheckbox(act, R.string.use_bluetooth,wasused);
    usebluetooth.setOnCheckedChangeListener(
         (buttonView,  isChecked) -> {
             {if(doLog) {Log.i(LOG_ID,"usebluetooth "+isChecked);};};
             if(isChecked!=wasused) {
                 act.setbluetoothmain( isChecked);
                 act.requestRender();
                 MainActivity.doonback();
                 bluediag.start(act);
             }
         });
    spin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
        @Override
        public  void onItemSelected (AdapterView<?> parent, View view, int position, long id) {
            {if(doLog) {Log.i(LOG_ID,"onItemSelected "+position);};};
            if(position!=waspos[0]) {
                waspos[0]=position;
                sensors.setSensorptrText(ptrs[position]);

                      
      //          setinfo(info,calview,ptrs[position]);

                }

        }
        @Override
        public  void onNothingSelected (AdapterView<?> parent) {

        } });
    LinearLayout layout=ConnectionUi.content(act);
    layout.setLayoutParams(new ViewGroup.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
    layout.addView(ClinicalUi.header(act,
          act.getString(R.string.connection_sensor_mirror_title),close));
    layout.addView(ConnectionUi.intro(act,R.string.connection_sensor_mirror_intro));
    layout.addView(ClinicalUi.sectionLabel(act,
          act.getString(R.string.connection_sensor_section)));
    layout.addView(ClinicalUi.card(act,
          ClinicalUi.fieldRow(act,act.getString(R.string.connection_active_sensor),spin),
          ConnectionUi.directToggle(act,usebluetooth)));
    layout.addView(sensors.viewgroup,new LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,0,1.0f));
    layout.addView(ClinicalUi.card(act,sensorHelp));
    LinearLayout.LayoutParams finishParams=new LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
    finishParams.topMargin=ClinicalUi.dp(act,14);
    finish.setLayoutParams(finishParams);
    layout.addView(finish);
    finish.setOnClickListener(view-> {
        int position=waspos[0];
        if(position>=0&&position<ptrs.length)
            confirmFinish(act,layout,ptrs[position]);
        });
    act.addMyContentView(layout,new ViewGroup.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
    MainActivity.setonback(() -> {
            isVisible=false;
            removeContentView(layout);
            });
    close.setOnClickListener(view->MainActivity.doonback());

    }

};
