package fr.enimaloc.catapult.service.ankama;

import fr.enimaloc.catapult.common.dto.connect.AnkamaLinkStateResponse;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.ankama.AnkamaFriendLink;
import fr.enimaloc.catapult.domain.ankama.AnkamaFriendLink.Status;
import fr.enimaloc.catapult.domain.ankama.AnkamaServiceAccount;
import fr.enimaloc.catapult.domain.ankama.AnkamaServiceAccount.AuthState;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.repository.ankama.AnkamaFriendLinkRepository;
import fr.enimaloc.catapult.repository.ankama.AnkamaServiceAccountRepository;
import fr.enimaloc.catapult.repository.binding.GetterConfigRepository;
import fr.enimaloc.catapult.service.ankama.chat.ChatMessages;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Friend links between Catapult users and the Ankama bots (spec §4.2).
 * <p>
 * {@link #enroll} writes a {@code PENDING} link on the first ready bot with room and has it invite the streamer; the
 * link then moves with the bot's chat events: a friend created accepts it, a rejected invite of the bot marks it
 * {@code INVITE_REJECTED}, a friend deleted removes it. An invite received by the bot is accepted only when a link of
 * that bot waits for it. Every change of a link is announced to its channel.
 * <p>
 * The chat replays the friends and invites after each connection, so every handler is idempotent. Database writes run
 * in short transactions of their own; the chat requests are sent once they are committed.
 */
@Slf4j
@Service
@ConditionalOnBooleanProperty("ankama.enabled")
public class AnkamaFriendService {

    /** {@code name#tag}: a nickname without {@code #}, a tag of one to eight digits. */
    static final Pattern NAME_TAG = Pattern.compile("^\\s*([^#\\s][^#]*?)\\s*#\\s*(\\d{1,8})\\s*$");

    private static final List<Status> OCCUPYING = List.of(Status.PENDING, Status.ACCEPTED, Status.INVITE_REJECTED);

    public record NameTag(String name, String tag) {
    }

    private final AnkamaServiceAccountRepository accountRepository;
    private final AnkamaFriendLinkRepository linkRepository;
    private final GetterConfigRepository getterConfigRepository;
    private final ChannelEventPublisher channelEventPublisher;
    private final AnkamaSessionManager sessionManager;
    private final TransactionOperations transactions;
    private final int maxFriendsPerBot;
    /**
     * Invites received by a bot ({@code botId:inviterUserId}) that were already answered: the resync replays them
     * until the answer lands, and an invite is answered once.
     */
    private final Set<String> answered = ConcurrentHashMap.newKeySet();

    public AnkamaFriendService(AnkamaServiceAccountRepository accountRepository,
                               AnkamaFriendLinkRepository linkRepository,
                               GetterConfigRepository getterConfigRepository,
                               ChannelEventPublisher channelEventPublisher,
                               AnkamaSessionManager sessionManager,
                               TransactionOperations transactions,
                               @Value("${ankama.max-friends-per-bot:200}") int maxFriendsPerBot) {
        this.accountRepository = accountRepository;
        this.linkRepository = linkRepository;
        this.getterConfigRepository = getterConfigRepository;
        this.channelEventPublisher = channelEventPublisher;
        this.sessionManager = sessionManager;
        this.transactions = transactions;
        this.maxFriendsPerBot = maxFriendsPerBot;
    }

    /** @throws IllegalArgumentException when {@code raw} is not a {@code name#tag} */
    public static NameTag parse(String raw) {
        Matcher matcher = raw == null ? null : NAME_TAG.matcher(raw);
        if (matcher == null || !matcher.matches()) {
            throw new IllegalArgumentException("Not an Ankama name#tag");
        }
        return new NameTag(matcher.group(1), matcher.group(2));
    }

    // --- streamer side ---

    /**
     * Links {@code user} to the first enabled, {@code READY} bot with room (by fill order), replacing any existing
     * link, then has the bot invite the streamer. A failed invite keeps the link (the streamer can still add the bot
     * himself). Without a bot to take the link, the existing one is left untouched.
     */
    public AnkamaFriendLink enroll(UserAccount user, String rawNameTag) {
        NameTag nameTag = parse(rawNameTag);
        List<AnkamaServiceAccount> ready = accountRepository.findByEnabledTrueAndAuthStateOrderByFillOrderAsc(
                AuthState.READY);
        if (ready.isEmpty()) {
            throw new AnkamaEnrollmentException(AnkamaEnrollmentException.Reason.NO_ACCOUNT_AVAILABLE,
                    "No enabled and ready Ankama service account");
        }
        AnkamaServiceAccount bot = ready.stream()
                .filter(account -> !account.isFriendLimitReached()
                        && linkRepository.countByServiceAccountIdAndStatusIn(account.getId(), OCCUPYING)
                        < maxFriendsPerBot)
                .findFirst()
                .orElseThrow(() -> new AnkamaEnrollmentException(AnkamaEnrollmentException.Reason.NO_CAPACITY,
                        "No Ankama service account has room for a new friend"));

        unenroll(user);
        AnkamaFriendLink link = transactions.execute(status -> {
            AnkamaFriendLink created = new AnkamaFriendLink();
            created.setUser(user);
            created.setServiceAccount(bot);
            created.setRequestedName(nameTag.name());
            created.setRequestedTag(nameTag.tag());
            created.setStatus(Status.PENDING);
            created.setRequestedAt(Instant.now());
            return linkRepository.save(created);
        });
        log.info("Ankama link requested for user {} via {}", user.getId(), bot.getId());
        publish(user.getId(), AnkamaLinkStateService.of(link));

        send(bot.getId(), ChatMessages.invite(bot.getAnkamaAccountId(), nameTag.name(), nameTag.tag()),
                "invite", user.getId());
        return link;
    }

    /** Has the bot invite the streamer again; only for a link still waiting ({@code PENDING}/{@code INVITE_REJECTED}). */
    public void resendInvite(UserAccount user) {
        AnkamaFriendLink link = linkRepository.findWithServiceAccountByUser(user).orElse(null);
        if (link == null || (link.getStatus() != Status.PENDING && link.getStatus() != Status.INVITE_REJECTED)) {
            return;
        }
        AnkamaServiceAccount bot = link.getServiceAccount();
        send(bot.getId(), ChatMessages.invite(bot.getAnkamaAccountId(), link.getRequestedName(),
                link.getRequestedTag()), "invite", user.getId());
    }

    /**
     * Deletes the link of {@code user}, after asking its bot to cancel the pending invite or to remove the friend. The
     * bot's answer is not awaited: a failure is logged and the link is gone anyway.
     */
    public void unenroll(UserAccount user) {
        AnkamaFriendLink removed = transactions.execute(status -> {
            Optional<AnkamaFriendLink> link = linkRepository.findWithServiceAccountByUser(user);
            link.ifPresent(linkRepository::delete);
            getterConfigRepository.findByUserAndProvider(user, GetterConfig.Provider.ANKAMA).ifPresent(config -> {
                config.setEnabled(false);
                getterConfigRepository.save(config);
            });
            return link.orElse(null);
        });
        if (removed == null) {
            return;
        }
        log.info("Ankama link of user {} deleted ({})", user.getId(), removed.getStatus());
        publish(user.getId(), AnkamaLinkStateService.of(AnkamaLinkStateService.NONE));

        AnkamaServiceAccount bot = removed.getServiceAccount();
        Long streamerId = removed.getAnkamaUserId();
        if (streamerId == null) {
            return;
        }
        if (removed.getStatus() == Status.ACCEPTED) {
            send(bot.getId(), ChatMessages.deleteFriend(bot.getAnkamaAccountId(), streamerId), "deleteFriend",
                    user.getId());
        } else if (removed.getStatus() == Status.PENDING) {
            send(bot.getId(), ChatMessages.deleteInvite(bot.getAnkamaAccountId(), streamerId,
                    ChatMessages.INVITE_CANCEL), "deleteInvite", user.getId());
        }
    }

    public Optional<AnkamaFriendLink> getLink(UserAccount user) {
        // with the service account fetched: the controller reads it outside the session (OSIV disabled)
        return linkRepository.findWithServiceAccountByUser(user);
    }

    // --- bot side ---

    /**
     * Moves the links of the bot with its chat events. Synchronous on purpose: the session manager hands the events
     * off one at a time, in the order the session reported them, and a link's transitions depend on that order.
     */
    @EventListener
    public void onFriendEvent(AnkamaFriendEventReceived received) {
        try {
            // The answers decided inside the transaction are sent once it is committed: an answer sent to the server
            // cannot be taken back by a rollback
            List<Runnable> afterCommit = new ArrayList<>();
            List<Change> changes = transactions.execute(status -> apply(received.serviceAccountId(),
                    received.event(), afterCommit));
            if (changes != null) {
                changes.forEach(change -> publish(change.ownerId(), change.state()));
            }
            afterCommit.forEach(Runnable::run);
        } catch (RuntimeException e) {
            log.warn("Could not apply the Ankama friend event {} of service account {}: {}",
                    received.event().getClass().getSimpleName(), received.serviceAccountId(), e.toString());
        }
    }

    private List<Change> apply(UUID botId, ChatMessages.Event event, List<Runnable> afterCommit) {
        AnkamaServiceAccount bot = accountRepository.findById(botId).orElse(null);
        if (bot == null) {
            log.debug("Ankama friend event for an unknown service account {}: ignored", botId);
            return List.of();
        }
        List<Change> changes = new ArrayList<>();
        switch (event) {
            case ChatMessages.InviteCreated created -> {
                ChatMessages.Invite invite = created.invite();
                if (invite == null) {
                    return changes;
                }
                if (isBot(bot, invite.inviter())) {
                    learnRecipient(bot, invite.recipient());
                } else if (isBot(bot, invite.recipient()) && invite.inviter() != null) {
                    answer(bot, invite.inviter(), afterCommit);
                }
            }
            case ChatMessages.InviteDeleted deleted -> {
                ChatMessages.Invite invite = deleted.invite();
                if (invite != null && isBot(bot, invite.recipient()) && invite.inviter() != null) {
                    answered.remove(answeredKey(bot, invite.inviter().userId()));
                }
                // Only the rejection of the bot's own invite matters: an accepted one shows up as a FriendCreated
                if (invite != null && isBot(bot, invite.inviter()) && deleted.reason() == ChatMessages.INVITE_REJECT) {
                    for (AnkamaFriendLink link : links(bot, invite.recipient())) {
                        if (link.getStatus() == Status.PENDING) {
                            link.setStatus(Status.INVITE_REJECTED);
                            changes.add(save(link));
                            log.info("Ankama invite of {} rejected by user {}", bot.getId(), link.getUser().getId());
                        }
                    }
                }
            }
            case ChatMessages.FriendCreated created -> {
                answered.remove(answeredKey(bot, created.friend() == null ? -1 : created.friend().userId()));
                for (AnkamaFriendLink link : links(bot, created.friend())) {
                    if (link.getAnkamaUserId() == null) {
                        link.setAnkamaUserId(created.friend().userId());
                    }
                    if (link.getStatus() == Status.ACCEPTED) {
                        linkRepository.save(link);
                        continue;
                    }
                    link.setStatus(Status.ACCEPTED);
                    link.setAcceptedAt(Instant.now());
                    enableGetter(link.getUser());
                    changes.add(save(link));
                    log.info("Ankama link accepted for user {} via {}", link.getUser().getId(), bot.getId());
                }
            }
            case ChatMessages.FriendDeleted deleted -> {
                answered.remove(answeredKey(bot, deleted.friendUserId()));
                // Only an accepted link was a friendship: a PENDING one on the same account is a newer enrolment
                for (AnkamaFriendLink link : linkRepository.findByServiceAccountId(bot.getId())) {
                    if (link.getStatus() == Status.ACCEPTED && link.getAnkamaUserId() != null
                            && link.getAnkamaUserId() == deleted.friendUserId()) {
                        link.setStatus(Status.REMOVED);
                        changes.add(save(link));
                        log.info("Ankama link removed by the streamer: user {} via {}", link.getUser().getId(),
                                bot.getId());
                    }
                }
            }
            default -> {
                // presence and endpoint updates are not link business
            }
        }
        return changes;
    }

    /** The bot's own invite reached the streamer: keeps the streamer's user id on the link. */
    private void learnRecipient(AnkamaServiceAccount bot, ChatMessages.User recipient) {
        if (recipient == null) {
            return;
        }
        for (AnkamaFriendLink link : links(bot, recipient)) {
            if (link.getAnkamaUserId() == null && link.getStatus() != Status.ACCEPTED) {
                link.setAnkamaUserId(recipient.userId());
                linkRepository.save(link);
            }
        }
    }

    /**
     * Decides the answer to an invite received by the bot, to send after the commit: accepted when a link of this bot
     * waits for the sender ({@code PENDING}, or {@code INVITE_REJECTED}: the streamer declined the bot's invite and
     * adds the bot himself), rejected when none does. A sender already accepted is left alone, and an invite is
     * answered once however often the resync replays it.
     */
    private void answer(AnkamaServiceAccount bot, ChatMessages.User inviter, List<Runnable> afterCommit) {
        List<AnkamaFriendLink> candidates = links(bot, inviter);
        if (candidates.stream().anyMatch(link -> link.getStatus() == Status.ACCEPTED)) {
            return;
        }
        String key = answeredKey(bot, inviter.userId());
        if (!answered.add(key)) {
            return;
        }
        Optional<AnkamaFriendLink> expected = candidates.stream()
                .filter(link -> link.getStatus() == Status.PENDING || link.getStatus() == Status.INVITE_REJECTED)
                .findFirst();
        UUID botId = bot.getId();
        if (expected.isPresent()) {
            expected.get().setAnkamaUserId(inviter.userId());
            linkRepository.save(expected.get());
            log.info("Accepting the Ankama invite of an enrolled streamer on {}", botId);
            afterCommit.add(() -> sendAnswer(botId, key, ChatMessages.deleteInvite(inviter.userId(),
                    bot.getAnkamaAccountId(), ChatMessages.INVITE_ACCEPT)));
        } else {
            log.info("Rejecting an Ankama invite no link waits for on {}", botId);
            afterCommit.add(() -> sendAnswer(botId, key, ChatMessages.deleteInvite(inviter.userId(),
                    bot.getAnkamaAccountId(), ChatMessages.INVITE_REJECT)));
        }
    }

    // --- internals ---

    private void sendAnswer(UUID botId, String key, ChatMessages.Command command) {
        if (!send(botId, command, "deleteInvite", null)) {
            answered.remove(key);
        }
    }

    /** Sends {@code command} through the bot's session; a failure is logged. @return whether it was handed off */
    private boolean send(UUID botId, ChatMessages.Command command, String what, UUID userId) {
        try {
            sessionManager.request(botId, command.toPayload()).whenComplete((response, failure) -> {
                if (failure != null) {
                    log.warn("Ankama {} failed on {}{}: {}", what, botId, userId == null ? "" : " for user " + userId,
                            failure.toString());
                }
            });
            return true;
        } catch (RuntimeException e) {
            log.warn("Ankama {} could not be sent on {}: {}", what, botId, e.toString());
            return false;
        }
    }

    private static boolean isBot(AnkamaServiceAccount bot, ChatMessages.User user) {
        return user != null && user.userId() == bot.getAnkamaAccountId();
    }

    private static String answeredKey(AnkamaServiceAccount bot, long inviterUserId) {
        return bot.getId() + ":" + inviterUserId;
    }

    /** The links of {@code bot} for {@code user}: by user id when the link knows it, else by name#tag. */
    private List<AnkamaFriendLink> links(AnkamaServiceAccount bot, ChatMessages.User user) {
        if (user == null) {
            return List.of();
        }
        return linkRepository.findByServiceAccountId(bot.getId()).stream()
                .filter(link -> link.getAnkamaUserId() != null
                        ? link.getAnkamaUserId() == user.userId()
                        : user.name() != null && user.tag() != null && link.displayTag().toLowerCase(Locale.ROOT)
                                .equals((user.name() + "#" + user.tag()).toLowerCase(Locale.ROOT)))
                .toList();
    }

    /**
     * The detection chain only consults providers that have a GetterConfig row: an accepted link creates or enables
     * the ANKAMA one, as {@code BattleNetFriendService} does for BATTLENET.
     */
    private void enableGetter(UserAccount user) {
        GetterConfig config = getterConfigRepository.findByUserAndProvider(user, GetterConfig.Provider.ANKAMA)
                .orElseGet(() -> {
                    GetterConfig created = new GetterConfig();
                    created.setUser(user);
                    created.setProvider(GetterConfig.Provider.ANKAMA);
                    created.setPriority(getterConfigRepository.findByUserOrderByPriorityAsc(user).size() + 1);
                    return created;
                });
        config.setEnabled(true);
        getterConfigRepository.save(config);
    }

    private Change save(AnkamaFriendLink link) {
        linkRepository.save(link);
        return new Change(link.getUser().getId(), AnkamaLinkStateService.of(link));
    }

    private void publish(UUID ownerId, AnkamaLinkStateResponse state) {
        channelEventPublisher.publishAnkamaLinkChanged(ownerId, state);
    }

    /** A link change to announce once its transaction is committed. */
    private record Change(UUID ownerId, AnkamaLinkStateResponse state) {
    }

    @Getter
    public static class AnkamaEnrollmentException extends RuntimeException {
        public enum Reason { NO_CAPACITY, NO_ACCOUNT_AVAILABLE }

        private final Reason reason;

        public AnkamaEnrollmentException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }
    }
}
