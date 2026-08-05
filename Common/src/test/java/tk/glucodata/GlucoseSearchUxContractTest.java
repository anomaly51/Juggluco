package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public class GlucoseSearchUxContractTest {
    @Test
    public void validationRejectsAmbiguousOrUnsafeRanges() {
        assertEquals(GlucoseCurve.SEARCH_INPUT_EMPTY_LIMIT,
                GlucoseCurve.validateSearchInput("","180",null,null));
        assertEquals(GlucoseCurve.SEARCH_INPUT_INVALID_LIMIT,
                GlucoseCurve.validateSearchInput("low","180",null,null));
        assertEquals(GlucoseCurve.SEARCH_INPUT_INVALID_LIMIT,
                GlucoseCurve.validateSearchInput("NaN","180",null,null));
        assertEquals(GlucoseCurve.SEARCH_INPUT_INVALID_LIMIT,
                GlucoseCurve.validateSearchInput("70","Infinity",null,null));
        assertEquals(GlucoseCurve.SEARCH_INPUT_REVERSED_RANGE,
                GlucoseCurve.validateSearchInput("181","180",null,null));
    }

    @Test
    public void validationKeepsMealConditionsExplicit() {
        assertEquals(GlucoseCurve.SEARCH_INPUT_INVALID_AMOUNT,
                GlucoseCurve.validateSearchInput("0","999","rice","many"));
        assertEquals(GlucoseCurve.SEARCH_INPUT_NEGATIVE_AMOUNT,
                GlucoseCurve.validateSearchInput("0","999","rice","-1"));
        assertEquals(GlucoseCurve.SEARCH_INPUT_INGREDIENT_REQUIRED,
                GlucoseCurve.validateSearchInput("0","999","  ","25"));
        assertEquals(GlucoseCurve.SEARCH_INPUT_VALID,
                GlucoseCurve.validateSearchInput("3,9","10,0","rice","25,5"));
        assertEquals(GlucoseCurve.SEARCH_INPUT_VALID,
                GlucoseCurve.validateSearchInput("0","999",null,null));
    }

    @Test
    public void phoneSearchIsFullScreenClinicalUiWithOnePrimaryAction()
            throws Exception {
        String source=source("main","java","tk","glucodata","GlucoseCurve.java");
        assertTrue(source.contains("private ViewGroup getsearchlayout(MainActivity context)"));
        assertTrue(source.contains("if(isWearable)"));
        assertTrue(source.contains("ClinicalUi.verticalContent(context)"));
        assertTrue(source.contains("ClinicalUi.scrollScreen(context,content)"));
        assertTrue(source.contains("ClinicalUi.ButtonRole.PRIMARY"));
        assertTrue(source.contains("new ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT)"));
        assertTrue(source.contains("clinical_search_error_range_order"));
        assertTrue(source.contains("ACCESSIBILITY_LIVE_REGION_ASSERTIVE"));
        assertFalse(source.contains("act.addMyContentView(meallayout"));
    }

    @Test
    public void nativeSearchFlagsAndResultNavigationRemainIntact() throws Exception {
        String source=source("main","java","tk","glucodata","GlucoseCurve.java");
        assertTrue(source.contains("0x40000001"));
        assertTrue(source.contains("0x40000002"));
        assertTrue(source.contains("0x40000004"));
        assertTrue(source.contains("0x40000008"));
        assertTrue(source.contains("0x40000010"));
        assertTrue(source.contains("Natives.search(glsearch==0?labelsel:glsearch"));
        assertTrue(source.contains("Natives.earliersearch()"));
        assertTrue(source.contains("Natives.latersearch()"));
        assertTrue(source.contains("Natives.stopsearch()"));
        assertTrue(source.contains("clinical_search_previous_result"));
        assertTrue(source.contains("clinical_search_next_result"));
        assertTrue(source.contains("clinical_search_close_results"));
    }

    @Test
    public void englishAndRussianCopyCoverTheWholeFlow() throws Exception {
        String english=source("main","res","values","clinical_search_strings.xml");
        String russian=source("main","res","values-ru","clinical_search_strings.xml");
        for(String key:new String[]{
                "clinical_search_title",
                "clinical_search_sources_section",
                "clinical_search_range_section",
                "clinical_search_time_section",
                "clinical_search_meal_section",
                "clinical_search_direction_section",
                "clinical_search_action",
                "clinical_search_no_results"
        }) {
            assertTrue("Missing EN copy: "+key,english.contains("name=\""+key+"\""));
            assertTrue("Missing RU copy: "+key,russian.contains("name=\""+key+"\""));
        }
    }

    private static String source(String... parts) throws IOException {
        Path relative=Paths.get("src",parts);
        if(!Files.exists(relative))
            relative=Paths.get("Common").resolve(relative);
        return new String(Files.readAllBytes(relative),StandardCharsets.UTF_8);
    }
}
