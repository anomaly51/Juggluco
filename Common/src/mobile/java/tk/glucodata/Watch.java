/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2 and 3 sensors.                         */
/*                                                                                   */
/*      Copyright (C) 2021 Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>         */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */

package tk.glucodata;

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.DONT_KILL_APP;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static tk.glucodata.Applic.mgdLmult;
import static tk.glucodata.MessageSender.initwearos;
import static tk.glucodata.settings.Settings.removeContentView;
import static tk.glucodata.util.getcheckbox;

import android.app.Application;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Phone presentation for watch and external-display integrations. */
class Watch {
    private static final boolean TestBridge=BuildConfig.DEBUG;
    private static float glucose=80f;
    private static float trend=-5.2f;
    private static final String LOG_ID="Watch";

    private static void enableMessageReceiver(boolean value) {
        try {
            Application app=Applic.app;
            PackageManager manager=app.getPackageManager();
            ComponentName receiver=new ComponentName(app,MessageReceiver.class);
            manager.setComponentEnabledSetting(receiver,
                    value?COMPONENT_ENABLED_STATE_ENABLED:COMPONENT_ENABLED_STATE_DISABLED,
                    DONT_KILL_APP);
        } catch(Throwable error) {
            Log.stack(LOG_ID,error);
        }
    }

    private static void setuseWearos(boolean value) {
        enableMessageReceiver(value);
    }

    static public void show(MainActivity context) {
        show(context,true);
    }

    static void show(MainActivity context,boolean restoreStandaloneUi) {
        CheckDirectionBox notify=getcheckbox(context,context.getString(R.string.notify),
                Notify.alertwatch);
        CheckDirectionBox separate=getcheckbox(context,context.getString(R.string.separate),
                Notify.alertseparate);
        CheckDirectionBox watchdrip=getcheckbox(context,"Watchdrip",SuperGattCallback.doWearInt);
        CheckDirectionBox gadget=getcheckbox(context,"GadgetBridge",
                SuperGattCallback.doGadgetbridge);
        CheckDirectionBox server=getcheckbox(context,R.string.webserver,
                Natives.getusexdripwebserver());
        final boolean wasGarmin=Natives.getusegarmin();
        CheckDirectionBox garmin=getcheckbox(context,"Garmin / Kerfstok",wasGarmin);
        final boolean wasWear=Applic.useWearos();
        CheckDirectionBox wear=getcheckbox(context,"Wear OS",wasWear);

        notify.setOnCheckedChangeListener((button,checked)->Applic.app.setnotify(checked));
        separate.setOnCheckedChangeListener((button,checked)->{
            Notify.alertseparate=checked;
            Natives.setSeparate(checked);
        });
        watchdrip.setOnCheckedChangeListener((button,checked)->{
            Natives.setwatchdrip(checked);
            tk.glucodata.watchdrip.set(checked);
        });
        gadget.setOnCheckedChangeListener((button,checked)->{
            Natives.setgadgetbridge(checked);
            SuperGattCallback.doGadgetbridge=checked;
        });
        server.setOnCheckedChangeListener((button,checked)->
                Natives.setusexdripwebserver(checked));

        if(wasWear) {
            MessageSender sender=MessageSender.getMessageSender();
            if(sender!=null) sender.finddevices();
            Natives.switchSync();
        }

        Button close=ClinicalUi.button(context,context.getString(R.string.closename),
                ClinicalUi.ButtonRole.SECONDARY);
        Button save=ClinicalUi.button(context,context.getString(R.string.save),
                ClinicalUi.ButtonRole.PRIMARY);
        Button helpButton=ClinicalUi.button(context,context.getString(R.string.helpname),
                ClinicalUi.ButtonRole.SECONDARY);
        LinearLayout wearSettings=ClinicalUi.actionRow(context,
                context.getString(R.string.clinical_watch_wear_settings),
                context.getString(R.string.clinical_watch_wear_settings_hint));
        LinearLayout serverSettings=ClinicalUi.actionRow(context,
                context.getString(R.string.clinical_watch_server_settings),
                context.getString(R.string.clinical_watch_server_settings_hint));
        LinearLayout garminStatus=ClinicalUi.actionRow(context,
                context.getString(R.string.status),
                context.getString(R.string.clinical_watch_garmin_status_hint));
        garmin.setOnCheckedChangeListener((button,checked)->{
            if(checked&&!wasGarmin) Applic.app.numdata.reinit(context);
            garminStatus.setVisibility(checked?VISIBLE:GONE);
        });
        wear.setOnCheckedChangeListener((button,checked)->{
            setuseWearos(checked);
            if(checked) {
                if(!wasWear) initwearos(Applic.app);
                else {
                    MessageSender sender=MessageSender.getMessageSender();
                    if(sender!=null) sender.finddevices();
                }
                Natives.switchSync();
            }
            wearSettings.setVisibility(checked?VISIBLE:GONE);
        });

        LinearLayout content=ClinicalUi.verticalContent(context);
        content.setPaddingRelative(MainActivity.systembarLeft+ClinicalUi.dp(context,20),
                MainActivity.systembarTop+ClinicalUi.dp(context,8),
                MainActivity.systembarRight+ClinicalUi.dp(context,20),
                MainActivity.systembarBottom+ClinicalUi.dp(context,24));
        content.addView(ClinicalUi.header(context,
                context.getString(R.string.clinical_watch_title),close));
        TextView intro=ClinicalUi.body(context,
                context.getString(R.string.clinical_watch_intro));
        intro.setPaddingRelative(ClinicalUi.dp(context,4),0,ClinicalUi.dp(context,4),
                ClinicalUi.dp(context,6));
        content.addView(intro);

        content.addView(ClinicalUi.sectionLabel(context,
                context.getString(R.string.clinical_watch_alerts_section)));
        content.addView(ClinicalUi.card(context,
                ClinicalUi.toggleRow(context,notify,
                        context.getString(R.string.clinical_watch_notify_hint)),
                ClinicalUi.toggleRow(context,separate,
                        context.getString(R.string.clinical_watch_separate_hint))));

        content.addView(ClinicalUi.sectionLabel(context,
                context.getString(R.string.clinical_watch_integrations_section)));
        content.addView(ClinicalUi.card(context,
                ClinicalUi.toggleRow(context,wear,
                        context.getString(R.string.clinical_watch_wear_hint)),
                wearSettings,
                ClinicalUi.toggleRow(context,watchdrip,
                        context.getString(R.string.clinical_watch_watchdrip_hint)),
                ClinicalUi.toggleRow(context,gadget,
                        context.getString(R.string.clinical_watch_gadget_hint))));

        content.addView(ClinicalUi.sectionLabel(context,
                context.getString(R.string.clinical_watch_services_section)));
        content.addView(ClinicalUi.card(context,
                ClinicalUi.toggleRow(context,server,
                        context.getString(R.string.clinical_watch_server_hint)),
                serverSettings,
                ClinicalUi.toggleRow(context,garmin,
                        context.getString(R.string.clinical_watch_garmin_hint)),
                garminStatus));
        wearSettings.setVisibility(wasWear?VISIBLE:GONE);
        garminStatus.setVisibility(wasGarmin?VISIBLE:GONE);

        if(TestBridge) {
            Button test=ClinicalUi.button(context,
                    context.getString(R.string.clinical_watch_test_bridge),
                    ClinicalUi.ButtonRole.SECONDARY);
            test.setOnClickListener(view->{
                int mgdl;
                trend+=.6f;
                if(trend>5f) trend=-5f;
                if(Applic.unit==1) {
                    glucose+=.6f;
                    if(glucose>28f) glucose=2.2f;
                    mgdl=(int)Math.round(glucose*mgdLmult);
                } else {
                    glucose+=13f;
                    if(glucose>500f) glucose=40f;
                    mgdl=(int)glucose;
                }
                Gadgetbridge.sendglucose(""+glucose,mgdl,glucose,trend,
                        System.currentTimeMillis());
            });
            LinearLayout.LayoutParams testParams=new LinearLayout.LayoutParams(MATCH_PARENT,
                    WRAP_CONTENT);
            testParams.topMargin=ClinicalUi.dp(context,14);
            content.addView(test,testParams);
        }

        LinearLayout actions=new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0,ClinicalUi.dp(context,22),0,0);
        actions.addView(helpButton,new LinearLayout.LayoutParams(0,WRAP_CONTENT,1f));
        View gap=new View(context);
        actions.addView(gap,new LinearLayout.LayoutParams(ClinicalUi.dp(context,12),1));
        actions.addView(save,new LinearLayout.LayoutParams(0,WRAP_CONTENT,1f));
        content.addView(actions);

        ScrollView screen=ClinicalUi.scrollScreen(context,content);
        context.addMyContentView(screen,new ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT));
        Runnable dismiss=()->{
            removeContentView(screen);
            // Standalone legacy routes own the menu/system-UI restoration.
            // Settings -> Legacy must simply reveal its existing parent.
            if(restoreStandaloneUi) {
                context.hideSystemUI();
                if(Menus.on) Menus.show(context);
            }
        };
        context.setonback(dismiss);
        close.setOnClickListener(view->context.doonback());
        save.setOnClickListener(view->{
            if(wasGarmin!=garmin.isChecked()) {
                Natives.setusegarmin(garmin.isChecked());
                if(wasGarmin) {
                    Natives.sethasgarmin(false);
                    Applic.app.numdata.stop();
                }
            }
            context.doonback();
        });
        helpButton.setOnClickListener(view->{
            context.themeLightBars();
            help.help(R.string.watchinfo,context,
                    ignored->context.lightBars(!Natives.getInvertColors()));
        });
        serverSettings.setOnClickListener(view->Nightscout.show(context,screen));
        wearSettings.setOnClickListener(view->Wearos.show(context,screen));
        garminStatus.setOnClickListener(view->new GarminStatus(
                context,Applic.app.numdata,screen));
    }
}
