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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Instant;
import java.util.Optional;

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

        linkRepository.findByUser(user).ifPresent(this::removeLink);

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

            MinecraftFriendLink link = new MinecraftFriendLink();
            link.setUser(user);
            link.setServiceAccount(account);
            link.setMinecraftProfileId(profile.dashedId());
            link.setMinecraftName(profile.name());
            link.setStatus(MinecraftFriendLink.Status.PENDING);
            link.setRequestedAt(Instant.now());
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
        return linkRepository.findByUser(user);
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
