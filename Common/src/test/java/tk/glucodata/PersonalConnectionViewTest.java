package tk.glucodata;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.Button;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.Locale;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class PersonalConnectionViewTest {
    @Test public void portraitAndLandscapeKeepActionsVisibleAtLargeTextSize() {
        for(float scale:new float[]{1f,2f}) for(int[] size:new int[][]{{375,812},{800,375},{800,1000}}) {
            Configuration config=new Configuration(RuntimeEnvironment.getApplication().getResources().getConfiguration());
            config.fontScale=scale;
            config.setLocale(Locale.forLanguageTag("ru"));
            Context context=new ContextThemeWrapper(RuntimeEnvironment.getApplication().createConfigurationContext(config),R.style.AppTheme_ClinicalDark);
            PersonalConnectionView view=new PersonalConnectionView(context);
            view.render(100,R.string.personal_connection_ready,"https://gluco-my-long-personal-address-abcdefghijklmnopqrstuvwxyz.api-api-api.com/",false,false);
            view.renderTiming(1,0,2,System.currentTimeMillis(),System.currentTimeMillis()-86_400_000,100);
            int width=ClinicalUi.dp(context,size[0]),height=ClinicalUi.dp(context,size[1]);
            int gutter=ClinicalUi.readableHorizontalGutter(context,width,16);
            view.setPadding(gutter,0,gutter,ClinicalUi.dp(context,16));
            view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
            view.layout(0,0,width,height);
            assertTrue(view.primary.getBottom()<=height);
            assertTrue(view.scroll.getHeight()>0);
            assertTrue(view.primary.getWidth()>0);
            for(Button button:new Button[]{view.back,view.open,view.change,view.primary})
                assertTrue(button.getMeasuredHeight()>=ClinicalUi.dp(context,48));
            assertTrue(view.link.isTextSelectable());
        }
    }

    @Test public void progressFailureAndReadyHaveDistinctAccessibleActions() {
        Context context=new ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.AppTheme_ClinicalDark);
        PersonalConnectionView view=new PersonalConnectionView(context);
        view.render(25,R.string.personal_connection_connecting,"",true,false);
        assertFalse(view.primary.isEnabled());
        assertFalse(view.open.isEnabled());
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,view.status.getAccessibilityLiveRegion());
        view.render(25,R.string.personal_connection_retry,"",false,false);
        assertTrue(view.primary.isEnabled());
        assertEquals(context.getString(R.string.personal_connection_try),view.primary.getText().toString());
        view.render(90,R.string.personal_connection_dns_pending,"https://gluco-abcdefghijklmnopqrstuvwxyz.api-api-api.com/",false,false);
        assertFalse(view.open.isEnabled());
        assertTrue("A DNS failure must not trap the user on an unusable link",view.change.isEnabled());
        assertTrue(view.primary.isEnabled());
        assertTrue(view.status.getText().toString().contains("90%"));
        view.render(100,R.string.personal_connection_ready,"https://example.com",false,false);
        assertTrue(view.change.isEnabled());
        assertEquals(context.getString(R.string.personal_connection_copy),view.primary.getText().toString());
    }

    @Test public void singleStatusShowsBlockedStepAndRetryCountdownWithoutChecklist() {
        Context context=new ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.AppTheme_ClinicalDark);
        PersonalConnectionView view=new PersonalConnectionView(context);
        view.render(40,R.string.personal_connection_dns_phone_wait,"https://example.com/",false,false);
        long now=android.os.SystemClock.elapsedRealtime();
        view.renderTiming(now-5000,now+15_000,2,0,0,40);
        assertEquals("40% · "+context.getString(R.string.personal_connection_dns_phone_wait),view.status.getText().toString());
        assertEquals(40,view.progress.getProgress());
        assertFalse(allText(view).contains("01 ·"));
        assertFalse(allText(view).contains("08 ·"));
        assertTrue(view.timing.getText().toString().contains("15"));
        assertEquals(View.GONE,view.reading.getVisibility());
        assertFalse(view.open.isEnabled());
        view.render(100,R.string.personal_connection_ready,"https://example.com/",false,false);
        view.renderTiming(now,0,2,System.currentTimeMillis(),0,100);
        assertEquals(context.getString(R.string.personal_connection_no_readings),view.reading.getText().toString());
        assertEquals(View.VISIBLE,view.recheck.getVisibility());
        assertTrue(view.recheck.isEnabled());
    }

    private static String allText(View view) {
        StringBuilder text=new StringBuilder();
        if(view instanceof android.widget.TextView) text.append(((android.widget.TextView)view).getText());
        if(view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group=(android.view.ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++) text.append(allText(group.getChildAt(i)));
        }
        return text.toString();
    }
}
