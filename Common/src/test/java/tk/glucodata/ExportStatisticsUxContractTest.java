package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public class ExportStatisticsUxContractTest {
    @Test
    public void exportPeriodValidationAcceptsLocalizedDecimals() {
        assertEquals(Dialogs.EXPORT_DAYS_EMPTY,Dialogs.validateExportDays(""));
        assertEquals(Dialogs.EXPORT_DAYS_NOT_NUMBER,Dialogs.validateExportDays("week"));
        assertEquals(Dialogs.EXPORT_DAYS_NOT_NUMBER,Dialogs.validateExportDays("NaN"));
        assertEquals(Dialogs.EXPORT_DAYS_NOT_POSITIVE,Dialogs.validateExportDays("0"));
        assertEquals(Dialogs.EXPORT_DAYS_VALID,Dialogs.validateExportDays(" 7,5 "));
        assertEquals(7.5f,Dialogs.parseExportDays("7,5"),0.0001f);
    }

    @Test
    public void exportTypesPreserveNativeRequestBitsAndFormats() {
        assertEquals(0,Dialogs.exportTypeWithCalibration(0,false));
        assertEquals(8,Dialogs.exportTypeWithCalibration(0,true));
        assertEquals(4,Dialogs.exportTypeWithCalibration(4,true));
        assertEquals(13,Dialogs.exportTypeWithCalibration(5,true));
        assertEquals(".tsv",Dialogs.extensionForExportType(0));
        assertEquals(".html",Dialogs.extensionForExportType(4));
        assertEquals(".csv",Dialogs.extensionForExportType(5));
    }

    @Test
    public void statisticsPeriodValidationMatchesNativeIntegerContract() {
        assertEquals(Stats.STATS_DAYS_EMPTY,Stats.validateStatisticsDays(""));
        assertEquals(Stats.STATS_DAYS_NOT_NUMBER,Stats.validateStatisticsDays("7.5"));
        assertEquals(Stats.STATS_DAYS_NOT_POSITIVE,Stats.validateStatisticsDays("0"));
        assertEquals(Stats.STATS_DAYS_TOO_LARGE,
                Stats.validateStatisticsDays("2147483648"));
        assertEquals(Stats.STATS_DAYS_VALID,Stats.validateStatisticsDays("90"));
        assertEquals(90,Stats.parseStatisticsDays(" 90 "));
    }

    @Test
    public void phoneClinicalPathsKeepExportAndStatisticsHandlers() throws Exception {
        String dialogs=source("Dialogs.java");
        assertTrue(dialogs.contains("if(!smallScreen)"));
        assertTrue(dialogs.contains("showPhoneExport(activity,parent)"));
        assertTrue(dialogs.contains("ClinicalUi.header"));
        assertTrue(dialogs.contains("MainActivity.REQUEST_EXPORT|type"));
        assertTrue(dialogs.contains("context.startActivityForResult(intent, request)"));

        String stats=source("Stats.java");
        assertTrue(stats.contains("mkPhoneStats(act)"));
        assertTrue(stats.contains("askPhoneDays(act,history)"));
        assertTrue(stats.contains("Natives.analysedays"));
        assertTrue(stats.contains("Natives.summarygraph(true)"));
        assertTrue(stats.contains("Natives.endstats()"));
        assertTrue(stats.contains("webPercentiles"));
    }

    private static String source(String name) throws IOException {
        Path relative=Paths.get("src","mobile","java","tk","glucodata",name);
        if(!Files.exists(relative))
            relative=Paths.get("Common").resolve(relative);
        return new String(Files.readAllBytes(relative),StandardCharsets.UTF_8);
    }
}
