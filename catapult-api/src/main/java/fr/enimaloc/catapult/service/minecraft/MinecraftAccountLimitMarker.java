package fr.enimaloc.catapult.service.minecraft;

import fr.enimaloc.catapult.domain.minecraft.MinecraftServiceAccount;
import fr.enimaloc.catapult.repository.minecraft.MinecraftServiceAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Persists the "friend limit reached" flag in an independent transaction:
 * the rollback of a failing enroll (NO_CAPACITY) must not erase the discovery
 * of full accounts, or they would be retried on every enrolment.
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
