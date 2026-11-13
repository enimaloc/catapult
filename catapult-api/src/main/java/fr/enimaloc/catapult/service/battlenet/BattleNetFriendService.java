package fr.enimaloc.catapult.service.battlenet;

import fr.enimaloc.catapult.bgs.client.BgsClient;
import fr.enimaloc.catapult.bgs.client.FriendEvent;
import fr.enimaloc.catapult.bgs.client.FriendEvent.FriendAdded;
import fr.enimaloc.catapult.bgs.client.FriendEvent.FriendRemoved;
import fr.enimaloc.catapult.bgs.client.FriendEvent.InvitationReceived;
import fr.enimaloc.catapult.bgs.client.FriendEvent.InvitationRemoved;
import fr.enimaloc.catapult.bgs.client.FriendEvent.InvitationSent;
import fr.enimaloc.catapult.bgs.client.FriendEvent.InvitationSentRemoved;
import fr.enimaloc.catapult.bgs.core.BgsRpcException;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.battlenet.BattleNetFriendLink;
import fr.enimaloc.catapult.domain.battlenet.BattleNetFriendLink.Status;
import fr.enimaloc.catapult.domain.battlenet.BattleNetServiceAccount;
import fr.enimaloc.catapult.domain.battlenet.BattleNetServiceAccount.AuthState;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.repository.battlenet.BattleNetFriendLinkRepository;
import fr.enimaloc.catapult.repository.battlenet.BattleNetServiceAccountRepository;
import fr.enimaloc.catapult.repository.binding.GetterConfigRepository;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Friend links between Catapult users and the Battle.net bots (spec §10.1).
 * <p>
 * {@link #enroll} writes a {@code PENDING} link on the first ready bot with room and has it send a friend invitation;
 * the link then moves with the bot's friend events: {@code FriendAdded} accepts it, a sent invitation removed with a
 * non-zero reason rejects it, {@code FriendRemoved} removes it. A received invitation is accepted only when it is the
 * one a link of that bot waits for ({@code PENDING} or {@code INVITE_REJECTED}). Every change of a link is announced
 * to its channel.
 * <p>
 * Database writes run in short transactions of their own; every BGS call is made outside them, once they are
 * committed.
 */
@Slf4j
@Service
@ConditionalOnBooleanProperty("battlenet.enabled")
public class BattleNetFriendService {

    /** The friends list cap of a Battle.net account. */
    static final int FRIEND_CAP = 600;

    /**
     * BGS status answering a {@code SendInvitation} from a bot whose friends list is full. Taken from the error names
     * of the client (ERROR_FRIENDS_INVITER_AT_MAX_FRIENDS); to confirm in real use. A bot holding
     * {@link #FRIEND_CAP} accepted links is marked full on any failed invitation anyway.
     */
    static final int STATUS_INVITER_AT_MAX_FRIENDS = 5012;

    private final BattleNetServiceAccountRepository accountRepository;
    private final BattleNetFriendLinkRepository linkRepository;
    private final GetterConfigRepository getterConfigRepository;
    private final ChannelEventPublisher channelEventPublisher;
    private final BattleNetSessionManager sessionManager;
    private final TransactionOperations transactions;
    private final Executor followUps;

    @Autowired
    public BattleNetFriendService(BattleNetServiceAccountRepository accountRepository,
                                  BattleNetFriendLinkRepository linkRepository,
                                  GetterConfigRepository getterConfigRepository,
                                  ChannelEventPublisher channelEventPublisher,
                                  BattleNetSessionManager sessionManager,
                                  TransactionOperations transactions) {
        // The outcome of an invitation completes on the BGS session loop: its database follow-up leaves that thread
        this(accountRepository, linkRepository, getterConfigRepository, channelEventPublisher, sessionManager,
                transactions, task -> Thread.ofVirtual().name("battlenet-friends").start(task));
    }

    /** @param followUps runs what follows the outcome of an invitation sent at enrolment */
    BattleNetFriendService(BattleNetServiceAccountRepository accountRepository,
                           BattleNetFriendLinkRepository linkRepository,
                           GetterConfigRepository getterConfigRepository,
                           ChannelEventPublisher channelEventPublisher,
                           BattleNetSessionManager sessionManager,
                           TransactionOperations transactions,
                           Executor followUps) {
        this.accountRepository = accountRepository;
        this.linkRepository = linkRepository;
        this.getterConfigRepository = getterConfigRepository;
        this.channelEventPublisher = channelEventPublisher;
        this.sessionManager = sessionManager;
        this.transactions = transactions;
        this.followUps = followUps;
    }

    // --- streamer side ---

    /**
     * Links {@code user} to the first enabled, {@code READY} bot with room (by fill order), replacing any existing
     * link, then has the bot invite {@code battleTag}. A failed invitation keeps the link (the streamer can still add
     * the bot himself), unless it shows the bot is full: the link then moves to the next bot with room, or is deleted
     * when none is left. Without a bot to take the link, the existing one is left untouched.
     */
    public BattleNetFriendLink enroll(UserAccount user, long bnetAccountId, String battleTag) {
        List<BattleNetServiceAccount> ready = accountRepository.findByEnabledTrueAndAuthStateOrderByFillOrderAsc(
                AuthState.READY);
        if (ready.isEmpty()) {
            throw new BattleNetEnrollmentException(BattleNetEnrollmentException.Reason.NO_ACCOUNT_AVAILABLE,
                    "No enabled and ready Battle.net service account");
        }
        BattleNetServiceAccount bot = ready.stream()
                .filter(account -> !account.isFriendLimitReached())
                .findFirst()
                .orElseThrow(() -> new BattleNetEnrollmentException(BattleNetEnrollmentException.Reason.NO_CAPACITY,
                        "No Battle.net service account has room for a new friend"));

        unenroll(user);
        BattleNetFriendLink link = transactions.execute(status -> {
            BattleNetFriendLink created = new BattleNetFriendLink();
            created.setUser(user);
            created.setServiceAccount(bot);
            created.setBnetAccountId(bnetAccountId);
            created.setBattleTag(battleTag);
            created.setStatus(Status.PENDING);
            created.setRequestedAt(Instant.now());
            return linkRepository.save(created);
        });
        log.info("Battle.net link requested for user {} via {}", user.getId(), bot.getLabel());
        channelEventPublisher.battleNetLinkChanged(user.getId(), Status.PENDING.name(), battleTag, bot.getBattleTag());

        invite(bot, link.getId(), user.getId(), battleTag);
        return link;
    }

    /** Has {@code bot} invite {@code battleTag} for the link {@code linkId}; the outcome is handled off the session. */
    private void invite(BattleNetServiceAccount bot, UUID linkId, UUID ownerId, String battleTag) {
        call(bot.getId(), client -> client.sendInvitation(battleTag))
                .whenCompleteAsync((ignored, failure) -> {
                    if (failure != null) {
                        invitationFailed(bot, linkId, ownerId, battleTag, unwrap(failure));
                    }
                }, followUps);
    }

    /**
     * Deletes the link of {@code user}, after asking its bot to revoke the pending invitation or to remove the friend.
     * The bot's answer is not awaited: a failure is logged and the link is gone anyway.
     */
    public void unenroll(UserAccount user) {
        BattleNetFriendLink removed = transactions.execute(status -> {
            Optional<BattleNetFriendLink> link = linkRepository.findWithServiceAccountByUser(user);
            link.ifPresent(linkRepository::delete);
            getterConfigRepository.findByUserAndProvider(user, GetterConfig.Provider.BATTLENET).ifPresent(config -> {
                config.setEnabled(false);
                getterConfigRepository.save(config);
            });
            return link.orElse(null);
        });
        if (removed == null) {
            return;
        }
        log.info("Battle.net link of user {} deleted ({})", user.getId(), removed.getStatus());
        channelEventPublisher.battleNetLinkChanged(user.getId(), "NONE", null, null);

        UUID botId = removed.getServiceAccount().getId();
        if (removed.getStatus() == Status.PENDING && removed.getInvitationId() != null) {
            long invitationId = removed.getInvitationId();
            call(botId, client -> client.revokeInvitation(invitationId))
                    .whenComplete(logFailure("revokeInvitation", user.getId()));
        } else if (removed.getStatus() == Status.ACCEPTED) {
            long friendId = removed.getBnetAccountId();
            call(botId, client -> client.removeFriend(friendId))
                    .whenComplete(logFailure("removeFriend", user.getId()));
        }
    }

    public Optional<BattleNetFriendLink> getLink(UserAccount user) {
        // with the service account fetched: the controller reads it outside the session (OSIV disabled)
        return linkRepository.findWithServiceAccountByUser(user);
    }

    // --- bot side ---

    /**
     * Moves the links of the bot with its friend events. Synchronous on purpose: the session manager hands the events
     * off one at a time, in the order the session reported them, and a link's transitions depend on that order.
     */
    @EventListener
    public void onFriendEvent(BattleNetFriendEventReceived received) {
        try {
            // The BGS calls decided inside the transaction run once it is committed: an answer sent to the server
            // cannot be taken back by a rollback
            List<Runnable> afterCommit = new ArrayList<>();
            List<Change> changes = transactions.execute(status -> apply(received.serviceAccountId(),
                    received.event(), afterCommit));
            if (changes != null) {
                changes.forEach(this::publish);
            }
            afterCommit.forEach(Runnable::run);
        } catch (RuntimeException e) {
            log.warn("Could not apply the Battle.net friend event {} of service account {}: {}",
                    received.event().getClass().getSimpleName(), received.serviceAccountId(), e.toString());
        }
    }

    private List<Change> apply(UUID botId, FriendEvent event, List<Runnable> afterCommit) {
        BattleNetServiceAccount bot = accountRepository.findById(botId).orElse(null);
        if (bot == null) {
            log.debug("Battle.net friend event for an unknown service account {}: ignored", botId);
            return List.of();
        }
        List<Change> changes = new ArrayList<>();
        switch (event) {
            case FriendAdded added -> {
                for (BattleNetFriendLink link : linkRepository.findByServiceAccountAndBnetAccountId(bot,
                        added.accountId())) {
                    if (link.getStatus() == Status.ACCEPTED) {
                        continue;
                    }
                    link.setStatus(Status.ACCEPTED);
                    link.setAcceptedAt(Instant.now());
                    enableGetter(link.getUser());
                    changes.add(save(link, bot));
                    log.info("Battle.net link accepted for user {} via {}", link.getUser().getId(), bot.getLabel());
                }
                if (changes.isEmpty()) {
                    log.debug("Battle.net friend added on {} without a link to accept", bot.getLabel());
                }
            }
            case FriendRemoved removed -> {
                // Only an accepted link was a friendship: a PENDING one on the same account is a newer enrolment
                for (BattleNetFriendLink link : linkRepository.findByServiceAccountAndBnetAccountId(bot,
                        removed.accountId())) {
                    if (link.getStatus() == Status.ACCEPTED) {
                        link.setStatus(Status.REMOVED);
                        changes.add(save(link, bot));
                        log.info("Battle.net link removed by the streamer: user {} via {}", link.getUser().getId(),
                                bot.getLabel());
                    }
                }
                if (changes.isEmpty()) {
                    log.debug("Battle.net friend removed on {} without an accepted link", bot.getLabel());
                }
            }
            case InvitationSent sent -> {
                // The server gives no account id for the target (account_id 0): matched by BattleTag
                Optional<BattleNetFriendLink> pending = linkRepository
                        .findByServiceAccountAndBattleTagIgnoreCase(bot, sent.targetName()).stream()
                        .filter(link -> link.getStatus() == Status.PENDING)
                        .max(Comparator.comparing(BattleNetFriendLink::getRequestedAt));
                pending.ifPresentOrElse(link -> {
                    link.setInvitationId(sent.invitationId());
                    linkRepository.save(link);
                }, () -> log.debug("Battle.net invitation sent by {} matches no pending link", bot.getLabel()));
            }
            case InvitationSentRemoved sentRemoved -> {
                if (sentRemoved.reason() == 0) {
                    return List.of(); // accepted: the FriendAdded follows
                }
                // Matched by invitation id only: a re-enrolment of the same BattleTag must not be rejected by the
                // revocation of its previous invitation
                for (BattleNetFriendLink link : linkRepository.findByServiceAccountAndInvitationId(bot,
                        sentRemoved.invitationId())) {
                    // ACCEPTED already: the resync diff reported the FriendAdded of the same acceptance first
                    if (link.getStatus() == Status.ACCEPTED || link.getStatus() == Status.INVITE_REJECTED) {
                        continue;
                    }
                    link.setStatus(Status.INVITE_REJECTED);
                    changes.add(save(link, bot));
                    log.info("Battle.net invitation of {} not accepted (reason {}) for user {}", bot.getLabel(),
                            sentRemoved.reason(), link.getUser().getId());
                }
            }
            case InvitationReceived invitation -> afterCommit.add(answer(bot, invitation));
            case InvitationRemoved ignored -> {
                // the outcome shows up as a FriendAdded, or not at all
            }
        }
        return changes;
    }

    /**
     * Decides the answer to a received invitation, to send after the commit: accepted when a link of this bot with the
     * sender's account id waits for it ({@code PENDING}, or {@code INVITE_REJECTED}: the streamer declined the bot's
     * invitation and adds the bot himself), declined otherwise.
     */
    private Runnable answer(BattleNetServiceAccount bot, InvitationReceived invitation) {
        boolean expected = linkRepository.findByServiceAccountAndBnetAccountId(bot, invitation.accountId()).stream()
                .anyMatch(link -> link.getStatus() == Status.PENDING || link.getStatus() == Status.INVITE_REJECTED);
        long invitationId = invitation.invitationId();
        UUID botId = bot.getId();
        String label = bot.getLabel();
        if (expected) {
            log.info("Accepting the Battle.net invitation of an enrolled streamer on {}", label);
            return () -> call(botId, client -> client.acceptInvitation(invitationId))
                    .whenComplete((ignored, failure) -> {
                        if (failure != null) {
                            log.warn("acceptInvitation failed on {}: {}", label, unwrap(failure).toString());
                        }
                    });
        }
        log.info("Declining a Battle.net invitation no link waits for on {}", label);
        return () -> call(botId, client -> client.declineInvitation(invitationId)).whenComplete((ignored, failure) -> {
            if (failure != null) {
                log.warn("declineInvitation failed on {}: {}", label, unwrap(failure).toString());
            }
        });
    }

    // --- internals ---

    /**
     * A failed invitation keeps its link, unless it shows the bot is full (the failure says so, or the bot already
     * holds a full friends list): the bot is then marked full and the link moved to the next bot with room, which
     * invites in turn, or deleted when none is left.
     */
    private void invitationFailed(BattleNetServiceAccount bot, UUID linkId, UUID ownerId, String battleTag,
                                  Throwable failure) {
        try {
            boolean full = failure instanceof BgsRpcException rpc && rpc.status() == STATUS_INVITER_AT_MAX_FRIENDS
                    || linkRepository.countByServiceAccountAndStatus(bot, Status.ACCEPTED) >= FRIEND_CAP;
            if (!full) {
                log.warn("The Battle.net invitation sent by {} failed, the link stays pending: {}", bot.getLabel(),
                        failure.toString());
                return;
            }
            log.warn("The Battle.net invitation sent by {} shows its friends list is full: {}", bot.getLabel(),
                    failure.toString());
            markFull(bot.getId());
            relocate(bot.getId(), linkId, ownerId, battleTag);
        } catch (RuntimeException e) {
            log.warn("Could not handle the failed invitation of {}: {}", bot.getLabel(), e.toString());
        }
    }

    /** Moves the pending link off the full bot {@code fullBotId} to the next bot with room, or deletes it. */
    private void relocate(UUID fullBotId, UUID linkId, UUID ownerId, String battleTag) {
        BattleNetServiceAccount[] next = new BattleNetServiceAccount[1];
        Boolean moved = transactions.execute(status -> {
            BattleNetFriendLink link = linkId == null ? null : linkRepository.findById(linkId).orElse(null);
            // The link was replaced, removed or answered meanwhile: it is no longer this invitation's
            if (link == null || link.getStatus() != Status.PENDING
                    || !fullBotId.equals(link.getServiceAccount().getId())) {
                return null;
            }
            next[0] = accountRepository.findByEnabledTrueAndAuthStateOrderByFillOrderAsc(AuthState.READY).stream()
                    .filter(account -> !account.isFriendLimitReached() && !fullBotId.equals(account.getId()))
                    .findFirst().orElse(null);
            if (next[0] == null) {
                linkRepository.delete(link);
                return false;
            }
            link.setServiceAccount(next[0]);
            link.setInvitationId(null);
            link.setRequestedAt(Instant.now());
            linkRepository.save(link);
            return true;
        });
        if (moved == null) {
            return;
        }
        if (!moved) {
            log.warn("No Battle.net service account has room left: the link of user {} is deleted", ownerId);
            channelEventPublisher.battleNetLinkChanged(ownerId, "NONE", null, null);
            return;
        }
        log.info("Battle.net link of user {} moved to {}", ownerId, next[0].getLabel());
        channelEventPublisher.battleNetLinkChanged(ownerId, Status.PENDING.name(), battleTag, next[0].getBattleTag());
        invite(next[0], linkId, ownerId, battleTag);
    }

    /** Persists {@code friendLimitReached} on a fresh copy of the bot (an admin edit may have moved its version). */
    private void markFull(UUID botId) {
        transactions.executeWithoutResult(status -> accountRepository.findById(botId).ifPresent(account -> {
            if (account.isFriendLimitReached()) {
                return;
            }
            account.setFriendLimitReached(true);
            accountRepository.save(account);
            log.warn("Battle.net service account {} marked full", account.getLabel());
        }));
    }

    /**
     * The detection chain only consults providers that have a GetterConfig row: an accepted link creates or enables
     * the BATTLENET one, as {@code CatapultOAuth2UserService.enableGetter} does.
     */
    private void enableGetter(UserAccount user) {
        GetterConfig config = getterConfigRepository.findByUserAndProvider(user, GetterConfig.Provider.BATTLENET)
                .orElseGet(() -> {
                    GetterConfig created = new GetterConfig();
                    created.setUser(user);
                    created.setProvider(GetterConfig.Provider.BATTLENET);
                    created.setPriority(getterConfigRepository.findByUserOrderByPriorityAsc(user).size() + 1);
                    return created;
                });
        config.setEnabled(true);
        getterConfigRepository.save(config);
    }

    private Change save(BattleNetFriendLink link, BattleNetServiceAccount bot) {
        linkRepository.save(link);
        return new Change(link.getUser().getId(), link.getStatus(), link.getBattleTag(), bot.getBattleTag());
    }

    private void publish(Change change) {
        channelEventPublisher.battleNetLinkChanged(change.ownerId(), change.status().name(), change.battleTag(),
                change.serviceAccountBattleTag());
    }

    private CompletableFuture<Void> call(UUID botId, Function<BgsClient, CompletableFuture<Void>> action) {
        try {
            return sessionManager.withClient(botId, action);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static BiConsumer<Void, Throwable> logFailure(String what, UUID userId) {
        return (ignored, failure) -> {
            if (failure != null) {
                log.warn("{} failed for the deleted Battle.net link of user {}: {}", what, userId,
                        unwrap(failure).toString());
            }
        };
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
    }

    /** A link change to announce once its transaction is committed. */
    private record Change(UUID ownerId, Status status, String battleTag, String serviceAccountBattleTag) {
    }

    @Getter
    public static class BattleNetEnrollmentException extends RuntimeException {
        public enum Reason { NO_CAPACITY, NO_ACCOUNT_AVAILABLE }

        private final Reason reason;

        public BattleNetEnrollmentException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }
    }
}
