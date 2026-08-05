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
/*      Sun Sep 21 14:02:17 CEST 2025                                                */
package tk.glucodata;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static tk.glucodata.Applic.backgroundcolor;
import static tk.glucodata.BluetoothGlucoseMeter.startAdapterScanner;
import static tk.glucodata.Natives.GlucoseMeterHasIndex;
import static tk.glucodata.NumberView.smallScreen;
import static tk.glucodata.settings.Settings.removeContentView;
import static tk.glucodata.util.getbutton;
import static tk.glucodata.util.getcheckbox;

import static tk.glucodata.MeterScanner.shouldUseDeviceAddress;

import static tk.glucodata.Log.doLog;

import android.content.Context;
import android.graphics.Color;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class DeviceList {
  static private final String LOG_ID ="DeviceList";

   static  class DeviceListViewHolder extends RecyclerView.ViewHolder {
       public DeviceListViewHolder(TextView view,View parent) {
         super(view);
         view.setOnClickListener(v -> {
             int pos=getAbsoluteAdapterPosition();
             final var scan=BluetoothGlucoseMeter.scanner;
             if(scan!=null) {
                 if(pos<scan.deviceNames.size()) {
                     String deviceName=scan.deviceNames.get(pos);
                     if(showAidexX) {
                       AidexXGattCallback.addbyDeviceName((MainActivity) view.getContext(),deviceName);
                        while(MainActivity.doonback())
                                ;
                        }
                     else {
                         var device=scan.devices.get(pos);
                         int meterIndex=Natives.GlucoseMeterGetIndex(deviceName,shouldUseDeviceAddress(deviceName,device)?device.getAddress():null);
                         if(doLog)
                             Log.i(LOG_ID,deviceName+" getId()="+view.getId()+" pos="+pos+" meterIndex="+meterIndex);
                         if(meterIndex>=0) {
                            MeterConfig.config((MainActivity)view.getContext(),meterIndex,parent,device,null);
                            }
                         else {
                            Applic.Toaster("Adding meter "+deviceName+" failed");
                            }
                         }
                     }
                  else {
                    Log.e(LOG_ID,"pos "+pos+" >=deviceNames "+ scan.deviceNames.size());
                    }
                 }
              else {
                    Log.e(LOG_ID,"scanner==null");
                }
             });
        }
   }


static  class DeviceListViewAdapter extends RecyclerView.Adapter<DeviceListViewHolder> {
View parent;
String newname;
            DeviceListViewAdapter(View parent) { 
              this.parent=parent;
              this.newname=parent.getContext().getString(R.string.newname);
            }

       @NonNull
      @Override
       public DeviceListViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
           Button view=new Button((MainActivity)parent.getContext());
           view.setAllCaps(false);
           view.setGravity(android.view.Gravity.START|android.view.Gravity.CENTER_VERTICAL);
           view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,15.0f);
           view.setTextColor(ClinicalUi.primaryText(parent.getContext()));
           view.setPaddingRelative(ClinicalUi.dp(parent.getContext(),16),
                 ClinicalUi.dp(parent.getContext(),10),ClinicalUi.dp(parent.getContext(),16),
                 ClinicalUi.dp(parent.getContext(),10));
           view.setMinimumHeight(ClinicalUi.dp(parent.getContext(),68));
           view.setBackground(ClinicalUi.surface(parent.getContext(),false,true));
           RecyclerView.LayoutParams params=new RecyclerView.LayoutParams(MATCH_PARENT,
                 ViewGroup.LayoutParams.WRAP_CONTENT);
           params.topMargin=ClinicalUi.dp(parent.getContext(),5);
           params.bottomMargin=ClinicalUi.dp(parent.getContext(),5);
           view.setLayoutParams(params);
           view.setStateListAnimator(null);
           return new DeviceListViewHolder((TextView)view,this.parent);
       }

private final SpannableString newcolor(Context context,String nameaddress) {
         SpannableString str = new SpannableString(nameaddress+"\t"+newname);
         int spanlength=str.length();
         int newlen=newname.length();
         str.setSpan(new ForegroundColorSpan(ClinicalUi.accent(context)),
               spanlength-newlen,spanlength,0);
         return str;
         }
static private int aidexXindex(String name) {
    String serial= AidexXGattCallback.deviceName2name(name);
    return Natives.sensorIndex(serial);
    }
      @Override
      public void onBindViewHolder(final DeviceListViewHolder holder, int pos) {
             TextView text=(TextView)holder.itemView;
             text.setId(pos);
             var scan=BluetoothGlucoseMeter.scanner;
             var device=scan.devices.get(pos);
             var name=scan.deviceNames.get(pos);
             var address=device.getAddress();
             String nameaddress=name+"\n"+address;
             int index= showAidexX?aidexXindex(name):GlucoseMeterHasIndex(name, shouldUseDeviceAddress(name,device)?address:null);
                 if(index<0) {
                     text.setText(newcolor(text.getContext(),nameaddress));
                     }
                else
                     text.setText(nameaddress);
          }
           @Override
       public int getItemCount() {
            final MeterScanner scanner=BluetoothGlucoseMeter.scanner;
            if(scanner==null)
                return 0;
            return scanner.devices.size();
           }

   }
static private boolean showAidexX=false;
static public void show(MainActivity act, MeterList.MeterListViewAdapter meteradapt) {
      RecyclerView recycle = new RecyclerView(act);
      recycle.setHasFixedSize(false);
      recycle.setClipToPadding(false);
      var lin=new androidx.recyclerview.widget.LinearLayoutManager(act);
      recycle.setLayoutManager(lin);
      Button close=ConnectionUi.headerButton(act,R.string.closename);
      close.setOnClickListener(view->MainActivity.doonback());
      LinearLayout deviceHelp=ClinicalUi.actionRow(act,act.getString(R.string.helpname),
            act.getString(R.string.connection_device_scan_help_hint));
      var aidex=getcheckbox(act,"AiDEX X",showAidexX);
      LinearLayout layout=ConnectionUi.content(act);
      layout.setLayoutParams(new ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT));
      layout.addView(ClinicalUi.header(act,
            act.getString(R.string.connection_find_meters_title),close));
      layout.addView(ConnectionUi.intro(act,R.string.connection_find_meters_intro));
      layout.addView(ClinicalUi.sectionLabel(act,
            act.getString(R.string.connection_scan_filter_section)));
      layout.addView(ClinicalUi.card(act,ConnectionUi.directToggle(act,aidex)));
      layout.addView(ClinicalUi.sectionLabel(act,
            act.getString(R.string.connection_nearby_devices_section)));
      layout.addView(recycle,new LinearLayout.LayoutParams(MATCH_PARENT,0,1.0f));
      layout.addView(ClinicalUi.card(act,deviceHelp));
    var deviceadapt = new DeviceListViewAdapter(layout);
    recycle.setAdapter(deviceadapt);
    startAdapterScanner(deviceadapt,showAidexX);
    aidex.setOnCheckedChangeListener((buttonView, isChecked) -> {
//        if(!doLog)
        showAidexX=isChecked;
        startAdapterScanner(deviceadapt,isChecked);
        deviceadapt.notifyDataSetChanged();
        });
     deviceHelp.setOnClickListener(v-> tk.glucodata.help.help(R.string.DeviceList,act));

     act.addMyContentView(layout, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
      MainActivity.setonback(()-> {
            BluetoothGlucoseMeter.stopScanner();
           removeContentView(layout);
            meteradapt.notifyDataSetChanged();
        });
      }

}
