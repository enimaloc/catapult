package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.MinecraftFriendLink;
import fr.enimaloc.catapult.domain.MinecraftServiceAccount;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.MinecraftFriendLinkRepository;
import fr.enimaloc.catapult.repository.MinecraftServiceAccountRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
                    minecraftService.removeFriend(existingToken.get(), null, existing.getMinecraftProfileId());
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
                minecraftService.addFriend(token.get(), null, profile.dashedId());
            } catch (HttpClientErrorException e) {
                // Code d'erreur de limite non documenté : tout 4xx sur l'ajout est
                // traité comme limite atteinte, body loggé pour affiner.
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
            return linkRepository.save(link);
        }

        throw new MinecraftEnrollmentException(
                MinecraftEnrollmentException.Reason.NO_CAPACITY,
                "Aucun compte de service disponible pour un nouvel ami");
    }

    @Transactional
    public void unenroll(UserAccount user) {
        linkRepository.findByUser(user).ifPresent(this::removeLink);
    }

    public Optional<MinecraftFriendLink> getLink(UserAccount user) {
        // fetch du compte de service inclus : le contrôleur lit son pseudo hors session (OSIV désactivé)
        return linkRepository.findWithServiceAccountByUser(user);
    }

    /**
     * Réconcilie les liens locaux avec la friends list réelle de chaque compte :
     * PENDING accepté en jeu → ACCEPTED ; ACCEPTED disparu → REMOVED ; pseudo rafraîchi.
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
                friendsList = minecraftService.getFriends(token.get());
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
                }

                if (friend != null && !friend.name().equals(link.getMinecraftName())) {
                    link.setMinecraftName(friend.name());
                    changed = true;
                }

                if (changed) linkRepository.save(link);
            }
        }
    }

    private void removeLink(MinecraftFriendLink link) {
        Optional<String> token = tokenService.getToken(link.getServiceAccount());
        if (token.isPresent()) {
            try {
                minecraftService.removeFriend(token.get(), null, link.getMinecraftProfileId());
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
