package tk.glucodata;

import java.util.UUID;

/** Mutable state for the independent insulin-only backend command. */
final class IntakeDraft {
    String clientEventId;
    long occurredAtMs = System.currentTimeMillis();
    float insulinUnits;
    String insulinName = "NovoRapid";

    IntakeDraft() {
        clientEventId = UUID.randomUUID().toString();
    }

    private IntakeDraft(String clientEventId) {
        this.clientEventId = clientEventId;
    }

    IntakeDraft snapshot() {
        IntakeDraft copy = new IntakeDraft(clientEventId);
        copy.occurredAtMs = occurredAtMs;
        copy.insulinUnits = insulinUnits;
        copy.insulinName = insulinName;
        return copy;
    }
}
