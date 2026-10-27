package fr.enimaloc.catapult.service.steam;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.steam.SteamApiKeyEntry;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.steam.SteamApiKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The writes behind a member's Steam API key: the account and the shared key pool, in one transaction, so they
 * commit together or not at all. {@link SteamPersonalTokenService} tells the dashboards once it has committed.
 */
@Service
@RequiredArgsConstructor
public class SteamPersonalTokenStore {

    private final UserAccountRepository userAccountRepository;
    private final SteamApiKeyRepository steamApiKeyRepository;

    /** Saves the account as {@code channelUser} holds it, then pools {@code plainToken} for it. */
    @Transactional
    public void save(UserAccount channelUser, String plainToken, boolean shared) {
        userAccountRepository.save(channelUser);
        steamApiKeyRepository.deleteByOwner(channelUser);
        if (!steamApiKeyRepository.existsById(plainToken)) {
            SteamApiKeyEntry entry = new SteamApiKeyEntry(plainToken);
            entry.setOwner(channelUser);
            entry.setExclusive(!shared);
            steamApiKeyRepository.save(entry);
        }
    }

    /** Clears the token on the account and drops the key it pooled. */
    @Transactional
    public void delete(UserAccount channelUser) {
        channelUser.setSteamPersonalToken(null);
        channelUser.setSteamTokenShared(false);
        userAccountRepository.save(channelUser);
        steamApiKeyRepository.deleteByOwner(channelUser);
    }
}
