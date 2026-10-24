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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Détecte si un utilisateur joue à Minecraft via la présence de ses amis
 * comptes de service. Un POST /presence par compte et par cycle couvre tous
 * les amis du compte (coût O(comptes), pas O(utilisateurs)).
 * Tout statut sauf OFFLINE compte comme « en jeu » — à condition que la
 * présence soit fraîche : un jeu fermé brutalement ne publie pas OFFLINE et
 * l'API continue de renvoyer le dernier statut connu (voir lastUpdated).
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

    /** Au-delà de cet âge, une présence est considérée périmée (jeu fermé sans OFFLINE). */
    @Value("${minecraft.presence-max-age-seconds:120}")
    long presenceMaxAgeSeconds = 120;

    static final String PRESENCE_OPERATION = "minecraft.presence";

    private volatile Map<String, MinecraftService.PresenceStatus> cycleCache = Map.of();

    private DebugRecording debug = DebugRecording.OFF;

    /** Records this class's grouped calls and incoming messages for the members in debug mode. */
    @Autowired(required = false)
    public void setDebugRecording(DebugRecording debug) {
        this.debug = debug == null ? DebugRecording.OFF : debug;
    }

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
                tokenService.getToken(account).ifPresent(token -> {
                    try {
                        MinecraftService.PresenceList presences = debug.batch(PRESENCE_OPERATION,
                                membersOf(account), BatchDebugFilter::minecraftPresence,
                                () -> minecraftService.updatePresence(token, MinecraftService.PresenceStatus.ONLINE));
                        Instant staleBefore = Instant.now().minusSeconds(presenceMaxAgeSeconds);
                        for (MinecraftService.PresenceList.Presence presence : presences.presence()) {
                            if (presence.lastUpdated() != null && presence.lastUpdated().isBefore(staleBefore)) {
                                continue; // présence périmée : le jeu ne la rafraîchit plus
                            }
                            // clé normalisée : le format d'UUID varie selon les endpoints Mojang
                            cache.put(MinecraftService.normalizeProfileId(presence.profileId()), presence.status());
                        }
                    } catch (Exception e) {
                        log.warn("Presence Minecraft en échec pour {}: {}", account.getLabel(), e.getMessage());
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
