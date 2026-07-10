package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.MinecraftFriendLink;
import fr.enimaloc.catapult.domain.MinecraftServiceAccount;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.MinecraftFriendLinkRepository;
import fr.enimaloc.catapult.repository.MinecraftServiceAccountRepository;
import fr.enimaloc.catapult.service.MinecraftService;
import fr.enimaloc.catapult.service.MinecraftTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Détecte si un utilisateur joue à Minecraft via la présence de ses amis
 * comptes de service. Un POST /presence par compte et par cycle couvre tous
 * les amis du compte (coût O(comptes), pas O(utilisateurs)).
 * Tout statut sauf OFFLINE compte comme « en jeu ».
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

    private volatile Map<String, MinecraftService.PresenceStatus> cycleCache = Map.of();

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
                        MinecraftService.PresenceList presences =
                                minecraftService.updatePresence(token, MinecraftService.PresenceStatus.ONLINE);
                        for (MinecraftService.PresenceList.Presence presence : presences.presence()) {
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

    public void clearCycleCache() {
        cycleCache = Map.of();
    }

    @Override
    public Optional<DetectedGame> getCurrentGame(UserAccount user) {
        return linkRepository.findByUser(user)
                .filter(link -> link.getStatus() == MinecraftFriendLink.Status.ACCEPTED)
                .map(link -> cycleCache.get(MinecraftService.normalizeProfileId(link.getMinecraftProfileId())))
                .filter(status -> status != MinecraftService.PresenceStatus.OFFLINE)
                .map(status -> new DetectedGame("minecraft", GameBinding.SourceType.MINECRAFT, "Minecraft"));
    }
}
