package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class LegacyLayoutPresentationTest {
    private Context context;

    @Before
    public void setUp() {
        context = new ContextThemeWrapper(
                RuntimeEnvironment.getApplication(),
                R.style.AppTheme_ClinicalDark
        );
    }

    @Test
    public void phoneRowsStayCompactAtTopOfFullScreenOverlay() {
        TextView first = row("First");
        TextView second = row("Second");
        TextView third = row("Third");
        Layout layout = new Layout(context,
                new View[]{first}, new View[]{second}, new View[]{third});

        int width = dp(411);
        int height = dp(914);
        layout.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        layout.layout(0, 0, width, height);

        assertEquals(dp(6), second.getTop() - first.getBottom());
        assertEquals(dp(6), third.getTop() - second.getBottom());
        assertTrue(third.getBottom() < dp(220));
    }

    private TextView row(String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setMinHeight(dp(48));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
