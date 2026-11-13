package fr.enimaloc.catapult.bgs.client;

import bgs.protocol.presence.v2.PresenceListenerOuterClass.PresenceStateUpdatedNotification;
import bgs.protocol.presence.v2.PresenceTypes.PresenceField;
import bgs.protocol.presence.v2.PresenceTypes.PresenceFieldKey;
import bgs.protocol.presence.v2.PresenceTypes.PresenceFieldState;
import bgs.protocol.account.v2.GameAccountHandleOuterClass.GameAccountHandle;
import bgs.protocol.v2.AttributeTypes.Variant;
import com.google.protobuf.ByteString;
import fr.enimaloc.catapult.bgs.proto.Fourcc;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Replays a real (redacted) capture of a friend launching Hearthstone, playing two games, then quitting. */
class PresenceReplayTest {

    private static final Pattern HEAD = Pattern.compile(
            "^\\[bgs] presence (\\d+) title=(\\S+) fields=\\d+$|^\\[JEU] (\\d+) (?:joue a (\\S+).*|a quitte le jeu)$");
    private static final Pattern FIELD = Pattern.compile(
            "^(?:\\[bgs])?\\s+\\('(\\w+)', (\\d+), (\\d+), (.*), (True|False)\\)$");

    @Test
    void replayEmitsHearthstoneThenNothing() throws IOException {
        PresenceTracker tracker = new PresenceTracker();
        List<String> events = new ArrayList<>();
        for (PresenceStateUpdatedNotification n : deltas()) {
            tracker.apply(n).forEach(e -> events.add(e.accountId() + ":" + e.game()));
        }
        assertThat(events).containsExactly("100000002:WTCG", "100000002:null");
    }

    private static List<PresenceStateUpdatedNotification> deltas() throws IOException {
        List<String> lines;
        try (InputStream in = PresenceReplayTest.class.getResourceAsStream("/presence.txt")) {
            lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
        List<PresenceStateUpdatedNotification> out = new ArrayList<>();
        PresenceFieldState.Builder cur = null;
        for (String line : lines) {
            Matcher h = HEAD.matcher(line);
            if (h.matches()) {
                if (cur != null) {
                    out.add(wrap(cur));
                }
                cur = PresenceFieldState.newBuilder().setAccountId(Long.parseLong(
                        h.group(1) != null ? h.group(1) : h.group(3)));
                String title = h.group(1) != null ? h.group(2) : h.group(4);
                if (title != null && !title.equals("None")) {
                    cur.setGameAccount(GameAccountHandle.newBuilder().setTitleId(Fourcc.toId(title)));
                }
                continue;
            }
            Matcher f = FIELD.matcher(line);
            if (f.matches() && cur != null) {
                int group = Integer.parseInt(f.group(2));
                int field = Integer.parseInt(f.group(3));
                cur.addFields(PresenceField.newBuilder()
                        .setKey(PresenceFieldKey.newBuilder().setTitleId(Fourcc.toId(f.group(1)))
                                .setGroup(group).setField(field))
                        .setValue(variant(group, field, f.group(4)))
                        .setDeleted(f.group(5).equals("True")));
            }
        }
        if (cur != null) {
            out.add(wrap(cur));
        }
        return out;
    }

    private static PresenceStateUpdatedNotification wrap(PresenceFieldState.Builder st) {
        return PresenceStateUpdatedNotification.newBuilder().addStates(st).build();
    }

    /** Python literals: None, True/False, int, 'string'; account-level hex strings at (1, 20|21) are blobs. */
    private static Variant variant(int group, int field, String literal) {
        Variant.Builder v = Variant.newBuilder();
        if (literal.equals("None")) {
            return v.build();
        }
        if (literal.equals("True") || literal.equals("False")) {
            return v.setBoolValue(literal.equals("True")).build();
        }
        if (literal.startsWith("'")) {
            String s = literal.substring(1, literal.length() - 1);
            if (group == 1 && (field == 20 || field == 21) && !s.isEmpty()) {
                return v.setBlobValue(ByteString.copyFrom(HexFormat.of().parseHex(s))).build();
            }
            return v.setStringValue(s).build();
        }
        return v.setIntValue(Long.parseLong(literal)).build();
    }
}
