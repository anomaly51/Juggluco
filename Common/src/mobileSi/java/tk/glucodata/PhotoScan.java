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
/*      Thu Apr 04 20:10:13 CEST 2024                                                 */


package tk.glucodata;


import static tk.glucodata.Applic.Toaster;
import static tk.glucodata.Applic.isWearable;
import static tk.glucodata.Applic.scheduler;
import static tk.glucodata.Applic.useZXing;
import static tk.glucodata.Backup.getedit;
import static tk.glucodata.GS3ID.GS3IDstatus;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.MainActivity.REQUEST_BARCODE;
import static tk.glucodata.MainActivity.REQUEST_BARCODE_SIB2;
import static tk.glucodata.ZXing.scanZXingAlg;
import static tk.glucodata.settings.Settings.editoptions;
import static tk.glucodata.settings.Settings.removeContentView;
import static tk.glucodata.util.getbutton;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static tk.glucodata.util.getlabel;
import static tk.glucodata.util.getcheckbox;
import static tk.glucodata.util.getradiobuttonId;


import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Toast;


//import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
//import com.google.mlkit.vision.barcode.common.Barcode;
//import com.google.mlkit.vision.barcode.common.Barcode;
//import com.google.mlkit.vision.barcode.common.Barcode;

//import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
//import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

public class PhotoScan {
private static final String LOG_ID="PhotoScan";


private static void     wrongtag() {
    Toaster(R.string.wrongcode) ;
    }




/*
E007-0M0063KNUJ0....
      LT2309GEPD   
802JPPLT2309GEPD

GEPD802J
31108 GEPD802J PP7
Sensorname
sensorgegs
int len=sensorgegs.size();
startpos=len-19

longserialnumber 


(01) 06972831640165
(11) 231209
(17) 241208
(10) LT41 231108 C

(21) 231108 GEPD802J PP76

0106972831640165112312091724120810LT41231108C21231108GEPD802JPP76 
^]0106972831641483112411201726051910LT46241155C^]21P22411J6EP
0106972831640165112312091724120810LT41231108C21231108 GEPD 802J PP76 
*/
//LT2309GEPD
/*
Sibionics2:
*/
private static void asktransmitter(MainActivity act,String name,long sensorptr) {
    act.lightBars(false);
    var cancel=ClinicalUi.button(act,act.getString(R.string.cancel),
            ClinicalUi.ButtonRole.SECONDARY);
    var proceed=ClinicalUi.button(act,act.getString(R.string.hardware_scan_transmitter),
            ClinicalUi.ButtonRole.PRIMARY);
    var reset=getcheckbox(act,R.string.resetname,true);

    LinearLayout content=ClinicalUi.verticalContent(act);
    content.setPadding(ClinicalUi.dp(act,20),
            MainActivity.systembarTop+ClinicalUi.dp(act,8),
            ClinicalUi.dp(act,20),ClinicalUi.dp(act,30));
    content.addView(ClinicalUi.header(act,
            act.getString(R.string.scantranstitle),cancel));
    var message=ClinicalUi.body(act,act.getString(R.string.scantransmessage));
    message.setPadding(ClinicalUi.dp(act,4),0,ClinicalUi.dp(act,4),
            ClinicalUi.dp(act,8));
    content.addView(message);
    content.addView(ClinicalUi.sectionLabel(act,
            act.getString(R.string.hardware_sensor_setup)));
    content.addView(ClinicalUi.card(act,ClinicalUi.toggleRow(act,reset,
            act.getString(R.string.hardware_reset_helper))));
    LinearLayout.LayoutParams proceedParams=new LinearLayout.LayoutParams(
            MATCH_PARENT,WRAP_CONTENT);
    proceedParams.topMargin=ClinicalUi.dp(act,20);
    content.addView(proceed,proceedParams);
    ScrollView screen=ClinicalUi.scrollScreen(act,content);

    cancel.setOnClickListener(v-> {
        MainActivity.doonback();
        });
    proceed.setOnClickListener(v-> {
        MainActivity.poponback();
        removeContentView(screen);
        Natives.setSensorptrResetSibionics2(sensorptr,reset.isChecked());
        scanner(act,REQUEST_BARCODE_SIB2,sensorptr);
        });   
   MainActivity.setonback(() -> {
      removeContentView(screen);
      Natives.finishfromSensorptr(sensorptr);
      SensorBluetooth.sensorEnded(name);
      });
   act.addMyContentView(screen,new FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT));
    }


private static void selectType(String name,long sensorptr,MainActivity act) {
    act.lightBars(false);
    int subtype=Natives.getSensorptrSiSubtype(sensorptr);

    var group=new RadioGroup(act);
    int id=0;
    group.addView(getradiobuttonId(act,R.string.eusibionics,id++));
    group.addView(getradiobuttonId(act,R.string.hematonix,id++));
    group.addView(getradiobuttonId(act,R.string.chsibionics,id++));
    group.addView(getradiobuttonId(act,R.string.sibionics2,id++));
    group.check(subtype);
    group.setPadding(ClinicalUi.dp(act,12),ClinicalUi.dp(act,8),
            ClinicalUi.dp(act,12),ClinicalUi.dp(act,8));
    var proceed=ClinicalUi.button(act,act.getString(R.string.hardware_continue_setup),
            ClinicalUi.ButtonRole.PRIMARY);
    LinearLayout content=ClinicalUi.verticalContent(act);
    content.setPadding(ClinicalUi.dp(act,20),
            MainActivity.systembarTop+ClinicalUi.dp(act,8),
            ClinicalUi.dp(act,20),ClinicalUi.dp(act,30));
    content.addView(ClinicalUi.header(act,
            act.getString(R.string.hardware_sensor_model_title),null));
    var intro=ClinicalUi.body(act,act.getString(R.string.hardware_sensor_model_intro));
    intro.setPadding(ClinicalUi.dp(act,4),0,ClinicalUi.dp(act,4),
            ClinicalUi.dp(act,8));
    content.addView(intro);
    content.addView(ClinicalUi.sectionLabel(act,
            act.getString(R.string.hardware_sensor_model_section)));
    content.addView(ClinicalUi.card(act,group));
    LinearLayout.LayoutParams proceedParams=new LinearLayout.LayoutParams(
            MATCH_PARENT,WRAP_CONTENT);
    proceedParams.topMargin=ClinicalUi.dp(act,20);
    content.addView(proceed,proceedParams);
    ScrollView screen=ClinicalUi.scrollScreen(act,content);
   MainActivity.setonback(() -> {
      removeContentView(screen);
      int type=group.getCheckedRadioButtonId();
      Log.i(LOG_ID,"getCheckedRadioButtonId()="+type);
      if(type>=0) {
          Natives.setSensorptrSiSubtype(sensorptr,type);
          if(type==3) {
                asktransmitter(act,name,sensorptr);
            }
        else
              deviceAdded(act);
            }

      });
   proceed.setOnClickListener(v-> {
        MainActivity.doonback();
        });

    act.addMyContentView(screen,new FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT));
    }
static void deviceAdded(MainActivity act) {
     Log.i(LOG_ID,"deviceAdded");
       if(Natives.getusebluetooth()) {
           var res=SensorBluetooth.updateDevices();
           SuperGattCallback.glucosealarms.setLossAlarm();
           if(res) {
                act.finepermission(); 
                }
              else
                act.systemlocation();
            }
        else {
            Natives.updateUsedSensors();
            }
       Applic.wakemirrors();
       MainActivity.tocalendarapp=true;
       }
static long wasdataptr=0L;
static void connectSensor(final String scantag,MainActivity act,int request,long sensorptr2)  {
     if(!isWearable) {
        switch(request) {
            case REQUEST_BARCODE:{
                if(scantag.endsWith("MirrorJuggluco")) {
                    MirrorString.makeMirror(scantag,act);
                    return;
                    }
                 else {
                    int[] indexptr={-1};
                    String name=Natives.addSIscangetName(scantag,indexptr);
                    if(name!=null&&name.length()>0)  {
                       var sensorptr= Natives.str2sensorptr(name);
                       int type=Natives.getSensorptrLibreVersion(sensorptr);
                       {if(doLog) {Log.i(LOG_ID,"type="+type);};};
                       if(type== 0x10) {
                            selectType(name,sensorptr,act);
                            }
                        else {
                           if(type== 0x15) {
                               GetGS3ID.gs3Number(name,act);
                               }
                            else
                                deviceAdded(act);
                            }
                       return;
                       }
                  else {
                     final int index=indexptr[0];
                     if(index>=0) {
                       if(Natives.staticnum()) {
                           help.help(R.string.staticnum, act);
                            }
                        else
                            MeterConfig.config(act,index,null,null,null);
                        return;
                        }
                     }
                    }
                 }break;
               case REQUEST_BARCODE_SIB2: {
                    if(Natives.siSensorptrTransmitterScan(sensorptr2,scantag)) {
                        deviceAdded(act);
                        return;
                        }
                    else {
                        transmitterScanCancelled(sensorptr2);
                        }

                    }
                }
                }
          wrongtag(); 
         }

static void transmitterScanCancelled(long sensorptr2) {
            if(sensorptr2!=0L) {
                Natives.finishfromSensorptr(sensorptr2);
                var serial=Natives.sensorptr2str(sensorptr2);
                if(serial!=null)  {
                    Log.i(LOG_ID,"transmitterScanCancelled "+serial);
                    SensorBluetooth.sensorEnded(serial);
                    }
                 }
            else {
                Log.i(LOG_ID,"transmitterScanCancelled sensorptr==0");
                }
           }



public static void scanner(MainActivity act,int type,long sensorptr) {
    if (!isWearable) {
        if(!Natives.getGoogleScan())
            scanZXingAlg(act,type,sensorptr);
        else {
            try {
                scanGoogle(act,type,sensorptr);
                } catch (Throwable th) {
                    Log.stack(LOG_ID, "scanGoogle", th);
                    scanZXingAlg(act,type,sensorptr);
                    }
           }
      }
}
public static void scan(MainActivity act,int type) {
    scanner(act,type,0L);
    }
private static void scanGoogle(MainActivity act,int type,long sensorptr) {
     if(!isWearable) {
        if(doLog) {Log.i(LOG_ID, "before scan");};
        final var options =  new com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder().setBarcodeFormats( com.google.mlkit.vision.barcode.common.Barcode.FORMAT_DATA_MATRIX, com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE).build();
        final var scanner =  com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(act, options);
        scanner.startScan().addOnSuccessListener(
           barcode -> {
               var rawValue = barcode.getRawValue();
               var message="Scanned: "+rawValue;
               if(doLog) {Log.i(LOG_ID,message);};
               connectSensor(rawValue,act,type,sensorptr);
               })
           .addOnCanceledListener(
               () -> {
                    var message="Scan cancelled";
                    if(doLog) {Log.i(LOG_ID,message);};
                    Toast.makeText(act, message, Toast.LENGTH_LONG).show();
                    transmitterScanCancelled(sensorptr);
                     // Task canceled
                   })
       .addOnFailureListener(
           e -> {
            var message=e.getMessage();
            if(doLog) {Log.i(LOG_ID,message);};
            Toast.makeText(act, message, Toast.LENGTH_SHORT).show();  
            if(useZXing) {
                Toast.makeText(act, "Move to zXing", Toast.LENGTH_SHORT).show();
                scanZXingAlg(act,type,sensorptr);
                }
        
         // Task failed with an exception
           });

   }
    }

/*
static void testsibionics() {
if(doLog) {
  String tag="^]0106972831640820112312221724122110LT48231127G^]212311271NTK237GAA21";
  var name=Natives.addSIscangetName(tag);
  long dataptr=Natives.getdataptr(name);
  var si=new SiGattCallback(name, dataptr);
 si.testchanged();
  }
};*/
}
