package tk.glucodata;

import android.app.Application;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PersonalConnectionNameViewTest {
    @Test public void previewMatchesNormalizedNameAndErrorsStayByTheField() {
        Context context=new ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.AppTheme_ClinicalDark);
        PersonalConnectionNameView view=new PersonalConnectionNameView(context);
        assertTrue(view.preview.getText().toString().contains("user-"));
        view.input.setText(" Vlad ");
        assertEquals("vlad",view.name());
        assertEquals("https://vlad-xxxxx.api-api-api.com/",view.preview.getText().toString());
        view.showError(R.string.personal_connection_name_taken);
        assertNotNull(view.input.getError());
        view.input.setText("new-name");
        assertNull(view.input.getError());
        assertEquals("https://new-name-xxxxx.api-api-api.com/",view.preview.getText().toString());
        view.input.setText("bad-");
        assertEquals(context.getString(R.string.personal_connection_label_invalid),view.preview.getText().toString());
        assertEquals(view.input.getId(),view.getChildAt(0).getLabelFor());
    }

    @Test public void longPreviewWrapsWithLargeTextOnSmallPhoneAndLandscape() throws Exception {
        try {
            for(String lang:new String[]{"en","ru"}) for(float scale:new float[]{1f,1.8f})
                for(int width:new int[]{335,760}) {
                    RuntimeEnvironment.setQualifiers(lang+"-w"+width+"dp-mdpi");
                    RuntimeEnvironment.setFontScale(scale);
                    Context context=new ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.AppTheme_ClinicalDark);
                    PersonalConnectionNameView view=new PersonalConnectionNameView(context);
                    view.input.setText("x".repeat(63));
                    assertEquals(57,view.name().length());
                    view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
                    view.layout(0,0,width,view.getMeasuredHeight());
                    assertTrue(view.input.getHeight()>=48);
                    for(int i=0;i<view.getChildCount();i++) {
                        TextView text=(TextView)view.getChildAt(i);
                        assertTrue(text.getRight()<=width);
                        assertTrue(text.getBottom()<=view.getHeight());
                        if(text!=view.input) assertTrue(text.getLayout().getHeight()<=text.getHeight());
                    }
                    if(width==335) {
                        view.setBackgroundColor(ClinicalUi.window(context));
                        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(width,view.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
                        view.draw(new android.graphics.Canvas(bitmap));
                        java.io.File output=new java.io.File("build/reports/personal-connection/name-"+lang+"-"+scale+".png");
                        output.getParentFile().mkdirs();
                        try(java.io.FileOutputStream stream=new java.io.FileOutputStream(output)) {
                            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream));
                        }
                        bitmap.recycle();
                    }
                }
        } finally { RuntimeEnvironment.setFontScale(1f); }
    }
}
