package fr.enimaloc.catapult.bgs.client;

import bgs.protocol.account.v1.AccountServiceOuterClass.AccountStateNotification;
import bgs.protocol.account.v1.AccountTypes.GameAccountList;
import bgs.protocol.account.v2.GameAccountHandleOuterClass.GameAccountHandle;
import bgs.protocol.authentication.v2.client.AuthenticationListenerOuterClass.ExternalChallengeNotification;
import bgs.protocol.authentication.v2.client.AuthenticationListenerOuterClass.LogonCompleteNotification;
import bgs.protocol.authentication.v2.client.AuthenticationTypes.LogonRecord;
import bgs.protocol.connection.v1.ConnectionServiceProto.ConnectResponse;
import bgs.protocol.connection.v1.ConnectionServiceProto.DisconnectNotification;
import bgs.protocol.friends.v2.client.FriendsListenerOuterClass.FriendAddedNotification;
import bgs.protocol.friends.v2.client.FriendsListenerOuterClass.FriendRemovedNotification;
import bgs.protocol.friends.v2.client.FriendsListenerOuterClass.ReceivedInvitationAddedNotification;
import bgs.protocol.friends.v2.client.FriendsListenerOuterClass.ReceivedInvitationRemovedNotification;
import bgs.protocol.friends.v2.client.FriendsListenerOuterClass.SentInvitationAddedNotification;
import bgs.protocol.friends.v2.client.FriendsListenerOuterClass.SentInvitationRemovedNotification;
import bgs.protocol.friends.v2.client.FriendsServiceOuterClass.GetFriendsResponse;
import bgs.protocol.friends.v2.client.FriendsServiceOuterClass.GetReceivedInvitationsResponse;
import bgs.protocol.friends.v2.client.FriendsServiceOuterClass.GetSentInvitationsResponse;
import bgs.protocol.friends.v2.client.FriendsServiceOuterClass.SubscribeRequest;
import bgs.protocol.friends.v2.client.FriendsTypes.Friend;
import bgs.protocol.friends.v2.client.FriendsTypes.ReceivedInvitation;
import bgs.protocol.friends.v2.client.FriendsTypes.RemovedFriendAssignment;
import bgs.protocol.friends.v2.client.FriendsTypes.RemovedInvitationAssignment;
import bgs.protocol.friends.v2.client.FriendsTypes.SentInvitation;
import bgs.protocol.presence.v2.client.PresenceServiceOuterClass.BatchSubscribeRequest;
import bgs.protocol.presence.v2.client.PresenceServiceOuterClass.BatchUnsubscribeRequest;
import com.google.protobuf.Message;
import fr.enimaloc.catapult.bgs.client.BgsListener.CloseReason;
import fr.enimaloc.catapult.bgs.core.BgsChannel;
import fr.enimaloc.catapult.bgs.core.BgsRpcException;
import fr.enimaloc.catapult.bgs.proto.ServiceMethod;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongFunction;

import static fr.enimaloc.catapult.bgs.proto.BgsServices.*;

/**
 * A Battle.net bot session: logon, friends list and friends' presence, on a session opened by the caller (Task 5
 * reconnects by calling {@link #start} again on a new session with the same client).
 *
 * <p>State of a session lives on its loop ({@link BgsChannel#loop()}); listener callbacks run there. The friends list
 * and the invitations survive across sessions: each new session resyncs them and reports the difference. The local
 * friends state changes only on pushes and sync results, never on the reply to an action.
 */
public final class BgsClient {

    private static final System.Logger LOG = System.getLogger(BgsClient.class.getName());

    /** ForceDisconnect code sent when another session opens the same account. */
    private static final int SESSION_REPLACED_CODE = 60;
    private static final int ERROR_INVALID_ARGS = 7;

    /** Connection settings. {@code email} goes into the logon options; it is masked in {@link #toString()}. */
    public record BgsClientConfig(String host, int port, String email, String locale) {

        @Override
        public String toString() {
            return "BgsClientConfig[host=" + host + ", port=" + port + ", email=***, locale=" + locale + "]";
        }
    }

    private record Invitation(long accountId, String battleTag) {
    }

    private final BgsClientConfig cfg;
    private final TicketProvider tickets;
    private final BgsListener listener;
    private final Executor mintExecutor;
    // Kept across sessions; written on the current session loop, read from any thread
    private final Map<Long, String> friends = new ConcurrentHashMap<>();
    private final Map<Long, Invitation> receivedInvitations = new ConcurrentHashMap<>();
    private final Map<Long, String> sentInvitations = new ConcurrentHashMap<>();
    private final PresenceTracker presence = new PresenceTracker();
    private volatile Session current;

    /** Mints tickets on virtual threads. */
    public BgsClient(BgsClientConfig cfg, TicketProvider tickets, BgsListener listener) {
        this(cfg, tickets, listener, Executors.newVirtualThreadPerTaskExecutor());
    }

    /** {@code mintExecutor} runs the blocking {@link TicketProvider#mint()}, away from the session loop. */
    public BgsClient(BgsClientConfig cfg, TicketProvider tickets, BgsListener listener, Executor mintExecutor) {
        this.cfg = cfg;
        this.tickets = tickets;
        this.listener = listener;
        this.mintExecutor = mintExecutor;
    }

    /**
     * Runs the session sequence on an open channel: Connect, ticket mint, Logon, account subscribe, game account
     * selection, then the friends sync and the presence watch.
     *
     * @return completes once the session is ready, meaning logon complete and game account selected (the presence
     * watch starts later, on the self presence push); fails with {@link TicketException} when minting fails,
     * {@link BgsRpcException} when the logon is refused (with the logon error code), or the cause of an early close
     * @throws IllegalStateException when the previous session is still open
     */
    public CompletableFuture<Void> start(BgsChannel channel) {
        Session previous = current;
        if (previous != null && !previous.closed.isDone()) {
            throw new IllegalStateException("A BGS session is already running");
        }
        Session session = new Session(channel);
        current = session;
        try {
            channel.loop().execute(session::begin);
        } catch (RejectedExecutionException e) {
            session.started.completeExceptionally(new IOException("BGS session already closed", e));
            session.onChannelClosed(e);
        }
        return session.started;
    }

    /**
     * Completes with the reason the current session ended: once per session, along with
     * {@link BgsListener#onClosed}. A local {@link #close()} reports {@link CloseReason#LOCAL}.
     *
     * @throws IllegalStateException before the first {@link #start}
     */
    public CompletableFuture<CloseReason> closed() {
        Session session = current;
        if (session == null) {
            throw new IllegalStateException("No BGS session started");
        }
        return session.closed;
    }

    /** True while the current session is logged on with its game account selected. */
    public boolean ready() {
        Session session = current;
        return session != null && session.ready && !session.closed.isDone();
    }

    /** Invites a BattleTag; the invitation shows up later as an {@link FriendEvent.InvitationSent} push. */
    public CompletableFuture<Void> sendInvitation(String battleTag) {
        return action("SendInvitation", FRIENDS_SEND_INVITATION, BgsRequests.sendInvitation(battleTag));
    }

    public CompletableFuture<Void> acceptInvitation(long invitationId) {
        return action("AcceptInvitation", FRIENDS_ACCEPT_INVITATION, BgsRequests.acceptInvitation(invitationId));
    }

    /** Declines a received invitation (IgnoreInvitation: friends v2 has no Decline). */
    public CompletableFuture<Void> declineInvitation(long invitationId) {
        return action("IgnoreInvitation", FRIENDS_IGNORE_INVITATION, BgsRequests.declineInvitation(invitationId));
    }

    /** Cancels an invitation the bot sent. Not tested against the real servers yet. */
    public CompletableFuture<Void> revokeInvitation(long invitationId) {
        return action("RevokeInvitation", FRIENDS_REVOKE_INVITATION, BgsRequests.revokeInvitation(invitationId));
    }

    public CompletableFuture<Void> removeFriend(long accountId) {
        return action("RemoveFriend", FRIENDS_REMOVE_FRIEND, BgsRequests.removeFriend(accountId));
    }

    /** A copy of the friends list: account id to BattleTag. */
    public Map<Long, String> friends() {
        return Map.copyOf(friends);
    }

    /** Closes the current session, if any; its close reason is {@link CloseReason#LOCAL}. */
    public void close() {
        Session session = current;
        if (session != null) {
            try {
                session.channel.loop().execute(session::closeLocally);
            } catch (RejectedExecutionException e) {
                // The loop is already shut down: the session is closed
            }
        }
    }

    /**
     * Fails at once, without queueing, when no session is ready. The reply never changes the local state: the push
     * that follows (often before the reply) does.
     */
    private CompletableFuture<Void> action(String what, ServiceMethod<?, ?> method, Message request) {
        Session session = current;
        if (session == null || !session.ready || session.closed.isDone()) {
            return CompletableFuture.failedFuture(new IllegalStateException("The BGS session is not ready"));
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        session.channel.call(method, request).whenComplete((reply, error) -> {
            if (error == null) {
                result.complete(null);
                return;
            }
            Throwable cause = unwrap(error);
            LOG.log(Level.WARNING, "BGS " + what + " failed: " + describe(cause));
            result.completeExceptionally(cause);
        });
        return result;
    }

    private void notifyListener(String what, Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "BGS listener " + what + " failed", e);
        }
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    private static String describe(Throwable error) {
        return error instanceof BgsRpcException rpc ? "status " + rpc.status() : error.getClass().getSimpleName();
    }

    /** One session: its sequence flags, handlers and outcome. Everything below runs on the channel loop. */
    private final class Session {

        final BgsChannel channel;
        final CompletableFuture<Void> started = new CompletableFuture<>();
        final CompletableFuture<CloseReason> closed = new CompletableFuture<>();
        volatile boolean ready;
        private CloseReason closeReason;
        private boolean selecting;
        private boolean presenceReady;
        private int connectedRegion;
        private long accountId;
        private String battleTag = "";
        private List<GameAccountHandle> logonGameAccounts = List.of();

        Session(BgsChannel channel) {
            this.channel = channel;
        }

        /** Pushes and replies of a session that ended, or was replaced, are ignored. */
        private boolean live() {
            return current == this && !closed.isDone();
        }

        void begin() {
            presence.clear();
            channel.onClose(this::onChannelClosed);
            channel.on(CONNECTION_FORCE_DISCONNECT, this::onForceDisconnect);
            channel.on(AUTHENTICATION_LISTENER_ON_LOGON_COMPLETE, this::onLogonComplete);
            channel.on(AUTHENTICATION_LISTENER_ON_LOGON_QUEUE_UPDATE,
                    n -> LOG.log(Level.INFO, "BGS logon queued"));
            channel.on(AUTHENTICATION_LISTENER_ON_EXTERNAL_CHALLENGE, this::onExternalChallenge);
            channel.on(ACCOUNT_LISTENER_ON_ACCOUNT_STATE_UPDATED, this::onAccountState);
            channel.on(FRIENDS_LISTENER_ON_FRIEND_ADDED, this::onFriendAdded);
            channel.on(FRIENDS_LISTENER_ON_FRIEND_REMOVED, this::onFriendRemoved);
            channel.on(FRIENDS_LISTENER_ON_RECEIVED_INVITATION_ADDED, this::onReceivedInvitationAdded);
            channel.on(FRIENDS_LISTENER_ON_RECEIVED_INVITATION_REMOVED, this::onReceivedInvitationRemoved);
            channel.on(FRIENDS_LISTENER_ON_SENT_INVITATION_ADDED, this::onSentInvitationAdded);
            channel.on(FRIENDS_LISTENER_ON_SENT_INVITATION_REMOVED, this::onSentInvitationRemoved);
            channel.on(PRESENCE_LISTENER_ON_PRESENCE_STATE_UPDATED, n -> {
                if (live()) {
                    presence.apply(n).forEach(e -> notifyListener("onPresence", () -> listener.onPresence(e)));
                }
            });
            channel.on(PRESENCE_LISTENER_ON_SUBSCRIBE_FAILURE,
                    n -> LOG.log(Level.WARNING, "BGS presence subscription failure pushed"));
            channel.onRaw(SELF_PRESENCE_HASH, 1, body -> onSelfPresence());
            channel.onRaw(SELF_PRESENCE_HASH, 2, body -> LOG.log(Level.WARNING,
                    "BGS account opened by another session: a ForceDisconnect is coming"));

            channel.call(CONNECTION_CONNECT, BgsRequests.connect()).whenComplete((reply, error) -> {
                if (!live()) {
                    return;
                }
                if (error != null) {
                    fail(CloseReason.IO, unwrap(error));
                    return;
                }
                onConnected(reply);
            });
        }

        private void onConnected(ConnectResponse reply) {
            channel.setCiid(reply.getCiid());
            connectedRegion = reply.getConnectedRegion();
            // The ticket is minted only now, between Connect and Logon: earlier, the logon raises OnExternalChallenge
            mintThen(ticket -> channel.call(AUTHENTICATION_LOGON, BgsRequests.logon(ticket, cfg))
                    .whenComplete((ack, error) -> {
                        if (error != null && live()) {
                            LOG.log(Level.ERROR, "BGS logon refused: " + describe(unwrap(error)));
                            fail(CloseReason.LOGON_FAILED, unwrap(error));
                        }
                    }));
        }

        /** Mints off the loop, then continues on it; a mint failure ends the session. */
        private void mintThen(Consumer<String> next) {
            CompletableFuture.supplyAsync(() -> {
                try {
                    return tickets.mint();
                } catch (TicketException e) {
                    throw new CompletionException(e);
                } catch (RuntimeException e) {
                    throw new CompletionException(new TicketException("Ticket minting failed", false, e));
                }
            }, mintExecutor).whenCompleteAsync((ticket, error) -> {
                if (!live()) {
                    return;
                }
                if (error == null) {
                    next.accept(ticket);
                    return;
                }
                Throwable cause = unwrap(error);
                boolean codeRequired = cause instanceof TicketException te && te.codeRequired();
                LOG.log(Level.WARNING, "BGS ticket minting failed (code required: " + codeRequired + "): "
                        + cause.getMessage());
                fail(codeRequired ? CloseReason.CODE_REQUIRED : CloseReason.IO, cause);
            }, channel.loop());
        }

        private void onLogonComplete(LogonCompleteNotification n) {
            if (!live() || accountId != 0) {
                return;
            }
            if (n.getErrorCode() != 0) {
                LOG.log(Level.ERROR, "BGS logon failed with error code " + n.getErrorCode());
                fail(CloseReason.LOGON_FAILED, new BgsRpcException(n.getErrorCode()));
                return;
            }
            LogonRecord record = n.getRecord();
            if (record.getAccountId() == 0) {
                LOG.log(Level.ERROR, "BGS logon complete without an account record");
                fail(CloseReason.LOGON_FAILED, new IllegalStateException("Logon complete without an account record"));
                return;
            }
            accountId = record.getAccountId();
            battleTag = record.getBattleTag();
            logonGameAccounts = record.getGameAccountList();
            LOG.log(Level.INFO, "BGS logon complete for account " + accountId);
            // Presence goes on at OnAccountStateUpdated: the game account to select comes with it
            channel.call(ACCOUNT_SUBSCRIBE, BgsRequests.accountSubscribe(accountId)).whenComplete((r, error) -> {
                if (error != null) {
                    LOG.log(Level.WARNING, "BGS account subscribe failed: " + describe(unwrap(error)));
                }
            });
        }

        private void onExternalChallenge(ExternalChallengeNotification n) {
            if (!live()) {
                return;
            }
            // Never seen live: the ticket minted between Connect and Logon avoids it
            LOG.log(Level.WARNING, "BGS external challenge received (payload type '" + n.getPayloadType()
                    + "', logon " + (accountId != 0 ? "complete" : "pending")
                    + "): untested path, re-minting a ticket and sending VerifyAuthToken on the same session");
            mintThen(ticket -> channel.call(AUTHENTICATION_VERIFY_AUTH_TOKEN, BgsRequests.verifyAuthToken(ticket))
                    .whenComplete((ack, error) -> {
                        if (error == null) {
                            LOG.log(Level.WARNING, "BGS VerifyAuthToken accepted");
                        } else {
                            LOG.log(Level.ERROR, "BGS VerifyAuthToken failed: " + describe(unwrap(error)));
                        }
                    }));
        }

        private void onAccountState(AccountStateNotification n) {
            // The select is done once per session: later account states are ignored
            if (!live() || selecting || accountId == 0) {
                return;
            }
            List<GameAccountHandle> candidates = new ArrayList<>(logonGameAccounts);
            for (GameAccountList list : n.getAccountState().getGameAccountsList()) {
                list.getHandleList().forEach(h -> candidates.add(GameAccountHandle.newBuilder()
                        .setId(h.getId()).setTitleId(h.getProgram()).setRegion(h.getRegion()).build()));
            }
            GameAccountHandle chosen = GameAccountSelector.select(candidates, connectedRegion);
            if (chosen == null) {
                LOG.log(Level.ERROR, "BGS account " + accountId + " has no game account to select: no presence");
                fail(CloseReason.IO, new IllegalStateException("No game account to select"));
                return;
            }
            selecting = true;
            channel.callRaw(SELECT_GAME_ACCOUNT_HASH, 1, BgsRequests.selectGameAccount(chosen), true)
                    .whenComplete((body, error) -> {
                        if (!live()) {
                            return;
                        }
                        if (error != null) {
                            LOG.log(Level.ERROR, "BGS game account select failed: " + describe(unwrap(error)));
                            fail(CloseReason.IO, unwrap(error));
                            return;
                        }
                        onSelected();
                    });
        }

        private void onSelected() {
            ready = true;
            LOG.log(Level.INFO, "BGS session ready for account " + accountId);
            started.complete(null);
            notifyListener("onReady", () -> listener.onReady(accountId, battleTag));
            // Presence subscriptions wait for the self presence push (onSelfPresence)
            channel.call(FRIENDS_SUBSCRIBE, SubscribeRequest.getDefaultInstance()).whenComplete((r, error) -> {
                if (error != null) {
                    LOG.log(Level.WARNING, "BGS friends subscribe failed: " + describe(unwrap(error)));
                }
            });
            syncFriends();
            syncReceivedInvitations();
            syncSentInvitations();
        }

        private void onSelfPresence() {
            if (!live() || presenceReady) {
                return;
            }
            presenceReady = true;
            batchSubscribe(List.of(accountId));
            if (!friends.isEmpty()) {
                batchSubscribe(friends.keySet().stream().sorted().toList());
            }
        }

        private void onForceDisconnect(DisconnectNotification n) {
            if (!live()) {
                return;
            }
            int code = n.getErrorCode();
            if (code == SESSION_REPLACED_CODE) {
                LOG.log(Level.ERROR, "BGS session replaced by another session on the same account (ForceDisconnect "
                        + code + ")");
                fail(CloseReason.SESSION_REPLACED, new IOException("Session replaced (ForceDisconnect 60)"));
            } else {
                LOG.log(Level.WARNING, "BGS ForceDisconnect with error code " + code);
                fail(CloseReason.FORCE_DISCONNECT, new IOException("ForceDisconnect " + code));
            }
        }

        /** Records why the session ends (first reason wins), fails a pending start and closes the channel. */
        private void fail(CloseReason reason, Throwable cause) {
            if (closeReason == null) {
                closeReason = reason;
            }
            started.completeExceptionally(cause);
            channel.close();
        }

        void closeLocally() {
            if (!closed.isDone()) {
                fail(CloseReason.LOCAL, new IOException("BGS session closed by the client"));
            }
        }

        void onChannelClosed(Throwable cause) {
            ready = false;
            CloseReason reason = closeReason != null ? closeReason : CloseReason.IO;
            started.completeExceptionally(cause != null ? cause : new IOException("BGS session closed before ready"));
            if (closed.complete(reason)) {
                LOG.log(reason == CloseReason.IO ? Level.INFO : Level.WARNING, "BGS session closed: " + reason);
                notifyListener("onClosed", () -> listener.onClosed(reason));
            }
        }

        // --- presence watch ---

        private void batchSubscribe(List<Long> ids) {
            channel.call(PRESENCE_BATCH_SUBSCRIBE, BatchSubscribeRequest.newBuilder().addAllAccountIds(ids).build())
                    .whenComplete((r, error) -> {
                        Throwable cause = error == null ? null : unwrap(error);
                        if (cause instanceof BgsRpcException rpc && rpc.status() == ERROR_INVALID_ARGS) {
                            LOG.log(Level.ERROR, "BGS presence BatchSubscribe refused with status 7: sent before"
                                    + " the self presence push, an ordering bug");
                        } else if (cause != null) {
                            LOG.log(Level.WARNING, "BGS presence BatchSubscribe failed: " + describe(cause));
                        }
                    });
        }

        private void watch(long id) {
            if (presenceReady) {
                batchSubscribe(List.of(id));
            }
        }

        private void unwatch(long id) {
            if (presenceReady) {
                channel.call(PRESENCE_BATCH_UNSUBSCRIBE, BatchUnsubscribeRequest.newBuilder().addAccountIds(id).build())
                        .whenComplete((r, error) -> {
                            if (error != null) {
                                LOG.log(Level.WARNING, "BGS presence BatchUnsubscribe failed: "
                                        + describe(unwrap(error)));
                            }
                        });
            }
            presence.forget(id).ifPresent(e -> notifyListener("onPresence", () -> listener.onPresence(e)));
        }

        // --- friends sync: page through, then report the difference with the known state ---

        private <R extends Message, T> void paged(ServiceMethod<?, R> method, LongFunction<Message> request,
                                                  Function<R, List<T>> items, Function<R, Long> continuation,
                                                  List<T> acc, long next, Consumer<List<T>> done) {
            channel.call(method, request.apply(next)).whenComplete((reply, error) -> {
                if (!live()) {
                    return;
                }
                if (error != null) {
                    LOG.log(Level.WARNING, "BGS friends sync (" + method.methodId() + ") failed: "
                            + describe(unwrap(error)));
                    return;
                }
                acc.addAll(items.apply(reply));
                long more = continuation.apply(reply);
                if (more != 0) {
                    paged(method, request, items, continuation, acc, more, done);
                } else {
                    done.accept(acc);
                }
            });
        }

        private void syncFriends() {
            paged(FRIENDS_GET_FRIENDS, BgsRequests::getFriends, GetFriendsResponse::getFriendsList,
                    GetFriendsResponse::getContinuation, new ArrayList<>(), 0, list -> {
                        Map<Long, String> now = new LinkedHashMap<>();
                        list.forEach(f -> now.put(f.getId(), f.getBattleTag()));
                        friends.keySet().stream().filter(id -> !now.containsKey(id)).sorted().toList()
                                .forEach(this::friendRemoved);
                        now.forEach((id, tag) -> {
                            if (!friends.containsKey(id)) {
                                friendAdded(id, tag);
                            }
                        });
                    });
        }

        private void syncReceivedInvitations() {
            paged(FRIENDS_GET_RECEIVED_INVITATIONS, BgsRequests::getReceivedInvitations,
                    GetReceivedInvitationsResponse::getInvitationsList,
                    GetReceivedInvitationsResponse::getContinuation, new ArrayList<>(), 0, list -> {
                        Map<Long, ReceivedInvitation> now = new LinkedHashMap<>();
                        list.forEach(i -> now.put(i.getId(), i));
                        receivedInvitations.keySet().stream().filter(id -> !now.containsKey(id)).sorted().toList()
                                .forEach(id -> receivedInvitationRemoved(id, 0));
                        now.values().forEach(this::receivedInvitationAdded);
                    });
        }

        private void syncSentInvitations() {
            paged(FRIENDS_GET_SENT_INVITATIONS, BgsRequests::getSentInvitations,
                    GetSentInvitationsResponse::getInvitationsList,
                    GetSentInvitationsResponse::getContinuation, new ArrayList<>(), 0, list -> {
                        Map<Long, String> now = new LinkedHashMap<>();
                        list.forEach(i -> now.put(i.getId(), i.getTargetName()));
                        // The outcome of an offline removal is unknown: not to be read as accepted (reason 0)
                        sentInvitations.keySet().stream().filter(id -> !now.containsKey(id)).sorted().toList()
                                .forEach(id -> sentInvitationRemoved(id, FriendEvent.REASON_GONE_WHILE_OFFLINE));
                        now.forEach(this::sentInvitationAdded);
                    });
        }

        // --- friends pushes (FriendsListener 1-6) ---

        private void onFriendAdded(FriendAddedNotification n) {
            if (live()) {
                for (Friend f : n.getFriendsList()) {
                    if (!friends.containsKey(f.getId())) {
                        friendAdded(f.getId(), f.getBattleTag());
                    }
                }
            }
        }

        private void onFriendRemoved(FriendRemovedNotification n) {
            if (live()) {
                for (RemovedFriendAssignment a : n.getAssignmentsList()) {
                    if (friends.containsKey(a.getId())) {
                        friendRemoved(a.getId());
                    }
                }
            }
        }

        private void onReceivedInvitationAdded(ReceivedInvitationAddedNotification n) {
            if (live()) {
                n.getInvitationsList().forEach(this::receivedInvitationAdded);
            }
        }

        private void onReceivedInvitationRemoved(ReceivedInvitationRemovedNotification n) {
            if (live()) {
                for (RemovedInvitationAssignment a : n.getAssignmentsList()) {
                    receivedInvitationRemoved(a.getInvitationId(), a.getReason());
                }
            }
        }

        private void onSentInvitationAdded(SentInvitationAddedNotification n) {
            if (live()) {
                for (SentInvitation i : n.getInvitationsList()) {
                    sentInvitationAdded(i.getId(), i.getTargetName());
                }
            }
        }

        private void onSentInvitationRemoved(SentInvitationRemovedNotification n) {
            if (live()) {
                for (RemovedInvitationAssignment a : n.getAssignmentsList()) {
                    sentInvitationRemoved(a.getInvitationId(), a.getReason());
                }
            }
        }

        // --- state changes: each one emits its event only when it changes something ---

        private void friendAdded(long id, String tag) {
            friends.put(id, tag);
            watch(id);
            emit(new FriendEvent.FriendAdded(id, tag));
        }

        private void friendRemoved(long id) {
            String tag = friends.remove(id);
            unwatch(id);
            emit(new FriendEvent.FriendRemoved(id, tag));
        }

        private void receivedInvitationAdded(ReceivedInvitation i) {
            Invitation inv = new Invitation(i.getInviter().getAccountId(), i.getInviter().getBattleTag());
            if (receivedInvitations.putIfAbsent(i.getId(), inv) == null) {
                emit(new FriendEvent.InvitationReceived(i.getId(), inv.accountId(), inv.battleTag()));
            }
        }

        private void receivedInvitationRemoved(long id, int reason) {
            Invitation inv = receivedInvitations.remove(id);
            if (inv != null) {
                emit(new FriendEvent.InvitationRemoved(id, inv.accountId(), inv.battleTag(), reason));
            }
        }

        private void sentInvitationAdded(long id, String targetName) {
            if (sentInvitations.putIfAbsent(id, targetName) == null) {
                emit(new FriendEvent.InvitationSent(id, targetName));
            }
        }

        private void sentInvitationRemoved(long id, int reason) {
            String targetName = sentInvitations.remove(id);
            if (targetName != null) {
                emit(new FriendEvent.InvitationSentRemoved(id, targetName, reason));
            }
        }

        private void emit(FriendEvent event) {
            notifyListener("onFriend", () -> listener.onFriend(event));
        }
    }
}
