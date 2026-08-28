package tk.glucodata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-record presentation history for one in-memory intake conversation.
 *
 * <p>An intake-chat action may affect more than one medical record. Keeping
 * one card per event lets a meal and an injection remain independently
 * visible, while backend tombstones retire only the exact event IDs they
 * name.</p>
 */
final class IntakeChatCardHistory {
    enum Status { ACTIVE, REPLACED, DELETED }

    static final class Card {
        final String actionId;
        private IntakeEvent event;
        private Status status;

        private Card(String actionId, IntakeEvent event) {
            this.actionId = IntakeEvent.clean(actionId);
            this.event = event;
            this.status = Status.ACTIVE;
        }

        IntakeEvent event() {
            return event;
        }

        Status status() {
            return status;
        }

        boolean isActive() {
            return status == Status.ACTIVE;
        }
    }

    private final ArrayList<Card> cards = new ArrayList<>();

    void accept(IntakeChatTurn turn) {
        if (turn == null) return;
        Status tombstoneStatus = turn.events.isEmpty()
                ? Status.DELETED : Status.REPLACED;
        for (String deletedEventId : turn.deletedEventIds) {
            Card card = find(deletedEventId);
            if (card != null) card.status = tombstoneStatus;
        }
        for (IntakeEvent event : turn.events) {
            Card existing = find(event.id);
            if (existing == null) {
                cards.add(new Card(turn.actionId, event));
            } else {
                // Undoing a replacement can restore an earlier event ID.
                existing.event = event;
                existing.status = Status.ACTIVE;
            }
        }
    }

    List<Card> cards() {
        return Collections.unmodifiableList(cards);
    }

    Card primaryForTurn(IntakeChatTurn turn) {
        if (turn == null) return null;
        for (int index = turn.events.size() - 1; index >= 0; index--) {
            Card card = find(turn.events.get(index).id);
            if (card != null) return card;
        }
        for (int index = cards.size() - 1; index >= 0; index--) {
            Card card = cards.get(index);
            if (card.actionId.equals(turn.actionId)) return card;
        }
        return null;
    }

    /**
     * A card-level Delete label is truthful only for a plain one-record
     * create. Replacement and compound actions have action-level inverse
     * semantics and must be changed by naming the intended record in chat.
     */
    static boolean supportsSingleCardDelete(IntakeChatTurn turn) {
        return turn != null
                && IntakeChatTurn.OUTCOME_APPLIED.equalsIgnoreCase(
                        turn.outcome)
                && turn.events.size() == 1
                && turn.deletedEventIds.isEmpty();
    }

    void clear() {
        cards.clear();
    }

    private Card find(String eventId) {
        String cleanId = IntakeEvent.clean(eventId);
        if (cleanId.isEmpty()) return null;
        for (Card card : cards) {
            if (cleanId.equals(card.event.id)) return card;
        }
        return null;
    }
}
