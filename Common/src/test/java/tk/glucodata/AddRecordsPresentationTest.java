package tk.glucodata;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public class AddRecordsPresentationTest {
    @Test
    public void editorUsesTwoPanesOnlyWhenBothColumnsStayComfortable() {
        assertTrue(NumberView.useTwoPaneEditor(2400, 1080, 3.0f));
        assertTrue(NumberView.useTwoPaneEditor(2560, 1600, 2.5f));

        assertFalse(NumberView.useTwoPaneEditor(1080, 2400, 3.0f));
        assertFalse(NumberView.useTwoPaneEditor(1280, 720, 2.0f));
        assertFalse(NumberView.useTwoPaneEditor(0, 0, 0.0f));
    }

    @Test
    public void phoneHidesNewRecordCreationButKeepsExistingRecordEditing() throws Exception {
        assertFalse(GlucoseCurve.allowsLegacyNewRecordCreation(false));
        assertTrue(GlucoseCurve.allowsLegacyNewRecordCreation(true));

        String source=source("GlucoseCurve.java");
        assertFalse(source.contains("Button addRecord=new Button"));
        assertFalse(source.contains("R.string.records_add"));
        assertTrue(source.contains("if(!allowsLegacyNewRecordCreation(isWearable))"));
        assertTrue(source.contains("if(hitptr==numio.newhit"));
        assertTrue(source.contains("numberview.addnumberview(act, base, pos)"));
        assertTrue(source.contains("numberview.addnumberview(activity,hitptr)"));
    }

    @Test
    public void emptyRecordsStateDoesNotAdvertiseRemovedAddAction() throws Exception {
        Path source=Paths.get("src","main","cpp","curve","appcurve.cpp");
        if(!Files.exists(source))
            source=Paths.get("Common").resolve(source);
        String nativeSource=new String(Files.readAllBytes(source),StandardCharsets.UTF_8);
        assertFalse(nativeSource.contains("Use Add to log your first entry"));
        assertTrue(nativeSource.contains("constexpr float lineoffsets[]"));
    }

    private static String source(String name) throws IOException {
        Path relative=Paths.get("src","main","java","tk","glucodata",name);
        if(!Files.exists(relative))
            relative=Paths.get("Common").resolve(relative);
        return new String(Files.readAllBytes(relative),StandardCharsets.UTF_8);
    }
}
