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
/*      Sun Oct 08 20:48:20 CEST 2023                                                */





package tk.glucodata;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;


import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static tk.glucodata.Applic.isWearable;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.MainActivity.REQUEST_BARCODE;
import static tk.glucodata.Natives.getInvertColors;
import static tk.glucodata.settings.Settings.removeContentView;

import tk.glucodata.settings.Settings;

public class Menus {
static public boolean on=false;
static private final String LOG_ID="Menus";

/**
 * Removes the sheet without leaving its back callback behind.  Screens opened
 * from More use {@code returnToMenu=true}; their existing close handlers then
 * rebuild a fresh sheet.
 */
static private void leaveMenu(MainActivity act,View view,boolean returnToMenu) {
	act.poponback();
	on=returnToMenu;
	removeContentView(view);
	}

static public void show(MainActivity act) {
	on=true;
	LayoutInflater flater= LayoutInflater.from(act);
	View view = flater.inflate(R.layout.menus, null, false);
	view.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);


	view.setAccessibilityDelegate(Layout.accessDeli);
	act.lightBars(false);
	act.setonback(() -> {
		act.lightBars(false);
			   {if(doLog) {Log.i(LOG_ID,"onback");};};
			   on=false;
			removeContentView(view);
				act.requestRender();
			});

	View backdrop=view.findViewById(R.id.modern_menu_backdrop);
	backdrop.setOnClickListener(v -> act.doonback());
	View panel=view.findViewById(R.id.modern_menu_panel);
	panel.setOnClickListener(v -> {});
	var displayMetrics=act.getResources().getDisplayMetrics();
	int edgeGap=Math.round(16.0f*displayMetrics.density);
	int maxPanelWidth=Math.round(360.0f*displayMetrics.density);
	Runnable limitPanelWidth=() -> {
		int rootWidth=view.getWidth();
		int availableWidth=rootWidth>0
				?rootWidth-view.getPaddingLeft()-view.getPaddingRight()
				:displayMetrics.widthPixels-MainActivity.systembarLeft-MainActivity.systembarRight;
		int panelWidth=Math.min(maxPanelWidth,Math.max(1,availableWidth-edgeGap));
		ViewGroup.LayoutParams panelParams=panel.getLayoutParams();
		if(panelParams.width!=panelWidth) {
			panelParams.width=panelWidth;
			panel.setLayoutParams(panelParams);
			}
		};
	limitPanelWidth.run();
	view.addOnLayoutChangeListener((changed,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom) -> {
		if(right-left!=oldRight-oldLeft)
			limitPanelWidth.run();
		});

        var menusview=view.findViewById(R.id.menus);menusview.setOnClickListener(v ->{
				act.poponback();
			   on=false;

			act.lightBars(false);
			removeContentView(view);
				act.requestRender();
		}); 
        var watchview=view.findViewById(R.id.watch);watchview.setOnClickListener(v ->{

				if(!isWearable) {
					act.lightBars(false);
					leaveMenu(act,view,true);
					tk.glucodata.Watch.show(act);
					}

	}); 
        var sensorview=view.findViewById(R.id.sensor);sensorview.setOnClickListener(v ->{

			act.lightBars(false);
				leaveMenu(act,view,true);
				       bluediag.start(act);
		}); 
        var settingsview=view.findViewById(R.id.settings);settingsview.setOnClickListener(v ->{
					leaveMenu(act,view,true);
					Settings.set(act);
	}); 
        Button aboutview=view.findViewById(R.id.about);
        if(tk.glucodata.BuildConfig.SiBionics==1) {
         aboutview.setText(R.string.photo);
        aboutview.setOnClickListener(v ->
              PhotoScan.scan(act,REQUEST_BARCODE));
            }
        else  {
           // About is intentionally not a PHONE More destination.  Keep the
           // section only in photo-capable SiBionics builds.
           aboutview.setVisibility(View.GONE);
           view.findViewById(R.id.menu_app_title).setVisibility(View.GONE);
           view.findViewById(R.id.menu_app_section).setVisibility(View.GONE);
                 }
        var closeview=view.findViewById(R.id.close);closeview.setOnClickListener(v ->{
		act.doonback();
		act.moveTaskToBack(true);
	}); 
        var exportview=view.findViewById(R.id.export);exportview.setOnClickListener(v ->{
		var c=Applic.app.curve;
		  if(c!=null) {
			  {if(doLog) {Log.i(LOG_ID,"EXPORT");};};
		     act.lightBars(!getInvertColors( ));
		     leaveMenu(act,view,true);
		     c.dialogs.showexport(act,c.getWidth(),c.getHeight(),null); 
		     }

	}); 
        var mirrorview=view.findViewById(R.id.mirror);mirrorview.setOnClickListener(v ->{
		     leaveMenu(act,view,true);
			(new Backup()).mkbackupview(act);

	}); 
        var listview=view.findViewById(R.id.list);listview.setOnClickListener(v -> {
				var c = Applic.app.curve;
				if (c != null) {
   		         act.lightBars(!getInvertColors( ));
					leaveMenu(act,view,true);
					Natives.makenumbers();
					act.requestRender();
					c.getnumcontrol(act);
				}
			}
						);
        var statisticsview=view.findViewById(R.id.statistics);statisticsview.setOnClickListener(v ->{

			if(Natives.makepercentages()) {
   		         act.lightBars(!getInvertColors( ));
				leaveMenu(act,view,true);
				act.requestRender();
				Stats.mkstats(act);
				}

		}

			);
        var talkview=view.findViewById(R.id.talk);talkview.setOnClickListener(v ->{
		leaveMenu(act,view,true);
		tk.glucodata.Talker.config(act);}); 
        var lastscanview=view.findViewById(R.id.lastscan);lastscanview.setOnClickListener(v ->{
		if(Natives.showlastscan()) {
               act.lightBars(!getInvertColors( ));
			leaveMenu(act,view,true);
			act.requestRender();
			}
	}); 

	  // view.setPadding(0,MainActivity.systembarTop,0,0);
  	view.setPadding(MainActivity.systembarLeft,MainActivity.systembarTop*3/4,MainActivity.systembarRight,MainActivity.systembarBottom);

	// This sheet has its own grouped component styling; keep the global pass
	// from replacing start-aligned menu actions with generic centered buttons.
	act.addMyContentView(view, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT),false);

    }


};



