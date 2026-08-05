/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2, Libre 3, Dexcom G7/ONE+,              */
/*      Sibionics GS1Sb and Accu-Chek SmartGuide sensors.                            */
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
/*      Sun Sep 21 10:44:11 CEST 2025                                                */

package tk.glucodata;

import static android.view.View.INVISIBLE;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.NumberView.avoidSpinnerDropdownFocus;
import static tk.glucodata.RingTones.EnableControls;
import static tk.glucodata.settings.Settings.removeContentView;
import static tk.glucodata.util.getbutton;

import android.bluetooth.BluetoothDevice;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Calendar;
import java.util.Locale;

class MeterConfig {
    private static final String LOG_ID="MeterConfig";

    static boolean validBloodLabelSelection(int position,int labelCount) {
        return position>=0&&position<labelCount-1;
    }

    static void config(MainActivity context,int meterIndex,View parent,
            BluetoothDevice device,MeterList.MeterListViewAdapter adapter) {
        if(parent!=null)
            EnableControls(parent,false);
        if(doLog)
            Log.i(LOG_ID,"MeterConfig "+meterIndex);
        String deviceName=Natives.GlucoseMeterDeviceName(meterIndex);
        if(deviceName==null)
            deviceName="Error: no device name";

        long lasttime=Natives.GlucoseMeterGetLastTime(meterIndex);
        long[] newtime={lasttime};
        Calendar calendar=Calendar.getInstance();
        calendar.setTimeInMillis(lasttime);
        int[] hour={calendar.get(Calendar.HOUR_OF_DAY)};
        int[] minute={calendar.get(Calendar.MINUTE)};

        Button cancel=ConnectionUi.headerButton(context,R.string.cancel);
        Button dateButton=getbutton(context,
                DateFormat.getDateInstance(DateFormat.DEFAULT).format(lasttime));
        Button timeButton=getbutton(context,
                String.format(Locale.US,"%02d:%02d",hour[0],minute[0]));
        ConnectionUi.styleButton(context,dateButton,ClinicalUi.ButtonRole.SECONDARY);
        ConnectionUi.styleButton(context,timeButton,ClinicalUi.ButtonRole.SECONDARY);
        Button save=ClinicalUi.button(context,context.getString(R.string.save),
                ClinicalUi.ButtonRole.PRIMARY);
        Button delete=ClinicalUi.button(context,context.getString(R.string.delete),
                ClinicalUi.ButtonRole.DANGER);
        LinearLayout helpRow=ClinicalUi.actionRow(context,
                context.getString(R.string.helpname),
                context.getString(R.string.connection_meter_help_hint));

        Spinner spinner=new Spinner(context);
        avoidSpinnerDropdownFocus(spinner);
        final var labels=Natives.getLabels();
        LabelAdapter<String> adapterLabels=new LabelAdapter<>(context,labels,0);
        spinner.setAdapter(adapterLabels);
        int bloodPosition=Natives.getbloodvar();
        int[] selectedBloodPosition={bloodPosition};
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id) {
                selectedBloodPosition[0]=position;
                Natives.setbloodvar((byte)position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        spinner.setSelection(bloodPosition);
        spinner.setMinimumHeight(ClinicalUi.dp(context,52));
        spinner.setMinimumWidth(ClinicalUi.dp(context,148));
        spinner.setPaddingRelative(ClinicalUi.dp(context,12),0,
                ClinicalUi.dp(context,12),0);
        spinner.setBackground(ClinicalUi.surface(context,false,true));

        TextView deviceLabel=ClinicalUi.body(context,deviceName);
        deviceLabel.setTextColor(ClinicalUi.primaryText(context));
        deviceLabel.setTextSize(17);
        deviceLabel.setPadding(ClinicalUi.dp(context,16),ClinicalUi.dp(context,16),
                ClinicalUi.dp(context,16),ClinicalUi.dp(context,16));
        TextView recordHint=ClinicalUi.body(context,
                context.getString(R.string.connection_meter_record_hint));
        recordHint.setPaddingRelative(ClinicalUi.dp(context,4),ClinicalUi.dp(context,10),
                ClinicalUi.dp(context,4),0);
        TextView formError=ConnectionUi.status(context,"",true);

        LinearLayout content=ConnectionUi.content(context);
        content.addView(ClinicalUi.header(context,
                context.getString(R.string.connection_meter_config_title),cancel));
        content.addView(ConnectionUi.intro(context,R.string.connection_meter_config_intro));
        content.addView(ClinicalUi.sectionLabel(context,
                context.getString(R.string.connection_meter_device_section)));
        content.addView(ClinicalUi.card(context,deviceLabel));
        content.addView(ClinicalUi.sectionLabel(context,
                context.getString(R.string.connection_meter_import_section)));
        content.addView(ClinicalUi.card(context,
                ClinicalUi.fieldRow(context,context.getString(R.string.connection_date_label),dateButton),
                ClinicalUi.fieldRow(context,context.getString(R.string.connection_time_label),timeButton)));
        content.addView(ClinicalUi.sectionLabel(context,
                context.getString(R.string.connection_meter_record_section)));
        content.addView(ClinicalUi.card(context,
                ClinicalUi.fieldRow(context,context.getString(R.string.bloodvar),spinner)));
        content.addView(recordHint);
        LinearLayout.LayoutParams errorParams=new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        errorParams.topMargin=ClinicalUi.dp(context,12);
        formError.setLayoutParams(errorParams);
        content.addView(formError);
        content.addView(ClinicalUi.sectionLabel(context,
                context.getString(R.string.connection_support_section)));
        content.addView(ClinicalUi.card(context,helpRow));
        content.addView(ClinicalUi.sectionLabel(context,
                context.getString(R.string.connection_maintenance_section)));
        content.addView(delete,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams saveParams=new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        saveParams.topMargin=ClinicalUi.dp(context,14);
        save.setLayoutParams(saveParams);
        content.addView(save);
        ScrollView screen=ConnectionUi.screen(context,content);
        ConnectionUi.fullScreen(context,screen);

        dateButton.setOnClickListener(view->
                context.getnumberview().getdateviewal(context,newtime[0],(year,month,day)-> {
                    calendar.set(Calendar.YEAR,year);
                    calendar.set(Calendar.MONTH,month);
                    calendar.set(Calendar.DAY_OF_MONTH,day);
                    newtime[0]=calendar.getTimeInMillis();
                    dateButton.setText(DateFormat.getDateInstance(DateFormat.DEFAULT)
                            .format(newtime[0]));
                }));
        timeButton.setOnClickListener(view-> {
            screen.setVisibility(INVISIBLE);
            context.getnumberview().gettimepicker(context,hour[0],minute[0],
                    (selectedHour,selectedMinute)-> {
                        hour[0]=selectedHour;
                        minute[0]=selectedMinute;
                        calendar.set(Calendar.HOUR_OF_DAY,selectedHour);
                        calendar.set(Calendar.MINUTE,selectedMinute);
                        newtime[0]=calendar.getTimeInMillis();
                        timeButton.setText(String.format(Locale.US,"%02d:%02d",
                                selectedHour,selectedMinute));
                    },()->screen.setVisibility(View.VISIBLE));
        });
        helpRow.setOnClickListener(view->help.help(R.string.GlucoseMeter,context));

        Runnable closeAll=()-> {
            removeContentView(screen);
            if(parent!=null)
                EnableControls(parent,true);
            if(doLog)
                Log.i(LOG_ID,"MeterConfig.config back");
        };
        MainActivity.setonback(closeAll);
        delete.setOnClickListener(view->ConnectionUi.confirmSheet(context,screen,
                context.getString(R.string.connection_meter_delete_title),
                context.getString(R.string.connection_meter_delete_message),
                context.getString(R.string.delete),ClinicalUi.ButtonRole.DANGER,()-> {
                    Natives.GlucoseMeterRemoveIndex(meterIndex);
                    MainActivity.doonback();
                    MainActivity.doonback();
                    if(adapter!=null)
                        adapter.notifyDataSetChanged();
                    BluetoothGlucoseMeter.restartDevices();
                }));
        save.setOnClickListener(view-> {
            if(!validBloodLabelSelection(selectedBloodPosition[0],labels.size())) {
                formError.setText(R.string.connection_meter_invalid_record);
                formError.setVisibility(View.VISIBLE);
                return;
            }
            MainActivity.doonback();
            MainActivity.doonback();
            Log.i(LOG_ID,"save");
            context.finepermission();
            Natives.GlucoseMeterSetLastTime(meterIndex,newtime[0]);
            if(device!=null)
                BluetoothGlucoseMeter.addDevice(meterIndex,device);
        });
        cancel.setOnClickListener(view-> {
            MainActivity.doonback();
            Natives.GlucoseMeterSetActive(meterIndex,false);
            Applic.argToaster(context,R.string.connection_meter_cancelled,Toast.LENGTH_LONG);
            if(adapter!=null)
                adapter.notifyDataSetChanged();
            BluetoothGlucoseMeter.restartDevices();
        });
    }
}
