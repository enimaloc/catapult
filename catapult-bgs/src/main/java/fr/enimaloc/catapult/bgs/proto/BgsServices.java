package fr.enimaloc.catapult.bgs.proto;

import com.google.protobuf.Message;
import bgs.protocol.RpcProto;
import bgs.protocol.account.v1.AccountServiceOuterClass;
import bgs.protocol.authentication.v2.client.AuthenticationListenerOuterClass;
import bgs.protocol.authentication.v2.client.AuthenticationServiceOuterClass;
import bgs.protocol.connection.v1.ConnectionServiceProto;
import bgs.protocol.friends.v2.client.FriendsListenerOuterClass;
import bgs.protocol.friends.v2.client.FriendsServiceOuterClass;
import bgs.protocol.presence.v2.PresenceListenerOuterClass;
import bgs.protocol.presence.v2.client.PresenceServiceOuterClass;

/**
 * The BGS methods the client uses, with the service hash, method id and body parsers of each. Hashes
 * and ids are checked against the descriptors in bgs.desc by ServiceTableTest.
 *
 * <p>Methods without a response parser are NO_RESPONSE: server notifications and fire-and-forget calls.
 */
public final class BgsServices {

    /** "Select game account" (service absent from bgs.desc): body {@code 1: GameAccountHandle{id, title_id, region}}. */
    public static final int SELECT_GAME_ACCOUNT_HASH = 0x7B37D770;

    /** Pushed by the server once the own presence is registered; no BatchSubscribe before it. Method 2 precedes ForceDisconnect. */
    public static final int SELF_PRESENCE_HASH = 0xBCA72135;

    public static final ServiceMethod<AccountServiceOuterClass.SubscriptionUpdateRequest, AccountServiceOuterClass.SubscriptionUpdateResponse>
            ACCOUNT_SUBSCRIBE =
            new ServiceMethod<>("account.v1.AccountService", 0x62DA0891, 25,
                    AccountServiceOuterClass.SubscriptionUpdateRequest.parser(), AccountServiceOuterClass.SubscriptionUpdateResponse.parser());

    public static final ServiceMethod<AccountServiceOuterClass.AccountStateNotification, Message>
            ACCOUNT_LISTENER_ON_ACCOUNT_STATE_UPDATED =
            new ServiceMethod<>("account.v1.AccountListener", 0x54DFDA17, 1,
                    AccountServiceOuterClass.AccountStateNotification.parser(), null);

    public static final ServiceMethod<AccountServiceOuterClass.GameAccountStateNotification, Message>
            ACCOUNT_LISTENER_ON_GAME_ACCOUNT_STATE_UPDATED =
            new ServiceMethod<>("account.v1.AccountListener", 0x54DFDA17, 2,
                    AccountServiceOuterClass.GameAccountStateNotification.parser(), null);

    public static final ServiceMethod<AuthenticationListenerOuterClass.LogonCompleteNotification, Message>
            AUTHENTICATION_LISTENER_ON_LOGON_COMPLETE =
            new ServiceMethod<>("authentication.v2.client.AuthenticationListener", 0x9DA8116B, 1,
                    AuthenticationListenerOuterClass.LogonCompleteNotification.parser(), null);

    public static final ServiceMethod<AuthenticationListenerOuterClass.LogonQueueUpdateNotification, Message>
            AUTHENTICATION_LISTENER_ON_LOGON_QUEUE_UPDATE =
            new ServiceMethod<>("authentication.v2.client.AuthenticationListener", 0x9DA8116B, 2,
                    AuthenticationListenerOuterClass.LogonQueueUpdateNotification.parser(), null);

    public static final ServiceMethod<RpcProto.NoData, Message> AUTHENTICATION_LISTENER_ON_LOGON_QUEUE_END =
            new ServiceMethod<>("authentication.v2.client.AuthenticationListener", 0x9DA8116B, 3,
                    RpcProto.NoData.parser(), null);

    public static final ServiceMethod<AuthenticationListenerOuterClass.ExternalChallengeNotification, Message>
            AUTHENTICATION_LISTENER_ON_EXTERNAL_CHALLENGE =
            new ServiceMethod<>("authentication.v2.client.AuthenticationListener", 0x9DA8116B, 4,
                    AuthenticationListenerOuterClass.ExternalChallengeNotification.parser(), null);

    public static final ServiceMethod<AuthenticationServiceOuterClass.LogonRequest, RpcProto.NoData>
            AUTHENTICATION_LOGON =
            new ServiceMethod<>("authentication.v2.client.AuthenticationService", 0xC02F8216, 1,
                    AuthenticationServiceOuterClass.LogonRequest.parser(), RpcProto.NoData.parser());

    public static final ServiceMethod<AuthenticationServiceOuterClass.VerifyAuthTokenRequest, RpcProto.NoData>
            AUTHENTICATION_VERIFY_AUTH_TOKEN =
            new ServiceMethod<>("authentication.v2.client.AuthenticationService", 0xC02F8216, 2,
                    AuthenticationServiceOuterClass.VerifyAuthTokenRequest.parser(), RpcProto.NoData.parser());

    public static final ServiceMethod<FriendsListenerOuterClass.FriendAddedNotification, Message>
            FRIENDS_LISTENER_ON_FRIEND_ADDED =
            new ServiceMethod<>("friends.v2.client.FriendsListener", 0xDBC01A89, 1,
                    FriendsListenerOuterClass.FriendAddedNotification.parser(), null);

    public static final ServiceMethod<FriendsListenerOuterClass.FriendRemovedNotification, Message>
            FRIENDS_LISTENER_ON_FRIEND_REMOVED =
            new ServiceMethod<>("friends.v2.client.FriendsListener", 0xDBC01A89, 2,
                    FriendsListenerOuterClass.FriendRemovedNotification.parser(), null);

    public static final ServiceMethod<FriendsListenerOuterClass.ReceivedInvitationAddedNotification, Message>
            FRIENDS_LISTENER_ON_RECEIVED_INVITATION_ADDED =
            new ServiceMethod<>("friends.v2.client.FriendsListener", 0xDBC01A89, 3,
                    FriendsListenerOuterClass.ReceivedInvitationAddedNotification.parser(), null);

    public static final ServiceMethod<FriendsListenerOuterClass.ReceivedInvitationRemovedNotification, Message>
            FRIENDS_LISTENER_ON_RECEIVED_INVITATION_REMOVED =
            new ServiceMethod<>("friends.v2.client.FriendsListener", 0xDBC01A89, 4,
                    FriendsListenerOuterClass.ReceivedInvitationRemovedNotification.parser(), null);

    public static final ServiceMethod<FriendsListenerOuterClass.SentInvitationAddedNotification, Message>
            FRIENDS_LISTENER_ON_SENT_INVITATION_ADDED =
            new ServiceMethod<>("friends.v2.client.FriendsListener", 0xDBC01A89, 5,
                    FriendsListenerOuterClass.SentInvitationAddedNotification.parser(), null);

    public static final ServiceMethod<FriendsListenerOuterClass.SentInvitationRemovedNotification, Message>
            FRIENDS_LISTENER_ON_SENT_INVITATION_REMOVED =
            new ServiceMethod<>("friends.v2.client.FriendsListener", 0xDBC01A89, 6,
                    FriendsListenerOuterClass.SentInvitationRemovedNotification.parser(), null);

    public static final ServiceMethod<FriendsListenerOuterClass.UpdateFriendStateNotification, Message>
            FRIENDS_LISTENER_ON_UPDATE_FRIEND_STATE =
            new ServiceMethod<>("friends.v2.client.FriendsListener", 0xDBC01A89, 7,
                    FriendsListenerOuterClass.UpdateFriendStateNotification.parser(), null);

    public static final ServiceMethod<FriendsServiceOuterClass.SubscribeRequest, RpcProto.NoData>
            FRIENDS_SUBSCRIBE =
            new ServiceMethod<>("friends.v2.client.FriendsService", 0x5869BE8C, 1,
                    FriendsServiceOuterClass.SubscribeRequest.parser(), RpcProto.NoData.parser());

    public static final ServiceMethod<FriendsServiceOuterClass.GetSentInvitationsRequest, FriendsServiceOuterClass.GetSentInvitationsResponse>
            FRIENDS_GET_SENT_INVITATIONS =
            new ServiceMethod<>("friends.v2.client.FriendsService", 0x5869BE8C, 3,
                    FriendsServiceOuterClass.GetSentInvitationsRequest.parser(), FriendsServiceOuterClass.GetSentInvitationsResponse.parser());

    public static final ServiceMethod<FriendsServiceOuterClass.GetReceivedInvitationsRequest, FriendsServiceOuterClass.GetReceivedInvitationsResponse>
            FRIENDS_GET_RECEIVED_INVITATIONS =
            new ServiceMethod<>("friends.v2.client.FriendsService", 0x5869BE8C, 4,
                    FriendsServiceOuterClass.GetReceivedInvitationsRequest.parser(), FriendsServiceOuterClass.GetReceivedInvitationsResponse.parser());

    public static final ServiceMethod<FriendsServiceOuterClass.GetFriendsRequest, FriendsServiceOuterClass.GetFriendsResponse>
            FRIENDS_GET_FRIENDS =
            new ServiceMethod<>("friends.v2.client.FriendsService", 0x5869BE8C, 5,
                    FriendsServiceOuterClass.GetFriendsRequest.parser(), FriendsServiceOuterClass.GetFriendsResponse.parser());

    public static final ServiceMethod<FriendsServiceOuterClass.SendInvitationRequest, RpcProto.NoData>
            FRIENDS_SEND_INVITATION =
            new ServiceMethod<>("friends.v2.client.FriendsService", 0x5869BE8C, 8,
                    FriendsServiceOuterClass.SendInvitationRequest.parser(), RpcProto.NoData.parser());

    public static final ServiceMethod<FriendsServiceOuterClass.AcceptInvitationRequest, RpcProto.NoData>
            FRIENDS_ACCEPT_INVITATION =
            new ServiceMethod<>("friends.v2.client.FriendsService", 0x5869BE8C, 9,
                    FriendsServiceOuterClass.AcceptInvitationRequest.parser(), RpcProto.NoData.parser());

    public static final ServiceMethod<FriendsServiceOuterClass.RevokeInvitationRequest, RpcProto.NoData>
            FRIENDS_REVOKE_INVITATION =
            new ServiceMethod<>("friends.v2.client.FriendsService", 0x5869BE8C, 10,
                    FriendsServiceOuterClass.RevokeInvitationRequest.parser(), RpcProto.NoData.parser());

    public static final ServiceMethod<FriendsServiceOuterClass.IgnoreInvitationRequest, RpcProto.NoData>
            FRIENDS_IGNORE_INVITATION =
            new ServiceMethod<>("friends.v2.client.FriendsService", 0x5869BE8C, 12,
                    FriendsServiceOuterClass.IgnoreInvitationRequest.parser(), RpcProto.NoData.parser());

    public static final ServiceMethod<FriendsServiceOuterClass.RemoveFriendRequest, RpcProto.NoData>
            FRIENDS_REMOVE_FRIEND =
            new ServiceMethod<>("friends.v2.client.FriendsService", 0x5869BE8C, 13,
                    FriendsServiceOuterClass.RemoveFriendRequest.parser(), RpcProto.NoData.parser());

    public static final ServiceMethod<PresenceListenerOuterClass.PresenceStateUpdatedNotification, Message>
            PRESENCE_LISTENER_ON_PRESENCE_STATE_UPDATED =
            new ServiceMethod<>("presence.v2.PresenceListener", 0xF4729709, 1,
                    PresenceListenerOuterClass.PresenceStateUpdatedNotification.parser(), null);

    public static final ServiceMethod<PresenceListenerOuterClass.SubscribeFailureNotification, Message>
            PRESENCE_LISTENER_ON_SUBSCRIBE_FAILURE =
            new ServiceMethod<>("presence.v2.PresenceListener", 0xF4729709, 2,
                    PresenceListenerOuterClass.SubscribeFailureNotification.parser(), null);

    public static final ServiceMethod<PresenceServiceOuterClass.BatchSubscribeRequest, RpcProto.NoData>
            PRESENCE_BATCH_SUBSCRIBE =
            new ServiceMethod<>("presence.v2.client.PresenceService", 0x138D200C, 3,
                    PresenceServiceOuterClass.BatchSubscribeRequest.parser(), RpcProto.NoData.parser());

    public static final ServiceMethod<PresenceServiceOuterClass.BatchUnsubscribeRequest, RpcProto.NoData>
            PRESENCE_BATCH_UNSUBSCRIBE =
            new ServiceMethod<>("presence.v2.client.PresenceService", 0x138D200C, 4,
                    PresenceServiceOuterClass.BatchUnsubscribeRequest.parser(), RpcProto.NoData.parser());

    public static final ServiceMethod<ConnectionServiceProto.ConnectRequest, ConnectionServiceProto.ConnectResponse>
            CONNECTION_CONNECT =
            new ServiceMethod<>("connection.v1.ConnectionService", 0x65446991, 1,
                    ConnectionServiceProto.ConnectRequest.parser(), ConnectionServiceProto.ConnectResponse.parser());

    public static final ServiceMethod<ConnectionServiceProto.DisconnectNotification, Message>
            CONNECTION_FORCE_DISCONNECT =
            new ServiceMethod<>("connection.v1.ConnectionService", 0x65446991, 4,
                    ConnectionServiceProto.DisconnectNotification.parser(), null);

    public static final ServiceMethod<RpcProto.NoData, Message> CONNECTION_KEEP_ALIVE =
            new ServiceMethod<>("connection.v1.ConnectionService", 0x65446991, 5,
                    RpcProto.NoData.parser(), null);

    public static final ServiceMethod<ConnectionServiceProto.DisconnectRequest, Message>
            CONNECTION_REQUEST_DISCONNECT =
            new ServiceMethod<>("connection.v1.ConnectionService", 0x65446991, 7,
                    ConnectionServiceProto.DisconnectRequest.parser(), null);

    private BgsServices() {
    }
}
