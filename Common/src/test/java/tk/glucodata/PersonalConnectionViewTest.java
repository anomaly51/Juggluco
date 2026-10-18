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
        assertFalse(view.change.isEnabled());
        assertTrue(view.primary.isEnabled());
        assertTrue(view.status.getText().toString().contains("90%"));
        view.render(100,R.string.personal_connection_ready,"https://example.com",false,false);
        assertTrue(view.change.isEnabled());
        assertEquals(context.getString(R.string.personal_connection_copy),view.primary.getText().toString());
    }
}
