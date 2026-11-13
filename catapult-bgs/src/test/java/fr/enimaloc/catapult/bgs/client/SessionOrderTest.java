package fr.enimaloc.catapult.bgs.client;

import bgs.protocol.account.v1.AccountServiceOuterClass.AccountStateNotification;
import bgs.protocol.account.v1.AccountServiceOuterClass.SubscriptionUpdateRequest;
import bgs.protocol.account.v1.AccountTypes.AccountState;
import bgs.protocol.account.v1.AccountTypes.GameAccountList;
import bgs.protocol.account.v1.AccountTypes.SubscriberReference;
import bgs.protocol.account.v2.GameAccountHandleOuterClass.GameAccountHandle;
import bgs.protocol.authentication.v2.client.AuthenticationListenerOuterClass.LogonCompleteNotification;
import bgs.protocol.authentication.v2.client.AuthenticationServiceOuterClass.LogonRequest;
import bgs.protocol.connection.v1.ConnectionServiceProto.ConnectResponse;
import bgs.protocol.connection.v1.ConnectionServiceProto.DisconnectNotification;
import bgs.protocol.presence.v2.client.PresenceServiceOuterClass.BatchSubscribeRequest;
import com.google.protobuf.CodedInputStream;
import fr.enimaloc.catapult.bgs.client.BgsListener.CloseReason;
import fr.enimaloc.catapult.bgs.core.BgsRpcException;
import fr.enimaloc.catapult.bgs.proto.BgsServices;
import fr.enimaloc.catapult.bgs.proto.Fourcc;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static fr.enimaloc.catapult.bgs.client.ClientHarness.*;
import static fr.enimaloc.catapult.bgs.proto.BgsServices.*;
import static org.assertj.core.api.Assertions.assertThat;

class SessionOrderTest {

    private static final String CONNECT = "call 0x65446991.1";
    private static final String LOGON = "call 0xC02F8216.1";

    @Test
    void mintHappensBetweenConnectAndLogon() {
        ClientHarness h = new ClientHarness().start();
        assertThat(h.session.timeline).containsExactly(CONNECT);

        h.connect();

        assertThat(h.session.timeline).containsExactly(CONNECT, "mint", LOGON);
        assertThat(h.session.ciid).isEqualTo(CIID);
        LogonRequest logon = h.session.requests(AUTHENTICATION_LOGON).getFirst();
        assertThat(logon.getTitleId()).isEqualTo(Fourcc.toId("App"));
        assertThat(logon.getPlatform()).isEqualTo("Win");
        assertThat(logon.getLocale()).isEqualTo("frFR");
        assertThat(logon.getApplicationVersion()).isEqualTo(25308);
        assertThat(logon.getLogonOptions().getAuthToken().toStringUtf8()).isEqualTo(TICKET);
        assertThat(logon.getLogonOptions().getEmail()).isEqualTo("bot@example.test");
        assertThat(logon.getLogonOptions().getAuthPlatform()).isEqualTo("BNET");
    }

    @Test
    void noBatchSubscribeBeforeSelfPresence() {
        ClientHarness h = new ClientHarness().ready().sync(friends(0, 30L, "c#3", 20L, "b#2"));
        assertThat(h.session.requests(PRESENCE_BATCH_SUBSCRIBE)).isEmpty();

        h.selfPresence();

        assertThat(h.session.requests(PRESENCE_BATCH_SUBSCRIBE)).map(BatchSubscribeRequest::getAccountIdsList)
                .containsExactly(List.of(SELF), List.of(20L, 30L));

        h.selfPresence(); // a duplicate push subscribes nothing more
        assertThat(h.session.requests(PRESENCE_BATCH_SUBSCRIBE)).hasSize(2);
    }

    @Test
    void friendAddedAfterSelfPresenceIsWatchedAtOnce() {
        ClientHarness h = new ClientHarness().ready().selfPresence().sync(friends(0, 20L, "b#2"));

        assertThat(h.session.requests(PRESENCE_BATCH_SUBSCRIBE)).map(BatchSubscribeRequest::getAccountIdsList)
                .containsExactly(List.of(SELF), List.of(20L));
    }

    @Test
    void ciidOnFriendsPresenceAndSelectOnly() {
        ClientHarness h = new ClientHarness().ready().sync(friends(0, 20L, "b#2")).selfPresence();

        Set<Integer> withCiid = Set.of(FRIENDS_SUBSCRIBE.serviceHash(), PRESENCE_BATCH_SUBSCRIBE.serviceHash(),
                BgsServices.SELECT_GAME_ACCOUNT_HASH);
        assertThat(h.session.sent).isNotEmpty().allSatisfy(s ->
                assertThat(s.ciid()).as("ciid on 0x%X.%d", s.serviceHash(), s.methodId())
                        .isEqualTo(withCiid.contains(s.serviceHash())));
        assertThat(h.session.sentTo(BgsServices.SELECT_GAME_ACCOUNT_HASH, 1)).singleElement()
                .satisfies(s -> assertThat(s.ciid()).isTrue());
    }

    @Test
    void accountSubscribeIsMinimal() {
        ClientHarness h = new ClientHarness().start().connect().logon();

        SubscriptionUpdateRequest req = h.session.requests(ACCOUNT_SUBSCRIBE).getFirst();
        assertThat(req.getRefCount()).isEqualTo(1);
        SubscriberReference ref = req.getRef(0);
        assertThat(ref.getObjectId()).isEqualTo(1);
        assertThat(ref.getEntityId().getHigh()).isEqualTo(0x0100000000000000L);
        assertThat(ref.getEntityId().getLow()).isEqualTo(SELF);
        assertThat(ref.getAccountOptions().getAllFields_()).isTrue();
        assertThat(ref.hasAccountTags()).isFalse();
        assertThat(h.session.sentTo(ACCOUNT_SUBSCRIBE.serviceHash(), ACCOUNT_SUBSCRIBE.methodId()))
                .singleElement().satisfies(s -> assertThat(s.ciid()).isFalse());
    }

    @Test
    void selectBodyWrapsTheChosenHandleInField1() throws IOException {
        ClientHarness h = new ClientHarness().start().connect().logon();
        h.session.push(ACCOUNT_LISTENER_ON_ACCOUNT_STATE_UPDATED, AccountStateNotification.newBuilder()
                .setAccountState(AccountState.newBuilder().addGameAccounts(GameAccountList.newBuilder()
                        .addHandle(bgs.protocol.account.v1.AccountTypes.GameAccountHandle.newBuilder()
                                .setId(9).setProgram(Fourcc.toId("WTCG")).setRegion(1))))
                .build());

        CodedInputStream in = h.session.sentTo(BgsServices.SELECT_GAME_ACCOUNT_HASH, 1).getFirst().body()
                .newCodedInput();
        assertThat(in.readTag()).isEqualTo(10); // field 1, length-delimited
        assertThat(GameAccountHandle.parseFrom(in.readBytes())).isEqualTo(APP_EU);
    }

    @Test
    void selectOnlyOncePerSession() {
        ClientHarness h = new ClientHarness().start().connect().logon().accountState().accountState();

        assertThat(h.session.sentTo(BgsServices.SELECT_GAME_ACCOUNT_HASH, 1)).hasSize(1);
        h.select().accountState();
        assertThat(h.session.sentTo(BgsServices.SELECT_GAME_ACCOUNT_HASH, 1)).hasSize(1);
    }

    @Test
    void readyOnceTheGameAccountIsSelected() {
        ClientHarness h = new ClientHarness().start().connect().logon().accountState();
        assertThat(h.client.ready()).isFalse();
        assertThat(h.started).isNotDone();
        assertThat(h.session.requests(FRIENDS_GET_FRIENDS)).isEmpty();

        h.select();

        assertThat(h.client.ready()).isTrue();
        assertThat(h.started).isCompleted();
        assertThat(h.readies).containsExactly(SELF + " " + SELF_TAG);
        assertThat(h.session.requests(FRIENDS_SUBSCRIBE)).hasSize(1);
        assertThat(h.session.requests(FRIENDS_GET_FRIENDS)).singleElement()
                .satisfies(r -> assertThat(r.getOptions().getFetchNames()).isTrue());
        assertThat(h.session.requests(FRIENDS_GET_RECEIVED_INVITATIONS)).hasSize(1);
        assertThat(h.session.requests(FRIENDS_GET_SENT_INVITATIONS)).hasSize(1);
    }

    @Test
    void logonErrorClosesWithLogonFailed() {
        ClientHarness h = new ClientHarness().start().connect();
        h.session.reply(AUTHENTICATION_LOGON, bgs.protocol.RpcProto.NoData.getDefaultInstance());
        h.session.push(AUTHENTICATION_LISTENER_ON_LOGON_COMPLETE,
                LogonCompleteNotification.newBuilder().setErrorCode(3).build());

        assertThat(h.started).isCompletedExceptionally();
        assertThat(h.started.exceptionNow()).isInstanceOf(BgsRpcException.class)
                .satisfies(e -> assertThat(((BgsRpcException) e).status()).isEqualTo(3));
        assertThat(h.session.closed).isTrue();
        assertThat(h.client.closed()).isCompletedWithValue(CloseReason.LOGON_FAILED);
        assertThat(h.closes).containsExactly(CloseReason.LOGON_FAILED);
        assertThat(h.session.requests(ACCOUNT_SUBSCRIBE)).isEmpty();
    }

    @Test
    void refusedLogonCallClosesWithLogonFailed() {
        ClientHarness h = new ClientHarness().start().connect();
        h.session.fail(AUTHENTICATION_LOGON, 7);

        assertThat(h.started.exceptionNow()).isInstanceOf(BgsRpcException.class);
        assertThat(h.client.closed()).isCompletedWithValue(CloseReason.LOGON_FAILED);
    }

    @Test
    void mintNeedingACodeClosesWithCodeRequired() {
        ClientHarness h = new ClientHarness();
        h.mintFailure = new TicketException("2FA code required", true);
        h.start().connect();

        assertThat(h.started.exceptionNow()).isSameAs(h.mintFailure);
        assertThat(h.session.requests(AUTHENTICATION_LOGON)).isEmpty();
        assertThat(h.session.closed).isTrue();
        assertThat(h.client.closed()).isCompletedWithValue(CloseReason.CODE_REQUIRED);
        assertThat(h.closes).containsExactly(CloseReason.CODE_REQUIRED);
    }

    @Test
    void otherMintFailureClosesWithIo() {
        ClientHarness h = new ClientHarness();
        h.mintFailure = new TicketException("Login page timed out", false);
        h.start().connect();

        assertThat(h.started.exceptionNow()).isSameAs(h.mintFailure);
        assertThat(h.client.closed()).isCompletedWithValue(CloseReason.IO);
    }

    @Test
    void anAbortedSessionInterruptsItsPendingMint() throws Exception {
        CountDownLatch minting = new CountDownLatch(1);
        CompletableFuture<Boolean> interrupted = new CompletableFuture<>();
        ClientHarness listener = new ClientHarness();
        BgsClient client = new BgsClient(CONFIG, () -> {
            minting.countDown();
            try {
                Thread.sleep(Duration.ofSeconds(30));
                interrupted.complete(false);
            } catch (InterruptedException e) {
                interrupted.complete(true);
            }
            throw new TicketException("Nobody waits for this ticket", false);
        }, listener, Executors.newVirtualThreadPerTaskExecutor());
        FakeSession session = new FakeSession();
        client.start(session);
        session.reply(CONNECTION_CONNECT,
                ConnectResponse.newBuilder().setCiid(CIID).setConnectedRegion(2).buildPartial());
        assertThat(minting.await(5, TimeUnit.SECONDS)).isTrue();

        client.abort();

        assertThat(interrupted.get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(client.closed()).isCompletedWithValue(CloseReason.IO);
    }

    @Test
    void forceDisconnect60MeansSessionReplaced() {
        ClientHarness h = new ClientHarness().ready();
        h.session.push(CONNECTION_FORCE_DISCONNECT, DisconnectNotification.newBuilder().setErrorCode(60).build());

        assertThat(h.session.closed).isTrue();
        assertThat(h.client.ready()).isFalse();
        assertThat(h.client.closed()).isCompletedWithValue(CloseReason.SESSION_REPLACED);
        assertThat(h.closes).containsExactly(CloseReason.SESSION_REPLACED);
    }

    @Test
    void otherForceDisconnectIsReported() {
        ClientHarness h = new ClientHarness().ready();
        h.session.push(CONNECTION_FORCE_DISCONNECT, DisconnectNotification.newBuilder().setErrorCode(3).build());

        assertThat(h.client.closed()).isCompletedWithValue(CloseReason.FORCE_DISCONNECT);
    }

    @Test
    void transportErrorClosesWithIoAndFailsAPendingStart() {
        ClientHarness h = new ClientHarness().start().connect();
        h.session.drop(new IOException("reset"));

        assertThat(h.started).isCompletedExceptionally();
        assertThat(h.client.closed()).isCompletedWithValue(CloseReason.IO);
        assertThat(h.closes).containsExactly(CloseReason.IO);
    }

    @Test
    void externalChallengeRemintsAndVerifiesOnTheSameSession() {
        ClientHarness h = new ClientHarness().start().connect();
        h.session.push(AUTHENTICATION_LISTENER_ON_EXTERNAL_CHALLENGE,
                bgs.protocol.authentication.v2.client.AuthenticationListenerOuterClass.ExternalChallengeNotification
                        .newBuilder().setPayloadType("web_auth_url").build());

        assertThat(h.session.timeline).containsExactly(CONNECT, "mint", LOGON, "mint", "call 0xC02F8216.2");
        assertThat(h.session.requests(AUTHENTICATION_VERIFY_AUTH_TOKEN)).singleElement()
                .satisfies(r -> assertThat(r.getAuthToken().toStringUtf8()).isEqualTo(TICKET));
    }

    @Test
    void newSessionResetsTheSessionState() {
        ClientHarness h = new ClientHarness().ready().sync(friends(0, 20L, "b#2")).selfPresence();
        h.session.drop(new IOException("reset"));
        assertThat(h.client.ready()).isFalse();

        h.restart();
        assertThat(h.client.closed()).isNotDone();
        h.connect().logon().accountState();

        assertThat(h.session.sentTo(BgsServices.SELECT_GAME_ACCOUNT_HASH, 1)).hasSize(1);
        h.select();
        assertThat(h.session.requests(PRESENCE_BATCH_SUBSCRIBE)).isEmpty();
        h.selfPresence();
        assertThat(h.session.requests(PRESENCE_BATCH_SUBSCRIBE)).map(BatchSubscribeRequest::getAccountIdsList)
                .containsExactly(List.of(SELF), List.of(20L));
    }

    @Test
    void localCloseReportsLocal() {
        ClientHarness h = new ClientHarness().ready();
        h.client.close();

        assertThat(h.session.closed).isTrue();
        assertThat(h.client.ready()).isFalse();
        assertThat(h.client.closed()).isCompletedWithValue(CloseReason.LOCAL);
        assertThat(h.closes).containsExactly(CloseReason.LOCAL);
    }

    @Test
    void localCloseBeforeReadyFailsTheStart() {
        ClientHarness h = new ClientHarness().start().connect();
        h.client.close();

        assertThat(h.started).isCompletedExceptionally();
        assertThat(h.client.closed()).isCompletedWithValue(CloseReason.LOCAL);
    }

    @Test
    void configToStringMasksTheEmail() {
        assertThat(CONFIG.toString()).doesNotContain("bot@example.test").contains("bgs.test");
    }
}
