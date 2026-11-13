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
 * Merges the presence deltas pushed by BGS into one state per friend and reports game changes only.
 * The account level is authoritative: the game is the fourcc of the {@code GameAccountHandle} stored at
 * {@code (null, 1, 21)}, except the launcher alone ({@code App}), which is no game. Not thread-safe.
 */
public final class PresenceTracker {

    private static final String LAUNCHER = "App";
    private static final PresenceKey ACTIVE_GAME_ACCOUNT = new PresenceKey(null, 1, 21);

    /** gaTitle is null unless group is 2 (game account level). */
    record PresenceKey(String gaTitle, int group, int field) {
    }

    private final Map<Long, Map<PresenceKey, Object>> states = new HashMap<>();
    private final Map<Long, String> games = new HashMap<>();

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
        if (Objects.equals(game, games.get(accountId))) {
            return Optional.empty();
        }
        if (game == null) {
            games.remove(accountId);
        } else {
            games.put(accountId, game);
        }
        return Optional.of(new PresenceEvent(accountId, game, game == null ? 0 : Fourcc.toId(game), raw));
    }

    /** Drops a friend; emits a game end when they were in a game. */
    public Optional<PresenceEvent> forget(long accountId) {
        states.remove(accountId);
        return games.remove(accountId) == null
                ? Optional.empty()
                : Optional.of(new PresenceEvent(accountId, null, 0, List.of()));
    }

    /** Forgets everything without emitting (reconnection: the state is rebuilt from new pushes). */
    public void clear() {
        states.clear();
        games.clear();
    }

    private static String gameOf(Map<PresenceKey, Object> state) {
        if (!(state.get(ACTIVE_GAME_ACCOUNT) instanceof com.google.protobuf.ByteString blob) || blob.isEmpty()) {
            return null;
        }
        try {
            String title = Fourcc.of(GameAccountHandle.parseFrom(blob).getTitleId());
            return LAUNCHER.equals(title) ? null : title;
        } catch (InvalidProtocolBufferException e) {
            return null;
        }
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
