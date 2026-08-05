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

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.BlendMode;
import android.graphics.BlendModeColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import android.widget.CompoundButton;
import android.widget.EditText;
//import android.widget.HorizontalScrollView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Enumeration;
import java.util.Locale;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import static tk.glucodata.Layout.getMargins;
import static android.graphics.Color.YELLOW;
import static android.view.View.GONE;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static tk.glucodata.Applic.backgroundcolor;
import static tk.glucodata.Applic.isWearable;
import static tk.glucodata.BuildConfig.isReleaseID;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.Natives.getICEside;
import static tk.glucodata.Natives.getInvertColors;
import static tk.glucodata.Natives.getWifi;
import static tk.glucodata.Natives.getbackJson;
import static tk.glucodata.Natives.getbackupHasHostname;
import static tk.glucodata.Natives.isWearOS;
import static tk.glucodata.Natives.mirrorStatus;
import static tk.glucodata.RingTones.EnableControls;
import static tk.glucodata.Specific.useclose;
import static tk.glucodata.UseWifi.usewifi;
import static tk.glucodata.help.help;
import static tk.glucodata.help.hidekeyboard;
import static tk.glucodata.settings.Settings.removeContentView;
import static tk.glucodata.util.getbutton;
import static tk.glucodata.util.getcheckbox;
import static tk.glucodata.util.getlabel;
import static tk.glucodata.settings.Settings.editoptions;
import static tk.glucodata.util.getradiobutton;
import static tk.glucodata.util.sethtml;

import tk.glucodata.nums.numio;

//import org.w3c.dom.Text;

public class Backup {
    static final int hide=isWearable?GONE:INVISIBLE;
   static final private String LOG_ID="Backup";
   static class changer implements TextWatcher {
      View view;
      changer(View v) {
         view=v;
         }
       public void    afterTextChanged(Editable s) {}

       public void    beforeTextChanged(CharSequence s, int start, int count, int after) {}

      public void    onTextChanged(CharSequence s, int start, int before, int count) {
         view.setVisibility(VISIBLE);
         }
      }
   static void hideSystemUI(Context cnt) {}
   static public  EditText getedit(Context act, String text) {
      EditText label=new EditText(act);
           label.setInputType(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
           label.setImeOptions(editoptions);
           label.setMinEms(6);
      label.setText(text);
      return label;
      }

   static public  EditText getnumedit(Context act, String text) {
      EditText label=new EditText(act);
      label.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
      label.setImeOptions(editoptions);
      label.setMinEms(3);
      label.setText(text);
      return label;
      }
   static String[] gethostnames() {
        String p2p=null;
        String norm=null;
        String bluepan=null;
        String hasone=null;
       try {
               Enumeration<NetworkInterface> inter = NetworkInterface.getNetworkInterfaces();
          if(inter!=null) {
               while(inter.hasMoreElements()) {
                   NetworkInterface in=inter.nextElement();
                   Enumeration<InetAddress> addrs= in.getInetAddresses();
                   while(addrs.hasMoreElements()) {
                       InetAddress a=addrs.nextElement();
                       String sa = a.getHostAddress();
                       String name=in.getName();
                       if(name.startsWith("p2p")) {
                     if(sa!=null&&sa.startsWith("192.168.")) {
                              p2p=sa;
                     hasone=p2p;
                     }
                     }
                       else {
                           if (!in.isVirtual()) {
                               if(name.startsWith("wlan")) {
                                   norm = sa;
                    hasone=norm;
                               } else {
                                   if(name.startsWith("bt-pan")) {
                                       bluepan = sa;
                    hasone=bluepan;
                                      }
                               }
                           }
                       }
                   }

         }
               }
        }
     catch(Throwable e) {
          String mess=e.getMessage() ;
          if(mess==null)
              mess="Network error";
           Log.stack(LOG_ID,mess,e);
           }
      return new String[]{p2p,norm,bluepan,hasone};
      }



   //String defaultport="7345";
   boolean isasender=false;
   boolean[] sendchecked;

      private static final String defaultport= isReleaseID==1?"8795":"9113";
      private    CheckDirectionBox Amounts =null;
      private CheckDirectionBox Scans =null;
      private CheckDirectionBox Stream =null,receive=null,detect=null,checkhostname;
      private CheckDirectionRadio activeonly=null,passiveonly=null,both=null;
      private final EditText[] editIPs={null,null,null,null};
      private EditText editpass=null;
      private EditText portedit=null;
      private ScrollView hostview=null;
      private CheckDirectionBox Password;
      private Button reset=null,deleteHost=null;
        private CheckDirectionBox testip,haslabel;
      private   EditText label;
   private CheckDirectionRadio[] sendfrom;
   private View[] fromrow;

    private CheckDirectionBox   visible;
      int hostindex=-1;

   public    static void setradio(RadioButton[] radios) {
         for(var but:radios) {
             but.setOnCheckedChangeListener( (buttonView,  isChecked) -> {
                if(isChecked) {
                    for(var b:radios)
                        if(b!=buttonView)
                       b.setChecked(false);
                       }
                    });
             }
        }
      static public void setradiotest(RadioButton[] radios,Object[] ap) {
         for(var but:radios) {
         but.setOnCheckedChangeListener( (buttonView,  isChecked) -> {
            if(isChecked) {
               for(var o:ap) {
                 var a = (Consumer<View>) o;
                  a.accept(buttonView);
               }
            for(var b:radios)
                if(b!=buttonView)
               b.setChecked(false);
               }
            });
         }
        }
      @SuppressWarnings("deprecation")
      public static int agetColor(Context context, int id) {
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return context.getColor(id);
         } else {
            //noinspection deprecation
            return context.getResources().getColor(id);
         }
      }
      @SuppressWarnings("deprecation")
      public static void setColorFilter(@NonNull Drawable drawable, @ColorInt int color) {
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            drawable.setColorFilter(new BlendModeColorFilter(color, BlendMode.SRC_ATOP));
         } else {
            drawable.setColorFilter(color, PorterDuff.Mode.SRC_ATOP);
         }
      }
   private void deleteconfirmation(MainActivity act) {
      String title=null;
      try {
         title=label.getText().toString();
         if((title==null||title.isEmpty())&&editIPs[0]!=null)
            title=editIPs[0].getText().toString();
         }
      catch(Throwable th) {
         Log.stack(LOG_ID,"title",th);
         }
      if(title==null||title.isEmpty())
         title=act.getString(R.string.connection_mirror_delete_title);
      ConnectionUi.confirmSheet(act,hostview,title,
            act.getString(R.string.connection_mirror_delete_message),
            act.getString(R.string.delete),ClinicalUi.ButtonRole.DANGER,()-> {
               if(hostindex>=0) {
                  Natives.deletebackuphost(hostindex);
                  hostadapt.notifyItemRemoved(hostindex);
                  }
               act.doonback();
               act.doonback();
               });
      }


static public String changehostError(MainActivity act,int pos) {
            String mess= switch (pos) {
               case -1 : yield act.getString(R.string.portrange);
               case -2 : yield act.getString(R.string.parseip);
               case -3 : yield act.getString(R.string.toomanyhosts);
               case -4 : yield act.getString(R.string.senthosts);
               case -5 : yield "Hostname too long";
               case -6 : yield "Database busy, try again";
               default : yield "Error";
            };
            return mess;
            }

   private void resentconfirmation(MainActivity act,int hostindex) {
      ConnectionUi.confirmSheet(act,hostview,
            act.getString(R.string.connection_mirror_resend_title),
            act.getString(R.string.connection_mirror_resend_message),
            act.getString(R.string.resenddata),ClinicalUi.ButtonRole.DANGER,()-> {
               Natives.resetbackuphost(hostindex);
               configchanged=true;
               });
      }


boolean makeQR(MainActivity act,int pos) {
        if(pos<0) {
                var mess= changehostError(act,pos);
                 Applic.argToaster(act,mess,Toast.LENGTH_SHORT);
                 return false;
                }
          else {
                hostadapt.notifyItemInserted(pos);
                var jsonstr= getbackJson(pos);
                QRmake.show(act,jsonstr);
                return true;
                }
         }

void makeAutoQR(MainActivity act,View parent) {
      if(isWearable) {
         legacyMakeAutoQR(act,parent);
         return;
         }
      EnableControls(parent,false);
      Button cancel=ConnectionUi.headerButton(act,R.string.cancel);
      LinearLayout homeSender=ClinicalUi.actionRow(act,
            act.getString(R.string.connection_home_network_title),
            act.getString(R.string.connection_home_network_hint));
      LinearLayout internetSender=ClinicalUi.actionRow(act,
            act.getString(R.string.connection_internet_title),
            act.getString(R.string.connection_internet_hint));
      LinearLayout homeReceiver=ClinicalUi.actionRow(act,
            act.getString(R.string.connection_home_network_title),
            act.getString(R.string.connection_home_network_hint));
      LinearLayout internetReceiver=ClinicalUi.actionRow(act,
            act.getString(R.string.connection_internet_title),
            act.getString(R.string.connection_internet_hint));
      LinearLayout helpRow=ClinicalUi.actionRow(act,act.getString(R.string.helpname),
            act.getString(R.string.connection_auto_qr_hint));

      LinearLayout content=ConnectionUi.content(act);
      content.addView(ClinicalUi.header(act,
            act.getString(R.string.connection_auto_qr_title),cancel));
      content.addView(ConnectionUi.intro(act,R.string.connection_auto_qr_intro));
      content.addView(ClinicalUi.sectionLabel(act,
            act.getString(R.string.connection_auto_qr_send_section)));
      content.addView(ClinicalUi.card(act,homeSender,internetSender));
      content.addView(ClinicalUi.sectionLabel(act,
            act.getString(R.string.connection_auto_qr_receive_section)));
      content.addView(ClinicalUi.card(act,homeReceiver,internetReceiver));
      content.addView(ClinicalUi.sectionLabel(act,
            act.getString(R.string.connection_support_section)));
      content.addView(ClinicalUi.card(act,helpRow));
      ScrollView screen=ConnectionUi.screen(act,content);
      ConnectionUi.fullScreen(act,screen);

      Runnable close=()-> {
         removeContentView(screen);
         EnableControls(parent,true);
         };
      MainActivity.setonback(close);
      cancel.setOnClickListener(view->MainActivity.doonback());
      helpRow.setOnClickListener(view->help(R.string.autoqrmessage,act));
      homeSender.setOnClickListener(view-> {
         MainActivity.poponback();
         makeQR(act,Natives.makeHomeSender());
         close.run();
         });
      internetSender.setOnClickListener(view-> {
         MainActivity.poponback();
         makeQR(act,Natives.makeICESender());
         close.run();
         });
      homeReceiver.setOnClickListener(view->dowhenasked(act,false,false,false,()-> {
         MainActivity.poponback();
         makeQR(act,Natives.makeHomeReceiver());
         close.run();
         }));
      internetReceiver.setOnClickListener(view->dowhenasked(act,false,false,false,()-> {
         MainActivity.poponback();
         makeQR(act,Natives.makeICEReceiver());
         close.run();
         }));
      }

private void legacyMakeAutoQR(MainActivity act,View parent) {
      EnableControls(parent,false);
      var cancel=getbutton(act,R.string.cancel);
     // var title=getlabel(act, R.string.autoqr);
      var help=getbutton(act,R.string.helpname);
      var send=getlabel(act,R.string.sendto);
      var homenetS=getbutton(act,R.string.homenet);
      var internetS=getbutton(act,R.string.internet);
      var receive=getlabel(act,R.string.receivefrom);
      var homenetR=getbutton(act,R.string.homenet);
      var internetR=getbutton(act,R.string.internet);
      help.setOnClickListener(v-> {
            help(R.string.autoqrmessage,act);
        });
      var layout=new Layout(act, new View[]{send},new View[]{homenetS,internetS},new View[]{receive},new View[]{homenetR,internetR},new View[] {help,cancel});
      layout.setPadding((int)(GlucoseCurve.metrics.density*4.0),(int)(GlucoseCurve.metrics.density*4.0),(int)(GlucoseCurve.metrics.density*4.0),(int)(GlucoseCurve.metrics.density*4));
      layout.setBackgroundColor(backgroundcolor);
    //  layout.measure(WRAP_CONTENT, WRAP_CONTENT);
     // layout.setX((GlucoseCurve.getwidth()-layout.getMeasuredWidth()+MainActivity.systembarLeft-MainActivity.systembarRight)*.5f);
     // layout.setY( (GlucoseCurve.getheight()-layout.getMeasuredHeight() +MainActivity.systembarTop-MainActivity.systembarBottom)*.5f);
    var  params =    new FrameLayout.LayoutParams( WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER|Gravity.CENTER_HORIZONTAL);

      act.addMyContentView(layout, params);
      layout.setBackgroundResource(R.drawable.dialogbackground);
      Runnable closerun=()->{
         removeContentView(layout);
         EnableControls(parent,true);
         };

      MainActivity.setonback(()->{
        closerun.run();
         });
      cancel.setOnClickListener(v-> {
        MainActivity.doonback();
        });
      homenetS.setOnClickListener(v-> {
            MainActivity.poponback();
            makeQR(act,Natives.makeHomeSender());
            closerun.run();
            });
      internetS.setOnClickListener(v-> {
            MainActivity.poponback();
            makeQR(act,Natives.makeICESender());
            closerun.run();
            });
      homenetR.setOnClickListener(v-> {
            dowhenasked(act,false,false,false,()-> {
                MainActivity.poponback();
                makeQR(act,Natives.makeHomeReceiver());
                closerun.run();
                });
            });
      internetR.setOnClickListener(v-> {
            dowhenasked(act,false,false,false,()-> {
                MainActivity.poponback();
                makeQR(act,Natives.makeICEReceiver());
                closerun.run();
                });
            });
    };
CheckDirectionRadio one;
EditText ICElabel;
CheckDirectionBox ICE;
   void makehostview(MainActivity act) {
      ICE=getcheckbox(act,R.string.ICE, true);
      for(int i=0;i<editIPs.length;i++) {
         editIPs[i]=new EditText(act);
         editIPs[i].setMinEms(6);
         editIPs[i].setInputType(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
         editIPs[i].setImeOptions(editoptions);
         setColorFilter(editIPs[i].getBackground().mutate(),agetColor(act,android.R.color.holo_blue_light));
         }
     CheckDirectionRadio zero=getradiobutton(act, R.string.zero);
     one=getradiobutton(act, R.string.one);
     var sides=new CheckDirectionRadio[]{zero,one};
     setradio(sides);
     zero.setChecked(true);
     var ICElabellabel=getlabel(act,R.string.icelabel);
     ICElabel = new EditText(act);
     ICElabel.setInputType(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
     ICElabel.setImeOptions(editoptions);
     ICElabel.setMinEms(16);
      portedit=new EditText(act);
      portedit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
      portedit.setImeOptions(editoptions);
      portedit.setMinEms(3);
      Button save=getbutton(act,R.string.save);
      TextView IPslabel=getlabel(act,R.string.ips);
      detect = new CheckDirectionBox(act);
      detect.setText(R.string.detect);
      detect.setOnCheckedChangeListener( (buttonView,  isChecked)-> {
            final int vis=isChecked?hide:VISIBLE;
            final int lastip=editIPs.length-(haslabel.isChecked()?1:0)-1;
            editIPs[lastip].setVisibility(vis);
            });
      detect.setVisibility(hide);

      testip= new CheckDirectionBox(act); testip.setText(R.string.testip);

      haslabel= new CheckDirectionBox(act); haslabel.setText(R.string.testlabel);
      label = new EditText(act);
           label.setInputType(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);

           label.setImeOptions(editoptions);
           label.setMinEms(10);

      checkhostname=getcheckbox(act,act.getString(R.string.hostname),false);
      final Runnable doHasName= ()->{
            IPslabel.setVisibility(hide);
            final int lastip= editIPs.length;
            for(var i=1;i<lastip;++i)
               editIPs[i].setVisibility(hide);
            editIPs[0].setMinEms(20);
            detect.setVisibility(hide);
            };

       checkhostname.setOnCheckedChangeListener( (buttonView,  isChecked)-> {
         if(isChecked) {
            Applic.argToaster(act,"hostname is slow",Toast.LENGTH_LONG);
            doHasName.run();
            }
         else {
            IPslabel.setVisibility(VISIBLE);
            detect.setVisibility(VISIBLE);
            final int nrips=editIPs.length-(detect.isChecked()?1:0)-(haslabel.isChecked()?1:0);
            for(var i=1;i<nrips;++i)
               editIPs[i].setVisibility(VISIBLE);
            editIPs[0].setMinEms(6);
            }
         });


      setColorFilter(label.getBackground().mutate(),agetColor(act,android.R.color.holo_red_light));
      haslabel.setOnCheckedChangeListener( (buttonView,  isChecked)-> {
            final int vis=isChecked?VISIBLE:hide;
            label.setVisibility(vis);
            label.requestFocus();
            if(checkhostname.isChecked()||ICE.isChecked())
               return;
            final int vis2=isChecked?hide:VISIBLE;
            final int lastip=editIPs.length-(detect.isChecked()?1:0)-1;
            editIPs[lastip].setVisibility(vis2);
            });

         
      passiveonly=new CheckDirectionRadio(act);
      passiveonly.setText(R.string.passiveonly);
      TextView Portlabel=getlabel(act,R.string.port);
        activeonly = new CheckDirectionRadio(act);
        activeonly.setText(R.string.activeonly);
        both = new CheckDirectionRadio(act);
        both.setText(R.string.both);
        CheckDirectionRadio[] actives={passiveonly,activeonly,both};
      Consumer<View> test1=
      buttonView-> {
         if(buttonView==activeonly)
            detect.setChecked(false);
         final var vis=buttonView==passiveonly?hide:VISIBLE;
         Portlabel.setVisibility(vis);
         portedit.setVisibility(vis);
         final var vis2=(buttonView==activeonly||(buttonView==passiveonly&&!testip.isChecked()))?hide:VISIBLE;
         detect.setVisibility(vis2);
          final var vis3=buttonView==activeonly?hide:VISIBLE;
          testip.setVisibility(vis3);
          if(checkhostname.isChecked()&&buttonView != passiveonly) {
            editIPs[0].setVisibility(VISIBLE);
            doHasName.run();
          }
          else {
              final var vis4 = (buttonView == passiveonly && !testip.isChecked()) ? hide : VISIBLE;
              final int ipnr = editIPs.length - (haslabel.isChecked() ? 1 : 0) - (detect.isChecked() ? 1 : 0);
              for (int i = 0; i < ipnr; i++)
                  editIPs[i].setVisibility(vis4);
             }
      };
      Object[] tests={test1};
        setradiotest(actives,tests);
      testip.setOnCheckedChangeListener( (buttonView,  isChecked)-> {
         final var vis2=(passiveonly.isChecked()&&!isChecked)?hide:VISIBLE;
         final var vis=(activeonly.isChecked()||(passiveonly.isChecked()&&!testip.isChecked()))?hide:VISIBLE;
         detect.setVisibility(vis);
         final int ipnr=editIPs.length-(haslabel.isChecked()?1:0)-(detect.isChecked()?1:0);
         for(int i=0;i<ipnr;i++)
            editIPs[i].setVisibility(vis2);
         });
      receive = new CheckDirectionBox(act);
      receive.setText(R.string.receivefrom);

      TextView Sendlabel=getlabel(act,R.string.sendto);

         Amounts = new CheckDirectionBox(act); Amounts.setText(R.string.amountsname);
         Scans = new CheckDirectionBox(act); Scans.setText(R.string.scansname);
         Stream = new CheckDirectionBox(act); Stream.setText(R.string.streamname);
      CheckDirectionRadio fromnow=new CheckDirectionRadio(act);
      CheckDirectionRadio alldata=new CheckDirectionRadio(act);
      CheckDirectionRadio screenpos=new CheckDirectionRadio(act);
      TextView startlabel=getlabel(act,act.getString(R.string.datapresentuntil));
         alldata.setText(R.string.start);
         fromnow.setText(R.string.now);
      sendfrom=new CheckDirectionRadio[]{alldata,fromnow,screenpos};
       fromrow=new View[]{startlabel, alldata,fromnow,screenpos};

      setradio(sendfrom);
      CheckDirectionBox restore=new CheckDirectionBox(act);restore.setText("Restore");
      if(!Natives.backuphasrestore( ))
         restore.setVisibility(GONE);

      Button Help=getbutton(act,R.string.helpname);
      Help.setOnClickListener(v-> help(R.string.addconnection,act));

      deleteHost=getbutton(act,act.getString(R.string.delete));
      Button Close=getbutton(act,R.string.cancel);
          Password = new CheckDirectionBox(act); Password.setText(R.string.password);
         Password.setChecked(true);
       editpass= new EditText(act);
           editpass.setImeOptions(editoptions);
           editpass.setInputType(InputType.TYPE_TEXT_VARIATION_PASSWORD);
      editpass.setTransformationMethod(new PasswordTransformationMethod());
           editpass.setMinEms(6);
          visible = new CheckDirectionBox(act);// visible.setText(R.string.visible);
          if(!isWearable)
             visible.setText(R.string.connection_show_password);
          visible.setButtonDrawable(R.drawable.password_visible);
//          visible.setButtonDrawable(R.drawable.visibility_toggle);
   //      visible.setMinimumWidth(0); visible.setMinWidth(0);
      visible.setOnCheckedChangeListener( (buttonView,  isChecked)-> {
               var sel=editpass.getSelectionStart();
               editpass.setInputType(isChecked?InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD:InputType.TYPE_TEXT_VARIATION_PASSWORD);
               if(isChecked)
                  editpass.setTransformationMethod(null);
               else
                  editpass.setTransformationMethod(new PasswordTransformationMethod());
               editpass.setSelection(sel);

            });

      Password.setOnCheckedChangeListener( (buttonView,  isChecked)-> {
            final int vis=isChecked?VISIBLE:hide;
            editpass.setVisibility(vis);
            visible.setVisibility(vis);
            });
       Password.setChecked(false); 
       IntSupplier saver= ()-> { 
         final boolean sender= Amounts.isChecked()|| Stream.isChecked()|| Scans.isChecked();
         final boolean receiver=receive.isChecked();
         if(!sender&&!receiver) {
            Applic.argToaster(act, R.string.specifyreceiveordata,Toast.LENGTH_SHORT);
            return -15;
            }
         if(receiver&& Amounts.isChecked()&& Stream.isChecked()&& Scans.isChecked()) {
            Applic.argToaster(act,R.string.allsentnoreceive ,Toast.LENGTH_LONG);
            return -15;
            }        
         hidekeyboard(act); //USE
         int hostnr=Natives.backuphostNr( );
         boolean ice=ICE.isChecked();
         int struse=0;
         String[] names=null;
         final boolean dodetect= detect.isChecked()&&!activeonly.isChecked();
         final var ICEstring=ICElabel.getText().toString();
         if(ice) {
            if(ICEstring.length()<16) {
                Applic.argToaster(act,R.string.ICElabeltooshort,Toast.LENGTH_LONG);
                return -16;
                }
            }
         else {
             names=new String[editIPs.length];
             if(testip.isChecked()||!passiveonly.isChecked()) {
                for (EditText editText : editIPs) {
                   String name = editText.getText().toString();
                   if (name.length() != 0) {
                      names[struse++] = name;
                   }
                }
                }
             int ipmax=editIPs.length-(dodetect?1:0)-(haslabel.isChecked()?1:0);
             if(struse>=ipmax)
                struse=ipmax;
             if((testip.isChecked()&&!dodetect)||activeonly.isChecked()) {
                if(struse==0) {
                   Applic.argToaster(act, R.string.specifyip,Toast.LENGTH_SHORT);
                   return -15;
                   }
                }
              }


         long starttime=(alldata.getVisibility()!=VISIBLE||alldata.isChecked())?0L:(fromnow.isChecked()? System.currentTimeMillis():Natives.getstarttime())/1000L;
         int pos=Natives.changebackuphost(hostindex,names,struse,dodetect,portedit.getText().toString(), Amounts.isChecked(),Stream.isChecked(),Scans.isChecked(),restore.isChecked(),receiver,activeonly.isChecked(),passiveonly.isChecked(),Password.isChecked()?editpass.getText().toString():null,starttime,haslabel.isChecked()?label.getText().toString():null,testip.isChecked(),checkhostname.isChecked(), ice?ICEstring:null,one.isChecked());

         if(pos<0) {
            String mess=changehostError(act, pos);
            Applic.argToaster(act,mess,Toast.LENGTH_SHORT);
            return pos;
            }    

         if(!receiver&& !(Amounts.isChecked()&& Stream.isChecked()&& Scans.isChecked())) {
            Applic.argToaster(act,R.string.notalldata ,Toast.LENGTH_LONG);
            }        
         configchanged=true;
         if(pos==hostnr)  {
            deleteHost.setVisibility(VISIBLE);
            hostadapt.notifyItemInserted(pos);
            }
         else
            hostadapt.notifyItemChanged(pos);
         return pos;
         };
      save.setOnClickListener(v->{
           if(saver.getAsInt()>=0)
              act.doonback();
        }); 
      deleteHost.setOnClickListener(v->{
         deleteconfirmation(act) ;
         //alarms.setEnabled( Natives.isreceiving( ));
         });
      reset=getbutton(act,R.string.resenddata);
      reset.setOnClickListener(v->{ 
         if(hostindex>=0) {
            resentconfirmation(act,hostindex);
            }
         });
      CheckDirectionBox[] boxes={Amounts,Scans,Stream,restore};
       CompoundButton.OnCheckedChangeListener needport =(buttonView, isChecked)-> {
         if(sendchecked==null)
            return;
         var vis=INVISIBLE;
         for(int i=0;i<3;i++) {
            if(!sendchecked[i]&&boxes[i].isChecked()) {
               vis=VISIBLE;
               }
            }
         for(View v:fromrow)
            v.setVisibility(vis);
         };
      for(CheckDirectionBox vi:boxes) {
         vi.setOnCheckedChangeListener(needport);
         }
     hostview=new ScrollView(act);
      visible.setPaddingRelative(0,0,(int)(GlucoseCurve.metrics.density*5.0),0);
    var iceviews=new View[]{ICE,ICElabellabel,ICElabel,zero,one};
      Sendlabel.setPaddingRelative((int)(GlucoseCurve.metrics.density*10.0),0,0,0);
   Stream.setPaddingRelative(0,0,(int)(GlucoseCurve.metrics.density*5.0),0);
    var firstrow=new View[]{Portlabel, portedit, checkhostname,IPslabel, detect};
    var directions=new View[]{passiveonly, activeonly, both};
      ViewGroup layout;
      if(isWearable) {
         getMargins(save).topMargin=(int)(GlucoseCurve.metrics.density*5.0);
         layout=new Layout(act, (l, w, h) -> {
            hideSystemUI(act);
            final int[] ret={w,h};
            return ret;

         }, new View[]{ICE},new View[]{ Portlabel},new View[] {portedit},new View[]{checkhostname},new View[]{new Space(act),IPslabel,detect,new Space(act)},new View[]{ICElabellabel},new View[]{ICElabel},sides, new View[]{editIPs[0]},new View[]{editIPs[1]},editIPs.length>=3?new View[]{editIPs[2]}:null,editIPs.length>=4?new View[]{editIPs[3]}:null ,new View[] {testip},new View[] {haslabel},new View[]{label},
               new View[]{passiveonly},new View[]{activeonly},new View[]{both},new View[] {receive},new View[] {Sendlabel,Stream},new View[]{Scans,Amounts},new View[]{startlabel},new View[]{alldata,fromnow},new View[]{screenpos} ,new View[]{Password },new View[]{editpass,visible},new View[]{deleteHost,Close},new View[] {reset},new View[]{save});

      layout.setPaddingRelative((int)(GlucoseCurve.metrics.density*4.0),0,(int)(GlucoseCurve.metrics.density*10.0),(int)(GlucoseCurve.metrics.density*4));
         }
      else {
         ConnectionUi.styleButton(act,Close,ClinicalUi.ButtonRole.SECONDARY);
         ConnectionUi.styleButton(act,save,ClinicalUi.ButtonRole.PRIMARY);
         ConnectionUi.styleButton(act,deleteHost,ClinicalUi.ButtonRole.DANGER);
         ConnectionUi.styleButton(act,reset,ClinicalUi.ButtonRole.DANGER);
         ConnectionUi.styleButton(act,Help,ClinicalUi.ButtonRole.SECONDARY);
         ConnectionUi.styleInput(portedit);
         ConnectionUi.styleInput(ICElabel);
         ConnectionUi.styleInput(label);
         ConnectionUi.styleInput(editpass);
         ICElabel.setHint(R.string.icelabel);
         label.setHint(R.string.testlabel);
         editpass.setHint(R.string.password);
         for(int index=0;index<editIPs.length;index++) {
            ConnectionUi.styleInput(editIPs[index]);
            editIPs[index].setHint(act.getString(R.string.connection_address_number,index+1));
            }
         for(CheckDirectionRadio choice:new CheckDirectionRadio[]{zero,one,
               passiveonly,activeonly,both,alldata,fromnow,screenpos})
            ConnectionUi.choice(act,choice);
         LinearLayout content=ConnectionUi.content(act);
         content.addView(ClinicalUi.header(act,
               act.getString(R.string.connection_mirror_editor_title),Close));
         content.addView(ConnectionUi.intro(act,R.string.connection_mirror_editor_intro));
         content.addView(ClinicalUi.sectionLabel(act,
               act.getString(R.string.connection_transport_section)));
         content.addView(ClinicalUi.card(act,
               ConnectionUi.directToggle(act,ICE),ICElabel,zero,one));
         content.addView(ClinicalUi.sectionLabel(act,
               act.getString(R.string.connection_endpoint_section)));
         LinearLayout endpointCard=new LinearLayout(act);
         endpointCard.setOrientation(LinearLayout.VERTICAL);
         endpointCard.setBackground(ClinicalUi.surface(act,false,false));
         endpointCard.setPadding(ClinicalUi.dp(act,6),ClinicalUi.dp(act,6),
               ClinicalUi.dp(act,6),ClinicalUi.dp(act,6));
         endpointCard.addView(ClinicalUi.fieldRow(act,act.getString(R.string.port),portedit));
         endpointCard.addView(ConnectionUi.directToggle(act,checkhostname));
         endpointCard.addView(ConnectionUi.directToggle(act,detect));
         for(EditText address:editIPs)
            endpointCard.addView(address,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
         endpointCard.addView(ConnectionUi.directToggle(act,testip));
         endpointCard.addView(ConnectionUi.directToggle(act,haslabel));
         endpointCard.addView(label,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
         content.addView(endpointCard);
         content.addView(ClinicalUi.sectionLabel(act,
               act.getString(R.string.connection_direction_section)));
         content.addView(ClinicalUi.card(act,passiveonly,activeonly,both));
         content.addView(ClinicalUi.sectionLabel(act,
               act.getString(R.string.connection_data_flow_section)));
         content.addView(ClinicalUi.card(act,
               ConnectionUi.directToggle(act,receive),
               ConnectionUi.directToggle(act,Amounts),
               ConnectionUi.directToggle(act,Scans),
               ConnectionUi.directToggle(act,Stream),
               ConnectionUi.directToggle(act,restore)));
         TextView fromHint=ClinicalUi.body(act,act.getString(R.string.connection_send_from_hint));
         fromHint.setPaddingRelative(ClinicalUi.dp(act,4),ClinicalUi.dp(act,12),
               ClinicalUi.dp(act,4),ClinicalUi.dp(act,6));
         content.addView(fromHint);
         content.addView(ClinicalUi.card(act,alldata,fromnow,screenpos));
         content.addView(ClinicalUi.sectionLabel(act,
               act.getString(R.string.connection_security_section)));
         content.addView(ClinicalUi.card(act,
               ConnectionUi.directToggle(act,Password),editpass,
               ConnectionUi.directToggle(act,visible)));
         content.addView(ClinicalUi.sectionLabel(act,
               act.getString(R.string.connection_support_section)));
         content.addView(ClinicalUi.card(act,Help));
         LinearLayout destructive=new LinearLayout(act);
         destructive.setOrientation(LinearLayout.HORIZONTAL);
         destructive.setPadding(0,ClinicalUi.dp(act,18),0,0);
         destructive.addView(deleteHost,new LinearLayout.LayoutParams(0,WRAP_CONTENT,1.0f));
         View destructiveGap=new View(act);
         destructive.addView(destructiveGap,new LinearLayout.LayoutParams(ClinicalUi.dp(act,12),1));
         destructive.addView(reset,new LinearLayout.LayoutParams(0,WRAP_CONTENT,1.0f));
         content.addView(destructive);
         LinearLayout.LayoutParams saveParams=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
         saveParams.topMargin=ClinicalUi.dp(act,12);
         save.setLayoutParams(saveParams);
         content.addView(save);
         layout=content;
         }
      Close.setOnClickListener(v-> act.doonback());
      hostview.addView(layout);
      hostview.setFillViewport(true);
      hostview.setSmoothScrollingEnabled(true);
       hostview.setVerticalScrollBarEnabled(Applic.scrollbar);
       hostview.setScrollbarFadingEnabled(true);
       act.addMyContentView(hostview, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
       hostview.setBackgroundColor(backgroundcolor);
    Consumer<Boolean> setICE=(isChecked) -> {
            final int vis=isChecked?VISIBLE:hide;
            for(int i=1;i<iceviews.length;++i) {
                iceviews[i].setVisibility(vis);
                }
            final int notvis=!isChecked?VISIBLE:hide;
            for(var el:firstrow) {
                el.setVisibility(notvis);
                }
            final int nrips=editIPs.length-(detect.isChecked()?1:0)-(haslabel.isChecked()?1:0);
            for(var i=0;i<nrips;++i)
               editIPs[i].setVisibility(notvis);
            for(var el:directions) {
                el.setVisibility(notvis);
                }
            testip.setVisibility(notvis);
            };

      ICE.setOnCheckedChangeListener( (buttonView,  isChecked)-> {
            setICE.accept(isChecked);
            });
     ICE.setChecked(false);
      // setICE.accept(false);
      }
   void changehostview(MainActivity act,final int index,String[] names,boolean dodetect,String port,String pass,View parent) {
         parent.setVisibility(GONE);
      if(hostview==null)
         makehostview(act);
      else {
         hostview.setVisibility(VISIBLE);
         hostview.bringToFront();
         visible.setChecked(false);
         }
      act.setonback(() -> {
            parent.setVisibility(VISIBLE);
            hidekeyboard(act);
            hostview.setVisibility(GONE);
            });
      boolean stream,scans,amounts;
      String ICElabelstr=Natives.getICElabel(index);
      boolean hasICE=ICElabelstr!=null;

      boolean isnew=names==null&&!hasICE;
      deleteHost.setVisibility(isnew?GONE:VISIBLE);
      String labelstr=null;
      if(!isnew) {
         stream=Natives.getbackuphoststream(index);
         scans=Natives.getbackuphostscans(index);
         amounts=Natives.getbackuphostnums(index);
         int recnum=Natives.getbackuphostreceive(index);
         boolean doreceive= (recnum&2)!=0;
         receive.setChecked(doreceive);
         labelstr=Natives.getbackuplabel(index);
         if(labelstr!=null) {
            label.setText(labelstr);
            haslabel.setChecked(true); 
              }
          else {
            label.setText("");
            haslabel.setChecked(false); 
            label.setVisibility(hide);
            }
          if(!isnew&&!hasICE) {
             final boolean dotestip=Natives.getbackuptestip(index);
             final boolean ispassive=Natives.getbackuphostpassive(index);
             testip.setChecked(dotestip);
             final var vis=(ispassive&&!dotestip)?hide:VISIBLE;
             detect.setChecked(dodetect);
             final boolean hasHostname=getbackupHasHostname(index);
             int maxhosts=hasHostname?1:(editIPs.length-(dodetect?1:0)-(labelstr==null?0:1));
              for(int i=0;i<Math.min(names.length,maxhosts);i++) {
                   editIPs[i].setText(names[i]);
                }
                for(int i=0;i<maxhosts;i++)
                       editIPs[i].setVisibility(vis);
                boolean isactiveonly =Natives.getbackuphostactive(index);
                detect.setVisibility((ispassive&&!dotestip||isactiveonly)?hide:VISIBLE);
             if(isactiveonly)
                activeonly.setChecked(true);
             else {
                if(ispassive) {
                   passiveonly.setChecked(true);
                   }
                else
                   both.setChecked(true);
                }
             boolean iswearos=isWearOS(index);
             {if(doLog) {Log.i(LOG_ID,(labelstr!=null?labelstr:"")+" Iswearos("+index+")="+iswearos);};};

             checkhostname.setChecked(hasHostname);
             }
          }
      else {
         stream=false;scans=false;amounts=false;
         haslabel.setChecked(false);
         receive.setChecked(false);
         label.setVisibility(hide);
         label.setText("");
         }
      if(isnew||hasICE) {
         checkhostname.setChecked(false);
         detect.setChecked(false);
         both.setChecked(true);
         testip.setChecked(true);
          } 

      Stream.setChecked(stream); Scans.setChecked(scans); Amounts.setChecked(amounts);
      isasender=stream||scans||amounts;
      sendchecked=new boolean[]{amounts,scans,stream};
      sendfrom[2].setText( tk.glucodata.util.timestring(Natives.getstarttime()));
      if(!isasender) {
         reset.setVisibility(INVISIBLE);
         }
      else {
         reset.setVisibility(VISIBLE);
         }
      sendfrom[0].setChecked(true);
      for(View v:fromrow) v.setVisibility(GONE);
      if(!hasICE) {
          for(int i=names==null?0:names.length;i<editIPs.length;i++) editIPs[i].setText("");
          portedit.setText(port);
          ICE.setChecked(false);
          ICElabel.setText("");
          one.setChecked(false);
          }
      else {
        ICElabel.setText(ICElabelstr);
        boolean side= getICEside(index);
        one.setChecked(side);
        ICE.setChecked(true);
        }
      if(pass!=null&&pass.length()>0) {
          editpass.setText(pass);
          Password.setChecked(true);
          editpass.setVisibility(VISIBLE);
          }
      else {
         editpass.setText("");
         Password.setChecked(false);
         editpass.setVisibility(hide);
         }

      hostindex=index;
      }
   void changehostview(MainActivity act,int index,View parent) {
      String[] names=Natives.getbackupIPs(index);
      String port=Natives.getbackuphostport(index);
      String pass= Natives.getbackuppassword(index);
      changehostview(act,index,names,Natives.detectIP(index),port,pass, parent) ;
      }

   private void clinicalHostInfo(MainActivity act,View parent,int position) {
      EnableControls(parent,false);
      Button close=ConnectionUi.headerButton(act,R.string.closename);
      LinearLayout modify=ClinicalUi.actionRow(act,act.getString(R.string.modify),
            act.getString(R.string.connection_modify_mirror_hint));
      LinearLayout qr=BuildConfig.minSDK>=20?ClinicalUi.actionRow(act,"QR",
            act.getString(R.string.connection_mirror_qr_hint)):null;
      CheckDirectionBox disabled=getcheckbox(act,R.string.off,
            Natives.getHostDeactivated(position));
      disabled.setOnCheckedChangeListener((button,checked)-> {
         Natives.setHostDeactivated(position,checked);
         hostadapt.notifyItemChanged(position);
         });
      TextView info=new TextView(act);
      sethtml(info,mirrorStatus(position));
      info.setTextColor(ClinicalUi.primaryText(act));
      info.setTextSize(TypedValue.COMPLEX_UNIT_SP,15.0f);
      info.setLineSpacing(0.0f,1.18f);
      info.setPadding(ClinicalUi.dp(act,16),ClinicalUi.dp(act,14),
            ClinicalUi.dp(act,16),ClinicalUi.dp(act,14));
      info.setBackground(ClinicalUi.surface(act,false,false));
      LinearLayout content=ConnectionUi.content(act);
      content.addView(ClinicalUi.header(act,
            act.getString(R.string.connection_mirror_details_title),close));
      content.addView(ConnectionUi.intro(act,R.string.connection_mirror_details_intro));
      content.addView(ClinicalUi.sectionLabel(act,
            act.getString(R.string.connection_status_section)));
      content.addView(info);
      content.addView(ClinicalUi.sectionLabel(act,
            act.getString(R.string.connection_options_section)));
      if(qr!=null)
         content.addView(ClinicalUi.card(act,
               ConnectionUi.directToggle(act,disabled),modify,qr));
      else
         content.addView(ClinicalUi.card(act,
               ConnectionUi.directToggle(act,disabled),modify));
      ScrollView screen=ConnectionUi.screen(act,content);
      ConnectionUi.fullScreen(act,screen);
      Runnable closeRun=()-> {
         removeContentView(screen);
         EnableControls(parent,true);
         };
      act.setonback(closeRun);
      close.setOnClickListener(view->{
         act.poponback();
         closeRun.run();
         });
      modify.setOnClickListener(view->changehostview(act,position,screen));
      if(qr!=null)
         qr.setOnClickListener(view->QRmake.show(act,getbackJson(position)));
      }

   void        showhostinfo(final MainActivity act,final View parview,int pos) {
   if(!isWearable) {
      clinicalHostInfo(act,parview,pos);
      return;
      }
   if(!isWearable)
         EnableControls(parview,false);
      var close=getbutton(act,R.string.closename);
      var modify=getbutton(act,R.string.modify);


      var info=new TextView(act);
      final int pad=(int)(GlucoseCurve.metrics.density*7.0);
      if(!isWearable) info.setPadding(pad,0,pad,0);

      var deactive=getcheckbox(act,R.string.off,Natives.getHostDeactivated(pos));
      deactive.setOnCheckedChangeListener( (buttonView,  isChecked)->  {
                Natives.setHostDeactivated(pos,isChecked);
                hostadapt.notifyItemChanged(pos);
               }
                );
      sethtml(info, mirrorStatus(pos));

      ViewGroup layall;
ViewGroup.LayoutParams params;
      if(isWearable) {
          if(!useclose) close.setVisibility(GONE);
         var space1=new Space(act);
         var space2=getlabel(act,"      ");
          Layout layout=new Layout(act,new View[]{space1,deactive,modify,space2}, new View[]{info},new View[]{close});
   //      layout.round=true;
         layout.setBackgroundColor(Applic.backgroundcolor);
         var leftpad=(int)(GlucoseCurve.getwidth()*.1);
         layout.setPaddingRelative(leftpad,leftpad,(int)(GlucoseCurve.getwidth()*0.08), leftpad*2);
         var scroll= new ScrollView(act);
         scroll.setFillViewport(true);
         scroll.setVerticalScrollBarEnabled(true);
         scroll.setScrollbarFadingEnabled(true);
         scroll.setSmoothScrollingEnabled(true);
         scroll.addView(layout);
         layall=scroll;
        params=new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT);
         }
      else {
           var modmar=Layout.getMargins(modify);
           var hormar= (int)(GlucoseCurve.metrics.density*10);
           modmar.setMarginStart(hormar);
           var closemar=Layout.getMargins(close);
           closemar.setMarginEnd(hormar);
           View[] firstrow;
           if(BuildConfig.minSDK>=20) {
                Button qr=getbutton(act,"QR");
                qr.setOnClickListener(v->  {
                      if(pos>=0) {
                            String jsonstr=getbackJson(pos);
                            QRmake.show(act,jsonstr);
                            }
                     });
                    firstrow=new View[]{modify,deactive,qr,close} ;
                     }
               else {
                     firstrow= new View[]{modify,deactive,close} ;
                    }
                                                                                                
           Layout layout=new Layout(act, (l, w, h) -> {
           /*
                var x=GlucoseCurve.getwidth()-MainActivity.systembarRight-w;
                if(x<MainActivity.systembarLeft)
                   x=MainActivity.systembarLeft;
                l.setX(GlucoseCurve.getwidth()-MainActivity.systembarRight-w);
                l.setY(MainActivity.systembarTop);
                */
                final int[] lret={w,h};
                return lret;
                },firstrow , new View[]{info});
          // info.setPadding(pad,0,pad,0);
            layout.setBackgroundResource(R.drawable.dialogbackground);
   //          layout.setRotation(90);
         layall=layout;
           params =    new FrameLayout.LayoutParams( WRAP_CONTENT, WRAP_CONTENT, Gravity.RIGHT);
            }

      modify.setOnClickListener(v->     changehostview(act,pos,layall));
//      final var lpar=isWearable?MATCH_PARENT: WRAP_CONTENT;
      act.addMyContentView(layall, params);
      var margs=getMargins(layall);
      margs.topMargin=MainActivity.systembarTop*3/4;
      margs.leftMargin=MainActivity.systembarLeft*3/4;
      margs.rightMargin=MainActivity.systembarRight*3/4;
      Runnable closerun= ()-> {
         removeContentView(layall);

   if(!isWearable)
         EnableControls(parview,true);
         };
      act.setonback(closerun);    
      close.setOnClickListener(v->  {
         act.poponback();    
         closerun.run();
         });
      }
   void addhostview(MainActivity act,View parent) {
      changehostview(act,-1,null,false,defaultport,"",parent) ;
      }


   HostViewAdapter hostadapt;
//   Button alarms;
   public  void mkbackupview(MainActivity act) {
      act.themeLightBars();
      act.showui=true;
      if(!isWearable&&!Natives.getsystemUI()) {
         act.showSystemUI();
         Applic.app.getHandler().postDelayed( ()->{
         realmkbackupview(act,true); },1);
         }
       else
         realmkbackupview(act,true);
   //    Applic.app.getHandler().postDelayed( ()-> realmkbackupview(act),1); //for what was it needed?
      }

   static boolean validMirrorPort(int port,int sslPort) {
      return port>=1024&&port<=65535&&port!=17580&&port!=sslPort;
      }

   private void clinicalBackupView(MainActivity act,boolean lightback) {
      configchanged=false;
      String[] hostNames=gethostnames();
      if(hostNames[3]!=null)
         Natives.networkpresent();
      EditText receivePort=getnumedit(act,Natives.getreceiveport());
      ConnectionUi.styleInput(receivePort);
      Button close=ConnectionUi.headerButton(act,R.string.closename);
      Button savePort=ClinicalUi.button(act,act.getString(R.string.save),
            ClinicalUi.ButtonRole.PRIMARY);
      Button addConnection=ClinicalUi.button(act,act.getString(R.string.addconnectionbutton),
            ClinicalUi.ButtonRole.PRIMARY);
      LinearLayout turn=ClinicalUi.actionRow(act,act.getString(R.string.turnserver),
            act.getString(R.string.connection_turn_action_hint));
      LinearLayout sync=ClinicalUi.actionRow(act,act.getString(R.string.sync),
            act.getString(R.string.connection_sync_hint));
      LinearLayout reinitialize=ClinicalUi.actionRow(act,act.getString(R.string.reinit),
            act.getString(R.string.connection_reinit_hint));
      LinearLayout autoQr=BuildConfig.minSDK>=20?ClinicalUi.actionRow(act,
            act.getString(R.string.autoqr),act.getString(R.string.connection_auto_qr_hint)):null;
      LinearLayout battery=android.os.Build.VERSION.SDK_INT>=android.os.Build.VERSION_CODES.M?
            ClinicalUi.actionRow(act,act.getString(R.string.dozemode),
                  act.getString(R.string.connection_battery_hint)):null;
      LinearLayout mirrorHelp=ClinicalUi.actionRow(act,act.getString(R.string.helpname),
            act.getString(R.string.connection_mirror_help_hint));
      CheckDirectionBox staticNumbers=new CheckDirectionBox(act);
      staticNumbers.setText(R.string.dontchangeamounts);
      staticNumbers.setChecked(Natives.staticnum());
      staticNumbers.setOnCheckedChangeListener((button,checked)-> {
         Natives.setstaticnum(checked);
         if(checked)
            BluetoothGlucoseMeter.stopDevices();
         else
            BluetoothGlucoseMeter.startDevices();
         });

      LinearLayout content=ConnectionUi.content(act);
      content.setLayoutParams(new ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT));
      content.addView(ClinicalUi.header(act,
            act.getString(R.string.connection_mirror_title),close));
      content.addView(ConnectionUi.intro(act,R.string.connection_mirror_intro));
      content.addView(ClinicalUi.sectionLabel(act,
            act.getString(R.string.connection_this_device_section)));
      TextView addresses=ConnectionUi.status(act,act.getString(R.string.connection_addresses,
            hostNames[1]==null?"—":hostNames[1],hostNames[0]==null?"—":hostNames[0],
            hostNames[2]==null?"—":hostNames[2]),false);
      content.addView(addresses);
      LinearLayout portCard=ClinicalUi.card(act,
            ClinicalUi.fieldRow(act,act.getString(R.string.connection_receive_port),receivePort));
      LinearLayout.LayoutParams portParams=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
      portParams.topMargin=ClinicalUi.dp(act,10);
      portCard.setLayoutParams(portParams);
      content.addView(portCard);

      TextView formError=ConnectionUi.status(act,Natives.serverError(),true);
      LinearLayout.LayoutParams errorParams=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
      errorParams.topMargin=ClinicalUi.dp(act,10);
      formError.setLayoutParams(errorParams);
      content.addView(formError);
      content.addView(ClinicalUi.sectionLabel(act,
            act.getString(R.string.connection_mirrors_section)));
      RecyclerView hosts=new RecyclerView(act);
      hosts.setLayoutManager(new LinearLayoutManager(act));
      // The whole Mirror page owns vertical scrolling.  Let this embedded list
      // expand to its content so it cannot trap swipes or consume the only
      // scrollable region on shorter phone screens.
      hosts.setNestedScrollingEnabled(false);
      hosts.setFocusable(false);
      hosts.setClipToPadding(false);
      hosts.setPadding(0,0,0,ClinicalUi.dp(act,8));
      content.addView(hosts,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
      LinearLayout.LayoutParams addParams=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
      addParams.topMargin=ClinicalUi.dp(act,8);
      addConnection.setLayoutParams(addParams);
      content.addView(addConnection);
      content.addView(ClinicalUi.sectionLabel(act,
            act.getString(R.string.connection_options_section)));
      if(autoQr!=null&&battery!=null)
         content.addView(ClinicalUi.card(act,turn,sync,reinitialize,autoQr,battery,
               ConnectionUi.directToggle(act,staticNumbers)));
      else if(autoQr!=null)
         content.addView(ClinicalUi.card(act,turn,sync,reinitialize,autoQr,
               ConnectionUi.directToggle(act,staticNumbers)));
      else if(battery!=null)
         content.addView(ClinicalUi.card(act,turn,sync,reinitialize,battery,
               ConnectionUi.directToggle(act,staticNumbers)));
      else
         content.addView(ClinicalUi.card(act,turn,sync,reinitialize,
               ConnectionUi.directToggle(act,staticNumbers)));
      content.addView(ClinicalUi.sectionLabel(act,
            act.getString(R.string.connection_support_section)));
      content.addView(ClinicalUi.card(act,mirrorHelp));
      LinearLayout.LayoutParams saveParams=new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
      saveParams.topMargin=ClinicalUi.dp(act,14);
      savePort.setLayoutParams(saveParams);
      content.addView(savePort);

      hostadapt=new HostViewAdapter(content);
      hosts.setAdapter(hostadapt);
      addConnection.setOnClickListener(view->addhostview(act,content));
      turn.setOnClickListener(view->TurnServer.show(act,content));
      sync.setOnClickListener(view->Applic.switchSync());
      reinitialize.setOnClickListener(view->MessageSender.reinit());
      if(autoQr!=null)
         autoQr.setOnClickListener(view->makeAutoQR(act,content));
      if(battery!=null)
         battery.setOnClickListener(view->Battery.batteryscreen(act,content));
      mirrorHelp.setOnClickListener(view->help(R.string.connectionoverview,act));
      savePort.setOnClickListener(view->{
         int parsed;
         try {
            parsed=Integer.parseInt(receivePort.getText().toString().trim());
            }
         catch(Throwable error) {
            formError.setText(R.string.connection_invalid_port);
            formError.setVisibility(VISIBLE);
            return;
            }
         if(!validMirrorPort(parsed,Natives.getsslport())) {
            formError.setText(R.string.connection_mirror_port_error);
            formError.setVisibility(VISIBLE);
            return;
            }
         Natives.setreceiveport(String.valueOf(parsed));
         formError.setText(Natives.serverError());
         formError.setVisibility(formError.length()==0?GONE:VISIBLE);
         hidekeyboard(act);
         Applic.argToaster(act,R.string.saved,Toast.LENGTH_SHORT);
         });
      ScrollView screen=ConnectionUi.screen(act,content);
      Runnable closeRun=()-> {
         if(lightback) {
            act.lightBars(!getInvertColors());
            }
         if(hostview!=null)
            removeContentView(hostview);
         hidekeyboard(act);
         removeContentView(screen);
         if(configchanged) {
            Natives.resetnetwork();
            Applic.wakemirrors();
         }
         Applic.updateservice(act,Natives.getusebluetooth());
         // A standalone route opened from More owns the system-UI/menu
         // restoration.  A nested Settings route must simply reveal its
         // existing parent and leave that parent's back stack and bars intact.
         if(lightback) {
            act.showui=false;
            Applic.app.getHandler().postDelayed(act::hideSystemUI,1);
            if(Menus.on)
               Menus.show(act);
            }
         };
      act.setonback(closeRun);
      close.setOnClickListener(view->{
         act.poponback();
         closeRun.run();
         });
      content.setBackgroundColor(ClinicalUi.window(act));
      ConnectionUi.fullScreen(act,screen);
      }

   public  void realmkbackupview(MainActivity act,boolean lightback) {
   if(!isWearable) {
      clinicalBackupView(act,lightback);
      return;
      }
   configchanged=false;
    // activity=act;
    String[] thishost=gethostnames();
    if(thishost[3]!=null)
     Natives.networkpresent();
     TextView ip= isWearable? getlabel(act,thishost[1]==null?"wlan: null":thishost[1]): getlabel(act,"wlan: "+thishost[1]);
     View p2p= (thishost[0]==null)?new Space(act):getlabel(act,"p2p: "+thishost[0]);
     View blpan= (thishost[2]==null)?new Space(act):getlabel(act,"bt-pan: "+thishost[2]);
     String port=Natives.getreceiveport();
     TextView labport=getlabel(act,R.string.port);
     EditText portview=getnumedit(act, port);

     portview.setMinEms(2);

     Button hosts=getbutton(act,R.string.addconnectionbutton);
     Button Help=getbutton(act,R.string.helpname);
      Help.setOnClickListener(v->
         help(R.string.connectionoverview,act) );

     Button Sync=getbutton(act,act.getString(R.string.sync));
      Sync.setOnClickListener(v-> {
          Applic.switchSync();
          });
     Button reinit=getbutton(act,R.string.reinit);
      reinit.setOnClickListener(v-> {
      MessageSender.reinit();
      }
      );
//     boolean[] issaved={false};
      //alarms=getbutton(act,R.string.alarms);
   //      if(!Natives.isreceiving( )) { alarms.setEnabled(false); }

     final Button battery = new Button(act);


     Button Cancel=getbutton(act,R.string.closename);
      Button Save=getbutton(act,R.string.save);
      Save.setVisibility(INVISIBLE);
      changer ch=new changer(Save);
      portview.addTextChangedListener(ch);
      RecyclerView recycle = new RecyclerView(act);
      LinearLayoutManager lin = new LinearLayoutManager(act);
      recycle.setLayoutManager(lin);

      CheckDirectionBox staticnum = new CheckDirectionBox(act);
      staticnum.setOnCheckedChangeListener( (buttonView,  isChecked)-> {
        Natives.setstaticnum(isChecked);
        if(!isWearable) {
            if(isChecked) {
                BluetoothGlucoseMeter.stopDevices();
                }
            else {
                BluetoothGlucoseMeter.startDevices();
                }
            }
        });

      staticnum.setText(R.string.dontchangeamounts);
      staticnum.setChecked(Natives.staticnum());
      if(!isWearable) {
         var lineheight=staticnum.getLineHeight();
         recycle.setMinimumHeight(lineheight*6);
         }
      else {
             recycle.setPadding(0,(int)(GlucoseCurve.metrics.density*7.0),0,(int)(GlucoseCurve.metrics.density*3.0));
         }
      View lay;

      var errstr=Natives.serverError();
      var errorrow=errstr.length()>0?new View[]{getlabel(act,errstr)}:null;
      var turnserver=getbutton(act,R.string.turnserver);
      if(isWearable) {
         CheckDirectionBox wifi=getcheckbox(act,act.getString(R.string.wifi),getWifi());
         wifi.setOnCheckedChangeListener( (buttonView,  isChecked)-> {
            Natives.setWifi(isChecked);
            if(isChecked) {
               usewifi(); 
               }
            else
               UseWifi.stopusewifi();
            });
         if(!useclose) Cancel.setVisibility(INVISIBLE);
         final var width=GlucoseCurve.getwidth();
         getMargins(labport).setMarginStart((int)(width*0.12));
         getMargins(Save).setMarginEnd((int)(width*0.12));
         var margIP=getMargins(ip);
         margIP.setMarginStart((int)(width*0.01));
   //      if(doLog) ip.setText("2a01:59f:a075:b0d1:a4ef:afff:fec4:59f2");
         //final Layout layout=new Layout(act, new View[]{getlabel(act,act.getString(R.string.thishost))},new View[]{blpan},new View[]{p2p},new View[]{ip},new View[]{new Space(act),labport,portview,Save,new Space(act)},new View[]{recycle},new View[] {hosts},new View[]{staticnum},new View[]{Sync,reinit},new View[]{space1,wifi,alarms,space2},errorrow,new View[]{Cancel});
         final Layout layout=new Layout(act, new View[]{getlabel(act,act.getString(R.string.thishost))},new View[]{labport,portview,Save},new View[]{ip},new View[]{blpan},new View[]{p2p},new View[]{recycle},new View[] {hosts},new View[]{staticnum},new View[]{Sync,reinit},new View[]{wifi},errorrow,new View[]{Cancel});
   //        var hori=new NestedScrollView(act);
         var hori=new ScrollView(act);
         hori.setFillViewport(true);
   //        hori.setSmoothScrollingEnabled(false);
          hori.setVerticalScrollBarEnabled(Applic.scrollbar);
   //       hori.setHorizontalScrollBarEnabled(Applic.horiScrollbar);
         hori.setScrollbarFadingEnabled(true);
         hori.setSmoothScrollingEnabled(true);
         int height=GlucoseCurve.getheight();
         hori.setMinimumHeight(height);
         hori.addView(layout);
         lay=hori;
         int pad=(int)(GlucoseCurve.metrics.density*5);
         layout.setPaddingRelative((int)(GlucoseCurve.metrics.density*6),pad,(int)(GlucoseCurve.metrics.density*9),pad);
         }
      else {
        Button autoqr;
         if(BuildConfig.minSDK>=20) {
             autoqr=getbutton(act,R.string.autoqr);
            }
         else {
            autoqr=null;
            }
         var hormarg=(int)(GlucoseCurve.metrics.density*20.0f);
         getMargins(Help).setMarginStart(hormarg);
         getMargins(Cancel).setMarginEnd(hormarg);
         var withqr=BuildConfig.minSDK>=20?new View[]{Help,autoqr,hosts,Cancel}:new View[]{Help,hosts,Cancel};
         var layout=new Layout(act, new View[]{ip,blpan,p2p,labport,portview,Save,turnserver},new View[]{recycle},new View[] {battery,Sync,reinit,staticnum},errorrow,withqr);
        if(BuildConfig.minSDK>=20) {
            autoqr.setOnClickListener(v -> {
                makeAutoQR(act, layout);
                });
          };

       var density=GlucoseCurve.metrics.density;
      layout.setPadding(MainActivity.systembarLeft+(int)(density*10),MainActivity.systembarTop/2,MainActivity.systembarRight+(int)(density*10),MainActivity.systembarBottom+(int)(density*3));

          {if(doLog) {Log.i(LOG_ID,"density="+GlucoseCurve.metrics.density+" systembarTop="+ MainActivity.systembarTop+" systembarLeft="+ MainActivity.systembarLeft);};};
         lay=layout;
         }

      Save.setOnClickListener(v->  {
         Natives.setreceiveport(portview.getText().toString());
         Save.setVisibility(GONE);
         hidekeyboard(act);
      });

      turnserver.setOnClickListener(v->  {
        TurnServer.show(act,lay);
         });
         //alarms.setOnClickListener(v-> tk.glucodata.settings.Settings.alarmsettings(act,lay,issaved));
         hosts.setOnClickListener(v-> addhostview(act,lay));
      hostadapt = new HostViewAdapter(lay); //USE
      recycle.setAdapter(hostadapt);
      recycle.setLayoutParams(new ViewGroup.LayoutParams(  MATCH_PARENT, WRAP_CONTENT));
      Runnable closerun= ()-> {
          if(lightback) act.lightBars(!getInvertColors( ));
         if(hostview!=null)
            removeContentView(hostview);
         hidekeyboard(act);
         removeContentView(lay);
         if(configchanged)  {
            Natives.resetnetwork();
            Applic.wakemirrors();
            }
         Applic.updateservice(act,Natives.getusebluetooth());
         act.showui=false;
         if(!isWearable)
            Applic.app.getHandler().postDelayed(act::hideSystemUI,1);
         if(Menus.on)
            Menus.show(act);

         };
      act.setonback(closerun);    
      Cancel.setOnClickListener(v->  {
         act.poponback();    
         closerun.run();
         });

      if(!isWearable&&android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
         battery.setText(R.string.dozemode);
         battery.setOnClickListener(v-> {
            Battery.batteryscreen(act,lay);
            });
         }
      else {
         battery.setVisibility(GONE);
      }
      lay.setBackgroundColor(Applic.backgroundcolor);
   //   act.themeLightBars();
      act.addMyContentView(lay, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
      }


     class HostViewHolder extends RecyclerView.ViewHolder {
       public HostViewHolder(View view,View parent) {
         super(view);
         view.setOnClickListener(v -> {
             int pos=getAbsoluteAdapterPosition();
             showhostinfo((MainActivity)(v.getContext()),parent,pos);
             });

       }

   }
    public class HostViewAdapter extends RecyclerView.Adapter<HostViewHolder> {
      View pview;
         HostViewAdapter(View parent) {
         this.pview=parent;
         }


private int getMirrorListColor(MainActivity act) {
    if(act.mirrorlistcolor==-1) {
        int[] attrs = new int[] { R.attr.colorMirrorConnection };

        try(TypedArray typedArray = act.obtainStyledAttributes(attrs)) {
            act.mirrorlistcolor = typedArray.getColor(0, android.graphics.Color.RED);
 //           typedArray.recycle();
            }
        }
    return act.mirrorlistcolor;
    }
       @NonNull
      @Override
       public HostViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
         TextView view=new TextView( parent.getContext());

          view.setAccessibilityDelegate(tk.glucodata.Layout.accessDeli);
   //        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
            // view.setTextSize(TypedValue.COMPLEX_UNIT_PX,Applic.largefontsize);
         view.setLayoutParams(new ViewGroup.LayoutParams(  ViewGroup.LayoutParams.MATCH_PARENT, WRAP_CONTENT));
         if(isWearable) {
            final var af=(int)(GlucoseCurve.metrics.density*12.0);
             view.setGravity(Gravity.CENTER);
             view.setPadding(0,0,0,af);
             }
          else {
             view.setTextSize(TypedValue.COMPLEX_UNIT_SP,16.0f);
             view.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
             view.setMinHeight(ClinicalUi.dp(parent.getContext(),72));
             view.setPaddingRelative(ClinicalUi.dp(parent.getContext(),16),
                   ClinicalUi.dp(parent.getContext(),10),ClinicalUi.dp(parent.getContext(),16),
                   ClinicalUi.dp(parent.getContext(),10));
             view.setLineSpacing(0.0f,1.12f);
             view.setBackground(ClinicalUi.surface(parent.getContext(),false,true));
             RecyclerView.LayoutParams params=new RecyclerView.LayoutParams(MATCH_PARENT,WRAP_CONTENT);
             params.topMargin=ClinicalUi.dp(parent.getContext(),5);
             params.bottomMargin=ClinicalUi.dp(parent.getContext(),5);
             view.setLayoutParams(params);
             }
           view.setTextColor(isWearable?getMirrorListColor((MainActivity)view.getContext()):
                 ClinicalUi.primaryText(view.getContext()));
           return new HostViewHolder(view,pview);

       }

   private static final DateFormat hhmm=             new SimpleDateFormat("HH:mm", Locale.US );
      @Override
      public void onBindViewHolder(final HostViewHolder holder, int pos) {
         TextView text=(TextView)holder.itemView;
         String[] names =Natives.getbackupIPs(pos);
          StringBuilder sb = new StringBuilder();
         String port=Natives.getbackuphostport(pos);
         long date=Natives.lastuptodate(pos);
         boolean passive=Natives.getbackuphostpassive(pos);
         String label=Natives.getbackuplabel(pos);
         boolean stream=Natives.getbackuphoststream(pos);
         boolean scans=Natives.getbackuphostscans(pos);
         boolean amounts=Natives.getbackuphostnums(pos);
         int recnum=Natives.getbackuphostreceive(pos);
         boolean off=Natives.getHostDeactivated(pos);
         boolean doreceive= (recnum&2)!=0;
         String ICElabelstr=Natives.getICElabel(pos);
      if(!isWearable) {
         if(off)
            text.setPaintFlags(text.getPaintFlags()|Paint.STRIKE_THRU_TEXT_FLAG);
         else
            text.setPaintFlags(text.getPaintFlags()&~Paint.STRIKE_THRU_TEXT_FLAG);
         String endpoint=ICElabelstr!=null?"ICE":
               ((names!=null&&names.length>0)?names[0]:
                     (Natives.detectIP(pos)?text.getContext().getString(
                           R.string.connection_mirror_detect):"—"));
         String title=(label==null||label.isEmpty())?endpoint:label;
         StringBuilder details=new StringBuilder();
         if(label!=null&&!label.isEmpty()&&!endpoint.equals(label))
            details.append(endpoint);
         if(!passive&&ICElabelstr==null&&port!=null&&!port.isEmpty()) {
            if(details.length()>0) details.append("  •  ");
            details.append(port);
            }
         StringBuilder data=new StringBuilder();
         if(stream) data.append(text.getContext().getString(R.string.streamname));
         if(scans) {
            if(data.length()>0) data.append(", ");
            data.append(text.getContext().getString(R.string.scansname));
            }
         if(amounts) {
            if(data.length()>0) data.append(", ");
            data.append(text.getContext().getString(R.string.amountsname));
            }
         if(data.length()>0) {
            if(details.length()>0) details.append("\n");
            details.append(data);
            }
         if(doreceive) {
            if(details.length()>0) details.append("  •  ");
            details.append(text.getContext().getString(R.string.connection_mirror_receive));
            }
         if(date!=0L) {
            if(details.length()>0) details.append("\n");
            details.append(text.getContext().getString(R.string.connection_mirror_last_sync,
                  bluediag.datestr(date)));
            }
         String rendered=(off?text.getContext().getString(R.string.connection_mirror_disabled)+" · ":"")
               +title+(details.length()>0?"\n"+details:"");
         text.setText(rendered);
         text.setContentDescription(rendered);
         return;
         }
      if(ICElabelstr!=null&&ICElabelstr.length()<16) {
           text.setText(R.string.ICElabeltooshort);
           }
       else  {
         if(off)
             text.setPaintFlags(text.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
         else
             text.setPaintFlags(text.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
         if(label!=null) {
            sb.append(label);
            sb.append(" ");
            }
       if(!isWearable) {
          if(ICElabelstr==null) {
               sb.append((names!=null&&names.length!=0)?names[0]:(Natives.detectIP(pos)?"Detect":"---"));
               if(!passive) {
                  sb.append(" ");
                  sb.append(port);
                  }
               sb.append(' ');
               }
            else {
                  sb.append(" ICE ");
                }
           }
          if(amounts) {
              sb.append("n");
              }
          if(scans) {
              sb.append("s");
              }
          if(stream) { 
              sb.append("b");
            }
          if(doreceive) { 
              sb.append("r");
            }
         if(date!=0L) {
            String str=isWearable?hhmm.format(date):bluediag.datestr(date);

            sb.append("   \u21CB ").append(str);
            }
         text.setText(sb);
         }
      }
           @Override
           public int getItemCount() {
         return Natives.backuphostNr( );

           }

   }
   boolean configchanged=false;

static private String mkreceiveString(Context act,boolean nums, boolean scans, boolean stream) {
        String type=""; 
        if(!nums&& numio.hasNumdata()) {
            type=act.getString(R.string.amountsname); 
            }
        if(!scans&&Natives.hasscans()) {
            final var addstr=act.getString(R.string.scansname); 
            if(!type.isEmpty())
                type+=", "+addstr; 
            else type=addstr; 
            }
        if(!stream&&Natives.hasstreamed( )) {
            final var addstr=act.getString(R.string.streamname); 
            if(!type.isEmpty())
                type+=", "+addstr; 
            else type=addstr; 
            }
        return type;
       }
static void dowhenasked(Context act,boolean nums,boolean scans,boolean stream, Runnable save) {
        String type=mkreceiveString(act,nums,scans,stream) ; 
        if(!type.isEmpty()) {
                Confirm.ask(act,act.getString(R.string.datapresent)+type,act.getString(R.string.overwrite),save);
                return;
               } 
         save.run();
         }
}
