package fr.enimaloc.catapult.service.epic;

import fr.enimaloc.catapult.common.dto.connect.EpicLinkStateResponse;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.domain.epic.EpicFriendLink;
import fr.enimaloc.catapult.domain.epic.EpicFriendLink.Status;
import fr.enimaloc.catapult.domain.epic.EpicServiceAccount;
import fr.enimaloc.catapult.domain.epic.EpicServiceAccount.AuthState;
import fr.enimaloc.catapult.repository.binding.GetterConfigRepository;
import fr.enimaloc.catapult.repository.epic.EpicFriendLinkRepository;
import fr.enimaloc.catapult.repository.epic.EpicServiceAccountRepository;
import fr.enimaloc.catapult.service.epic.EpicEnrollmentException.Reason;
import fr.enimaloc.catapult.service.epic.api.EpicAuthException;
import fr.enimaloc.catapult.service.epic.api.EpicFriendsClient;
import fr.enimaloc.catapult.service.epic.api.EpicFriendsClient.AddResult;
import fr.enimaloc.catapult.service.epic.api.EpicFriendsClient.Summary;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Friend links between Catapult users and the Epic bots.
 * <p>
 * {@link #enroll} writes a {@code PENDING} link on the first ready bot with room and has it send the friend request;
 * the link then follows the bot's friends listing ({@link #onFriendsSynced}): a friend accepts it, a request gone
 * without a friendship marks it {@code INVITE_REJECTED}, a friend gone removes it. A request received by the bot is
 * accepted only when a link of that bot waits for it. Every change of a link is announced to its channel.
 * <p>
 * Every listing is a full snapshot, so the handler is idempotent. Database writes run in short transactions of their
 * own; the requests to Epic are sent once they are committed, and a failed one is logged, never thrown.
 */
@Slf4j
@Service
@ConditionalOnBooleanProperty("epic.enabled")
public class EpicFriendService {

    private static final List<Status> OCCUPYING = List.of(Status.PENDING, Status.ACCEPTED, Status.INVITE_REJECTED);

    private final EpicServiceAccountRepository accountRepository;
    private final EpicFriendLinkRepository linkRepository;
    private final GetterConfigRepository getterConfigRepository;
    private final ChannelEventPublisher channelEventPublisher;
    private final EpicTokenService tokenService;
    private final EpicFriendsClient friendsClient;
    private final TransactionOperations transactions;
    private final int maxFriendsPerBot;

    public EpicFriendService(EpicServiceAccountRepository accountRepository,
                             EpicFriendLinkRepository linkRepository,
                             GetterConfigRepository getterConfigRepository,
                             ChannelEventPublisher channelEventPublisher,
                             EpicTokenService tokenService,
                             EpicFriendsClient friendsClient,
                             TransactionOperations transactions,
                             @Value("${epic.max-friends-per-bot:500}") int maxFriendsPerBot) {
        this.accountRepository = accountRepository;
        this.linkRepository = linkRepository;
        this.getterConfigRepository = getterConfigRepository;
        this.channelEventPublisher = channelEventPublisher;
        this.tokenService = tokenService;
        this.friendsClient = friendsClient;
        this.transactions = transactions;
        this.maxFriendsPerBot = maxFriendsPerBot;
    }

    // --- streamer side ---

    /**
     * Links {@code user} to the first enabled, {@code READY} bot with room (by fill order), replacing any existing
     * link, then has the bot send the friend request. A failed request keeps the link {@code PENDING} (the streamer
     * can still add the bot himself). Without a bot to take the link, the existing one is left untouched.
     *
     * @throws EpicEnrollmentException when the Epic account is another member's or a bot's, or no bot can take it
     */
    public EpicFriendLink enroll(UserAccount user, String epicAccountId, String displayName) {
        requireNotLinkedByAnother(user, epicAccountId);
        EpicFriendLink same = linkRepository.findWithServiceAccountByUser(user)
                .filter(own -> own.getEpicAccountId().equals(epicAccountId)
                        && (own.getStatus() == Status.PENDING || own.getStatus() == Status.ACCEPTED))
                .orElse(null);
        if (same != null) {
            return keep(same, displayName);
        }
        List<EpicServiceAccount> ready = accountRepository.findByEnabledTrueAndAuthStateOrderByFillOrderAsc(
                AuthState.READY);
        if (ready.isEmpty()) {
            throw new EpicEnrollmentException(Reason.NO_ACCOUNT_AVAILABLE,
                    "No enabled and ready Epic service account");
        }
        EpicServiceAccount bot = ready.stream()
                .filter(account -> !account.isFriendLimitReached()
                        && linkRepository.countByServiceAccountIdAndStatusIn(account.getId(), OCCUPYING)
                        < maxFriendsPerBot)
                .findFirst()
                .orElseThrow(() -> new EpicEnrollmentException(Reason.NO_CAPACITY,
                        "No Epic service account has room for a new friend"));

        unenroll(user);
        List<UUID> staleOwners = new ArrayList<>();
        EpicFriendLink link;
        try {
            link = transactions.execute(status -> {
                // A REMOVED link of another member on this account is stale: the account belongs to this member now
                List<EpicFriendLink> stale = linkRepository.findByEpicAccountIdAndStatus(epicAccountId,
                        Status.REMOVED);
                stale.forEach(old -> staleOwners.add(old.getUser().getId()));
                linkRepository.deleteAll(stale);
                EpicFriendLink created = new EpicFriendLink();
                created.setUser(user);
                created.setServiceAccount(bot);
                created.setEpicAccountId(epicAccountId);
                created.setDisplayName(displayName);
                created.setStatus(Status.PENDING);
                created.setRequestedAt(Instant.now());
                return linkRepository.save(created);
            });
        } catch (DataIntegrityViolationException e) {
            // Another enrolment took the Epic account (or this member's link) between the check and the insert
            throw new EpicEnrollmentException(Reason.ALREADY_LINKED,
                    "This Epic account is already linked to another user");
        }
        staleOwners.stream().filter(owner -> !owner.equals(user.getId()))
                .forEach(owner -> publish(owner, EpicLinkStateService.of(EpicLinkStateService.NONE)));
        log.info("Epic link requested for user {} via {}", user.getId(), bot.getId());
        publish(user.getId(), EpicLinkStateService.of(link));

        return sendRequest(link, bot).orElse(link);
    }

    /** The member links the Epic account they already have: the friendship or request stays, only the name moves. */
    private EpicFriendLink keep(EpicFriendLink link, String displayName) {
        if (displayName != null && !displayName.equals(link.getDisplayName())) {
            link.setDisplayName(displayName);
            transactions.executeWithoutResult(status -> linkRepository.save(link));
        }
        publish(link.getUser().getId(), EpicLinkStateService.of(link));
        return link;
    }

    /** One active link per Epic account, and never a bot's own account. */
    private void requireNotLinkedByAnother(UserAccount user, String epicAccountId) {
        boolean taken = accountRepository.findByEpicAccountId(epicAccountId).isPresent()
                || linkRepository.findByEpicAccountIdAndStatusNot(epicAccountId, Status.REMOVED).stream()
                .anyMatch(other -> !other.getUser().getId().equals(user.getId()));
        if (taken) {
            throw new EpicEnrollmentException(Reason.ALREADY_LINKED,
                    "This Epic account is already linked to another user");
        }
    }

    /**
     * Has the bot send the request; the streamer being a friend already accepts the link at once.
     *
     * @return the accepted link, empty when the request was sent or failed (the link stays {@code PENDING})
     */
    private Optional<EpicFriendLink> sendRequest(EpicFriendLink link, EpicServiceAccount bot) {
        AddResult result;
        try {
            result = withToken(bot.getId(),
                    token -> friendsClient.add(token, bot.getEpicAccountId(), link.getEpicAccountId()));
        } catch (RuntimeException e) {
            log.warn("Epic friend request failed on {} for user {}: {}", bot.getId(), link.getUser().getId(),
                    e.toString());
            return Optional.empty();
        }
        if (result != AddResult.ALREADY_FRIENDS) {
            return Optional.empty();
        }
        EpicFriendLink accepted = transactions.execute(status -> {
            EpicFriendLink current = linkRepository.findById(link.getId()).orElse(null);
            if (current == null || current.getStatus() == Status.ACCEPTED) {
                return null;
            }
            accept(current);
            linkRepository.save(current);
            return current;
        });
        if (accepted == null) {
            return Optional.empty();
        }
        log.info("Epic link accepted at once for user {} via {}", link.getUser().getId(), bot.getId());
        publish(link.getUser().getId(), EpicLinkStateService.of(accepted));
        return Optional.of(accepted);
    }

    /** Has the bot send the request again; only for a link still waiting ({@code PENDING}/{@code INVITE_REJECTED}). */
    public void resendInvite(UserAccount user) {
        EpicFriendLink link = linkRepository.findWithServiceAccountByUser(user).orElse(null);
        if (link == null || (link.getStatus() != Status.PENDING && link.getStatus() != Status.INVITE_REJECTED)) {
            return;
        }
        sendRequest(link, link.getServiceAccount());
    }

    /**
     * Deletes the link of {@code user}, after asking its bot to cancel the pending request or to remove the friend.
     * Epic's answer does not hold the deletion back: a failure is logged and the link is gone anyway.
     */
    public void unenroll(UserAccount user) {
        EpicFriendLink existing = linkRepository.findWithServiceAccountByUser(user).orElse(null);
        if (existing == null) {
            return;
        }
        // Every status but REMOVED: a resent request of an INVITE_REJECTED link is live, and deleting what Epic no
        // longer has is harmless
        if (existing.getStatus() != Status.REMOVED) {
            EpicServiceAccount bot = existing.getServiceAccount();
            try {
                withToken(bot.getId(), token -> {
                    friendsClient.delete(token, bot.getEpicAccountId(), existing.getEpicAccountId());
                    return null;
                });
            } catch (RuntimeException e) {
                log.warn("Epic friend delete failed on {} for user {}: {}", bot.getId(), user.getId(), e.toString());
            }
        }
        transactions.executeWithoutResult(status -> {
            linkRepository.delete(existing);
            getterConfigRepository.findByUserAndProvider(user, GetterConfig.Provider.EPIC).ifPresent(config -> {
                config.setEnabled(false);
                getterConfigRepository.save(config);
            });
        });
        log.info("Epic link of user {} deleted ({})", user.getId(), existing.getStatus());
        publish(user.getId(), EpicLinkStateService.of(EpicLinkStateService.NONE));
    }

    public Optional<EpicFriendLink> getLink(UserAccount user) {
        // with the service account fetched: the controller reads it outside the session (OSIV disabled)
        return linkRepository.findWithServiceAccountByUser(user);
    }

    // --- bot side ---

    /**
     * Moves the links of the bot with its friends listing. Synchronous on purpose: the session manager hands the
     * events off in order. A {@code REMOVED} link is final: the streamer links again through {@link #enroll}.
     */
    @EventListener
    public void onFriendsSynced(EpicFriendEventReceived received) {
        try {
            // The requests decided inside the transaction are sent once it is committed
            List<Runnable> afterCommit = new ArrayList<>();
            List<Change> changes = transactions.execute(
                    status -> apply(received.serviceAccountId(), received.summary(), afterCommit));
            try {
                if (changes != null) {
                    changes.forEach(change -> publish(change.ownerId(), change.state()));
                }
            } finally {
                afterCommit.forEach(Runnable::run);
            }
        } catch (RuntimeException e) {
            log.warn("Could not apply the Epic friends of service account {}: {}", received.serviceAccountId(),
                    e.toString());
        }
    }

    private List<Change> apply(UUID botId, Summary summary, List<Runnable> afterCommit) {
        EpicServiceAccount bot = accountRepository.findById(botId).orElse(null);
        if (bot == null) {
            log.debug("Epic friends of an unknown service account {}: ignored", botId);
            return List.of();
        }
        List<EpicFriendLink> links = linkRepository.findByServiceAccountId(botId).stream()
                .filter(link -> link.getStatus() != Status.REMOVED).toList();
        List<Change> changes = new ArrayList<>();
        for (EpicFriendLink link : links) {
            String id = link.getEpicAccountId();
            Status before = link.getStatus();
            if (summary.friends().contains(id)) {
                if (before == Status.ACCEPTED) {
                    continue;
                }
                accept(link);
                log.info("Epic link accepted for user {} via {}", link.getUser().getId(), botId);
            } else if (before == Status.ACCEPTED) {
                link.setStatus(Status.REMOVED);
                log.info("Epic link removed by the streamer: user {} via {}", link.getUser().getId(), botId);
            } else if (before == Status.PENDING) {
                // A request the streamer sent to the bot first is as alive as the bot's own
                if (summary.outgoing().contains(id) || summary.incoming().contains(id)) {
                    continue;
                }
                link.setStatus(Status.INVITE_REJECTED);
                log.info("Epic request of {} gone without a friendship: user {}", botId, link.getUser().getId());
            } else if (summary.outgoing().contains(id)) {
                // INVITE_REJECTED, the bot sent the request again
                link.setStatus(Status.PENDING);
            } else {
                continue;
            }
            linkRepository.save(link);
            changes.add(new Change(link.getUser().getId(), EpicLinkStateService.of(link)));
        }
        for (String requester : summary.incoming()) {
            answerRequest(bot, requester, links, afterCommit);
        }
        return changes;
    }

    /**
     * Accepts the request received by the bot when a link of this bot waits for the sender, refuses it otherwise. A
     * sender already accepted is left alone.
     */
    private void answerRequest(EpicServiceAccount bot, String requester, List<EpicFriendLink> links,
                               List<Runnable> afterCommit) {
        List<EpicFriendLink> candidates = links.stream()
                .filter(link -> link.getEpicAccountId().equals(requester)).toList();
        if (candidates.stream().anyMatch(link -> link.getStatus() == Status.ACCEPTED)) {
            return;
        }
        boolean expected = candidates.stream()
                .anyMatch(link -> link.getStatus() == Status.PENDING || link.getStatus() == Status.INVITE_REJECTED);
        UUID botId = bot.getId();
        String botAccountId = bot.getEpicAccountId();
        if (expected) {
            log.info("Accepting the Epic request of an enrolled streamer on {}", botId);
            afterCommit.add(() -> call(botId, "accept", token -> friendsClient.add(token, botAccountId, requester)));
        } else {
            log.info("Refusing an Epic request no link waits for on {}", botId);
            afterCommit.add(() -> call(botId, "refuse", token -> {
                friendsClient.delete(token, botAccountId, requester);
                return null;
            }));
        }
    }

    // --- internals ---

    private void accept(EpicFriendLink link) {
        link.setStatus(Status.ACCEPTED);
        link.setAcceptedAt(Instant.now());
        enableGetter(link.getUser());
    }

    private <T> void call(UUID botId, String what, Function<String, T> request) {
        try {
            withToken(botId, request);
        } catch (RuntimeException e) {
            log.warn("Epic {} failed on {}: {}", what, botId, e.toString());
        }
    }

    /** Runs {@code request} with the bot's access token; a token Epic rejects is dropped and the request retried once. */
    private <T> T withToken(UUID botId, Function<String, T> request) {
        try {
            return request.apply(tokenService.accessToken(botId));
        } catch (EpicAuthException e) {
            if (e.reason() != EpicAuthException.Reason.ACCESS_REJECTED) {
                throw e;
            }
            tokenService.evict(botId);
            return request.apply(tokenService.accessToken(botId));
        }
    }

    /**
     * The detection chain only consults providers that have a GetterConfig row: an accepted link creates or enables
     * the EPIC one, as {@code AnkamaFriendService} does for ANKAMA.
     */
    private void enableGetter(UserAccount user) {
        GetterConfig config = getterConfigRepository.findByUserAndProvider(user, GetterConfig.Provider.EPIC)
                .orElseGet(() -> {
                    GetterConfig created = new GetterConfig();
                    created.setUser(user);
                    created.setProvider(GetterConfig.Provider.EPIC);
                    created.setPriority(getterConfigRepository.findByUserOrderByPriorityAsc(user).size() + 1);
                    return created;
                });
        config.setEnabled(true);
        getterConfigRepository.save(config);
    }

    private void publish(UUID ownerId, EpicLinkStateResponse state) {
        channelEventPublisher.publishEpicLinkChanged(ownerId, state);
    }

    /** A link change to announce once its transaction is committed. */
    private record Change(UUID ownerId, EpicLinkStateResponse state) {
    }
}
