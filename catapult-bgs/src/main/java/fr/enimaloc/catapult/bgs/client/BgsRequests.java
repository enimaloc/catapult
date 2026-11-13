package fr.enimaloc.catapult.bgs.client;

import bgs.protocol.EntityProto.EntityId;
import bgs.protocol.SemanticVersionOuterClass.SemanticVersion;
import bgs.protocol.account.v1.AccountServiceOuterClass.SubscriptionUpdateRequest;
import bgs.protocol.account.v1.AccountTypes.AccountFieldOptions;
import bgs.protocol.account.v1.AccountTypes.SubscriberReference;
import bgs.protocol.account.v2.GameAccountHandleOuterClass.GameAccountHandle;
import bgs.protocol.authentication.v2.client.AuthenticationServiceOuterClass.LogonRequest;
import bgs.protocol.authentication.v2.client.AuthenticationServiceOuterClass.VerifyAuthTokenRequest;
import bgs.protocol.authentication.v2.client.AuthenticationTypes.LogonOptions;
import bgs.protocol.connection.v1.ConnectionServiceProto.ClientSdkVersionInfo;
import bgs.protocol.connection.v1.ConnectionServiceProto.ConnectRequest;
import bgs.protocol.connection.v1.ConnectionServiceProto.MeteringLevel;
import bgs.protocol.friends.v2.client.FriendsServiceOuterClass.AcceptInvitationRequest;
import bgs.protocol.friends.v2.client.FriendsServiceOuterClass.GetFriendsRequest;
import bgs.protocol.friends.v2.client.FriendsServiceOuterClass.GetReceivedInvitationsRequest;
import bgs.protocol.friends.v2.client.FriendsServiceOuterClass.GetSentInvitationsRequest;
import bgs.protocol.friends.v2.client.FriendsServiceOuterClass.IgnoreInvitationRequest;
import bgs.protocol.friends.v2.client.FriendsServiceOuterClass.RemoveFriendRequest;
import bgs.protocol.friends.v2.client.FriendsServiceOuterClass.RevokeInvitationRequest;
import bgs.protocol.friends.v2.client.FriendsServiceOuterClass.SendInvitationRequest;
import bgs.protocol.friends.v2.client.FriendsTypes.AcceptInvitationOptions;
import bgs.protocol.friends.v2.client.FriendsTypes.GetFriendsOptions;
import bgs.protocol.friends.v2.client.FriendsTypes.SendInvitationOptions;
import bgs.protocol.friends.v2.client.FriendsTypes.SendInvitationTarget;
import bgs.protocol.v2.AttributeTypes.Attribute;
import bgs.protocol.v2.AttributeTypes.Variant;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import fr.enimaloc.catapult.bgs.client.BgsClient.BgsClientConfig;
import fr.enimaloc.catapult.bgs.proto.Fourcc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Builds the request bodies of the session sequence and the friend actions, with the launcher's values. */
final class BgsRequests {

    /** The launcher itself ("App"): the title the bot logs on with. */
    static final int LAUNCHER_TITLE_ID = Fourcc.toId("App");
    private static final String PLATFORM = "Win";
    private static final int APPLICATION_VERSION = 25308;
    private static final String AUTH_PLATFORM = "BNET";
    /** Marks an account (not a game account) in an EntityId. */
    private static final long ACCOUNT_ENTITY_HIGH = 0x0100000000000000L;
    /** Any id works for the minimal subscribe; the spike used 1. */
    private static final long ACCOUNT_SUBSCRIBER_OBJECT_ID = 1;
    /** Without this attribute SendInvitation answers status 7. */
    private static final Attribute INVITATION_REASON = Attribute.newBuilder().setName("App-FriendInvitationReason")
            .setValue(Variant.newBuilder().setStringValue("enter_battletag")).build();
    private static final int FRIEND_LEVEL = 1;

    private BgsRequests() {
    }

    static ConnectRequest connect() {
        return ConnectRequest.newBuilder()
                .setUseBindlessRpc(true)
                .setMeteringLevel(MeteringLevel.METERING_LEVEL_LEGACY)
                .setClientSdkInfo(ClientSdkVersionInfo.newBuilder()
                        .setSdkName("BGS C++ SDK")
                        .setSdkVersion(version(8, 1, 0, "8.1.0"))
                        .setProtocolVersion(version(3, 18, 2, "3.18.2-dd0d4dc339")))
                .build();
    }

    static LogonRequest logon(String ticket, BgsClientConfig cfg) {
        LogonOptions.Builder options = LogonOptions.newBuilder()
                .setAuthToken(ByteString.copyFrom(ticket, StandardCharsets.UTF_8))
                .setAuthPlatform(AUTH_PLATFORM);
        if (cfg.email() != null && !cfg.email().isEmpty()) {
            options.setEmail(cfg.email());
        }
        return LogonRequest.newBuilder()
                .setTitleId(LAUNCHER_TITLE_ID)
                .setPlatform(PLATFORM)
                .setLocale(cfg.locale())
                .setApplicationVersion(APPLICATION_VERSION)
                .setLogonOptions(options)
                .build();
    }

    static VerifyAuthTokenRequest verifyAuthToken(String ticket) {
        return VerifyAuthTokenRequest.newBuilder().setAuthToken(ByteString.copyFrom(ticket, StandardCharsets.UTF_8))
                .build();
    }

    /** The minimal subscribe validated by the spike: one ref, no account tags. */
    static SubscriptionUpdateRequest accountSubscribe(long accountId) {
        return SubscriptionUpdateRequest.newBuilder()
                .addRef(SubscriberReference.newBuilder()
                        .setObjectId(ACCOUNT_SUBSCRIBER_OBJECT_ID)
                        .setEntityId(EntityId.newBuilder().setHigh(ACCOUNT_ENTITY_HIGH).setLow(accountId))
                        .setAccountOptions(AccountFieldOptions.newBuilder().setAllFields_(true)))
                .build();
    }

    /** Body of the "select game account" call, absent from bgs.desc: {@code { 1: GameAccountHandle }}. */
    static ByteString selectGameAccount(GameAccountHandle handle) {
        ByteString.Output out = ByteString.newOutput();
        CodedOutputStream coded = CodedOutputStream.newInstance(out);
        try {
            coded.writeMessage(1, handle);
            coded.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteString();
    }

    static GetFriendsRequest getFriends(long continuation) {
        return GetFriendsRequest.newBuilder().setContinuation(continuation)
                .setOptions(GetFriendsOptions.newBuilder().setFetchNames(true)).build();
    }

    static GetReceivedInvitationsRequest getReceivedInvitations(long continuation) {
        return GetReceivedInvitationsRequest.newBuilder().setContinuation(continuation).build();
    }

    static GetSentInvitationsRequest getSentInvitations(long continuation) {
        return GetSentInvitationsRequest.newBuilder().setContinuation(continuation).build();
    }

    static SendInvitationRequest sendInvitation(String battleTag) {
        return SendInvitationRequest.newBuilder()
                .setTarget(SendInvitationTarget.newBuilder().setBattleTag(battleTag))
                .setOptions(SendInvitationOptions.newBuilder().setLevel(FRIEND_LEVEL).addAttributes(INVITATION_REASON))
                .build();
    }

    static AcceptInvitationRequest acceptInvitation(long invitationId) {
        return AcceptInvitationRequest.newBuilder().setInvitationId(invitationId)
                .setOptions(AcceptInvitationOptions.newBuilder().setLevel(FRIEND_LEVEL)).build();
    }

    /** Friends v2 has no Decline: ignoring is declining (the inviter sees reason 3). */
    static IgnoreInvitationRequest declineInvitation(long invitationId) {
        return IgnoreInvitationRequest.newBuilder().setInvitationId(invitationId).build();
    }

    static RevokeInvitationRequest revokeInvitation(long invitationId) {
        return RevokeInvitationRequest.newBuilder().setInvitationId(invitationId).build();
    }

    static RemoveFriendRequest removeFriend(long accountId) {
        return RemoveFriendRequest.newBuilder().setTargetAccountId(accountId).build();
    }

    private static SemanticVersion version(int major, int minor, int patch, String text) {
        return SemanticVersion.newBuilder().setMajorVersion(major).setMinorVersion(minor).setPatchVersion(patch)
                .setVersionString(text).build();
    }
}
