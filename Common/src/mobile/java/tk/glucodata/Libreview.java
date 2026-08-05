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
/*      Fri Jan 27 15:32:11 CET 2023                                                 */


package tk.glucodata;

import android.app.Activity;
import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Build;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;

import android.widget.EditText;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.security.ProviderInstaller;

import org.json.JSONException;
import org.json.JSONObject;


import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.UUID;

import androidx.annotation.Keep;
import tk.glucodata.settings.LibreNumbers;

import static tk.glucodata.Log.doLog;
import static tk.glucodata.NightPost.readJSONObject;
import static android.view.View.GONE;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static java.net.HttpURLConnection.HTTP_OK;
import static tk.glucodata.Backup.getedit;
import static tk.glucodata.Log.stackline;
import static tk.glucodata.Natives.getlibreDeviceID;
import static tk.glucodata.Natives.getlibrebaseurl;
import static tk.glucodata.Natives.getlibreemail;
import static tk.glucodata.Natives.getlibrepass;
import static tk.glucodata.Natives.getnewYuApiKey;
import static tk.glucodata.Natives.getuselibreview;
import static tk.glucodata.Natives.savelibrerubbish;
import static tk.glucodata.Natives.setlibreAccountID;
import static tk.glucodata.Natives.setlibrebaseurl;
import static tk.glucodata.Natives.setlibreemail;
import static tk.glucodata.Natives.setlibrepass;
import static tk.glucodata.Natives.setnewYuApiKey;
import static tk.glucodata.Natives.setuselibreview;
import static tk.glucodata.Natives.wakelibreview;
import static tk.glucodata.RingTones.EnableControls;
import static tk.glucodata.bluediag.datestr;
import static tk.glucodata.help.help;
import static tk.glucodata.settings.Settings.editoptions;
import static tk.glucodata.settings.Settings.removeContentView;
import static tk.glucodata.util.getbutton;
import static tk.glucodata.util.getcheckbox;
import static tk.glucodata.util.getlabel;
import static tk.glucodata.NightPost.getstring;
import static tk.glucodata.NightPost.nothing;
import static tk.glucodata.NightPost.success;
import static tk.glucodata.util.getlocale;

public class Libreview  {
   private static final String LOG_ID="Libreview";
private static String getputtext(String sensorid,String usertoken,String gateway) {
 return "{\"DomainData\":\"{\\\"activeSensor\\\":\\\""+sensorid+"\\\"}\",\"UserToken\":\""+usertoken +"\",\"Domain\":\"Libreview\",\"GatewayType\":\""+gateway+"\"}";
 }


   /*
private static int getalldata(HttpURLConnection urlConnection,byte[] buf) throws IOException {
   try(InputStream in = urlConnection.getInputStream()) {
      int off=0,len;
      while((len=in.read(buf,off,alllen-off))>0) {
         off+=len;
         }
      return off;
      }
   finally {
      urlConnection.disconnect();
      }

   }
static JSONObject  readJSONObject(HttpURLConnection urlConnection)  throws IOException, JSONException {
   byte[] buf=new byte[10*4096];
   int len=getalldata(urlConnection,buf);
   String ant=new String(getSlice(buf, 0, len));
   {if(doLog) {Log.i(LOG_ID,"readJSONObject len="len+" "+ant);};};
    return new JSONObject(ant);
   }*/

private static String librestatus=nothing;

@Keep
static boolean putsensor(boolean libre3,byte[] textbytes) {
   if(librestatus==nothing||librestatus==success)
      librestatus=datestr(System.currentTimeMillis())+" start putsensor";
   try {
   for(int i=0;i<3;i++) {
      final String gateway=getlibregateway(libre3);
      final String baseurl=getlibrebaseurl(libre3);
      URL url = new URL(baseurl+"/api/nisperson");
      HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
      urlConnection.setDoOutput(true);
          urlConnection.setRequestMethod("PUT");
      urlConnection.setRequestProperty("Content-Type","application/json; charset=UTF-8");
      String usertoken=Natives.getlibreUserToken(libre3);
      urlConnection.setRequestProperty( "Content-Length", Integer.toString( textbytes.length ));
      OutputStream outputPost = new BufferedOutputStream(urlConnection.getOutputStream());
      outputPost.write(textbytes);
      outputPost.flush();
      outputPost.close();
       JSONObject object = readJSONObject(urlConnection);
      final int status=object.getInt("status");
      if(status!=0) {
         String reason=object.getString("reason");
         if(status==20) {
            if(reason.contains("wrongDeviceInToken")) {
               switch(i) {
                  case 0:{
                  if(!postgetauth(libre3)) {
                     if(!libreconfig(libre3,false))
                        return false;
                     i=1;
                        }
                     };break;
                    case 1: {
                     if(!libreconfig(libre3,false))
                        return false;
                     };break;
                  default: {
                     librestatus="putsensor  reason="+reason;
                     return false;
                     }

                  }
               continue;
               }
            }

         librestatus="putsensor: status="+status+(reason==null?"":(" reason="+reason));
         }
      return status==0;
        }
   return false;
      }  
   catch(Throwable th) {
      librestatus="putsensor "+ stackline(th);
      Log.e(LOG_ID,librestatus);
      return false;
      }
   }
static String getlibregateway(boolean libre3) {
   if(libre3)
      return "FSLibreLink3.Android";
   return "FSLibreLink.Android";
   }
static private boolean gettermversion(String lang) {
   try {
      if(termsofuseversionurl==null) {
            {if(doLog) {Log.d(LOG_ID, "termsofuseversionurl==null");};};
            return false;
         }
      String rep=termsofuseversionurl.replace("<locale>",lang);

      URL url = new URL(rep);
      HttpURLConnection  urlConnection = (HttpURLConnection) url.openConnection();
      urlConnection.setRequestMethod("GET");
      final int code=urlConnection.getResponseCode();
      if(code==HTTP_OK) {
         {if(doLog) {Log.i(LOG_ID,"gettermversion  success");};};
         return true;
         }
      else {
         Log.e(LOG_ID,"gettermversion code="+code);
         return false;
         }

      }
   catch(Throwable th) {
      Log.stack(LOG_ID,"gettermversion",th);
      return false;
      }
   }

static boolean postgetauth(boolean libre3) {
   String gateway=getlibregateway(libre3);
   String one= Natives.getlibreDeviceID(libre3);

   String password=getlibrepass();

   String login=getlibreemail();
   {if(doLog) {Log.i(LOG_ID,"postgetauth "+login+" "+password);};};

   var loc= Locale.getDefault();
   String language=loc.getLanguage()+'-'+loc.getCountry();
   String culture=language;
   String setdevice="false";
   while(true) {
      try {
      final String baseurl=getlibrebaseurl(libre3);
      URL url = new URL(baseurl+"/api/nisperson/getauthentication");
      HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
      urlConnection.setRequestMethod("POST");
      urlConnection.setDoOutput(true);
      String getauthtext;
      if(libre3) {

         urlConnection.setRequestProperty("Content-Type", "application/json");
         urlConnection.setRequestProperty("Platform","Android");
         urlConnection.setRequestProperty("Version","3.3.0");
         urlConnection.setRequestProperty("Abbott-ADC-App-Platform","Android/"+((Object) Build.VERSION.RELEASE) +"/FSL3/3.3.0.9092");
         urlConnection.setRequestProperty("Accept-Language",language);
         final String newYuApiKey=getnewYuApiKey(libre3);
         urlConnection.setRequestProperty("x-api-key", newYuApiKey);
         urlConnection.setRequestProperty("x-newyu-token",""); 
         getauthtext="{\n"+
         "  \"Culture\": \""+culture+"\",\n"+
         "  \"DeviceId\": \""+one+"\",\n"+
         "  \"Password\": \""+password+"\",\n"+
         "  \"SetDevice\": "+setdevice+",\n"+
         "  \"UserName\": \""+login+"\",\n"+
         "  \"Domain\": \"Libreview\",\n"+
         "  \"GatewayType\": \""+ gateway+ "\"\n"+
         "}\n";
         }
      else {
         urlConnection.setRequestProperty("Abbott-ADC-App-Platform", "Android/"+((Object) Build.VERSION.RELEASE)+"/FSLL/2.10.1.10406");


         urlConnection.setRequestProperty("Accept-Language",language+", "+loc.getLanguage()+";q=0.8");
         urlConnection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
         getauthtext="{\"Culture\":\""+culture+"\",\"DeviceId\":\""+one+"\",\"Password\":\""+password+"\",\"SetDevice\":"+setdevice+",\"UserName\":\""+login+"\",\"Domain\":\"Libreview\",\"GatewayType\":\""+ gateway+ "\""+ "}";

         }
      byte[] textbytes=getauthtext.getBytes();
      {if(doLog) {Log.i(LOG_ID,"postauth: "+getauthtext);};};
          urlConnection.setRequestProperty( "Content-Length", Integer.toString( textbytes.length ));

   //   {if(doLog) {Log.i(LOG_ID,getauthtext);};};
      OutputStream outputPost = new BufferedOutputStream(urlConnection.getOutputStream());
      outputPost.write(textbytes);
      outputPost.flush();
      outputPost.close();
      final int code=urlConnection.getResponseCode();
      
      {if(doLog) {Log.i(LOG_ID,"ResponseCode="+code);};};
      if(code==HTTP_OK) {
         JSONObject object = readJSONObject(urlConnection);
         int status=object.getInt("status");
         if(status!=0) {
            String reason=object.getString("reason");
            String poststatus="postgetauth: status="+status+" reason="+reason;
            Log.e(LOG_ID,poststatus);
            if(status==20) {
               if(reason.contains("wrongDeviceForUser")) {
                  setdevice="true";
                  continue;   
                  }
               }
            librestatus=poststatus;
            return false;
            }
         {if(doLog) {Log.i(LOG_ID,"getauth Success");};};
         JSONObject result=object.getJSONObject("result");
         String usertoken=result.getString("UserToken");
         Natives.setlibreUserToken(libre3,usertoken);
         String accountid=result.getString("AccountId");
         setlibreAccountID(accountid);
         librestatus="Received AccountID";
         if(libre3) {//TODO enkel als send to libreview aanstaat?
            String DateOfBirth=result.getString("DateOfBirth");
            int dat=Integer.parseInt(DateOfBirth);
            String FirstName=result.getString("FirstName");
            String LastName=result.getString("LastName");
            String GuardianLastName=result.getString("GuardianLastName");
            String GuardianFirstName=result.getString("GuardianFirstName");
            savelibrerubbish(FirstName,LastName,dat,GuardianFirstName,GuardianLastName);
            String UiLanguage=result.getString("UiLanguage");
            gettermversion(UiLanguage);
            }
         else {
            URL url2 = new URL(baseurl+"/api/nisperson/getAccountInfo"); //IS this really needed?
            HttpURLConnection urlConnection2 = (HttpURLConnection) url2.openConnection();
            urlConnection2.setRequestMethod("POST");
            urlConnection2.setDoOutput(true);

            urlConnection2.setRequestProperty("Abbott-ADC-App-Platform", "Android/"+((Object) Build.VERSION.RELEASE)+"/FSLL/2.10.1.10406");
            urlConnection2.setRequestProperty("Accept-Language",language+", "+loc.getLanguage()+";q=0.8");
            urlConnection2.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            String notneeded="{\"UserToken\":\""+usertoken+"\",\"Domain\":\"Libreview\",\"GatewayType\":\"FSLibreLink.Android\"}";
            byte[] notneedbytes=notneeded.getBytes();
            {if(doLog) {Log.i(LOG_ID,"postauth: "+notneeded);};};
                urlConnection2.setRequestProperty( "Content-Length", Integer.toString( notneedbytes.length ));
            OutputStream outputPost2 = new BufferedOutputStream(urlConnection2.getOutputStream());
            outputPost2.write(notneedbytes);
            outputPost2.flush();
            outputPost2.close();
            final int code2=urlConnection2.getResponseCode();
            {if(doLog) {Log.i(LOG_ID,"ResponseCode="+code2);};};
            if(code2!=HTTP_OK) {
                  librestatus="getAccountInfo: getResponseCode()="+code2;
                  }
            }
         return true;
         }
      else {
         librestatus="postgetauth: urlConnection.getResponseCode()="+code;
         return false;
         }
       }
      catch(Throwable th) {
         librestatus="postgetauth:\t"+ stackline(th);

         Log.e(LOG_ID,librestatus);
         return false;
         }
   }
 }
 /*
@Keep
static boolean postmeasurements(byte[] measurementdata) {
   return postmeasurements(false, measurementdata);
   }*/

static String posttime=null;
@Keep
static boolean postmeasurements(boolean libre3,byte[] measurementdata) {
   String nowstr=datestr(System.currentTimeMillis());
   if(librestatus==nothing||librestatus==success)
      librestatus=nowstr+" start posting";
   try {
   for(int i=0;i<3;i++) {
      final String baseurl=getlibrebaseurl(libre3);
      URL url = new URL(baseurl+"/api/measurements");
      HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
      urlConnection.setConnectTimeout(10000);
      urlConnection.setReadTimeout(60000);
      urlConnection.setRequestMethod("POST");
      urlConnection.setDoOutput(true);
      urlConnection.setRequestProperty("Content-Type", "application/json");
          urlConnection.setRequestProperty( "Content-Length", Integer.toString( measurementdata.length ));
      if(libre3) {
         urlConnection.setRequestProperty("Platform","Android");
         urlConnection.setRequestProperty("Version","3.3.0");
         urlConnection.setRequestProperty("Abbott-ADC-App-Platform","Android/"+((Object) Build.VERSION.RELEASE) +"/FSL3/3.3.0.9092");
 
         var loc= Locale.getDefault();
         String language=loc.getLanguage()+'-'+loc.getCountry();
         urlConnection.setRequestProperty("Accept-Language",language);
         final String newYuApiKey=getnewYuApiKey(libre3);
         urlConnection.setRequestProperty("x-api-key", newYuApiKey);
         final String usertoken=Natives.getlibreUserToken(libre3);
         urlConnection.setRequestProperty("x-newyu-token",usertoken); 
         }
      OutputStream outputPost = new BufferedOutputStream(urlConnection.getOutputStream());
      outputPost.write(measurementdata);
      outputPost.flush();
      outputPost.close();
      final int code=urlConnection.getResponseCode();
      if(code==HTTP_OK) {
         JSONObject object = readJSONObject(urlConnection);
         int status=object.getInt("status");
         if(status!=0) {
            Log.e(LOG_ID,"Post with status "+status);
            String reason=object.getString("reason");
            if(status==20) {
               if(reason.contains("wrongDeviceInToken")) {
                  switch(i) {
                     case 0:{
                        if(!postgetauth(libre3)) {
                           if(!libreconfig(libre3,false))
                              return false;
                           i=1;
                           }
                        };break;
                     case 1: {
                        if(!libreconfig(libre3,false))
                           return false;
                        };break;
                     default: {
                        librestatus="postmeasurements1 status="+status+" reason="+reason;
                        return false;
                        }

                     }
//                  return postmeasurements(libre3,measurementdata);
                  continue;
                  }
               }
            librestatus="postmeasurements2 status="+status+" reason="+reason;
            return false;
            }
         posttime=nowstr;
         librestatus=success;
         return true;
         }
      else {
         librestatus="postmeasurements ResponseCode="+code;
         {if(doLog) {Log.i(LOG_ID,librestatus);};};
         return false;
         }
         }
      return false;
       }
   catch(Throwable th) {
      final String posterror="postmeasurements\n"+stackline(th);
      librestatus=posterror;
      Log.e(LOG_ID,posterror);
      return false;
      }
 }
 /*TODO: where:
        try {
           ProviderInstaller.installIfNeeded(Applic.app);
        }
      catch(Throwable th) {
         librestatus= "ProviderInstaller.installIfNeeded: \n"+stackline(th);
          Log.e(LOG_ID,librestatus);
           }
*/
//   https://fsll3.freestyleserver.com/Payloads/Mobile/FFSLibre3/Android/Assets/3.3.0%2FDE.json
private static String termsofuseversionurl=null;
private static final String libre3start="https://fsll3.freestyleserver.com/Payloads/Mobile/FSLibre3/Android/Assets/3.3.0/DE.json";
private static String  libre3getconfigURL() {
   try {

      URL url = new URL(libre3start);
      HttpURLConnection  urlConnection = (HttpURLConnection) url.openConnection();
      urlConnection.setRequestMethod("GET");
      final int code=urlConnection.getResponseCode();
      if(code==HTTP_OK) {
         JSONObject object =  readJSONObject(urlConnection) ;
         final String conurl=object.getString( "Configuration");
         try {
            termsofuseversionurl=object.getString( "TermsOfUseVersion");
          }
          catch(Throwable th) {
            librestatus="libre3getconfigURL 1:\n"+stackline(th);
            Log.e(LOG_ID,librestatus);
            }
         finally {
            return conurl;
            } 
         }
      else {
         librestatus="libre3getconfigURL failed code="+code;
         Log.e(LOG_ID,librestatus);
         return null;
         }

      }
   catch(Throwable th) {
      librestatus="libre3getconfigURL:\n"+(th==null?"Network error ":th.getMessage());
      Log.e(LOG_ID,librestatus);
      return null;
      }
   }
   /*
public static void testlibre3() { 
   String url=libre3getconfigURL();
   {if(doLog) {Log.i(LOG_ID,"libre3getconfigURL()="+(url==null?"null":url));};};
   }*/
//https://fsll.freestyleserver.com/Payloads/Mobile/Android/FSLibreLink/Config/FreeStyleLibreLink_Android_2.3_DE_config.json
@Keep
public static boolean libreconfig(boolean libre3,boolean restart){
   if(restart||librestatus==nothing||librestatus==success)
      librestatus=datestr(System.currentTimeMillis())+" libreconfig";
   {if(doLog) {Log.i(LOG_ID,librestatus);};};
     try {
        ProviderInstaller.installIfNeeded(Applic.app);
     }
   catch(Throwable th) {
      librestatus= "ProviderInstaller.installIfNeeded: \n"+stackline(th);
       Log.e(LOG_ID,librestatus);
        }

//   final String libre23url= "https://www.google.com";
 final  String[] urlnames= {
         "https://fsll.freestyleserver.com/Payloads/Mobile/FSLibreLink/Android/Config/FSLibreLink_Android_2.10_GB_config.json",
         "https://fsll.freestyleserver.com/Payloads/Mobile/FSLibreLink/Android/Config/FSLibreLink_Android_2.10_FR_config.json",
         "https://fsll.freestyleserver.com/Payloads/Mobile/FSLibreLink/Android/Config/FSLibreLink_Android_2.10_NL_config.json",
         "https://fsll.freestyleserver.com/Payloads/Mobile/FSLibreLink/Android/Config/FSLibreLink_Android_2.10_PL_config.json",
         "https://fsll.freestyleserver.com/Payloads/Mobile/FSLibreLink/Android/Config/FSLibreLink_Android_2.10_RU_config.json"};
//   final String libre23url= "https://fsll.freestyleserver.com/Payloads/Mobile/Android/FSLibreLink/Config/FreeStyleLibreLink_Android_2.3_DE_config.json";
final String libre210url=urlnames[Natives.getLibreCountry()];

//final String libre33url="https://fsll3.freestyleserver.com/Payloads/Mobile/FSLibre3/Android/Config/FSLibre3_Android_3.3_DE_config_production.json";
   String urlstring;
   if(libre3) {
      urlstring=libre3getconfigURL();
      if(urlstring==null) {
         return false;
         }
      }
   else
      urlstring=libre210url;

   try {
      URL url = new URL(urlstring);
      if(url==null)  {
         return false;
         }
      HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
      urlConnection.setRequestMethod("GET");

      final int code=urlConnection.getResponseCode();
      if(code==HTTP_OK) {
         JSONObject object =  readJSONObject(urlConnection) ;
         final String baseurl=object.getString( "newYuUrl");
         setlibrebaseurl(libre3,baseurl);
         final var jobj = object.opt("newYuApiKey");

         if(jobj!=null)  {
            String value=jobj instanceof String?(String)jobj: String.valueOf(jobj);
            setnewYuApiKey(libre3,value);
         }
         return postgetauth(libre3);
         }
      else {
         librestatus="urlConnection.getResponseCode()="+code;
         Log.e(LOG_ID,librestatus);
         return false;
         }

      }
   catch(Throwable th) {
      librestatus="libreconfig:\n"+stackline(th);

      Log.e(LOG_ID,librestatus);
      return false;
      }
   }

static long defaultLibreResendStart(long now) {
   return now-89L*24L*60L*60L*1000L;
   }

private static void resendDateDialog(MainActivity context,View parent) {
   EnableControls(parent,false);
   long lasttime=defaultLibreResendStart(System.currentTimeMillis());
   long[] newtime={lasttime};
   Calendar cal=Calendar.getInstance();
   cal.setTimeInMillis(lasttime);
   int[] hour={cal.get(Calendar.HOUR_OF_DAY)};
   int[] min={cal.get(Calendar.MINUTE)};

   Button cancel=ConnectionUi.headerButton(context,R.string.cancel);
   Button datebutton=getbutton(context,
         DateFormat.getDateInstance(DateFormat.DEFAULT).format(lasttime));
   Button timebutton=getbutton(context,String.format(Locale.US,"%02d:%02d",hour[0],min[0]));
   ConnectionUi.styleButton(context,datebutton,ClinicalUi.ButtonRole.SECONDARY);
   ConnectionUi.styleButton(context,timebutton,ClinicalUi.ButtonRole.SECONDARY);
   Button save=ClinicalUi.button(context,context.getString(R.string.save),
         ClinicalUi.ButtonRole.PRIMARY);
   LinearLayout helpRow=ClinicalUi.actionRow(context,context.getString(R.string.helpname),
         context.getString(R.string.connection_libre_resend_hint));

   LinearLayout content=ConnectionUi.content(context);
   content.addView(ClinicalUi.header(context,
         context.getString(R.string.connection_resend_title),cancel));
   content.addView(ConnectionUi.intro(context,R.string.connection_resend_intro));
   content.addView(ClinicalUi.sectionLabel(context,
         context.getString(R.string.connection_resend_from_section)));
   content.addView(ClinicalUi.card(context,
         ClinicalUi.fieldRow(context,context.getString(R.string.connection_date_label),datebutton),
         ClinicalUi.fieldRow(context,context.getString(R.string.connection_time_label),timebutton)));
   content.addView(ClinicalUi.sectionLabel(context,
         context.getString(R.string.connection_support_section)));
   content.addView(ClinicalUi.card(context,helpRow));
   LinearLayout.LayoutParams saveParams=new LinearLayout.LayoutParams(
         ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
   saveParams.topMargin=ClinicalUi.dp(context,20);
   save.setLayoutParams(saveParams);
   content.addView(save);
   ScrollView screen=ConnectionUi.screen(context,content);
   ConnectionUi.fullScreen(context,screen);

   datebutton.setOnClickListener(view->
         context.getnumberview().getdateviewal(context,newtime[0],(year,month,day)-> {
            cal.set(Calendar.YEAR,year);
            cal.set(Calendar.MONTH,month);
            cal.set(Calendar.DAY_OF_MONTH,day);
            newtime[0]=cal.getTimeInMillis();
            datebutton.setText(DateFormat.getDateInstance(DateFormat.DEFAULT)
                  .format(newtime[0]));
            }));
   timebutton.setOnClickListener(view-> {
      screen.setVisibility(INVISIBLE);
      context.getnumberview().gettimepicker(context,hour[0],min[0],(selectedHour,selectedMinute)-> {
         hour[0]=selectedHour;
         min[0]=selectedMinute;
         cal.set(Calendar.HOUR_OF_DAY,selectedHour);
         cal.set(Calendar.MINUTE,selectedMinute);
         newtime[0]=cal.getTimeInMillis();
         timebutton.setText(String.format(Locale.US,"%02d:%02d",selectedHour,selectedMinute));
         },()->screen.setVisibility(VISIBLE));
      });
   helpRow.setOnClickListener(view->help.help(R.string.changestart,context));
   Runnable closeall=()-> {
      removeContentView(screen);
      EnableControls(parent,true);
      {if(doLog) {Log.i(LOG_ID,"resendDateDialog back");};};
      };
   context.setonback(closeall);
   cancel.setOnClickListener(view->context.doonback());
   save.setOnClickListener(view->askclearlibreview(context,newtime[0],screen,closeall));
   }

private static void askclearlibreview(MainActivity context,long fromtime,View parent,
      Runnable closeDateScreen) {
   ConnectionUi.confirmSheet(context,parent,
         context.getString(R.string.connection_resend_confirm_title),
         context.getString(R.string.connection_resend_confirm_message),
         context.getString(R.string.connection_resend_confirm_action),
         ClinicalUi.ButtonRole.DANGER,()-> {
            {if(doLog) {Log.i(LOG_ID,"askclearlibreview Click");};};
            Natives.clearlibreFromMSec(fromtime);
            context.poponback();
            closeDateScreen.run();
            });
   }

private static void confirmGetAccountID(MainActivity context,View parent) {
   ConnectionUi.confirmSheet(context,parent,
         context.getString(R.string.connection_account_request_title),
         context.getString(R.string.connection_account_request_message),
         context.getString(R.string.connection_account_request_action),
         ClinicalUi.ButtonRole.PRIMARY,()-> {
            Natives.setlibreAccountIDnumber(-1L);
            Natives.askServerforAccountID();
            });
   }

static boolean validLibreAccountId(String value) {
   if(value==null||value.trim().isEmpty())
      return false;
   try {
      Long.parseLong(value.trim());
      return true;
      }
   catch(Throwable ignored) {
      return false;
      }
   }

private static void getAccountid(MainActivity context,Predicate<Boolean> getgegs,
      View settingsview,CheckDirectionBox sendto,boolean[] donothing) {
   boolean setmanually=Natives.manualLibreAccountIDnumber()!=-1L;
   CheckDirectionBox manual=getcheckbox(context,R.string.manual,setmanually);
   long accountidnum=Natives.getlibreAccountIDnumber();
   EditText editid=new EditText(context);
   editid.setText(String.valueOf(accountidnum));
   editid.setImeOptions(tk.glucodata.settings.Settings.editoptions);
   editid.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED);
   ConnectionUi.styleInput(editid);

   Button close=ConnectionUi.headerButton(context,R.string.closename);
   Button save=ClinicalUi.button(context,context.getString(R.string.save),
         ClinicalUi.ButtonRole.PRIMARY);
   LinearLayout manualField=ClinicalUi.fieldRow(context,
         context.getString(R.string.connection_account_id_field),editid);
   LinearLayout fromLibreView=ClinicalUi.actionRow(context,
         context.getString(R.string.connection_account_id_server_title),
         context.getString(R.string.connection_account_id_server_hint));
   LinearLayout helpRow=ClinicalUi.actionRow(context,context.getString(R.string.helpname),
         context.getString(R.string.connection_libre_help_hint));
   TextView formError=ConnectionUi.status(context,"",true);

   LinearLayout content=ConnectionUi.content(context);
   content.addView(ClinicalUi.header(context,
         context.getString(R.string.connection_account_id_title),close));
   content.addView(ConnectionUi.intro(context,R.string.connection_account_id_intro));
   content.addView(ClinicalUi.sectionLabel(context,
         context.getString(R.string.connection_account_id_source_section)));
   LinearLayout sourceCard=ClinicalUi.card(context,
         ConnectionUi.directToggle(context,manual),manualField,fromLibreView);
   content.addView(sourceCard);
   LinearLayout.LayoutParams errorParams=new LinearLayout.LayoutParams(
         ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
   errorParams.topMargin=ClinicalUi.dp(context,12);
   formError.setLayoutParams(errorParams);
   content.addView(formError);
   content.addView(ClinicalUi.sectionLabel(context,
         context.getString(R.string.connection_support_section)));
   content.addView(ClinicalUi.card(context,helpRow));
   LinearLayout.LayoutParams saveParams=new LinearLayout.LayoutParams(
         ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
   saveParams.topMargin=ClinicalUi.dp(context,20);
   save.setLayoutParams(saveParams);
   content.addView(save);
   ScrollView screen=ConnectionUi.screen(context,content);
   ConnectionUi.fullScreen(context,screen);

   Consumer<Boolean> showManual=isChecked-> {
      manualField.setVisibility(isChecked?VISIBLE:GONE);
      fromLibreView.setVisibility(isChecked?GONE:VISIBLE);
      save.setVisibility(isChecked?VISIBLE:GONE);
      formError.setVisibility(GONE);
      };
   showManual.accept(setmanually);
   manual.setOnCheckedChangeListener((button,checked)->showManual.accept(checked));

   Runnable closerun=()-> {
      removeContentView(screen);
      config(context,settingsview,sendto,donothing);
      };
   context.setonback(closerun);
   close.setOnClickListener(view->context.doonback());
   helpRow.setOnClickListener(view->help(R.string.getaccountidhelp,context));
   save.setOnClickListener(view-> {
      String idText=editid.getText().toString().trim();
      if(idText.isEmpty()) {
         formError.setText(R.string.connection_account_id_error_empty);
         formError.setVisibility(VISIBLE);
         return;
         }
      if(!validLibreAccountId(idText)) {
         formError.setText(R.string.connection_account_id_error_format);
         formError.setVisibility(VISIBLE);
         return;
         }
      long id=Long.parseLong(idText);
      Natives.setlibreAccountIDnumber(id);
      Applic.argToaster(context,context.getString(R.string.saved)+" "+id,Toast.LENGTH_SHORT);
      context.doonback();
      });
   fromLibreView.setOnClickListener(view-> {
      if(!getgegs.test(true))
         return;
      confirmGetAccountID(context,screen);
      });
   }
//      Natives.askServerforAccountID();
static boolean validLibreCredentials(String email,String password,boolean enabled) {
   if(!enabled)
      return true;
   return email!=null&&email.length()>=3&&email.length()<=255
         &&password!=null&&password.length()>=3&&password.length()<=36;
   }

public static void config(MainActivity context,View settingsView,
      CheckDirectionBox sendTo,boolean[] doNothing) {
   EnableControls(settingsView,false);
   EditText email=getedit(context,getlibreemail());
   EditText password=new EditText(context);
   password.setImeOptions(editoptions);
   password.setInputType(InputType.TYPE_TEXT_VARIATION_PASSWORD);
   password.setTransformationMethod(new PasswordTransformationMethod());
   String previousPassword=getlibrepass();
   if(previousPassword!=null)
      password.setText(previousPassword);
   ConnectionUi.styleInput(email);
   ConnectionUi.styleInput(password);

   CheckDirectionBox showPassword=getcheckbox(context,R.string.connection_show_password,false);
   showPassword.setOnCheckedChangeListener((button,checked)-> {
      int selection=password.getSelectionStart();
      password.setInputType(checked?InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD:
            InputType.TYPE_TEXT_VARIATION_PASSWORD);
      password.setTransformationMethod(checked?null:new PasswordTransformationMethod());
      password.setSelection(Math.max(0,Math.min(selection,password.length())));
      });
   boolean wasEnabled=getuselibreview();
   CheckDirectionBox enabled=getcheckbox(context,R.string.uselibreview,wasEnabled);
   CheckDirectionBox russian=getcheckbox(context,R.string.connection_russian_region,
         Natives.getLibreCountry()==4);
   CheckDirectionBox current=getcheckbox(context,R.string.librecurrent,Natives.getLibreCurrent());
   current.setOnCheckedChangeListener((button,checked)->Natives.setLibreCurrent(checked));
   CheckDirectionBox viewed=getcheckbox(context,R.string.libreisviewed,Natives.getLibreIsViewed());
   viewed.setOnCheckedChangeListener((button,checked)->Natives.setLibreIsViewed(checked));
   CheckDirectionBox numbers=getcheckbox(context,R.string.sendamounts,Natives.getSendNumbers());

   Button cancel=ConnectionUi.headerButton(context,R.string.cancel);
   Button save=ClinicalUi.button(context,context.getString(R.string.save),
         ClinicalUi.ButtonRole.PRIMARY);
   LinearLayout sendNow=ClinicalUi.actionRow(context,context.getString(R.string.sendnow),
         context.getString(R.string.connection_libre_send_hint));
   sendNow.setEnabled(wasEnabled);
   sendNow.setAlpha(wasEnabled?1.0f:0.46f);
   LinearLayout changeStart=ClinicalUi.actionRow(context,
         context.getString(R.string.changestartbutton),
         context.getString(R.string.connection_libre_resend_hint));
   LinearLayout account=ClinicalUi.actionRow(context,
         context.getString(R.string.getaccountid),
         context.getString(R.string.connection_libre_account_hint,
               Natives.getlibreAccountIDnumber()));
   LinearLayout libreHelp=ClinicalUi.actionRow(context,
         context.getString(R.string.helpname),context.getString(R.string.connection_libre_help_hint));
   String localizedStatus=NightPost.visibleStatus(context,librestatus);
   String visibleStatus=librestatus==success?(posttime+": "+localizedStatus):localizedStatus;
   TextView status=ConnectionUi.status(context,visibleStatus,librestatus!=success&&librestatus!=nothing);
   TextView formError=ConnectionUi.status(context,"",true);

   LinearLayout content=ConnectionUi.content(context);
   content.addView(ClinicalUi.header(context,
         context.getString(R.string.connection_libre_title),cancel));
   content.addView(ConnectionUi.intro(context,R.string.connection_libre_intro));
   content.addView(ClinicalUi.sectionLabel(context,
         context.getString(R.string.connection_account_section)));
   content.addView(ClinicalUi.card(context,
         ClinicalUi.fieldRow(context,context.getString(R.string.email),email),
         ClinicalUi.fieldRow(context,context.getString(R.string.password),password),
         ConnectionUi.directToggle(context,showPassword),
         ConnectionUi.directToggle(context,russian),account));
   content.addView(ClinicalUi.sectionLabel(context,
         context.getString(R.string.connection_upload_section)));
   content.addView(ClinicalUi.card(context,
         ConnectionUi.directToggle(context,enabled),
         ConnectionUi.directToggle(context,current),
         ConnectionUi.directToggle(context,viewed),
         ConnectionUi.directToggle(context,numbers),sendNow));
   LinearLayout.LayoutParams statusParams=new LinearLayout.LayoutParams(
         ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
   statusParams.topMargin=ClinicalUi.dp(context,12);
   status.setLayoutParams(statusParams);
   formError.setLayoutParams(statusParams);
   content.addView(status);
   content.addView(formError);
   content.addView(ClinicalUi.sectionLabel(context,
         context.getString(R.string.connection_maintenance_section)));
   content.addView(ClinicalUi.card(context,changeStart));
   content.addView(ClinicalUi.sectionLabel(context,
         context.getString(R.string.connection_support_section)));
   content.addView(ClinicalUi.card(context,libreHelp));
   LinearLayout.LayoutParams saveParams=new LinearLayout.LayoutParams(
         ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
   saveParams.topMargin=ClinicalUi.dp(context,20);
   save.setLayoutParams(saveParams);
   content.addView(save);
   ScrollView screen=ConnectionUi.screen(context,content);
   ConnectionUi.fullScreen(context,screen);

   int[] noNumbersChange={0};
   numbers.setOnCheckedChangeListener((button,checked)-> {
      switch(noNumbersChange[0]) {
         case 0:
            noNumbersChange[0]++;
            numbers.setChecked(!checked);
            LibreNumbers.mklayout(context,0,numbers,noNumbersChange,screen);
            break;
         case 2:
            Natives.setSendNumbers(checked);
            break;
         default:
            break;
         }
      });
   Runnable close=()-> {
      removeContentView(screen);
      EnableControls(settingsView,true);
      sendTo.setChecked(wasEnabled);
      doNothing[0]=false;
      };
   context.setonback(close);
   cancel.setOnClickListener(view->context.doonback());
   Predicate<Boolean> persistCredentials=turnOn-> {
      String emailValue=email.getText().toString().trim();
      String passwordValue=password.getText().toString();
      if(!validLibreCredentials(emailValue,passwordValue,turnOn)) {
         formError.setText(R.string.connection_libre_credentials_error);
         formError.setVisibility(View.VISIBLE);
         return false;
         }
      setlibreemail(emailValue);
      setlibrepass(passwordValue);
      if(emailValue.isEmpty()&&passwordValue.isEmpty())
         Natives.clearlibreFromMSec(0L);
      boolean wasRussian=Natives.getLibreCountry()==4;
      if(wasRussian!=russian.isChecked())
         Natives.setLibreCountry(russian.isChecked()?4:(Applic.unit==1?0:1));
      return true;
      };
   save.setOnClickListener(view-> {
      boolean turnOn=enabled.isChecked();
      if(!persistCredentials.test(turnOn))
         return;
      setuselibreview(turnOn);
      context.poponback();
      removeContentView(screen);
      EnableControls(settingsView,true);
      sendTo.setChecked(turnOn);
      doNothing[0]=false;
      });
   sendNow.setOnClickListener(view->wakelibreview(0));
   changeStart.setOnClickListener(view->resendDateDialog(context,screen));
   libreHelp.setOnClickListener(view->help(R.string.libreview,context));
   account.setOnClickListener(view-> {
      if(!persistCredentials.test(false))
         return;
      context.poponback();
      removeContentView(screen);
      getAccountid(context,persistCredentials,settingsView,sendTo,doNothing);
      });
   }

@SuppressWarnings("unused")
private static void legacyConfig(MainActivity act, View settingsview,CheckDirectionBox sendto,boolean[] donothing) {
   EnableControls(settingsview,false);
   var emaillabel=getlabel(act,R.string.email);
   var email=getedit(act, getlibreemail());
        email.setMinEms(16);

   var passlabel=getlabel(act,act.getString(R.string.password)+":");
   var      editpass= new EditText(act);
        editpass.setImeOptions(editoptions);
        editpass.setInputType(InputType.TYPE_TEXT_VARIATION_PASSWORD);
        editpass.setTransformationMethod(new PasswordTransformationMethod());
        editpass.setMinEms(12);
   String passwas=getlibrepass();
   if(passwas!=null) {
      editpass.setText(passwas);
      }
   var send=getbutton(act,act.getString(R.string.sendnow));
   var ok=getbutton(act,R.string.ok);
   var cancel=getbutton(act,R.string.cancel);
   var help=getbutton(act,R.string.helpname);
   help.setOnClickListener(v-> help(R.string.libreview,act));
   final boolean isRussia=Natives.getLibreCountry()==4;
   var russia=getcheckbox(act,"RU",isRussia);
   boolean usedlibre= getuselibreview();
   var sendtolibreview=getcheckbox(act,R.string.uselibreview,usedlibre);
   var librecurrent=getcheckbox(act,R.string.librecurrent,Natives.getLibreCurrent());
   librecurrent.setOnCheckedChangeListener( (buttonView,  isChecked) -> {
      Natives.setLibreCurrent(isChecked);
      });
   var libreisviewed=getcheckbox(act,R.string.libreisviewed,Natives.getLibreIsViewed());
   libreisviewed.setOnCheckedChangeListener( (buttonView,  isChecked) -> {
      Natives.setLibreIsViewed(isChecked);
      });

   int[] nochangeamounts={0};
   var numbers=getcheckbox(act,R.string.sendamounts,Natives.getSendNumbers());
/*   sendtolibreview.setOnCheckedChangeListener( (buttonView,  isChecked) -> {
      numbers.setVisibility(isChecked?VISIBLE:INVISIBLE);;
      }); */
   var clear=getbutton(act,act.getString(R.string.changestartbutton));

     String localizedStatus=NightPost.visibleStatus(act,librestatus);
     var statusview=getlabel(act,librestatus==success?(posttime+": "+localizedStatus):localizedStatus);
     int statuspad=  (int)tk.glucodata.GlucoseCurve.metrics.density*7;
   statusview.setPadding(statuspad,statuspad,statuspad,statuspad);
   
//     clear.setPadding(0,0,0,pad*5);
   long accountidnum=Natives.getlibreAccountIDnumber();
   var accountid=getlabel(act, String.valueOf(accountidnum));
   var getaccountid=getbutton(act,R.string.getaccountid);
   final Layout layout=new Layout(act, (lay, w, h) -> {
   /*
      var height=GlucoseCurve.getheight();
      var width=GlucoseCurve.getwidth();
      if(w>=width||h>=height) {
            lay.setX(0);
            }
       else {
            lay.setX((width-w)/2); 
            };
       lay.setY(MainActivity.systembarTop);
       */
       return new int[] {w,h};}, 
                new View[]{emaillabel,email},new View[]{passlabel,editpass,russia},new View[]{clear,accountid,getaccountid},new View[]{statusview},new View[]{ librecurrent,libreisviewed}, new View[]{sendtolibreview,numbers},new View[]{send,help,cancel,ok});

    clear.setOnClickListener(v->  {
         resendDateDialog(act,layout);
         });
   if(usedlibre) {
      send.setOnClickListener(v-> wakelibreview(0));
      }
   else  {
//      numbers.setVisibility(INVISIBLE);;
      send.setVisibility(INVISIBLE);
      }

   numbers.setOnCheckedChangeListener( (buttonView,  isChecked) -> {
      switch(nochangeamounts[0])  {
         case 0: {
            ++nochangeamounts[0];
            numbers.setChecked(!isChecked);
            LibreNumbers.mklayout(act,0,numbers,nochangeamounts,layout);
            };break;
         case  2: Natives.setSendNumbers(isChecked);break;
         };
      });
     

   Runnable closerun=()-> {
      layout.setVisibility(GONE);
      removeContentView(layout);
      EnableControls(settingsview,true);
      sendto.setChecked(usedlibre);
      donothing[0]=false;
      };
   act.setonback(closerun);
   cancel.setOnClickListener(v->  {
         act.doonback();
         });
    Predicate<Boolean> getgegs= use -> {
         String emailstr=email.getText().toString();
         String passstr=editpass.getText().toString();
         if(use) {
            if(emailstr.length()<3) {
               Applic.argToaster(act, act.getString( R.string.emailaddresstooshort)+emailstr, Toast.LENGTH_SHORT);
               return false;
               }
            if(emailstr.length()>255) {
               Applic.argToaster(act,  act.getString( R.string.emailaddresstoolong)+emailstr, Toast.LENGTH_SHORT);
               return false;
               }
            if(passstr.length()<3) {
               Applic.argToaster(act, act.getString(R.string.password8)+passstr, Toast.LENGTH_SHORT);
               return false;
               }
            if(passstr.length()>36) {
               Applic.argToaster(act,  act.getString(R.string.password36)+passstr, Toast.LENGTH_SHORT);
               return false;
               }
            }   
         setlibreemail(emailstr);
         setlibrepass(passstr);
         if((emailstr.length()==0&&passstr.length()==0)) {
            Natives.clearlibreFromMSec(0L);
            }
         if(isRussia!=russia.isChecked()) {
            Natives.setLibreCountry((!isRussia)?4:(Applic.unit==1?0:1));
            }
         return true;
      };
   ok.setOnClickListener(v-> {
         boolean turnonlibre=sendtolibreview.isChecked();
         if(!getgegs.test(turnonlibre))
            return;
         setuselibreview(turnonlibre);
            
         act.poponback();
         layout.setVisibility(GONE);
         removeContentView(layout);
         EnableControls(settingsview,true);
         sendto.setChecked(turnonlibre);
         donothing[0]=false;
         });
   getaccountid.setOnClickListener(v->  {
         act.poponback();
         getgegs.test(false);

         layout.setVisibility(GONE);
         removeContentView(layout);
         getAccountid(act,getgegs,  settingsview,sendto, donothing);
      });
         layout.setBackgroundResource(R.drawable.dialogbackground);
         int pad= (int)tk.glucodata.GlucoseCurve.metrics.density*7;
      layout.setPadding(pad,0,pad,pad);
    var  params =    
            new FrameLayout.LayoutParams(
                    WRAP_CONTENT,
                    WRAP_CONTENT,
                    Gravity.CENTER_HORIZONTAL);
    params.topMargin=MainActivity.systembarTop;
   act.addMyContentView(layout, params);
   
   }



}





