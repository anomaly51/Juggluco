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

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static tk.glucodata.Applic.backgroundcolor;
import static tk.glucodata.BluetoothGlucoseMeter.getExistingGatt;
import static tk.glucodata.RingTones.EnableControls;
import static tk.glucodata.settings.Settings.removeContentView;
import static tk.glucodata.util.getbutton;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class MeterList {
//  static private final String LOG_ID ="MeterList";
static final class MeterView extends Layout{
   MeterListViewAdapter adapter;
   int meterIndex;
   TextView text;
   CheckDirectionBox active;
   void setdata(int index) {
         meterIndex=index;
         String name=Natives.GlucoseMeterDeviceName(index);
         String addr=Natives.GlucoseMeterDeviceAddress( index);
         boolean a=Natives.GlucoseMeterGetActive(index);
         long lastused=Natives.GlucoseMeterGetLastTime(index);
         String addtext=name+"\n"+addr+(lastused>0L?("\n"+text.getContext().getString(R.string.last)+bluediag.datestr(lastused)):"");
         if(a) {
            Context context=getContext();
            var gatt=getExistingGatt(index);
            if(gatt!=null) {
                gatt.view=this;
                if(gatt.connectedTime>gatt.disconnectedTime) {
                    addtext+="\n"+context.getString(R.string.isconnected)+": "+bluediag.datestr(gatt.connectedTime);
                    if(gatt.receivedTime==0L)
                        addtext+=gatt.isBonded?"\nBonded":"\nNot bonded";
                    }
                else {  
                    if(gatt.disconnectedTime>0L) {
                        addtext+="\n"+context.getString(R.string.isdisconnected)+": "+bluediag.datestr(gatt.disconnectedTime);
                        if(gatt.receivedTime==0L)
                            addtext+=gatt.isBonded?"\nWas bonded":"\nWas not bonded";
                        }

                      }
                if(gatt.receivedTime>0L) {
                    addtext+="\n"+context.getString(gatt.newvalues?R.string.newdata:R.string.nonewdata)+": "+bluediag.datestr(gatt. receivedTime);
                        }
                  }
            }
        text.setText(addtext);
         active.setChecked(a);
        }
    MeterView(MainActivity act,View parent,TextView t,CheckDirectionBox b, MeterListViewAdapter adapter) {
        super(act,(l,w,h)->{
             return new int[] {w,h};
               },new View[]{t},new View[]{b});
        text=t;
        active=b;
        meterIndex=-1;
        text.setOnClickListener( v -> { 
            MeterConfig.config(act,meterIndex,parent,null,adapter);
            });
        active.setOnCheckedChangeListener( (buttonView,  isChecked) -> {
            if(meterIndex>=0) {
                if(Natives.GlucoseMeterSetActive(meterIndex,isChecked)) {
                    if(isChecked) {
                           var gatt=BluetoothGlucoseMeter.addDevice(meterIndex,null);
                           gatt.view=this;
                            }
                     else
                            BluetoothGlucoseMeter.removeDevice(meterIndex);
                    }
                }
                } );
         active.setText(R.string.active);
         ConnectionUi.directToggle(act,active);
         text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,15.0f);
         text.setTextColor(ClinicalUi.primaryText(act));
         text.setLineSpacing(0.0f,1.12f);
         text.setPaddingRelative(ClinicalUi.dp(act,14),ClinicalUi.dp(act,10),
               ClinicalUi.dp(act,14),ClinicalUi.dp(act,4));
         text.setMinimumHeight(ClinicalUi.dp(act,64));
         setPadding(ClinicalUi.dp(act,4),ClinicalUi.dp(act,4),
               ClinicalUi.dp(act,4),ClinicalUi.dp(act,4));
         setBackground(ClinicalUi.surface(act,false,false));
         RecyclerView.LayoutParams params=new RecyclerView.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
         params.topMargin=ClinicalUi.dp(act,6);
         params.bottomMargin=ClinicalUi.dp(act,6);
         setLayoutParams(params);

        }

    MeterView(MainActivity act,View parent,MeterListViewAdapter adapter) {
        this(act,parent,new TextView(act),new CheckDirectionBox(act),adapter);
        }
    };
   static  class MeterListViewHolder extends RecyclerView.ViewHolder {
       public MeterListViewHolder(MeterView view) {
         super(view);
        }
   }


public static  class MeterListViewAdapter extends RecyclerView.Adapter<MeterListViewHolder> {
        View layout;
        MeterListViewAdapter(View par) {
             layout=par;
            }

       @NonNull
      @Override
       public MeterListViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
           MeterView view=new MeterView((MainActivity)parent.getContext(),layout,this);
           return new MeterListViewHolder(view);
       }


      @Override
      public void onBindViewHolder(final MeterListViewHolder holder, int pos) {
         MeterView text=(MeterView)holder.itemView;
         text.setdata(pos);
          }
           @Override
       public int getItemCount() {
            return Natives.GlucoseMeterCount();

           }

   }
static public void show(MainActivity act, View parent) {
     if(Natives.staticnum()) {
            if(parent!=null)
                 EnableControls(parent,false);
            help.help(R.string.staticnum,act,l-> {
                if(parent!=null)
                    EnableControls(parent,true);
                  });
            return;
            }
      if(parent!=null) parent.setVisibility(GONE);
      RecyclerView recycle = new RecyclerView(act);
      recycle.setHasFixedSize(false);
      recycle.setClipToPadding(false);
      var lin=new androidx.recyclerview.widget.LinearLayoutManager(act);
      recycle.setLayoutManager(lin);
      Button close=ConnectionUi.headerButton(act,R.string.closename);
      Button devices=ClinicalUi.button(act,act.getString(R.string.finddevices),
            ClinicalUi.ButtonRole.PRIMARY);
      LinearLayout meterHelp=ClinicalUi.actionRow(act,act.getString(R.string.helpname),
            act.getString(R.string.connection_meter_help_hint));
      LinearLayout layout=ConnectionUi.content(act);
      layout.setLayoutParams(new ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT));
      layout.addView(ClinicalUi.header(act,
            act.getString(R.string.connection_meters_title),close));
      layout.addView(ConnectionUi.intro(act,R.string.connection_meters_intro));
      layout.addView(ClinicalUi.sectionLabel(act,
            act.getString(R.string.connection_meters_section)));
      layout.addView(recycle,new LinearLayout.LayoutParams(MATCH_PARENT,0,1.0f));
      layout.addView(ClinicalUi.card(act,meterHelp));
      LinearLayout.LayoutParams deviceParams=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
      deviceParams.topMargin=ClinicalUi.dp(act,14);
      devices.setLayoutParams(deviceParams);
      layout.addView(devices);
      var meteradapt = new MeterListViewAdapter(layout);
      recycle.setAdapter(meteradapt);
      devices.setOnClickListener( v -> { 
            DeviceList.show(act,meteradapt);
            });
        meterHelp.setOnClickListener(v-> tk.glucodata.help.help(R.string.GlucoseMeterList,act));
        close.setOnClickListener(view->MainActivity.doonback());

      act.addMyContentView(layout, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
     MainActivity.setonback(()-> {
           BluetoothGlucoseMeter.zeroViews();
           removeContentView(layout);
           if(parent!=null)
              parent.setVisibility(VISIBLE);
        });
      }

}
