package fr.enimaloc.catapult.service.minecraft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.domain.minecraft.MinecraftFriendLink;
import fr.enimaloc.catapult.domain.minecraft.MinecraftServiceAccount;
import fr.enimaloc.catapult.repository.binding.GetterConfigRepository;
import fr.enimaloc.catapult.repository.minecraft.MinecraftFriendLinkRepository;
import fr.enimaloc.catapult.repository.minecraft.MinecraftServiceAccountRepository;
import fr.enimaloc.catapult.service.debug.BatchDebugFilter;
import fr.enimaloc.catapult.service.debug.DebugRecording;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulationGate;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Lifecycle of the friend links between users and service accounts.
 * Accounts are filled in sequence: the (unknown) friend limit is discovered at the
 * first failed add, and the account is then marked full (reversible by an admin).
 * <p>
 * Under the simulation, enrolment and unenrolment only reach Minecraft while the member's {@code minecraft.friends}
 * is {@code REAL}; otherwise the link is written as if the player had accepted at once, with a made-up profile id,
 * and removed without asking the service account.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("minecraft.enabled")
public class MinecraftFriendService {

    private final MinecraftService minecraftService;
    private final MinecraftTokenService tokenService;
    private final MinecraftServiceAccountRepository accountRepository;
    private final MinecraftFriendLinkRepository linkRepository;
    private final MinecraftAccountLimitMarker limitMarker;
    private final GetterConfigRepository getterConfigRepository;
    private final ObjectMapper objectMapper;
    private final ChannelEventPublisher channelEventPublisher;

    static final String FRIENDS_OPERATION = "minecraft.friends";
    static final String MANAGE_FRIENDS_OPERATION = "minecraft.manage_friends";

    private final DebugRecording debug;
    private final ObjectProvider<SimulationGate> simulationGate;

    @Transactional
    public MinecraftFriendLink enroll(UserAccount user, String playerName) {
        if (SimulationGate.blocks(simulationGate, user.getId(), SimulatedCalls.MINECRAFT_FRIENDS)) {
            return enrollSimulated(user, playerName);
        }
        MinecraftService.ProfileLookup profile = minecraftService.lookupProfile(playerName)
                .orElseThrow(() -> new MinecraftEnrollmentException(
                        MinecraftEnrollmentException.Reason.UNKNOWN_PLAYER, "Unknown Minecraft player: " + playerName));

        // Fetch the existing link without deleting it and reuse it in place, to avoid violating the
        // unique user_id constraint (Hibernate orders INSERT before DELETE on flush).
        MinecraftFriendLink existing = linkRepository.findByUser(user).orElse(null);
        if (existing != null) {
            // Best-effort removal on the API side, with the current service account's token.
            Optional<String> existingToken = tokenService.getToken(existing.getServiceAccount());
            if (existingToken.isPresent()) {
                try {
                    friendsCall(existing.getUser(), existing.getMinecraftProfileId(),
                            () -> minecraftService.removeFriend(existingToken.get(), null,
                                    existing.getMinecraftProfileId()));
                } catch (Exception e) {
                    log.warn("removeFriend failed for {}: {}", existing.getMinecraftProfileId(), e.getMessage());
                }
            } else {
                log.warn("No token for {} — re-enrolling without removeFriend on the API side",
                        existing.getServiceAccount().getLabel());
            }
        }

        for (MinecraftServiceAccount account : accountRepository.findByEnabledTrueOrderByFillOrderAsc()) {
            if (account.isFriendLimitReached()) continue;
            Optional<String> token = tokenService.getToken(account);
            if (token.isEmpty()) continue; // account failing auth: try the next one

            try {
                friendsCall(user, profile.dashedId(),
                        () -> minecraftService.addFriend(token.get(), null, profile.dashedId()));
            } catch (HttpClientErrorException e) {
                if (isInviteRejected(e)) {
                    log.info("Minecraft invite rejected by the player (privacy) for {} via {}",
                            profile.name(), account.getLabel());
                    MinecraftFriendLink link = existing != null ? existing : new MinecraftFriendLink();
                    link.setUser(user);
                    link.setServiceAccount(account);
                    link.setMinecraftProfileId(profile.dashedId());
                    link.setMinecraftName(profile.name());
                    link.setStatus(MinecraftFriendLink.Status.INVITE_REJECTED);
                    link.setRequestedAt(Instant.now());
                    link.setAcceptedAt(null);
                    enableMinecraftGetter(user);
                    return linkRepository.save(link);
                }
                // The limit error code is undocumented: any 4xx (other than INVITE_REJECTED) on the
                // add is treated as the limit being reached, and the body is logged to refine this.
                log.warn("addFriend refused for {} ({}): {}", account.getLabel(),
                        e.getStatusCode(), e.getResponseBodyAsString());
                limitMarker.markFull(account);
                continue;
            }

            // Reuse the existing instance when there is one, to keep the same DB row.
            MinecraftFriendLink link = existing != null ? existing : new MinecraftFriendLink();
            link.setUser(user);
            link.setServiceAccount(account);
            link.setMinecraftProfileId(profile.dashedId());
            link.setMinecraftName(profile.name());
            link.setStatus(MinecraftFriendLink.Status.PENDING);
            link.setRequestedAt(Instant.now());
            link.setAcceptedAt(null);
            enableMinecraftGetter(user);
            return linkRepository.save(link);
        }

        throw new MinecraftEnrollmentException(
                MinecraftEnrollmentException.Reason.NO_CAPACITY,
                "No service account has room for a new friend");
    }

    @Transactional
    public void unenroll(UserAccount user) {
        if (SimulationGate.blocks(simulationGate, user.getId(), SimulatedCalls.MINECRAFT_FRIENDS)) {
            linkRepository.findByUser(user).ifPresent(linkRepository::delete);
        } else {
            linkRepository.findByUser(user).ifPresent(this::removeLink);
        }
        getterConfigRepository.findByUserAndProvider(user, GetterConfig.Provider.MINECRAFT)
                .ifPresent(config -> {
                    config.setEnabled(false);
                    getterConfigRepository.save(config);
                });
    }

    /**
     * A simulated enrolment: no profile lookup, no token, no friend request. The link is ACCEPTED at once with a
     * profile id made from the name, on the first enabled service account, so the simulated presence works through
     * the real chain.
     */
    private MinecraftFriendLink enrollSimulated(UserAccount user, String playerName) {
        MinecraftServiceAccount account = accountRepository.findByEnabledTrueOrderByFillOrderAsc().stream()
                .findFirst()
                .orElseThrow(() -> new MinecraftEnrollmentException(
                        MinecraftEnrollmentException.Reason.NO_ACCOUNT_AVAILABLE,
                        "No enabled service account to link the simulated player to"));
        MinecraftFriendLink link = linkRepository.findByUser(user).orElseGet(MinecraftFriendLink::new);
        link.setUser(user);
        link.setServiceAccount(account);
        link.setMinecraftProfileId(simulatedProfileId(playerName));
        link.setMinecraftName(playerName);
        link.setStatus(MinecraftFriendLink.Status.ACCEPTED);
        link.setRequestedAt(Instant.now());
        link.setAcceptedAt(Instant.now());
        enableMinecraftGetter(user);
        return linkRepository.save(link);
    }

    /** The same made-up profile id for the same name, dashed as Mojang's. */
    static String simulatedProfileId(String playerName) {
        return UUID.nameUUIDFromBytes(("mock-mc:" + playerName).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * The detection chain only consults providers that have a GetterConfig
     * row: enrolment creates/enables the MINECRAFT one (accounts created before
     * the provider was added have none).
     */
    private void enableMinecraftGetter(UserAccount user) {
        GetterConfig config = getterConfigRepository.findByUserAndProvider(user, GetterConfig.Provider.MINECRAFT)
                .orElseGet(() -> {
                    GetterConfig created = new GetterConfig();
                    created.setUser(user);
                    created.setProvider(GetterConfig.Provider.MINECRAFT);
                    created.setPriority(getterConfigRepository.findByUserOrderByPriorityAsc(user).size() + 1);
                    return created;
                });
        config.setEnabled(true);
        getterConfigRepository.save(config);
    }

    public Optional<MinecraftFriendLink> getLink(UserAccount user) {
        // includes a fetch of the service account: the controller reads its name outside the session (OSIV disabled)
        return linkRepository.findWithServiceAccountByUser(user);
    }

    /**
     * Reconciles the local links with each account's real friends list:
     * PENDING accepted in game → ACCEPTED; ACCEPTED gone → REMOVED; player name refreshed.
     * Every modified link is announced to its channel's dashboards.
     */
    // No @Transactional: avoids holding a DB connection during the getFriends HTTP calls;
    // each save() runs in its own transaction and the reconciliation is idempotent.
    @Scheduled(fixedRateString = "${minecraft.friends-sync-interval-ms:300000}")
    public void syncFriendLinks() {
        for (MinecraftServiceAccount account : accountRepository.findByEnabledTrueOrderByFillOrderAsc()) {
            List<MinecraftFriendLink> links = linkRepository.findByServiceAccount(account);
            if (links.isEmpty()) continue;
            if (SimulationGate.blocksAll(simulationGate, () -> links.stream()
                    .filter(link -> link.getUser() != null)
                    .map(link -> link.getUser().getId())
                    .toList(), SimulatedCalls.MINECRAFT_PRESENCE)) {
                continue; // simulated: no member of this account has its Minecraft presence REAL
            }

            Optional<String> token = tokenService.getToken(account);
            if (token.isEmpty()) continue; // failing account: leave its links alone

            MinecraftService.FriendsList friendsList;
            try {
                String accessToken = token.get();
                friendsList = debug.batch(FRIENDS_OPERATION, membersOf(links), BatchDebugFilter::minecraftFriends,
                        () -> minecraftService.getFriends(accessToken));
            } catch (Exception e) {
                log.warn("getFriends failed for {}: {}", account.getLabel(), e.getMessage());
                continue;
            }

            // match regardless of the UUID format (with/without dashes depending on the endpoint)
            Map<String, MinecraftService.FriendsList.Friend> byProfileId =
                    Arrays.stream(friendsList.friends())
                            .collect(Collectors.toMap(
                                    f -> MinecraftService.normalizeProfileId(f.profileId()),
                                    Function.identity(),
                                    (a, b) -> a));

            Map<String, MinecraftService.FriendsList.Friend> byIncomingProfileId =
                    Arrays.stream(friendsList.incomingRequests())
                            .collect(Collectors.toMap(
                                    f -> MinecraftService.normalizeProfileId(f.profileId()),
                                    Function.identity(),
                                    (a, b) -> a));

            for (MinecraftFriendLink link : links) {
                MinecraftService.FriendsList.Friend friend =
                        byProfileId.get(MinecraftService.normalizeProfileId(link.getMinecraftProfileId()));
                boolean changed = false;

                if (friend != null && link.getStatus() == MinecraftFriendLink.Status.PENDING) {
                    link.setStatus(MinecraftFriendLink.Status.ACCEPTED);
                    link.setAcceptedAt(Instant.now());
                    log.info("Minecraft link accepted: {} via {}", link.getMinecraftName(), account.getLabel());
                    changed = true;
                } else if (friend == null && link.getStatus() == MinecraftFriendLink.Status.ACCEPTED) {
                    link.setStatus(MinecraftFriendLink.Status.REMOVED);
                    log.info("Minecraft link removed by the player: {} via {}", link.getMinecraftName(), account.getLabel());
                    changed = true;
                } else if (link.getStatus() == MinecraftFriendLink.Status.INVITE_REJECTED) {
                    if (friend != null) {
                        link.setStatus(MinecraftFriendLink.Status.ACCEPTED);
                        link.setAcceptedAt(Instant.now());
                        log.info("Minecraft link accepted (after an initial rejection): {} via {}",
                                link.getMinecraftName(), account.getLabel());
                        changed = true;
                    } else {
                        MinecraftService.FriendsList.Friend incoming =
                                byIncomingProfileId.get(MinecraftService.normalizeProfileId(link.getMinecraftProfileId()));
                        if (incoming != null) {
                            try {
                                friendsCall(link.getUser(), link.getMinecraftProfileId(),
                                        () -> minecraftService.addFriend(token.get(), null, link.getMinecraftProfileId()));
                                link.setStatus(MinecraftFriendLink.Status.ACCEPTED);
                                link.setAcceptedAt(Instant.now());
                                log.info("Minecraft link accepted (retry after a manual invite): {} via {}",
                                        link.getMinecraftName(), account.getLabel());
                                changed = true;
                            } catch (Exception retryFailure) {
                                log.info("addFriend retry still refused for {} via {}: {}",
                                        link.getMinecraftName(), account.getLabel(), retryFailure.getMessage());
                            }
                        }
                    }
                }

                if (friend != null && !friend.name().equals(link.getMinecraftName())) {
                    link.setMinecraftName(friend.name());
                    changed = true;
                }

                if (changed) {
                    linkRepository.save(link);
                    channelEventPublisher.minecraftLinkChanged(link.getUser().getId(),
                            link.getStatus().name(), link.getMinecraftName());
                }
            }
        }
    }

    /**
     * Adding or removing one member answers with the service account's whole friend list: it is recorded for that
     * member cut down to its own entries, like the sync's list.
     */
    private MinecraftService.FriendsList friendsCall(UserAccount member, String profileId,
                                                     Supplier<MinecraftService.FriendsList> call) {
        UUID userId = member == null ? null : member.getId();
        return debug.batch(MANAGE_FRIENDS_OPERATION, List.of(new DebugRecording.Member(userId, profileId)),
                BatchDebugFilter::minecraftFriends, call);
    }

    private static List<DebugRecording.Member> membersOf(List<MinecraftFriendLink> links) {
        return links.stream()
                .filter(link -> link.getUser() != null && link.getMinecraftProfileId() != null)
                .map(link -> new DebugRecording.Member(link.getUser().getId(), link.getMinecraftProfileId()))
                .toList();
    }

    private void removeLink(MinecraftFriendLink link) {
        Optional<String> token = tokenService.getToken(link.getServiceAccount());
        if (token.isPresent()) {
            try {
                friendsCall(link.getUser(), link.getMinecraftProfileId(),
                        () -> minecraftService.removeFriend(token.get(), null, link.getMinecraftProfileId()));
            } catch (Exception e) {
                // best effort: the local link is deleted even if the API refuses
                log.warn("removeFriend failed for {}: {}", link.getMinecraftProfileId(), e.getMessage());
            }
        } else {
            log.warn("No token for {} — link {} deleted without removeFriend on the API side",
                    link.getServiceAccount().getLabel(), link.getMinecraftProfileId());
        }
        linkRepository.delete(link);
    }

    /**
     * Tells a privacy rejection (the player blocks external requests) apart
     * from a real friend limit reached on the service account.
     * Defensive parsing: any failure falls back to the "account full" behaviour.
     */
    private boolean isInviteRejected(HttpClientErrorException e) {
        if (e.getStatusCode().value() != 403) return false;
        try {
            JsonNode body = objectMapper.readTree(e.getResponseBodyAsByteArray());
            return "INVITE_REJECTED".equals(body.path("details").path("status").asText(null));
        } catch (Exception parseFailure) {
            return false;
        }
    }

    @Getter
    public static class MinecraftEnrollmentException extends RuntimeException {
        public enum Reason { UNKNOWN_PLAYER, NO_CAPACITY, NO_ACCOUNT_AVAILABLE }

        private final Reason reason;

        public MinecraftEnrollmentException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }
    }
}
