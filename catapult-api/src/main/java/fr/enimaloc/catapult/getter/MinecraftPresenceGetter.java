package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.minecraft.MinecraftFriendLink;
import fr.enimaloc.catapult.domain.minecraft.MinecraftServiceAccount;
import fr.enimaloc.catapult.repository.minecraft.MinecraftFriendLinkRepository;
import fr.enimaloc.catapult.repository.minecraft.MinecraftServiceAccountRepository;
import fr.enimaloc.catapult.service.debug.BatchDebugFilter;
import fr.enimaloc.catapult.service.debug.DebugRecording;
import fr.enimaloc.catapult.service.minecraft.MinecraftGateService;
import fr.enimaloc.catapult.service.minecraft.MinecraftService;
import fr.enimaloc.catapult.service.minecraft.MinecraftTokenService;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulationGate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Detects whether a user is playing Minecraft through the presence of their
 * service-account friends. One POST /presence per account and per cycle covers all
 * the account's friends (cost O(accounts), not O(users)).
 * Any status except OFFLINE counts as "in game", provided the
 * presence is fresh: a game closed abruptly does not publish OFFLINE and
 * the API keeps returning the last known status (see lastUpdated).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("minecraft.enabled")
public class MinecraftPresenceGetter implements GameGetter {

    private final MinecraftService minecraftService;
    private final MinecraftTokenService tokenService;
    private final MinecraftServiceAccountRepository accountRepository;
    private final MinecraftFriendLinkRepository linkRepository;
    private final MinecraftGateService gateService;

    /** Past this age, a presence is considered stale (game closed without OFFLINE). */
    @Value("${minecraft.presence-max-age-seconds:120}")
    long presenceMaxAgeSeconds = 120;

    static final String PRESENCE_OPERATION = "minecraft.presence";

    private volatile Map<String, MinecraftService.PresenceStatus> cycleCache = Map.of();

    private final DebugRecording debug;
    private final ObjectProvider<SimulationGate> simulationGate;

    @Override
    public String name() {
        return "Minecraft";
    }

    public CompletableFuture<Void> prefetchBatch() {
        return CompletableFuture.runAsync(() -> {
            Map<String, MinecraftService.PresenceStatus> cache = new HashMap<>();
            for (MinecraftServiceAccount account : accountRepository.findByEnabledTrueOrderByFillOrderAsc()) {
                if (!linkRepository.existsByServiceAccountAndStatus(account, MinecraftFriendLink.Status.ACCEPTED)) {
                    continue;
                }
                if (simulated(account)) {
                    continue;
                }
                tokenService.getToken(account).ifPresent(token -> {
                    try {
                        MinecraftService.PresenceList presences = debug.batch(PRESENCE_OPERATION,
                                membersOf(account), BatchDebugFilter::minecraftPresence,
                                () -> minecraftService.updatePresence(token, MinecraftService.PresenceStatus.ONLINE));
                        Instant staleBefore = Instant.now().minusSeconds(presenceMaxAgeSeconds);
                        for (MinecraftService.PresenceList.Presence presence : presences.presence()) {
                            if (presence.lastUpdated() != null && presence.lastUpdated().isBefore(staleBefore)) {
                                continue; // stale presence: the game no longer refreshes it
                            }
                            // normalized key: the UUID format varies across the Mojang endpoints
                            cache.put(MinecraftService.normalizeProfileId(presence.profileId()), presence.status());
                        }
                    } catch (Exception e) {
                        log.warn("Minecraft presence failed for {}: {}", account.getLabel(), e.getMessage());
                    }
                });
            }
            cycleCache = Map.copyOf(cache);
        });
    }

    /**
     * The members befriended by {@code account}, known to its presence list by their profile id. Looked up only
     * when someone is in debug mode: otherwise the poll does exactly what it did without it.
     */
    private List<DebugRecording.Member> membersOf(MinecraftServiceAccount account) {
        if (!debug.anyMemberInDebug()) {
            return List.of();
        }
        try {
            return linkRepository.findByServiceAccount(account).stream()
                    .filter(link -> link.getUser() != null && link.getMinecraftProfileId() != null)
                    .map(link -> new DebugRecording.Member(link.getUser().getId(), link.getMinecraftProfileId()))
                    .toList();
        } catch (RuntimeException e) {
            // the presence call goes on, unrecorded
            log.warn("Could not list the members of {} for the debug recording: {}", account.getLabel(), e.toString());
            return List.of();
        }
    }

    /**
     * Whether the simulation keeps the account's presence call (and its token refresh) from going out: none of its
     * members has {@code minecraft.presence} REAL.
     */
    private boolean simulated(MinecraftServiceAccount account) {
        return SimulationGate.blocksAll(simulationGate, () -> linkRepository.findByServiceAccount(account).stream()
                .filter(link -> link.getUser() != null)
                .map(link -> link.getUser().getId())
                .toList(), SimulatedCalls.MINECRAFT_PRESENCE);
    }

    public void clearCycleCache() {
        cycleCache = Map.of();
    }

    @Override
    public Optional<DetectedGame> getCurrentGame(UserAccount user) {
        if (!gateService.isAvailableReadOnly(user)) {
            return Optional.empty();
        }
        return linkRepository.findByUser(user)
                .filter(link -> link.getStatus() == MinecraftFriendLink.Status.ACCEPTED)
                .map(link -> cycleCache.get(MinecraftService.normalizeProfileId(link.getMinecraftProfileId())))
                .filter(status -> status != MinecraftService.PresenceStatus.OFFLINE)
                .map(status -> new DetectedGame("minecraft", GameBinding.SourceType.MINECRAFT, "Minecraft"));
    }
}
