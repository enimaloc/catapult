package fr.enimaloc.catapult.bgs.client;

import bgs.protocol.RpcProto.NoData;
import bgs.protocol.account.v2.GameAccountHandleOuterClass.GameAccountHandle;
import bgs.protocol.friends.v2.client.FriendsListenerOuterClass.FriendAddedNotification;
import bgs.protocol.friends.v2.client.FriendsListenerOuterClass.FriendRemovedNotification;
import bgs.protocol.friends.v2.client.FriendsListenerOuterClass.ReceivedInvitationAddedNotification;
import bgs.protocol.friends.v2.client.FriendsListenerOuterClass.ReceivedInvitationRemovedNotification;
import bgs.protocol.friends.v2.client.FriendsListenerOuterClass.SentInvitationAddedNotification;
import bgs.protocol.friends.v2.client.FriendsListenerOuterClass.SentInvitationRemovedNotification;
import bgs.protocol.friends.v2.client.FriendsServiceOuterClass.AcceptInvitationRequest;
import bgs.protocol.friends.v2.client.FriendsServiceOuterClass.GetFriendsRequest;
import bgs.protocol.friends.v2.client.FriendsServiceOuterClass.GetReceivedInvitationsResponse;
import bgs.protocol.friends.v2.client.FriendsServiceOuterClass.GetSentInvitationsResponse;
import bgs.protocol.friends.v2.client.FriendsServiceOuterClass.SendInvitationRequest;
import bgs.protocol.friends.v2.client.FriendsTypes.Friend;
import bgs.protocol.friends.v2.client.FriendsTypes.ReceivedInvitation;
import bgs.protocol.friends.v2.client.FriendsTypes.RemovedFriendAssignment;
import bgs.protocol.friends.v2.client.FriendsTypes.RemovedInvitationAssignment;
import bgs.protocol.friends.v2.client.FriendsTypes.SentInvitation;
import bgs.protocol.friends.v2.client.FriendsTypes.UserDescription;
import bgs.protocol.presence.v2.PresenceListenerOuterClass.PresenceStateUpdatedNotification;
import bgs.protocol.presence.v2.PresenceTypes.PresenceField;
import bgs.protocol.presence.v2.PresenceTypes.PresenceFieldKey;
import bgs.protocol.presence.v2.PresenceTypes.PresenceFieldState;
import bgs.protocol.presence.v2.client.PresenceServiceOuterClass.BatchSubscribeRequest;
import bgs.protocol.presence.v2.client.PresenceServiceOuterClass.BatchUnsubscribeRequest;
import bgs.protocol.v2.AttributeTypes.Variant;
import fr.enimaloc.catapult.bgs.client.FriendEvent.FriendAdded;
import fr.enimaloc.catapult.bgs.client.FriendEvent.FriendRemoved;
import fr.enimaloc.catapult.bgs.client.FriendEvent.InvitationReceived;
import fr.enimaloc.catapult.bgs.client.FriendEvent.InvitationRemoved;
import fr.enimaloc.catapult.bgs.client.FriendEvent.InvitationSent;
import fr.enimaloc.catapult.bgs.client.FriendEvent.InvitationSentRemoved;
import fr.enimaloc.catapult.bgs.core.BgsRpcException;
import fr.enimaloc.catapult.bgs.proto.Fourcc;
import fr.enimaloc.catapult.bgs.proto.ServiceMethod;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static fr.enimaloc.catapult.bgs.client.ClientHarness.friends;
import static fr.enimaloc.catapult.bgs.proto.BgsServices.*;
import static org.assertj.core.api.Assertions.assertThat;

class FriendsSyncTest {

    @Test
    void syncFollowsTheContinuationUntilZero() {
        ClientHarness h = new ClientHarness().ready();
        h.session.reply(FRIENDS_GET_FRIENDS, friends(7, 1L, "a#1"));
        assertThat(h.friendEvents).isEmpty();

        h.session.reply(FRIENDS_GET_FRIENDS, friends(0, 2L, "b#2"));

        List<GetFriendsRequest> requests = h.session.requests(FRIENDS_GET_FRIENDS);
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).getContinuation()).isEqualTo(7);
        assertThat(requests.get(1).getOptions().getFetchNames()).isTrue();
        assertThat(h.client.friends()).isEqualTo(Map.of(1L, "a#1", 2L, "b#2"));
        assertThat(h.friendEvents).containsExactly(new FriendAdded(1, "a#1"), new FriendAdded(2, "b#2"));
    }

    @Test
    void resyncAfterReconnectionEmitsTheDiffRemovalsFirst() {
        ClientHarness h = new ClientHarness().ready().sync(friends(0, 1L, "a#1", 2L, "b#2")).selfPresence();
        h.session.drop(new IOException("reset"));
        h.friendEvents.clear();

        h.restart().connect().logon().accountState().select().selfPresence();
        h.sync(friends(0, 2L, "b#2", 3L, "c#3"));

        assertThat(h.friendEvents).containsExactly(new FriendRemoved(1, "a#1"), new FriendAdded(3, "c#3"));
        assertThat(h.client.friends()).isEqualTo(Map.of(2L, "b#2", 3L, "c#3"));
        assertThat(h.session.requests(PRESENCE_BATCH_UNSUBSCRIBE)).map(BatchUnsubscribeRequest::getAccountIdsList)
                .containsExactly(List.of(1L));
        assertThat(h.session.requests(PRESENCE_BATCH_SUBSCRIBE)).map(BatchSubscribeRequest::getAccountIdsList)
                .containsExactly(List.of(ClientHarness.SELF), List.of(1L, 2L), List.of(3L));
    }

    @Test
    void resyncReportsInvitationsChangedOffline() {
        ClientHarness h = new ClientHarness().ready();
        h.session.reply(FRIENDS_GET_FRIENDS, friends(0));
        h.session.reply(FRIENDS_GET_RECEIVED_INVITATIONS, received(70, 5, "e#5"));
        h.session.reply(FRIENDS_GET_SENT_INVITATIONS, GetSentInvitationsResponse.newBuilder()
                .addInvitations(SentInvitation.newBuilder().setId(80).setTargetName("t#8")).build());
        h.session.drop(new IOException("reset"));
        h.friendEvents.clear();

        h.restart().connect().logon().accountState().select();
        h.session.reply(FRIENDS_GET_FRIENDS, friends(0));
        h.session.reply(FRIENDS_GET_RECEIVED_INVITATIONS, GetReceivedInvitationsResponse.getDefaultInstance());
        h.session.reply(FRIENDS_GET_SENT_INVITATIONS, GetSentInvitationsResponse.getDefaultInstance());

        assertThat(h.friendEvents).containsExactly(new InvitationRemoved(70, 5, "e#5", 0),
                new InvitationSentRemoved(80, "t#8", 0));
    }

    @Test
    void pushesAreIdempotent() {
        ClientHarness h = new ClientHarness().ready().sync(friends(0));
        ReceivedInvitationAddedNotification invited = ReceivedInvitationAddedNotification.newBuilder()
                .addInvitations(ReceivedInvitation.newBuilder().setId(50)
                        .setInviter(UserDescription.newBuilder().setAccountId(9).setBattleTag("z#9"))).build();
        ReceivedInvitationRemovedNotification uninvited = ReceivedInvitationRemovedNotification.newBuilder()
                .addAssignments(RemovedInvitationAssignment.newBuilder().setInvitationId(50).setReason(3)).build();
        FriendAddedNotification added = FriendAddedNotification.newBuilder()
                .addFriends(Friend.newBuilder().setId(9).setBattleTag("z#9")).build();
        FriendRemovedNotification removed = FriendRemovedNotification.newBuilder()
                .addAssignments(RemovedFriendAssignment.newBuilder().setId(9)).build();
        SentInvitationAddedNotification sent = SentInvitationAddedNotification.newBuilder()
                .addInvitations(SentInvitation.newBuilder().setId(60).setTargetName("t#6")).build();
        SentInvitationRemovedNotification unsent = SentInvitationRemovedNotification.newBuilder()
                .addAssignments(RemovedInvitationAssignment.newBuilder().setInvitationId(60)).build();

        for (int i = 0; i < 2; i++) {
            h.session.push(FRIENDS_LISTENER_ON_RECEIVED_INVITATION_ADDED, invited);
        }
        for (int i = 0; i < 2; i++) {
            h.session.push(FRIENDS_LISTENER_ON_RECEIVED_INVITATION_REMOVED, uninvited);
        }
        for (int i = 0; i < 2; i++) {
            h.session.push(FRIENDS_LISTENER_ON_FRIEND_ADDED, added);
        }
        for (int i = 0; i < 2; i++) {
            h.session.push(FRIENDS_LISTENER_ON_FRIEND_REMOVED, removed);
        }
        for (int i = 0; i < 2; i++) {
            h.session.push(FRIENDS_LISTENER_ON_SENT_INVITATION_ADDED, sent);
        }
        for (int i = 0; i < 2; i++) {
            h.session.push(FRIENDS_LISTENER_ON_SENT_INVITATION_REMOVED, unsent);
        }

        assertThat(h.friendEvents).containsExactly(
                new InvitationReceived(50, 9, "z#9"),
                new InvitationRemoved(50, 9, "z#9", 3),
                new FriendAdded(9, "z#9"),
                new FriendRemoved(9, "z#9"),
                new InvitationSent(60, "t#6"),
                new InvitationSentRemoved(60, "t#6", 0));
    }

    @Test
    void removingAFriendInGameEndsTheGameAndUnsubscribes() {
        ClientHarness h = new ClientHarness().ready().selfPresence().sync(friends(0, 4L, "d#4"));
        h.session.push(PRESENCE_LISTENER_ON_PRESENCE_STATE_UPDATED, playing(4, "WTCG"));
        assertThat(h.presenceEvents).singleElement().satisfies(e -> assertThat(e.game()).isEqualTo("WTCG"));

        h.session.push(FRIENDS_LISTENER_ON_FRIEND_REMOVED, FriendRemovedNotification.newBuilder()
                .addAssignments(RemovedFriendAssignment.newBuilder().setId(4)).build());

        assertThat(h.presenceEvents).hasSize(2);
        assertThat(h.presenceEvents.get(1).accountId()).isEqualTo(4);
        assertThat(h.presenceEvents.get(1).game()).isNull();
        assertThat(h.session.requests(PRESENCE_BATCH_UNSUBSCRIBE)).map(BatchUnsubscribeRequest::getAccountIdsList)
                .containsExactly(List.of(4L));
    }

    @Test
    void receivedInvitationsAtSyncAreReported() {
        ClientHarness h = new ClientHarness().ready();
        h.session.reply(FRIENDS_GET_RECEIVED_INVITATIONS, received(70, 5, "e#5"));
        h.session.reply(FRIENDS_GET_SENT_INVITATIONS, GetSentInvitationsResponse.newBuilder()
                .addInvitations(SentInvitation.newBuilder().setId(80).setTargetName("t#8")).build());

        assertThat(h.friendEvents).containsExactly(new InvitationReceived(70, 5, "e#5"), new InvitationSent(80, "t#8"));
    }

    @Test
    void actionsSendTheExactRequests() {
        ClientHarness h = new ClientHarness().ready();

        CompletableFuture<Void> invite = h.client.sendInvitation("Streamer#1234");
        CompletableFuture<Void> accept = h.client.acceptInvitation(70);
        CompletableFuture<Void> decline = h.client.declineInvitation(71);
        CompletableFuture<Void> revoke = h.client.revokeInvitation(72);
        CompletableFuture<Void> remove = h.client.removeFriend(4);

        SendInvitationRequest inv = h.session.requests(FRIENDS_SEND_INVITATION).getFirst();
        assertThat(inv.getTarget().getBattleTag()).isEqualTo("Streamer#1234");
        assertThat(inv.getOptions().getLevel()).isEqualTo(1);
        assertThat(inv.getOptions().getAttributesList()).singleElement().satisfies(a -> {
            assertThat(a.getName()).isEqualTo("App-FriendInvitationReason");
            assertThat(a.getValue().getStringValue()).isEqualTo("enter_battletag");
        });
        AcceptInvitationRequest acc = h.session.requests(FRIENDS_ACCEPT_INVITATION).getFirst();
        assertThat(acc.getInvitationId()).isEqualTo(70);
        assertThat(acc.getOptions().getLevel()).isEqualTo(1);
        assertThat(h.session.requests(FRIENDS_IGNORE_INVITATION).getFirst().getInvitationId()).isEqualTo(71);
        assertThat(h.session.requests(FRIENDS_REVOKE_INVITATION).getFirst().getInvitationId()).isEqualTo(72);
        assertThat(h.session.requests(FRIENDS_REMOVE_FRIEND).getFirst().getTargetAccountId()).isEqualTo(4);
        for (ServiceMethod<?, ?> m : List.of(FRIENDS_SEND_INVITATION, FRIENDS_ACCEPT_INVITATION,
                FRIENDS_IGNORE_INVITATION, FRIENDS_REVOKE_INVITATION, FRIENDS_REMOVE_FRIEND)) {
            assertThat(h.session.sentTo(m.serviceHash(), m.methodId())).singleElement()
                    .satisfies(s -> assertThat(s.ciid()).isTrue());
        }

        h.session.reply(FRIENDS_SEND_INVITATION, NoData.getDefaultInstance());
        h.session.reply(FRIENDS_ACCEPT_INVITATION, NoData.getDefaultInstance());
        h.session.reply(FRIENDS_IGNORE_INVITATION, NoData.getDefaultInstance());
        h.session.reply(FRIENDS_REVOKE_INVITATION, NoData.getDefaultInstance());
        h.session.fail(FRIENDS_REMOVE_FRIEND, 0x2A);

        assertThat(List.of(invite, accept, decline, revoke)).allSatisfy(f -> assertThat(f).isCompleted());
        assertThat(remove.exceptionNow()).isInstanceOf(BgsRpcException.class)
                .satisfies(e -> assertThat(((BgsRpcException) e).status()).isEqualTo(0x2A));
    }

    @Test
    void actionsOutsideAReadySessionFailAtOnce() {
        ClientHarness idle = new ClientHarness();
        ClientHarness logging = new ClientHarness().start().connect().logon();
        ClientHarness closed = new ClientHarness().ready();
        closed.session.drop(new IOException("reset"));

        for (ClientHarness h : List.of(idle, logging, closed)) {
            int before = h.session.sent.size();
            List<Function<BgsClient, CompletableFuture<Void>>> actions = List.of(
                    c -> c.sendInvitation("x#1"), c -> c.acceptInvitation(1), c -> c.declineInvitation(1),
                    c -> c.revokeInvitation(1), c -> c.removeFriend(1));
            for (Function<BgsClient, CompletableFuture<Void>> action : actions) {
                CompletableFuture<Void> f = action.apply(h.client);
                assertThat(f).isCompletedExceptionally();
                assertThat(f.exceptionNow()).isInstanceOf(IllegalStateException.class);
            }
            assertThat(h.session.sent).hasSize(before);
        }
    }

    @Test
    void localStateChangesOnPushesOnlyNotOnReplies() {
        ClientHarness h = new ClientHarness().ready().sync(friends(0, 4L, "d#4"));
        h.friendEvents.clear();

        h.client.removeFriend(4);
        h.session.reply(FRIENDS_REMOVE_FRIEND, NoData.getDefaultInstance());
        h.client.acceptInvitation(70);
        h.session.reply(FRIENDS_ACCEPT_INVITATION, NoData.getDefaultInstance());
        assertThat(h.client.friends()).containsOnlyKeys(4L);
        assertThat(h.friendEvents).isEmpty();

        h.session.push(FRIENDS_LISTENER_ON_FRIEND_REMOVED, FriendRemovedNotification.newBuilder()
                .addAssignments(RemovedFriendAssignment.newBuilder().setId(4)).build());
        assertThat(h.client.friends()).isEmpty();
        assertThat(h.friendEvents).containsExactly(new FriendRemoved(4, "d#4"));
    }

    private static GetReceivedInvitationsResponse received(long id, long accountId, String tag) {
        return GetReceivedInvitationsResponse.newBuilder().addInvitations(ReceivedInvitation.newBuilder().setId(id)
                .setInviter(UserDescription.newBuilder().setAccountId(accountId).setBattleTag(tag))).build();
    }

    private static PresenceStateUpdatedNotification playing(long accountId, String fourcc) {
        GameAccountHandle active = GameAccountHandle.newBuilder().setTitleId(Fourcc.toId(fourcc)).build();
        return PresenceStateUpdatedNotification.newBuilder().addStates(PresenceFieldState.newBuilder()
                .setAccountId(accountId).addFields(PresenceField.newBuilder()
                        .setKey(PresenceFieldKey.newBuilder().setGroup(1).setField(21))
                        .setValue(Variant.newBuilder().setBlobValue(active.toByteString())))).build();
    }
}
