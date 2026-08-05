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

public class ReminderSoundUxContractTest {
    @Test
    public void reminderFormattingIsStableAndReadable() {
        assertEquals("00:00 \u2013 23:59",setNumAlarm.formatTimeRange(0,1439));
        assertEquals("08:05 \u2013 17:09",setNumAlarm.formatTimeRange(485,1029));
        assertEquals(5.4f,setNumAlarm.parseReminderValue(" 5,4 "),0.0001f);
    }

    @Test
    public void reminderValidationExplainsEveryLegacySilentFailure() {
        assertEquals(setNumAlarm.REMINDER_INVALID_LABEL,
                setNumAlarm.validateReminderInput("5.5",-1,60,120));
        assertEquals(setNumAlarm.REMINDER_EMPTY_VALUE,
                setNumAlarm.validateReminderInput("  ",0,60,120));
        assertEquals(setNumAlarm.REMINDER_INVALID_VALUE,
                setNumAlarm.validateReminderInput("five",0,60,120));
        assertEquals(setNumAlarm.REMINDER_INVALID_VALUE,
                setNumAlarm.validateReminderInput("NaN",0,60,120));
        assertEquals(setNumAlarm.REMINDER_SAME_TIME,
                setNumAlarm.validateReminderInput("5.5",0,60,60));
        assertEquals(setNumAlarm.REMINDER_VALID,
                setNumAlarm.validateReminderInput("5,5",0,60,120));
    }

    @Test
    public void soundValidationKeepsNativeStorageBoundsExplicit() {
        assertEquals(RingTones.SOUND_INPUT_EMPTY,RingTones.validateDurationInput(""));
        assertEquals(RingTones.SOUND_INPUT_NOT_NUMBER,RingTones.validateDurationInput("2.5"));
        assertEquals(RingTones.SOUND_INPUT_NEGATIVE,RingTones.validateDurationInput("-1"));
        assertEquals(RingTones.SOUND_INPUT_TOO_LARGE,RingTones.validateDurationInput("65536"));
        assertEquals(RingTones.SOUND_INPUT_VALID,RingTones.validateDurationInput("65535"));
        assertEquals(RingTones.SOUND_INPUT_TOO_LARGE,
                RingTones.validateSuspensionInput("32768"));
        assertEquals(RingTones.SOUND_INPUT_VALID,
                RingTones.validateSuspensionInput("32767"));
        assertTrue(RingTones.usesSuspension(0));
        assertTrue(RingTones.usesSuspension(7));
        assertFalse(RingTones.usesSuspension(3));
        assertTrue(RingTones.isDefaultTone(null));
        assertTrue(RingTones.isDefaultTone(""));
        assertFalse(RingTones.isDefaultTone("content://tone"));
    }

    @Test
    public void phonePathsUseClinicalScreensWhileNativeContractsRemain() throws Exception {
        String reminders=source("setNumAlarm.java");
        assertTrue(reminders.contains("mkPhoneViews(act,set)"));
        assertTrue(reminders.contains("mkPhoneItemLayout(act,parent)"));
        assertTrue(reminders.contains("ReminderCardView"));
        assertTrue(reminders.contains("ClinicalUi.header"));
        assertTrue(reminders.contains("Natives.setNumAlarm(labelsel,parsed"));
        assertTrue(reminders.contains("Natives.delNumAlarm"));

        String sounds=source("RingTones.java");
        assertTrue(sounds.contains("mkPhoneViews(context,label,parview)"));
        assertTrue(sounds.contains("sound_modern_preview"));
        assertTrue(sounds.contains("Notify.stopalarm()"));
        assertTrue(sounds.contains("Natives.writering(kind,uri"));
        assertTrue(sounds.contains("context.setonback(closeScreen)"));
    }

    @Test
    public void remindersPhoneScreenScrollsAboveSystemNavigation() throws Exception {
        String reminders=source("setNumAlarm.java");
        String phoneScreen=reminders.substring(reminders.indexOf("private void mkPhoneViews"),
                reminders.indexOf("private View makePhoneEmptyState"));
        assertTrue(phoneScreen.contains("ClinicalUi.scrollScreen(act,content)"));
        assertTrue(phoneScreen.contains(
                "MainActivity.systembarBottom+ClinicalUi.dp(act,24)"));
        assertTrue(phoneScreen.contains("phoneRecycler.setNestedScrollingEnabled(false)"));
        assertTrue(phoneScreen.contains("listHost.setMinimumHeight(ClinicalUi.dp(act,420))"));
        assertTrue(phoneScreen.contains("act.lightBars(false)"));
    }

    private static String source(String name) throws IOException {
        Path relative=Paths.get("src","main","java","tk","glucodata",name);
        if(!Files.exists(relative))
            relative=Paths.get("Common").resolve(relative);
        return new String(Files.readAllBytes(relative),StandardCharsets.UTF_8);
    }
}
