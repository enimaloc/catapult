package fr.enimaloc.catapult.service.battlenet;

import bgs.protocol.RpcProto.Header;
import bgs.protocol.RpcProto.NoData;
import bgs.protocol.authentication.v2.client.AuthenticationServiceOuterClass.LogonRequest;
import bgs.protocol.friends.v2.client.FriendsListenerOuterClass.UpdateFriendStateNotification;
import bgs.protocol.friends.v2.client.FriendsServiceOuterClass.GetFriendsResponse;
import bgs.protocol.friends.v2.client.FriendsServiceOuterClass.SendInvitationRequest;
import bgs.protocol.friends.v2.client.FriendsTypes.Friend;
import bgs.protocol.friends.v2.client.FriendsTypes.FriendStateAssignment;
import bgs.protocol.friends.v2.client.FriendsTypes.SendInvitationTarget;
import bgs.protocol.presence.v2.client.PresenceServiceOuterClass.BatchSubscribeRequest;
import com.google.protobuf.ByteString;
import fr.enimaloc.catapult.bgs.core.WireExchange;
import fr.enimaloc.catapult.bgs.proto.BgsServices;
import fr.enimaloc.catapult.bgs.proto.ServiceMethod;
import fr.enimaloc.catapult.domain.debug.DebugExchange.Direction;
import fr.enimaloc.catapult.service.debug.RawExchange;
import fr.enimaloc.catapult.service.debug.SessionDebug;
import fr.enimaloc.catapult.service.debug.TestSessionDebug;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** The BGS frames a bot exchanges are recorded for the member in debug mode they name, and only those. */
class BgsWireRecorderTest {

    private static final UUID BOT = UUID.randomUUID();
    private static final Instant AT = Instant.parse("2026-11-20T10:00:00Z");

    private final TestSessionDebug watched = new TestSessionDebug("battlenet", BOT, BattleNetDebugKeys.account(42L),
            BattleNetDebugKeys.tag("Streamer#1234"));
    private final BgsWireRecorder recorder = new BgsWireRecorder(BOT, watched.debug);

    private static Header header(int serviceHash, int methodId) {
        return Header.newBuilder().setServiceId(0).setServiceHash(serviceHash).setMethodId(methodId).setToken(7)
                .setCiid("secret-ciid").buildPartial();
    }

    private static WireExchange serverCall(ServiceMethod<?, ?> method, ByteString body) {
        return new WireExchange(false, AT, 0, header(method.serviceHash(), method.methodId()), body, null, null,
                method, null);
    }

    private RawExchange recorded(Direction direction, String operation) {
        ArgumentCaptor<RawExchange> raw = ArgumentCaptor.forClass(RawExchange.class);
        verify(watched.recorder).record(eq(watched.member), eq(direction), raw.capture(), eq(operation), eq(false),
                eq("battlenet"));
        return raw.getValue();
    }

    private void nothingRecorded() {
        verify(watched.recorder, never()).record(any(), any(), any(), any(), anyBoolean(), any());
    }

    private static String text(byte[] bytes) {
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    private static ByteString friendState(long accountId) {
        return UpdateFriendStateNotification.newBuilder().setAgentAccountId(5)
                .addAssignments(FriendStateAssignment.newBuilder().setId(accountId).setLevel(3))
                .build().toByteString();
    }

    @Test
    void recordsAPresenceNotificationForTheMemberItNames() {
        ByteString body = friendState(42L);

        recorder.onExchange(serverCall(BgsServices.FRIENDS_LISTENER_ON_UPDATE_FRIEND_STATE, body));

        RawExchange raw = recorded(Direction.IN, "bgs.friends.v2.client.FriendsListener."
                + BgsServices.FRIENDS_LISTENER_ON_UPDATE_FRIEND_STATE.methodId());
        assertThat(raw.method()).isEqualTo("BGS");
        assertThat(raw.at()).isEqualTo(AT);
        assertThat(text(raw.requestBody())).contains("\"assignments\":[{\"id\":\"42\",\"level\":3}]");
        assertThat(raw.requestRaw()).isEqualTo(body.toByteArray());
        assertThat(raw.responseRaw()).isNull();
        assertThat(raw.status()).isNull();
    }

    @Test
    void recordsOurCallWithItsReply() {
        ByteString body = SendInvitationRequest.newBuilder()
                .setTarget(SendInvitationTarget.newBuilder().setBattleTag("Streamer#1234")).build().toByteString();
        Header reply = Header.newBuilder().setServiceId(254).setMethodId(0).setToken(7).setStatus(0).buildPartial();
        ServiceMethod<?, ?> method = BgsServices.FRIENDS_SEND_INVITATION;

        recorder.onExchange(new WireExchange(true, AT, 3_500_000L, header(method.serviceHash(), method.methodId()),
                body, reply, NoData.getDefaultInstance().toByteString(), method, null));

        RawExchange raw = recorded(Direction.OUT, "bgs.friends.v2.client.FriendsService." + method.methodId());
        assertThat(text(raw.requestBody())).contains("\"battle_tag\":\"Streamer#1234\"");
        assertThat(raw.status()).isZero();
        assertThat(raw.durationMs()).isEqualTo(3);
        assertThat(raw.responseHeaders().getFirst(BgsWireRecorder.HEADER)).contains("\"service_id\":254");
    }

    @Test
    void recordsAnUndecodableFrameFoundByItsRawBytes() {
        byte[] garbage = ("\u00ff\u00ffStreamer#1234").getBytes(StandardCharsets.ISO_8859_1);

        recorder.onExchange(serverCall(BgsServices.FRIENDS_LISTENER_ON_UPDATE_FRIEND_STATE,
                ByteString.copyFrom(garbage)));

        RawExchange raw = recorded(Direction.IN, "bgs.friends.v2.client.FriendsListener."
                + BgsServices.FRIENDS_LISTENER_ON_UPDATE_FRIEND_STATE.methodId());
        assertThat(raw.requestBody()).isNull();
        assertThat(raw.requestRaw()).isEqualTo(garbage);
    }

    @Test
    void neverRecordsAnUnknownMethodOfTheFriendsService() {
        ByteString named = ByteString.copyFrom("Streamer#1234", StandardCharsets.UTF_8);
        int friends = BgsServices.FRIENDS_LISTENER_ON_UPDATE_FRIEND_STATE.serviceHash();

        recorder.onExchange(new WireExchange(false, AT, 0, header(friends, 99), named, null, null, null, null));

        nothingRecorded();
    }

    @Test
    void neverRecordsTheBatchPresenceSubscription() {
        ServiceMethod<?, ?> method = BgsServices.PRESENCE_BATCH_SUBSCRIBE;
        ByteString request = BatchSubscribeRequest.newBuilder().addAccountIds(42L).addAccountIds(43L)
                .buildPartial().toByteString();

        recorder.onExchange(new WireExchange(true, AT, 0, header(method.serviceHash(), method.methodId()), request,
                null, null, method, null));

        nothingRecorded();
    }

    @Test
    void neverRecordsTheFriendsSubscription() {
        ServiceMethod<?, ?> method = BgsServices.FRIENDS_SUBSCRIBE;
        ByteString reply = ByteString.copyFrom("Streamer#1234", StandardCharsets.UTF_8);

        recorder.onExchange(new WireExchange(true, AT, 0, header(method.serviceHash(), method.methodId()),
                ByteString.EMPTY, Header.newBuilder().setServiceId(254).buildPartial(), reply, method, null));

        nothingRecorded();
    }

    @Test
    void searchesRawBytesOnlyInTheBodyThatDidNotDecode() {
        // The decoded request only holds the BattleTag inside a longer value: no exact match
        ByteString request = SendInvitationRequest.newBuilder()
                .setTarget(SendInvitationTarget.newBuilder().setBattleTag("xStreamer#1234y")).build().toByteString();
        ByteString garbageReply = ByteString.copyFrom(new byte[] {(byte) 0xFF, (byte) 0xFF});
        ServiceMethod<?, ?> method = BgsServices.FRIENDS_SEND_INVITATION;

        recorder.onExchange(new WireExchange(true, AT, 0, header(method.serviceHash(), method.methodId()), request,
                Header.newBuilder().setServiceId(254).buildPartial(), garbageReply, method, null));

        nothingRecorded();
    }

    @Test
    void ignoresAFrameNamingNoMemberInDebug() {
        recorder.onExchange(serverCall(BgsServices.FRIENDS_LISTENER_ON_UPDATE_FRIEND_STATE, friendState(43L)));

        nothingRecorded();
    }

    @Test
    void neverRecordsServicesOutsideTheAllowlist() {
        ByteString logon = LogonRequest.newBuilder().setLocale("Streamer#1234").buildPartial().toByteString();
        ByteString named = ByteString.copyFrom("Streamer#1234", StandardCharsets.UTF_8);

        recorder.onExchange(new WireExchange(true, AT, 0, header(BgsServices.AUTHENTICATION_LOGON.serviceHash(), 1),
                logon, null, null, BgsServices.AUTHENTICATION_LOGON, null));
        recorder.onExchange(new WireExchange(false, AT, 0, header(0x12345678, 3), named, null, null, null, null));
        recorder.onExchange(new WireExchange(true, AT, 0, header(BgsServices.SELECT_GAME_ACCOUNT_HASH, 1), named,
                null, null, null, null));
        recorder.onExchange(new WireExchange(false, AT, 0, header(BgsServices.SELF_PRESENCE_HASH, 1), named,
                null, null, null, null));

        nothingRecorded();
    }

    @Test
    void neverRecordsTheFriendLists() {
        ServiceMethod<?, ?> method = BgsServices.FRIENDS_GET_FRIENDS;
        ByteString friends = GetFriendsResponse.newBuilder().addFriends(Friend.newBuilder().setId(42L))
                .buildPartial().toByteString();
        Header reply = Header.newBuilder().setServiceId(254).setStatus(0).buildPartial();

        recorder.onExchange(new WireExchange(true, AT, 0, header(method.serviceHash(), method.methodId()),
                ByteString.EMPTY, reply, friends, method, null));
        // The same list answered to a raw call, known only by its header
        recorder.onExchange(new WireExchange(true, AT, 0, header(method.serviceHash(), method.methodId()),
                ByteString.EMPTY, reply, friends, null, null));

        nothingRecorded();
    }

    @Test
    void masksTheCiidInTheHeader() {
        recorder.onExchange(serverCall(BgsServices.FRIENDS_LISTENER_ON_UPDATE_FRIEND_STATE, friendState(42L)));

        RawExchange raw = recorded(Direction.IN, "bgs.friends.v2.client.FriendsListener."
                + BgsServices.FRIENDS_LISTENER_ON_UPDATE_FRIEND_STATE.methodId());
        String header = raw.requestHeaders().getFirst(BgsWireRecorder.HEADER);
        assertThat(header).contains("\"ciid\":\"[ciid]\"").doesNotContain("secret-ciid");
    }

    @Test
    void wantsIsFalseWhenNoMemberIsInDebug() {
        assertThat(recorder.wants()).isTrue();
        assertThat(new BgsWireRecorder(UUID.randomUUID(), watched.debug).wants()).isFalse();
        assertThat(new BgsWireRecorder(BOT, SessionDebug.OFF).wants()).isFalse();
    }

    @Test
    void ignoresAnExchangeWithoutHeader() {
        recorder.onExchange(new WireExchange(false, AT, 0, null, null, null, null, null, null));

        nothingRecorded();
    }

    @Test
    void aFailureIsSwallowed() {
        SessionDebug failing = mock(SessionDebug.class);
        doThrow(new IllegalStateException("boom")).when(failing).exchange(any(), any(), any(), any(), any());

        new BgsWireRecorder(BOT, failing).onExchange(
                serverCall(BgsServices.FRIENDS_LISTENER_ON_UPDATE_FRIEND_STATE, friendState(42L)));

        verify(failing).exchange(eq(BOT), any(), eq(Direction.IN), any(), any());
    }
}
