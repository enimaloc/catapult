package fr.enimaloc.catapult.bgs.client;

import java.util.List;

/**
 * A friend's game change: {@code game} is the fourcc of the game now played (e.g. "WTCG"), or null when the friend
 * stopped playing; {@code titleId} is its numeric form (0 when there is no game).
 */
public record PresenceEvent(long accountId, String game, int titleId, List<RawPresenceField> rawFields) {

    public PresenceEvent {
        rawFields = List.copyOf(rawFields);
    }

    /**
     * One presence field of the delta that caused the change. {@code gaTitle} is null for non-game-account groups;
     * {@code value} is the Variant's set value (String, Long, Double, Boolean or ByteString), null when deleted.
     */
    public record RawPresenceField(int group, int field, String gaTitle, Object value, boolean deleted) {
    }
}
