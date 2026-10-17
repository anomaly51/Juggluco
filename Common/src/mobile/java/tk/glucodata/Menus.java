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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.core.view.ViewCompat;


import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static tk.glucodata.Applic.isWearable;
import static tk.glucodata.Log.doLog;
import static tk.glucodata.MainActivity.REQUEST_BARCODE;
import static tk.glucodata.settings.Settings.removeContentView;

import tk.glucodata.settings.Settings;

public class Menus {
static public boolean on=false;
static private final String LOG_ID="Menus";
static private View currentView;
static private boolean dismissing;
static private final long DRAWER_ENTER_DURATION_MS=220L;
static private final long DRAWER_EXIT_DURATION_MS=160L;
static private final float DRAWER_WIDTH_FRACTION=0.88f;

/** Clears a deferred return from a More child during configuration teardown. */
public static void cancelReturnToMenu() {
	on=false;
	}

public static boolean isShowing() {
	return currentView!=null&&currentView.getParent()!=null
			&&currentView.getVisibility()==View.VISIBLE;
	}

public static void onConfigurationChanged() {
	if(isShowing()) {
		currentView.requestLayout();
		currentView.post(currentView::requestLayout);
		}
	}

static float closedTranslationForLayoutDirection(int layoutDirection,int width) {
	return layoutDirection==View.LAYOUT_DIRECTION_RTL?-width:width;
	}

static private float closedTranslation(View root,View panel) {
	int width=panel.getWidth();
	if(width<=0)
		width=panel.getLayoutParams().width;
	return closedTranslationForLayoutDirection(root.getLayoutDirection(),width);
	}

static boolean shouldAnimateDrawer(boolean configurationDrain,
		boolean attached,boolean animationsEnabled) {
	return !configurationDrain&&attached&&animationsEnabled;
	}

static private boolean systemAnimationsEnabled() {
	return Build.VERSION.SDK_INT<Build.VERSION_CODES.O
			||ValueAnimator.areAnimatorsEnabled();
	}

static void configureDirectionalChevron(Button action) {
	Drawable[] drawables=action.getCompoundDrawablesRelative();
	Drawable end=drawables[2];
	if(end==null)
		return;
	end=end.mutate();
	if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.KITKAT)
		end.setAutoMirrored(true);
	action.setCompoundDrawablesRelativeWithIntrinsicBounds(
			drawables[0],drawables[1],end,drawables[3]);
	}

static private void focusDrawer(View panel,View firstAction) {
	panel.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
	firstAction.requestFocus();
	}

/**
 * Closes the modal side drawer and optionally continues into a child screen.
 * The callback is removed before starting a child so its own Back handler is
 * always the top entry. Configuration teardown skips motion entirely.
 */
static private void dismissMenu(MainActivity act,View root,View panel,
		View backdrop,boolean popBack,boolean returnToMenu,Runnable after) {
	if(dismissing)
		return;
	dismissing=true;
	if(popBack)
		act.poponback();
	on=returnToMenu;

	final Runnable finish=() -> {
		removeContentView(root);
		if(currentView==root)
			currentView=null;
		dismissing=false;
		act.requestRender();
		if(after!=null)
			after.run();
		};

	boolean configurationDrain=MainActivity.isConfigurationBackDrain();
	if(!shouldAnimateDrawer(configurationDrain,root.isAttachedToWindow(),
			systemAnimationsEnabled())) {
		panel.animate().cancel();
		backdrop.animate().cancel();
		backdrop.setAlpha(0.0f);
		panel.setTranslationX(closedTranslation(root,panel));
		finish.run();
		return;
		}

	panel.animate().cancel();
	backdrop.animate().cancel();
	backdrop.animate()
			.alpha(0.0f)
			.setDuration(DRAWER_EXIT_DURATION_MS)
			.setInterpolator(new AccelerateInterpolator())
			.start();
	panel.animate()
			.translationX(closedTranslation(root,panel))
			.setDuration(DRAWER_EXIT_DURATION_MS)
			.setInterpolator(new AccelerateInterpolator())
			.setListener(new AnimatorListenerAdapter() {
				@Override public void onAnimationEnd(Animator animation) {
					panel.animate().setListener(null);
					finish.run();
					}
				})
			.start();
	}

static public void show(MainActivity act) {
	if(isShowing())
		return;
	on=true;
	dismissing=false;
	LayoutInflater flater= LayoutInflater.from(act);
	View view = flater.inflate(R.layout.menus, null, false);
	TextView version = view.findViewById(R.id.modern_menu_version);
	version.setText(AppUpdates.versionLabel());
	version.setContentDescription(act.getString(R.string.app_update_version_accessibility,
			BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));
	view.findViewById(R.id.modern_menu_updates).setOnClickListener(v ->
			dismissMenu(act, view, view.findViewById(R.id.modern_menu_panel),
					view.findViewById(R.id.modern_menu_backdrop), true, false,
					() -> AppUpdates.showSettings(act)));
	currentView=view;
	view.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);

	view.setAccessibilityDelegate(Layout.accessDeli);
	act.themeLightBars();
	View backdrop=view.findViewById(R.id.modern_menu_backdrop);
	View panel=view.findViewById(R.id.modern_menu_panel);
	ViewCompat.setAccessibilityPaneTitle(panel,
			act.getString(R.string.dashboard_more));
	panel.setOnClickListener(v -> {});
	var displayMetrics=act.getResources().getDisplayMetrics();
	int maxSideWidth=Math.round(400.0f*displayMetrics.density);
	Runnable limitPanelWidth=() -> {
		int rootWidth=view.getWidth();
		int availableWidth=rootWidth>0
				?rootWidth-view.getPaddingLeft()-view.getPaddingRight()
				:displayMetrics.widthPixels-MainActivity.systembarLeft-MainActivity.systembarRight;
		int panelWidth=Math.min(maxSideWidth,
				Math.max(1,Math.round(availableWidth*DRAWER_WIDTH_FRACTION)));
		FrameLayout.LayoutParams panelParams=(FrameLayout.LayoutParams)panel.getLayoutParams();
		panelParams.width=panelWidth;
		panelParams.height=MATCH_PARENT;
		panelParams.gravity=Gravity.END;
		panel.setLayoutParams(panelParams);
		panel.setBackgroundResource(R.drawable.modern_menu_panel);
		};
	limitPanelWidth.run();
	view.addOnLayoutChangeListener((changed,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom) -> {
		if(right-left!=oldRight-oldLeft)
			limitPanelWidth.run();
		});

	act.setonback(() -> {
		act.themeLightBars();
		{if(doLog) {Log.i(LOG_ID,"onback");};};
		dismissMenu(act,view,panel,backdrop,false,false,null);
			});
	backdrop.setOnClickListener(v -> act.doonback());

        var menusview=view.findViewById(R.id.menus);menusview.setOnClickListener(v ->{
			act.themeLightBars();
			act.doonback();
		}); 
        var watchview=view.findViewById(R.id.watch);watchview.setOnClickListener(v ->{

				if(!isWearable) {
				act.themeLightBars();
					dismissMenu(act,view,panel,backdrop,true,true,
							() -> tk.glucodata.Watch.show(act));
					}

	}); 
		Button sensorview=view.findViewById(R.id.sensor);sensorview.setOnClickListener(v ->{

			act.themeLightBars();
			dismissMenu(act,view,panel,backdrop,true,true,
					() -> bluediag.start(act));
		}); 
		Button alertsview=view.findViewById(R.id.alerts);alertsview.setOnClickListener(v ->{
			dismissMenu(act,view,panel,backdrop,true,true,() ->
					Settings.showGlucoseAlerts(act,()-> {
						if(MainActivity.isConfigurationBackDrain())
							cancelReturnToMenu();
						else if(on)
							show(act);
						}));
		});
        Button settingsview=view.findViewById(R.id.settings);settingsview.setOnClickListener(v ->{
				dismissMenu(act,view,panel,backdrop,true,true,
						() -> Settings.set(act));
	}); 
        Button aboutview=view.findViewById(R.id.about);
        if(tk.glucodata.BuildConfig.SiBionics==1) {
		aboutview.setText(R.string.photo);
		aboutview.setOnClickListener(v -> dismissMenu(act,view,panel,backdrop,
				true,true,() -> PhotoScan.scan(act,REQUEST_BARCODE)));
            }
        else  {
           // About is intentionally not a PHONE More destination.  Keep the
           // section only in photo-capable SiBionics builds.
           aboutview.setVisibility(View.GONE);
           view.findViewById(R.id.menu_app_title).setVisibility(View.GONE);
           view.findViewById(R.id.menu_app_section).setVisibility(View.GONE);
			         }
	configureDirectionalChevron(sensorview);
	configureDirectionalChevron(alertsview);
	configureDirectionalChevron(settingsview);
	configureDirectionalChevron(aboutview);
        var closeview=view.findViewById(R.id.close);closeview.setOnClickListener(v ->{
		dismissMenu(act,view,panel,backdrop,true,false,
				() -> act.moveTaskToBack(true));
	}); 
        var exportview=view.findViewById(R.id.export);exportview.setOnClickListener(v ->{
		var c=Applic.app.curve;
		  if(c!=null) {
			  {if(doLog) {Log.i(LOG_ID,"EXPORT");};};
		     act.themeLightBars();
		     dismissMenu(act,view,panel,backdrop,true,true,
					 () -> c.dialogs.showexport(act,c.getWidth(),c.getHeight(),null));
		     }

	}); 
        var mirrorview=view.findViewById(R.id.mirror);mirrorview.setOnClickListener(v ->{
		     dismissMenu(act,view,panel,backdrop,true,true,
					 () -> (new Backup()).mkbackupview(act));

	}); 
        var talkview=view.findViewById(R.id.talk);talkview.setOnClickListener(v ->{
		dismissMenu(act,view,panel,backdrop,true,true,
				() -> tk.glucodata.Talker.config(act));});
        var lastscanview=view.findViewById(R.id.lastscan);lastscanview.setOnClickListener(v ->{
		if(Natives.showlastscan()) {
               act.themeLightBars();
			dismissMenu(act,view,panel,backdrop,true,true,null);
			}
	}); 

	view.setPadding(MainActivity.systembarLeft,MainActivity.systembarTop,
			MainActivity.systembarRight,MainActivity.systembarBottom);

	// This drawer has its own grouped component styling; keep the global pass
	// from replacing start-aligned menu actions with generic centered buttons.
	backdrop.setAlpha(0.0f);
	panel.setTranslationX(closedTranslation(view,panel));
	act.addMyContentView(view, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT),false);
	view.post(() -> {
		if(currentView!=view||dismissing||!view.isAttachedToWindow())
			return;
		if(!shouldAnimateDrawer(false,true,systemAnimationsEnabled())) {
			backdrop.setAlpha(1.0f);
			panel.setTranslationX(0.0f);
			focusDrawer(panel,sensorview);
			return;
			}
		backdrop.animate()
				.alpha(1.0f)
				.setDuration(DRAWER_ENTER_DURATION_MS)
				.setInterpolator(new DecelerateInterpolator())
				.start();
		panel.animate()
				.translationX(0.0f)
				.setDuration(DRAWER_ENTER_DURATION_MS)
				.setInterpolator(new DecelerateInterpolator())
				.withEndAction(() -> focusDrawer(panel,sensorview))
				.start();
		});

    }


};



