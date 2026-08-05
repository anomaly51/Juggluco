package tk.glucodata.settings;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.Arrays;

import org.junit.Test;

import tk.glucodata.R;

/** Protects the enum ordinals persisted by the native settings layer. */
public class AppThemeContractTest {
    private static final String[] EXPECTED_NAMES = {
            "DEFAULT",
            "DARK",
            "OCEANIC",
            "SUNSET",
            "OBSIDIAN",
            "AMBERDUSK",
            "MusicNeon",
            "SocialSunset",
            "RoyalNight",
            "OLEDBlack",
            "HighVisibility",
            "SolarizedDark",
            "CyberpunkNeon",
            "CUPERTINO_DARK",
            "MIDNIGHT_INDIGO",
            "DEEP_TEAL",
            "PLUM_NIGHT",
            "TERMINAL_GREEN",
            "LIGHT",
            "CLASSIC",
            "FOREST",
            "NORDICFROST",
            "LAVENDERDREAM",
            "MessagingEmerald",
            "VideoCrimson",
            "LearningLime",
            "MinimalInk",
            "CoffeeCream",
            "CloudBlue",
            "VintageParchment",
            "MatchaMint",
            "RoseQuartz",
            "CUPERTINO_LIGHT",
            "SAGE_PAPER",
            "SKY_MIST",
            "PEACH_SOFT",
            "CANDY_PASTEL",
            "STARRY_NIGHT",
            "GIRL_WITH_PEARL",
            "NIGHT_WATCH",
            "ROTHKO_DUSK",
            "ALMOND_BLOSSOM",
            "GREAT_WAVE",
            "WATER_LILIES",
            "SUNFLOWERS",
            "MONDRIAN_GRID",
            "UKIYOE_VERMILION",
            "SUNLIGHT_READABLE",
            "PAPER_LOW_GLARE",
            "DEUTERANOPIA_SAFE",
            "PROTANOPIA_SAFE_DARK",
            "TRITANOPIA_SAFE",
            "SENIOR_CONTRAST",
            "ALARM_RESERVED",
            "PERIPHERAL_SALIENCE"
    };

    @Test
    public void persistedThemeOrderAndDefaultStayStable() {
        AppTheme[] values = AppTheme.values();
        assertEquals(55, values.length);
        assertEquals(55, EXPECTED_NAMES.length);
        assertArrayEquals(
                EXPECTED_NAMES,
                Arrays.stream(values).map(Enum::name).toArray(String[]::new)
        );

        assertSame(AppTheme.DEFAULT, values[0]);
        assertEquals(0, AppTheme.DEFAULT.ordinal());
        assertEquals(R.style.AppTheme_ClinicalDark,
                AppTheme.DEFAULT.getStyleResId());
        assertEquals("Clinical Dark", AppTheme.DEFAULT.toString());
    }
}
