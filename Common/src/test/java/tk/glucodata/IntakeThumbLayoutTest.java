package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/** Standalone synthetic UI fixtures. No MainActivity, repository, or network. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "en-rUS-mdpi")
public class IntakeThumbLayoutTest {
    @Test
    public void tallPhoneKeepsPrimaryActionsBelowTheConversation() {
        ViewGroup root = fixture(360, 900, 1f, false, false);
        assertComposerBounds(root, 360, 900);
        for (int id : new int[]{R.id.intake_back_button,
                R.id.intake_start_insulin, R.id.intake_start_food,
                R.id.meal_chat_time, R.id.meal_chat_attach,
                R.id.meal_chat_voice}) {
            Rect bounds = bounds(root, root.findViewById(id));
            assertTrue("Primary action outside bottom thumb region: " + id,
                    bounds.top >= 900 * .65f);
        }
    }

    @Test
    public void primaryTargetsAndChatRemainUsableAcrossScreenAndTextSizes() {
        for (float scale : new float[]{1f, 1.5f, 2f}) {
            assertComposerBounds(fixture(375, 812, scale, false, false), 375, 812);
            assertComposerBounds(fixture(360, 900, scale, false, false), 360, 900);
            assertComposerBounds(fixture(800, 700, scale, false, false), 800, 700);
        }
        assertComposerBounds(fixture(812, 375, 1f, false, false), 812, 375);
        assertComposerBounds(fixture(375, 440, 1f, false, true), 375, 440);
    }

    @Test
    public void expandedEditorHasItsOwnBoundedScrollAndDoesNotPushDockOffscreen() {
        for (int[] size : new int[][]{{375, 812}, {360, 900}, {812, 375},
                {800, 700}, {375, 440}}) {
            for (float scale : new float[]{1f, 2f}) {
                ViewGroup root = fixture(size[0], size[1], scale, true, false);
                assertEditorBounds(root, size[0], size[1]);
            }
        }
    }

    @Test
    public void collapsedEditorPreservesConversationHeightAndLatestIsProgressive() {
        ViewGroup root = fixture(375, 812, 1f, false, false);
        assertEquals(View.GONE, root.findViewById(R.id.intake_editor_scroll)
                .getVisibility());
        assertEquals(View.GONE, root.findViewById(R.id.intake_chat_latest)
                .getVisibility());
        assertTrue(root.findViewById(R.id.meal_chat_scroll).getHeight() > 400);
        assertFalse(isDescendant(root.findViewById(R.id.intake_thumb_dock),
                root.findViewById(R.id.meal_chat_scroll)));
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void nativeRendererActuallyDrawsTheEnteredDose() {
        ViewGroup root = fixture(375, 812, 1f, true, false);
        TextView dose = root.findViewById(R.id.intake_quick_dose);
        assertEquals("5", dose.getText().toString());
        Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(),
                Bitmap.Config.ARGB_8888);
        try {
            // Draw through the parent so Android applies the EditText's
            // horizontal scroll transform, exactly as in the full PNG.
            root.draw(new Canvas(bitmap));
            Rect field = bounds(root, dose);
            int textColor = dose.getCurrentTextColor() & 0x00ffffff;
            int foregroundPixels = 0;
            for (int y = field.top; y < field.bottom; y++) {
                for (int x = field.left; x < field.right; x++) {
                    if ((bitmap.getPixel(x, y) & 0x00ffffff) == textColor) {
                        foregroundPixels++;
                    }
                }
            }
            assertTrue("Entered dose is not visibly drawn; size="
                            + dose.getWidth() + "x" + dose.getHeight()
                            + " scroll=" + dose.getScrollX() + "," + dose.getScrollY()
                            + " layout=" + dose.getLayout().getWidth()
                            + " line=" + dose.getLayout().getLineLeft(0) + ".."
                            + dose.getLayout().getLineRight(0),
                    foregroundPixels >= 4);
        } finally {
            bitmap.recycle();
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void rendersSyntheticThumbReachKeyboardAndRecordedResultFixtures()
            throws Exception {
        render("phone-empty", 375, 812, 1f, false, false);
        render("tall-empty", 360, 900, 1f, false, false);
        render("phone-recorded", 375, 812, 1f, false, true);
        render("phone-editor", 375, 812, 1f, true, true);
        render("phone-food-editor", 375, 812, 1f, true, false, true);
        render("landscape-editor", 812, 375, 1f, true, false);
        render("fold-recorded", 800, 700, 1f, false, true);
        render("fold-editor", 800, 700, 1f, true, true);
        render("phone-font-150", 375, 812, 1.5f, false, true);
        render("phone-font-200", 375, 812, 2f, false, true);
        render("phone-editor-font-200", 375, 812, 2f, true, false);
        render("phone-food-font-200", 375, 812, 2f, true, false, true);
        render("ime-compact", 375, 440, 1f, false, true);
        render("ime-editor", 375, 440, 1f, true, false);
        renderTyping("phone-typing", 375, 812);
        renderTyping("ime-typing", 375, 440);
        renderRussianEmpty();
    }

    private static ViewGroup fixture(int width, int height, float fontScale,
            boolean expanded, boolean recorded) {
        Context context = context(width, height, fontScale);
        ViewGroup root = (ViewGroup) LayoutInflater.from(context).inflate(
                R.layout.modern_meal_chat, null, false);
        // Approximate unobscured status/gesture regions without an Activity.
        root.setPadding(0, 24, 0, 20);
        text(root, R.id.intake_backend_status, "DEMO");
        text(root, R.id.meal_chat_time, "Now · change time");
        if (recorded) {
            root.findViewById(R.id.intake_empty_state).setVisibility(View.GONE);
            LinearLayout messages = root.findViewById(R.id.meal_chat_messages);
            addBubble(messages, true, "QA example: logged an injection five minutes ago.");
            addBubble(messages, false, "Saved. You can correct this entry by voice or tap its card.");
            root.findViewById(R.id.meal_chat_proposal).setVisibility(View.VISIBLE);
            text(root, R.id.meal_chat_proposal_title, "SYNTHETIC TEST RECORD");
            text(root, R.id.meal_chat_proposal_meal, "NovoRapid · 5 U");
            text(root, R.id.meal_chat_proposal_time, "Today · 14:25");
            root.findViewById(R.id.meal_chat_proposal_time).setVisibility(View.VISIBLE);
            text(root, R.id.meal_chat_proposal_hint, "Test fixture only · no record was saved");
            root.findViewById(R.id.meal_chat_proposal_carbs).setVisibility(View.GONE);
            root.findViewById(R.id.meal_chat_proposal_absorption).setVisibility(View.GONE);
            root.findViewById(R.id.meal_chat_proposal_confidence).setVisibility(View.GONE);
            root.findViewById(R.id.meal_chat_proposal_warnings).setVisibility(View.GONE);
        }
        if (expanded) {
            root.findViewById(R.id.intake_editor_scroll).setVisibility(View.VISIBLE);
            root.findViewById(R.id.intake_quick_submit_bar).setVisibility(View.VISIBLE);
            root.findViewById(R.id.intake_quick_body).setVisibility(View.VISIBLE);
            root.findViewById(R.id.intake_composer_surface).setVisibility(View.GONE);
            root.findViewById(R.id.meal_chat_time).setVisibility(View.GONE);
            root.findViewById(R.id.meal_chat_voice).setVisibility(View.GONE);
            root.findViewById(R.id.intake_chat_voice_hint).setVisibility(View.GONE);
            root.findViewById(R.id.meal_chat_safety).setVisibility(View.GONE);
            text(root, R.id.intake_quick_dose, "5");
            root.findViewById(R.id.intake_quick_insulin_submit).setVisibility(View.VISIBLE);
            root.findViewById(R.id.intake_quick_meal_submit).setVisibility(View.GONE);
            root.findViewById(R.id.intake_quick_novorapid).setSelected(true);
            root.findViewById(R.id.intake_quick_time_5m).setSelected(true);
        }
        measure(root, width, height);
        preDrawTree(root);
        // Weighted text fields may request another layout during pre-draw,
        // just as a real ViewRoot schedules the following traversal.
        measure(root, width, height);
        preDrawTree(root);
        if (recorded && !expanded) {
            ScrollView scroll = root.findViewById(R.id.meal_chat_scroll);
            scroll.fullScroll(View.FOCUS_DOWN);
        }
        return root;
    }

    private static void assertComposerBounds(ViewGroup root, int width, int height) {
        View dock = root.findViewById(R.id.intake_thumb_dock);
        assertContained(root, dock, width, height);
        View feed = root.findViewById(R.id.meal_chat_scroll);
        assertTrue("Chat viewport collapsed", feed.getHeight() > 0);
        assertTrue("Chat overlaps input dock",
                bounds(root, feed).bottom <= bounds(root, dock).top);
        for (int id : new int[]{R.id.intake_back_button,
                R.id.intake_start_insulin, R.id.intake_start_food,
                R.id.meal_chat_time, R.id.meal_chat_attach,
                R.id.meal_chat_voice, R.id.meal_chat_input}) {
            View action = root.findViewById(id);
            assertNotNull(action);
            assertTrue("Action is outside input dock: " + id, isDescendant(action, dock));
            assertTrue("Action smaller than 48dp: " + id,
                    action.getWidth() >= 48 && action.getHeight() >= 48);
            assertContained(root, action, width, height);
        }
    }

    private static void assertEditorBounds(ViewGroup root, int width, int height) {
        View dock = root.findViewById(R.id.intake_thumb_dock);
        ScrollView editor = root.findViewById(R.id.intake_editor_scroll);
        View body = root.findViewById(R.id.intake_quick_editor);
        assertContained(root, dock, width, height);
        assertContained(root, editor, width, height);
        assertTrue("Editor not measurable", editor.getHeight() > 0);
        assertTrue("Editor must be capped at440dp", editor.getHeight() <= 440);
        assertTrue(isDescendant(body, editor));
        assertFalse(isDescendant(body, root.findViewById(R.id.meal_chat_scroll)));
        View submitBar = root.findViewById(R.id.intake_quick_submit_bar);
        assertContained(root, submitBar, width, height);
        assertFalse(isDescendant(submitBar, editor));
        for (int id : new int[]{R.id.intake_quick_insulin_submit, R.id.intake_quick_meal_submit}) {
            View submit = root.findViewById(id);
            if (submit.getVisibility() == View.VISIBLE) {
                assertContained(root, submit, width, height);
                assertTrue("Save action smaller than48dp", submit.getHeight() >= 48);
            }
        }
        assertTrue("Expanded editor removed conversation viewport",
                root.findViewById(R.id.meal_chat_scroll).getHeight() > 0);
        // Time and submit can exceed the cap, but remain accessible by scrolling.
        assertTrue(body.getHeight() >= editor.getHeight() - editor.getPaddingTop()
                - editor.getPaddingBottom());
    }

    private static void render(String name, int width, int height, float scale,
            boolean expanded, boolean recorded) throws Exception {
        render(name, width, height, scale, expanded, recorded, false);
    }

    private static void render(String name, int width, int height, float scale,
            boolean expanded, boolean recorded, boolean mealMode) throws Exception {
        ViewGroup root = fixture(width, height, scale, expanded, recorded);
        if (mealMode) {
            root.findViewById(R.id.intake_quick_insulin_panel).setVisibility(View.GONE);
            root.findViewById(R.id.intake_quick_meal_panel).setVisibility(View.VISIBLE);
            root.findViewById(R.id.intake_quick_insulin_submit).setVisibility(View.GONE);
            root.findViewById(R.id.intake_quick_meal_submit).setVisibility(View.VISIBLE);
            text(root, R.id.intake_quick_meal_name, "Toast · test fixture");
            text(root, R.id.intake_quick_carbs, "30");
            measure(root, width, height);
            preDrawTree(root);
        }
        if (expanded) assertEditorBounds(root, width, height);
        else assertComposerBounds(root, width, height);
        writePng(name, root, width, height);
    }

    private static void renderRussianEmpty() throws Exception {
        Context context = context(360, 900, 1f, "ru");
        ViewGroup root = (ViewGroup) LayoutInflater.from(context).inflate(
                R.layout.modern_meal_chat, null, false);
        root.setPadding(0, 24, 0, 20);
        text(root, R.id.intake_backend_status, "Preview");
        text(root, R.id.meal_chat_time, "Now · change time");
        measure(root, 360, 900);
        preDrawTree(root);
        assertComposerBounds(root, 360, 900);
        writePng("tall-empty-ru", root, 360, 900);
    }

    private static void renderTyping(String name, int width, int height)
            throws Exception {
        ViewGroup root = fixture(width, height, 1f, false, false);
        text(root, R.id.meal_chat_input, "QA example: I ate toast");
        root.findViewById(R.id.meal_chat_voice).setVisibility(View.GONE);
        View send = root.findViewById(R.id.meal_chat_send);
        View voice = root.findViewById(R.id.meal_chat_voice_secondary);
        send.setVisibility(View.VISIBLE);
        voice.setVisibility(View.VISIBLE);
        if (height < 500) root.findViewById(R.id.meal_chat_safety).setVisibility(View.GONE);
        measure(root, width, height);
        preDrawTree(root);
        assertContained(root, root.findViewById(R.id.intake_thumb_dock), width, height);
        for (View action : new View[]{send, voice, root.findViewById(R.id.meal_chat_time),
                root.findViewById(R.id.meal_chat_attach), root.findViewById(R.id.meal_chat_input)}) {
            assertTrue(action.getWidth() >= 48 && action.getHeight() >= 48);
            assertContained(root, action, width, height);
        }
        assertTrue(root.findViewById(R.id.meal_chat_scroll).getHeight() > 0);
        writePng(name, root, width, height);
    }

    private static void writePng(String name, ViewGroup root, int width,
            int height) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            root.draw(new Canvas(bitmap));
            Path directory = Paths.get("build", "reports", "intake-thumb");
            Files.createDirectories(directory);
            try (OutputStream output = Files.newOutputStream(directory.resolve(name + ".png"))) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
            }
        } finally {
            bitmap.recycle();
        }
    }

    private static void addBubble(LinearLayout parent, boolean user, String value) {
        Context context = parent.getContext();
        LinearLayout row = new LinearLayout(context);
        row.setGravity(user ? Gravity.END : Gravity.START);
        row.setPadding(0, 4, 0, 4);
        TextView bubble = new TextView(context);
        bubble.setText(value);
        bubble.setTextSize(14f);
        bubble.setTypeface(Typeface.MONOSPACE);
        bubble.setTextColor(ContextCompat.getColor(context, R.color.lifeprompt_on_surface));
        bubble.setPadding(14, 11, 14, 11);
        bubble.setMaxWidth(Math.min(640, context.getResources()
                .getConfiguration().screenWidthDp - 64));
        bubble.setBackgroundResource(user ? R.drawable.intake_chat_user
                : R.drawable.intake_chat_assistant);
        row.addView(bubble, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static Context context(int width, int height, float scale) {
        return context(width, height, scale, "en");
    }

    private static Context context(int width, int height, float scale,
            String language) {
        Context application = RuntimeEnvironment.getApplication();
        Configuration config = new Configuration(application.getResources().getConfiguration());
        config.setLocale(Locale.forLanguageTag(language));
        config.densityDpi = 160;
        config.screenWidthDp = width;
        config.screenHeightDp = height;
        config.smallestScreenWidthDp = Math.min(width, height);
        config.orientation = width > height ? Configuration.ORIENTATION_LANDSCAPE
                : Configuration.ORIENTATION_PORTRAIT;
        config.fontScale = scale;
        return new ContextThemeWrapper(application.createConfigurationContext(config),
                R.style.AppTheme_ClinicalDark);
    }

    private static void text(View root, int id, String value) {
        ((TextView) root.findViewById(id)).setText(value);
    }

    private static void measure(View root, int width, int height) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }

    private static Rect bounds(ViewGroup root, View child) {
        // offsetDescendantRectToMyCoords transforms content coordinates;
        // start at the visible scroll origin to obtain the View's actual box.
        Rect rect = new Rect(child.getScrollX(), child.getScrollY(),
                child.getScrollX() + child.getWidth(), child.getScrollY() + child.getHeight());
        root.offsetDescendantRectToMyCoords(child, rect);
        return rect;
    }

    private static void preDrawTree(View view) {
        // Detached fixtures have one ViewTreeObserver per View, unlike the
        // shared observer on an attached ViewRoot. Dispatch each TextView's
        // listener so single-line fields scroll their text into the viewport.
        view.getViewTreeObserver().dispatchOnPreDraw();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                preDrawTree(group.getChildAt(index));
            }
        }
    }

    private static void assertContained(ViewGroup root, View child, int width, int height) {
        Rect rect = bounds(root, child);
        assertTrue("Clipped view " + child.getId() + ": " + rect,
                rect.left >= 0 && rect.right <= width && rect.top >= 24 && rect.bottom <= height - 20);
    }

    private static boolean isDescendant(View child, View ancestor) {
        ViewParent parent = child.getParent();
        while (parent instanceof View) {
            if (parent == ancestor) return true;
            parent = parent.getParent();
        }
        return false;
    }
}
