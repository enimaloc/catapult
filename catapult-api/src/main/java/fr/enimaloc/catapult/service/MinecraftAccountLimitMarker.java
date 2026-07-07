package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.MinecraftServiceAccount;
import fr.enimaloc.catapult.repository.MinecraftServiceAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Persiste le flag « limite d'amis atteinte » dans une transaction indépendante :
 * le rollback d'un enroll qui échoue (NO_CAPACITY) ne doit pas effacer la découverte
 * des comptes pleins, sinon ils seraient retentés à chaque enrôlement.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("minecraft.enabled")
public class MinecraftAccountLimitMarker {

    private final MinecraftServiceAccountRepository accountRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFull(MinecraftServiceAccount account) {
        account.setFriendLimitReached(true);
        account.setUpdatedAt(Instant.now());
        accountRepository.save(account);
    }
}
