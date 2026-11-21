package fr.enimaloc.catapult.bgs.client;

import java.util.List;

/**
 * A friend's game or activity change: {@code game} is the fourcc of the game now played (e.g. "WTCG"), or null when
 * the friend stopped playing; {@code titleId} is its numeric form (0 when there is no game); {@code activity} is the
 * activity text of that game ({@code (1, 25)}, e.g. "Forever - Normal - Undercity"), null when there is none.
 */
public record PresenceEvent(long accountId, String game, int titleId, String activity,
                            List<RawPresenceField> rawFields) {

    public PresenceEvent {
        rawFields = List.copyOf(rawFields);
    }

    /** An event without activity text. */
    public PresenceEvent(long accountId, String game, int titleId, List<RawPresenceField> rawFields) {
        this(accountId, game, titleId, null, rawFields);
    }

    /**
     * One presence field of the delta that caused the change. {@code gaTitle} is null for non-game-account groups;
     * {@code value} is the Variant's set value (String, Long, Double, Boolean or ByteString), null when deleted.
     */
    public record RawPresenceField(int group, int field, String gaTitle, Object value, boolean deleted) {
    }
}
