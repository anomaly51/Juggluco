package tk.glucodata;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Guards the phone meal flow that previously fell back to side panels and grids. */
public class MealClinicalUxContractTest {
    @Test
    public void everyPhoneMealStepHasAClinicalFullScreenImplementation() throws Exception {
        String source=mobileSource("tk/glucodata/Meal.java");
        assertTrue(source.contains("phoneMealConstructor"));
        assertTrue(source.contains("phoneEditMealItem"));
        assertTrue(source.contains("phoneSelectIngredient"));
        assertTrue(source.contains("phoneDefineIngredient"));
        assertTrue(source.contains("phoneFoodDatabase"));
        assertTrue(source.contains("phoneShowNutrients"));
        assertTrue(source.contains("new ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT),false"));
        assertTrue(source.contains("ClinicalUi.header"));
        assertTrue(source.contains("ClinicalUi.sectionLabel"));
    }

    @Test
    public void phoneRoutesReturnBeforeLegacyWearPresentation() throws Exception {
        String source=mobileSource("tk/glucodata/Meal.java");
        assertTrue(source.matches("(?s).*if\\(!isWearable\\)\\s*return phoneMealConstructor.*"));
        assertTrue(source.matches("(?s).*if\\(!isWearable\\) \\{\\s*phoneEditMealItem.*"));
        assertTrue(source.matches("(?s).*if\\(!isWearable\\) \\{\\s*phoneSelectIngredient.*"));
        assertTrue(source.matches("(?s).*if\\(!isWearable\\)\\s*return phoneFoodDatabase.*"));
        assertTrue(source.matches("(?s).*if\\(!isWearable\\) \\{\\s*phoneShowNutrients.*"));
    }

    @Test
    public void phoneFormsUseInlineValidationAndSemanticDestructiveActions() throws Exception {
        String source=mobileSource("tk/glucodata/Meal.java");
        assertTrue(source.contains("setPhoneError"));
        assertTrue(source.contains("meal_modern_error_ingredient"));
        assertTrue(source.contains("meal_modern_error_quantity"));
        assertTrue(source.contains("meal_modern_error_name"));
        assertTrue(source.contains("ClinicalUi.ButtonRole.DANGER"));
        String phone=source.substring(source.indexOf("private static Layout phoneMealConstructor"),
                source.indexOf("static public class MealItemViewAdapter"));
        assertFalse(phone.contains("new GridLayout"));
        assertFalse(phone.contains("new FrameLayout.LayoutParams( WRAP_CONTENT"));
    }

    @Test
    public void nativePersistenceAndSearchCallbacksRemainConnected() throws Exception {
        String source=mobileSource("tk/glucodata/Meal.java");
        assertTrue(source.contains("Natives.changemealitem"));
        assertTrue(source.contains("Natives.deletefrommeal"));
        assertTrue(source.contains("Natives.saveingredient"));
        assertTrue(source.contains("Natives.deleteingredient"));
        assertTrue(source.contains("Natives.searchIngredient"));
        assertTrue(source.contains("Natives.foodsearch"));
        assertTrue(source.contains("Natives.cpmeal"));
    }

    @Test
    public void mealCopyIsLocalizedInEnglishAndRussian() throws Exception {
        String english=resource("values/meal_clinical_strings.xml");
        String russian=resource("values-ru/meal_clinical_strings.xml");
        for(String key:new String[]{"meal_modern_title","meal_modern_empty",
                "meal_modern_error_quantity","meal_modern_food_database_title",
                "meal_modern_nutrients_title","meal_modern_use_food"}) {
            assertTrue(english.contains("name=\""+key+"\""));
            assertTrue(russian.contains("name=\""+key+"\""));
        }
    }

    private static String mobileSource(String relative) throws IOException {
        return read(Paths.get("src","mobile","java").resolve(relative));
    }

    private static String resource(String relative) throws IOException {
        return read(Paths.get("src","mobile","res").resolve(relative));
    }

    private static String read(Path relative) throws IOException {
        if(!Files.exists(relative)) relative=Paths.get("Common").resolve(relative);
        return new String(Files.readAllBytes(relative),StandardCharsets.UTF_8);
    }
}
