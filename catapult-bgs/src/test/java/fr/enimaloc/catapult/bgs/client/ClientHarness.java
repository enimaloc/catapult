package fr.enimaloc.catapult.bgs.client;

import bgs.protocol.RpcProto.NoData;
import bgs.protocol.account.v1.AccountServiceOuterClass.AccountStateNotification;
import bgs.protocol.account.v1.AccountTypes.AccountState;
import bgs.protocol.account.v2.GameAccountHandleOuterClass.GameAccountHandle;
import bgs.protocol.authentication.v2.client.AuthenticationListenerOuterClass.LogonCompleteNotification;
import bgs.protocol.authentication.v2.client.AuthenticationTypes.LogonRecord;
import bgs.protocol.connection.v1.ConnectionServiceProto.ConnectResponse;
import bgs.protocol.friends.v2.client.FriendsServiceOuterClass.GetFriendsResponse;
import bgs.protocol.friends.v2.client.FriendsServiceOuterClass.GetReceivedInvitationsResponse;
import bgs.protocol.friends.v2.client.FriendsServiceOuterClass.GetSentInvitationsResponse;
import bgs.protocol.friends.v2.client.FriendsTypes.Friend;
import com.google.protobuf.ByteString;
import fr.enimaloc.catapult.bgs.client.BgsClient.BgsClientConfig;
import fr.enimaloc.catapult.bgs.proto.BgsServices;
import fr.enimaloc.catapult.bgs.proto.Fourcc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static fr.enimaloc.catapult.bgs.proto.BgsServices.*;

/** A BgsClient on a FakeSession, with a recording listener and helpers walking the session sequence. */
final class ClientHarness implements BgsListener {

    static final long SELF = 1000L;
    static final String SELF_TAG = "Bot#1111";
    static final String TICKET = "EU-test-ticket";
    static final String CIID = "ciid-42";
    static final GameAccountHandle APP_EU = handle(7, "App", 2);
    static final BgsClientConfig CONFIG = new BgsClientConfig("bgs.test", 1119, "bot@example.test", "frFR");

    FakeSession session = new FakeSession();
    final List<FriendEvent> friendEvents = new ArrayList<>();
    final List<PresenceEvent> presenceEvents = new ArrayList<>();
    final List<CloseReason> closes = new ArrayList<>();
    final List<String> readies = new ArrayList<>();
    TicketException mintFailure;
    final BgsClient client = new BgsClient(CONFIG, this::mint, this, Runnable::run);
    CompletableFuture<Void> started;

    static GameAccountHandle handle(long id, String title, int region) {
        return GameAccountHandle.newBuilder().setId(id).setTitleId(Fourcc.toId(title)).setRegion(region).build();
    }

    private String mint() throws TicketException {
        session.timeline.add("mint");
        if (mintFailure != null) {
            throw mintFailure;
        }
        return TICKET;
    }

    /** Starts the client on a new FakeSession. */
    ClientHarness restart() {
        session = new FakeSession();
        return start();
    }

    ClientHarness start() {
        started = client.start(session);
        return this;
    }

    ClientHarness connect() {
        // server_id is a required field nobody reads: a partial message is enough here
        session.reply(CONNECTION_CONNECT,
                ConnectResponse.newBuilder().setCiid(CIID).setConnectedRegion(2).buildPartial());
        return this;
    }

    ClientHarness logon() {
        session.reply(AUTHENTICATION_LOGON, NoData.getDefaultInstance());
        session.push(AUTHENTICATION_LISTENER_ON_LOGON_COMPLETE, LogonCompleteNotification.newBuilder()
                .setRecord(LogonRecord.newBuilder().setAccountId(SELF).setBattleTag(SELF_TAG).addGameAccount(APP_EU))
                .build());
        return this;
    }

    ClientHarness accountState() {
        session.push(ACCOUNT_LISTENER_ON_ACCOUNT_STATE_UPDATED,
                AccountStateNotification.newBuilder().setAccountState(AccountState.getDefaultInstance()).build());
        return this;
    }

    ClientHarness select() {
        session.replyRaw(BgsServices.SELECT_GAME_ACCOUNT_HASH, 1, ByteString.EMPTY);
        return this;
    }

    /** Start to ready: connect, logon, account state, game account selected. */
    ClientHarness ready() {
        return start().connect().logon().accountState().select();
    }

    ClientHarness selfPresence() {
        session.pushRaw(BgsServices.SELF_PRESENCE_HASH, 1, ByteString.EMPTY);
        return this;
    }

    /** Answers the three sync calls in one page each. */
    ClientHarness sync(GetFriendsResponse friends) {
        session.reply(FRIENDS_GET_FRIENDS, friends);
        session.reply(FRIENDS_GET_RECEIVED_INVITATIONS, GetReceivedInvitationsResponse.getDefaultInstance());
        session.reply(FRIENDS_GET_SENT_INVITATIONS, GetSentInvitationsResponse.getDefaultInstance());
        return this;
    }

    static GetFriendsResponse friends(long continuation, Object... idTagPairs) {
        GetFriendsResponse.Builder b = GetFriendsResponse.newBuilder().setContinuation(continuation);
        for (int i = 0; i < idTagPairs.length; i += 2) {
            b.addFriends(Friend.newBuilder().setId(((Number) idTagPairs[i]).longValue())
                    .setBattleTag((String) idTagPairs[i + 1]));
        }
        return b.build();
    }

    // --- BgsListener ---

    @Override
    public void onReady(long accountId, String battleTag) {
        readies.add(accountId + " " + battleTag);
    }

    @Override
    public void onPresence(PresenceEvent event) {
        presenceEvents.add(event);
    }

    @Override
    public void onFriend(FriendEvent event) {
        friendEvents.add(event);
    }

    @Override
    public void onClosed(CloseReason reason) {
        closes.add(reason);
    }
}
