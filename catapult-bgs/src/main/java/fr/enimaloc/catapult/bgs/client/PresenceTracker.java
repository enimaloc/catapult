package fr.enimaloc.catapult.bgs.client;

import bgs.protocol.account.v2.GameAccountHandleOuterClass.GameAccountHandle;
import bgs.protocol.presence.v2.PresenceListenerOuterClass.PresenceStateUpdatedNotification;
import bgs.protocol.presence.v2.PresenceTypes.PresenceField;
import bgs.protocol.presence.v2.PresenceTypes.PresenceFieldState;
import com.google.protobuf.InvalidProtocolBufferException;
import fr.enimaloc.catapult.bgs.client.PresenceEvent.RawPresenceField;
import fr.enimaloc.catapult.bgs.proto.Fourcc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Merges the presence deltas pushed by BGS into one state per friend and reports changes of the game or of its
 * activity text only. The account level is authoritative: the game is the fourcc of the {@code GameAccountHandle}
 * stored at {@code (null, 1, 21)}, except the launcher alone ({@code App}), which is no game; the activity is the text
 * at {@code (null, 1, 25)}, reported only while a game is played. Not thread-safe.
 */
public final class PresenceTracker {

    private static final String LAUNCHER = "App";
    private static final String MOBILE_APP = "BSAp";
    private static final PresenceKey ACTIVE_GAME_ACCOUNT = new PresenceKey(null, 1, 21);
    private static final PresenceKey ACTIVITY = new PresenceKey(null, 1, 25);

    /** gaTitle is null unless group is 2 (game account level). */
    record PresenceKey(String gaTitle, int group, int field) {
    }

    private final Map<Long, Map<PresenceKey, Object>> states = new HashMap<>();
    private final Map<Long, Reported> reported = new HashMap<>();

    /** What was last reported about a friend in a game. */
    private record Reported(String game, String activity) {
    }

    /** Applies a push and returns one event per friend whose game changed. */
    public List<PresenceEvent> apply(PresenceStateUpdatedNotification update) {
        List<PresenceEvent> events = new ArrayList<>();
        for (PresenceFieldState st : update.getStatesList()) {
            String gaTitle = st.hasGameAccount() ? Fourcc.of(st.getGameAccount().getTitleId()) : null;
            applyState(st.getAccountId(), gaTitle, st.getFieldsList()).ifPresent(events::add);
        }
        return events;
    }

    private Optional<PresenceEvent> applyState(long accountId, String gaTitle, List<PresenceField> fields) {
        Map<PresenceKey, Object> state = states.computeIfAbsent(accountId, k -> new HashMap<>());
        List<RawPresenceField> raw = new ArrayList<>();
        for (PresenceField f : fields) {
            int group = f.getKey().getGroup();
            int field = f.getKey().getField();
            String title = group == 2 ? gaTitle : null;
            Object value = f.getDeleted() ? null : valueOf(f.getValue());
            raw.add(new RawPresenceField(group, field, title, value, f.getDeleted()));
            PresenceKey key = new PresenceKey(title, group, field);
            if (f.getDeleted()) {
                state.remove(key);
            } else {
                state.put(key, value);
            }
        }
        String game = gameOf(state);
        Reported now = game == null ? null : new Reported(game, activityOf(state));
        if (Objects.equals(now, reported.get(accountId))) {
            return Optional.empty();
        }
        if (now == null) {
            reported.remove(accountId);
            return Optional.of(new PresenceEvent(accountId, null, 0, null, raw));
        }
        reported.put(accountId, now);
        return Optional.of(new PresenceEvent(accountId, game, Fourcc.toId(game), now.activity(), raw));
    }

    /** Drops a friend; emits a game end when they were in a game. */
    public Optional<PresenceEvent> forget(long accountId) {
        states.remove(accountId);
        return reported.remove(accountId) == null
                ? Optional.empty()
                : Optional.of(new PresenceEvent(accountId, null, 0, List.of()));
    }

    /** Forgets everything without emitting (reconnection: the state is rebuilt from new pushes). */
    public void clear() {
        states.clear();
        reported.clear();
    }

    private static String gameOf(Map<PresenceKey, Object> state) {
        if (!(state.get(ACTIVE_GAME_ACCOUNT) instanceof com.google.protobuf.ByteString blob) || blob.isEmpty()) {
            return null;
        }
        try {
            String title = Fourcc.of(GameAccountHandle.parseFrom(blob).getTitleId());
            return LAUNCHER.equals(title) || MOBILE_APP.equals(title) ? null : title;
        } catch (InvalidProtocolBufferException e) {
            return null;
        }
    }

    private static String activityOf(Map<PresenceKey, Object> state) {
        return state.get(ACTIVITY) instanceof String text && !text.isEmpty() ? text : null;
    }

    private static Object valueOf(bgs.protocol.v2.AttributeTypes.Variant v) {
        return switch (v.getTypeCase()) {
            case BOOL_VALUE -> v.getBoolValue();
            case INT_VALUE -> v.getIntValue();
            case FLOAT_VALUE -> v.getFloatValue();
            case STRING_VALUE -> v.getStringValue();
            case BLOB_VALUE -> v.getBlobValue();
            case UINT_VALUE -> v.getUintValue();
            case TYPE_NOT_SET -> null;
        };
    }
}
