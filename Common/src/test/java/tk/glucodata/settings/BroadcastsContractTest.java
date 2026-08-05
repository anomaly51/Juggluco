package tk.glucodata.settings;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Guards the receiver summary shown by the redesigned broadcast picker. */
public class BroadcastsContractTest {
    @Test
    public void selectedReceiverCountMatchesVisibleSelection() {
        assertEquals(0, Broadcasts.selectedReceiverCount());
        assertEquals(0, Broadcasts.selectedReceiverCount(false, false, false));
        assertEquals(2, Broadcasts.selectedReceiverCount(true, false, true));
        assertEquals(3, Broadcasts.selectedReceiverCount(true, true, true));
    }
}
