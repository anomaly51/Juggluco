package tk.glucodata;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Guards the phone screens that used to fall back to small centered legacy panels. */
public class SecondaryClinicalScreensContractTest {
    @Test
    public void floatingGlucoseUsesFocusedFullScreenControls() throws Exception {
        String source=mobileSource("tk/glucodata/FloatingConfig.java");
        assertTrue(source.contains("clinical_floating_title"));
        assertTrue(source.contains("ClinicalUi.toggleRow"));
        assertTrue(source.contains("openColorPicker"));
        assertTrue(source.contains("new ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT)"));
        assertFalse(source.contains("new Object[]{new View[]{view,leftlayout}}"));
    }

    @Test
    public void labelsAndServiceMappingsUseReadableSingleColumnLists() throws Exception {
        String labels=mobileSource("tk/glucodata/settings/LabelsClass.java");
        assertTrue(labels.contains("ClinicalUi.header"));
        assertTrue(labels.contains("ClinicalUi.ButtonRole.DANGER"));
        assertTrue(labels.contains("LinearLayoutManager"));

        String mapping=mobileSource("tk/glucodata/settings/LibreNumbers.java");
        assertTrue(mapping.contains("clinical_amount_mapping_title"));
        assertTrue(mapping.contains("new LinearLayoutManager(context)"));
        assertFalse(mapping.contains("new GridLayoutManager(context,3)"));
    }

    @Test
    public void diagnosticsCalibrationAndHelpHaveFullScreenShells() throws Exception {
        String logs=mainSource("tk/glucodata/settings/LogConfig.java");
        assertTrue(logs.contains("makePhone(act,parent)"));
        assertTrue(logs.contains("ClinicalUi.ButtonRole.DANGER"));
        assertTrue(logs.contains("clinical_log_title"));

        String calibration=mainSource("tk/glucodata/settings/Calibration.java");
        assertTrue(calibration.contains("ClinicalUi.header"));
        assertTrue(calibration.contains("MATCH_PARENT,MATCH_PARENT"));

        String help=mainSource("tk/glucodata/help.java");
        assertTrue(help.contains("phoneHelp"));
        assertTrue(help.contains("ClinicalUi.header"));
        assertTrue(help.contains("ScrollView scroll=new ScrollView"));
    }

    @Test
    public void themeAndColorEditorsNoLongerOpenAsTinyLegacyModals() throws Exception {
        String theme=mobileSource("tk/glucodata/settings/SelectTheme.java");
        assertTrue(theme.contains("new FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT)"));
        assertFalse(theme.contains("(int)(height*.8f)"));

        String colors=mobileSource("tk/glucodata/settings/SetColors.java");
        assertTrue(colors.contains("clinical_graph_color_title"));
        assertTrue(colors.contains("ClinicalUi.scrollScreen"));
        assertTrue(colors.contains("Natives.setlastcolor(c)"));
    }

    @Test
    public void dateAndTimePickersFollowOrientationInFullScreen() throws Exception {
        String source=mainSource("tk/glucodata/NumberView.java");
        assertTrue(source.contains("clinical_choose_date"));
        assertTrue(source.contains("clinical_choose_time"));
        assertTrue(source.contains("GlucoseCurve.getheight()>=GlucoseCurve.getwidth()"));
        assertTrue(source.contains("new FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT)"));
    }

    @Test
    public void watchAndInsulinToolsUseTheSameClinicalNavigation() throws Exception {
        String watch=mobileSource("tk/glucodata/Watch.java");
        assertTrue(watch.contains("clinical_watch_title"));
        assertTrue(watch.contains("ClinicalUi.toggleRow"));
        assertTrue(watch.contains("Natives.setusegarmin(garmin.isChecked())"));

        String iob=mobileSource("tk/glucodata/IOB.java");
        assertTrue(iob.contains("clinical_iob_title"));
        assertTrue(iob.contains("new LinearLayoutManager(act)"));
        assertFalse(iob.contains("new GridLayoutManager(act,3)"));

        String type=mobileSource("tk/glucodata/InsulinTypeHolder.java");
        assertTrue(type.contains("ClinicalUi.scrollScreen"));
        assertTrue(type.contains("setInsulinType(index,i)"));

        String talker=mainSource("tk/glucodata/Talker.java");
        assertTrue(talker.contains("clinical_talker_title"));
        assertTrue(talker.contains("ClinicalUi.toggleRow"));
        assertTrue(talker.contains("Natives.saveVoice"));
    }

    private static String mainSource(String relative) throws IOException {
        return source(Paths.get("src","main","java").resolve(relative));
    }

    private static String mobileSource(String relative) throws IOException {
        return source(Paths.get("src","mobile","java").resolve(relative));
    }

    private static String source(Path relative) throws IOException {
        if(!Files.exists(relative)) relative=Paths.get("Common").resolve(relative);
        return new String(Files.readAllBytes(relative),StandardCharsets.UTF_8);
    }
}
