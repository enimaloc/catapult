package fr.enimaloc.catapult.bgs.client;

import bgs.protocol.account.v2.GameAccountHandleOuterClass.GameAccountHandle;
import bgs.protocol.presence.v2.PresenceListenerOuterClass.PresenceStateUpdatedNotification;
import bgs.protocol.presence.v2.PresenceTypes.PresenceField;
import bgs.protocol.presence.v2.PresenceTypes.PresenceFieldKey;
import bgs.protocol.presence.v2.PresenceTypes.PresenceFieldState;
import bgs.protocol.v2.AttributeTypes.Variant;
import fr.enimaloc.catapult.bgs.proto.Fourcc;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PresenceTrackerTest {

    private static final long FRIEND = 42L;

    private static PresenceStateUpdatedNotification activeGame(String fourcc) {
        return push(PresenceField.newBuilder()
                .setKey(PresenceFieldKey.newBuilder().setGroup(1).setField(21))
                .setValue(Variant.newBuilder().setBlobValue(
                        GameAccountHandle.newBuilder().setTitleId(Fourcc.toId(fourcc)).build().toByteString())));
    }

    private static PresenceStateUpdatedNotification noActiveGame() {
        return push(PresenceField.newBuilder()
                .setKey(PresenceFieldKey.newBuilder().setGroup(1).setField(21)).setDeleted(true));
    }

    private static PresenceStateUpdatedNotification push(PresenceField.Builder field) {
        return PresenceStateUpdatedNotification.newBuilder()
                .addStates(PresenceFieldState.newBuilder().setAccountId(FRIEND).addFields(field)).build();
    }

    @Test
    void repeatedDeltasOfTheSameGameEmitOnce() {
        PresenceTracker tracker = new PresenceTracker();
        assertThat(tracker.apply(activeGame("WTCG"))).singleElement()
                .satisfies(e -> {
                    assertThat(e.game()).isEqualTo("WTCG");
                    assertThat(e.titleId()).isEqualTo(Fourcc.toId("WTCG"));
                });
        assertThat(tracker.apply(activeGame("WTCG"))).isEmpty();
    }

    @Test
    void launcherAloneIsNoGame() {
        PresenceTracker tracker = new PresenceTracker();
        assertThat(tracker.apply(activeGame("App"))).isEmpty();
        tracker.apply(activeGame("WTCG"));
        assertThat(tracker.apply(activeGame("App"))).singleElement().satisfies(e -> assertThat(e.game()).isNull());
    }

    @Test
    void deletedAccountKeyEndsTheGame() {
        PresenceTracker tracker = new PresenceTracker();
        tracker.apply(activeGame("WTCG"));
        assertThat(tracker.apply(noActiveGame())).singleElement().satisfies(e -> assertThat(e.game()).isNull());
    }

    @Test
    void forgetWhileInGameEmitsGameEnd() {
        PresenceTracker tracker = new PresenceTracker();
        tracker.apply(activeGame("WTCG"));
        assertThat(tracker.forget(FRIEND)).hasValueSatisfying(e -> {
            assertThat(e.accountId()).isEqualTo(FRIEND);
            assertThat(e.game()).isNull();
        });
    }

    @Test
    void forgetWhileIdleEmitsNothing() {
        assertThat(new PresenceTracker().forget(FRIEND)).isEmpty();
    }

    @Test
    void clearDropsStateWithoutEmitting() {
        PresenceTracker tracker = new PresenceTracker();
        tracker.apply(activeGame("WTCG"));
        tracker.clear();
        assertThat(tracker.forget(FRIEND)).isEmpty();
        assertThat(tracker.apply(activeGame("WTCG"))).hasSize(1);
    }
}
