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
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;

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
 * Cycle de vie des liens d'amitié entre utilisateurs et comptes de service.
 * Remplissage séquentiel : la limite d'amis (inconnue) est découverte à la
 * première erreur d'ajout, le compte est alors marqué plein (réversible en admin).
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

    private DebugRecording debug = DebugRecording.OFF;

    /** Records this class's grouped calls and incoming messages for the members in debug mode. */
    @Autowired(required = false)
    public void setDebugRecording(DebugRecording debug) {
        this.debug = debug == null ? DebugRecording.OFF : debug;
    }

    @Transactional
    public MinecraftFriendLink enroll(UserAccount user, String pseudo) {
        MinecraftService.ProfileLookup profile = minecraftService.lookupProfile(pseudo)
                .orElseThrow(() -> new MinecraftEnrollmentException(
                        MinecraftEnrollmentException.Reason.UNKNOWN_PLAYER, "Pseudo introuvable: " + pseudo));

        // Récupère le lien existant sans le supprimer — réutilisation in-place pour éviter
        // la violation de contrainte unique user_id (Hibernate ordonne INSERT avant DELETE à la flush).
        MinecraftFriendLink existing = linkRepository.findByUser(user).orElse(null);
        if (existing != null) {
            // Suppression best-effort côté API avec le token du compte de service actuel.
            Optional<String> existingToken = tokenService.getToken(existing.getServiceAccount());
            if (existingToken.isPresent()) {
                try {
                    friendsCall(existing.getUser(), existing.getMinecraftProfileId(),
                            () -> minecraftService.removeFriend(existingToken.get(), null,
                                    existing.getMinecraftProfileId()));
                } catch (Exception e) {
                    log.warn("removeFriend en échec pour {}: {}", existing.getMinecraftProfileId(), e.getMessage());
                }
            } else {
                log.warn("Pas de token pour {} — ré-enrôlement sans removeFriend côté API",
                        existing.getServiceAccount().getLabel());
            }
        }

        for (MinecraftServiceAccount account : accountRepository.findByEnabledTrueOrderByFillOrderAsc()) {
            if (account.isFriendLimitReached()) continue;
            Optional<String> token = tokenService.getToken(account);
            if (token.isEmpty()) continue; // compte en échec d'auth : on tente le suivant

            try {
                friendsCall(user, profile.dashedId(),
                        () -> minecraftService.addFriend(token.get(), null, profile.dashedId()));
            } catch (HttpClientErrorException e) {
                if (isInviteRejected(e)) {
                    log.info("Invite Minecraft rejetée par le joueur (confidentialité) pour {} via {}",
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
                // Code d'erreur de limite non documenté : tout 4xx (hors INVITE_REJECTED) sur
                // l'ajout est traité comme limite atteinte, body loggé pour affiner.
                log.warn("addFriend refusé pour {} ({}): {}", account.getLabel(),
                        e.getStatusCode(), e.getResponseBodyAsString());
                limitMarker.markFull(account);
                continue;
            }

            // Réutilise l'instance existante si disponible pour conserver la même ligne BD.
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
                "Aucun compte de service disponible pour un nouvel ami");
    }

    @Transactional
    public void unenroll(UserAccount user) {
        linkRepository.findByUser(user).ifPresent(this::removeLink);
        getterConfigRepository.findByUserAndProvider(user, GetterConfig.Provider.MINECRAFT)
                .ifPresent(config -> {
                    config.setEnabled(false);
                    getterConfigRepository.save(config);
                });
    }

    /**
     * La chaîne de détection ne consulte que les providers ayant une ligne
     * GetterConfig : l'enrôlement crée/active celle de MINECRAFT (les comptes
     * créés avant l'ajout du provider n'en ont pas).
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
        // fetch du compte de service inclus : le contrôleur lit son pseudo hors session (OSIV désactivé)
        return linkRepository.findWithServiceAccountByUser(user);
    }

    /**
     * Réconcilie les liens locaux avec la friends list réelle de chaque compte :
     * PENDING accepté en jeu → ACCEPTED ; ACCEPTED disparu → REMOVED ; pseudo rafraîchi.
     * Chaque lien modifié est annoncé aux dashboards de sa chaîne.
     */
    // Pas de @Transactional : évite de tenir une connexion BD pendant les appels HTTP getFriends ;
    // chaque save() porte sa propre transaction et la réconciliation est idempotente.
    @Scheduled(fixedRateString = "${minecraft.friends-sync-interval-ms:300000}")
    public void syncFriendLinks() {
        for (MinecraftServiceAccount account : accountRepository.findByEnabledTrueOrderByFillOrderAsc()) {
            List<MinecraftFriendLink> links = linkRepository.findByServiceAccount(account);
            if (links.isEmpty()) continue;

            Optional<String> token = tokenService.getToken(account);
            if (token.isEmpty()) continue; // compte en échec : on ne touche pas aux liens

            MinecraftService.FriendsList friendsList;
            try {
                String accessToken = token.get();
                friendsList = debug.batch(FRIENDS_OPERATION, membersOf(links), BatchDebugFilter::minecraftFriends,
                        () -> minecraftService.getFriends(accessToken));
            } catch (Exception e) {
                log.warn("getFriends en échec pour {}: {}", account.getLabel(), e.getMessage());
                continue;
            }

            // matching insensible au format d'UUID (avec/sans tirets selon les endpoints)
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
                    log.info("Lien Minecraft accepté: {} via {}", link.getMinecraftName(), account.getLabel());
                    changed = true;
                } else if (friend == null && link.getStatus() == MinecraftFriendLink.Status.ACCEPTED) {
                    link.setStatus(MinecraftFriendLink.Status.REMOVED);
                    log.info("Lien Minecraft retiré côté joueur: {} via {}", link.getMinecraftName(), account.getLabel());
                    changed = true;
                } else if (link.getStatus() == MinecraftFriendLink.Status.INVITE_REJECTED) {
                    if (friend != null) {
                        link.setStatus(MinecraftFriendLink.Status.ACCEPTED);
                        link.setAcceptedAt(Instant.now());
                        log.info("Lien Minecraft accepté (après rejet initial): {} via {}",
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
                                log.info("Lien Minecraft accepté (retry après invite manuelle): {} via {}",
                                        link.getMinecraftName(), account.getLabel());
                                changed = true;
                            } catch (Exception retryFailure) {
                                log.info("Retry addFriend toujours refusé pour {} via {}: {}",
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
                // best effort : le lien local est supprimé même si l'API refuse
                log.warn("removeFriend en échec pour {}: {}", link.getMinecraftProfileId(), e.getMessage());
            }
        } else {
            log.warn("Pas de token pour {} — lien {} supprimé sans removeFriend côté API",
                    link.getServiceAccount().getLabel(), link.getMinecraftProfileId());
        }
        linkRepository.delete(link);
    }

    /**
     * Distingue un rejet par confidentialité (le joueur bloque les demandes
     * externes) d'une vraie limite d'amis atteinte côté compte de service.
     * Parsing défensif : tout échec retombe sur le comportement "compte plein".
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
