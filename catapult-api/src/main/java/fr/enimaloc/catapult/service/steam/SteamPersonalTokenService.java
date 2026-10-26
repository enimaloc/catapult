package fr.enimaloc.catapult.service.steam;

import fr.enimaloc.catapult.common.dto.channel.SteamTokenRequest;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.getter.steam.SteamApiKeyRotator;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.steam.SteamApiKeyRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.connections.SteamProfileDiagnostics;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * A member's own Steam API key: saved on the account, kept in step with the shared key pool, then published to the
 * dashboards. {@link #save} and {@link #updateSharing} write through {@link SteamPersonalTokenStore}'s transaction,
 * then refresh the pool and publish once it has committed; {@link #delete} is one transaction.
 */
@Service
@RequiredArgsConstructor
public class SteamPersonalTokenService {

    private final SteamPersonalTokenStore store;
    private final UserAccountRepository userAccountRepository;
    private final TokenEncryptionService tokenEncryptionService;
    private final SteamApiKeyRepository steamApiKeyRepository;
    private final ChannelEventPublisher channelEventPublisher;
    private final SteamProfileDiagnostics steamDiagnostics;

    private final Optional<SteamApiKeyRotator> rotator;

    public void save(UserAccount channelUser, SteamTokenRequest body) {
        if (body.token() == null || body.token().isBlank()) return;
        String trimmed = body.token().trim();
        channelUser.setSteamPersonalToken(tokenEncryptionService.encrypt(trimmed));
        channelUser.setSteamTokenShared(body.shared());
        store.save(channelUser, trimmed, body.shared());
        rotator.ifPresent(SteamApiKeyRotator::refreshKeys);
        channelEventPublisher.steamProfileChanged(channelUser.getId(), steamDiagnostics.diagnose(channelUser));
    }

    public void updateSharing(UserAccount channelUser, boolean shared) {
        if (channelUser.getSteamPersonalToken() == null) return;
        channelUser.setSteamTokenShared(shared);
        String decryptedToken = tokenEncryptionService.decrypt(channelUser.getSteamPersonalToken());
        store.save(channelUser, decryptedToken, shared);
        rotator.ifPresent(SteamApiKeyRotator::refreshKeys);
        channelEventPublisher.steamProfileChanged(channelUser.getId(), steamDiagnostics.diagnose(channelUser));
    }

    @Transactional
    public void delete(UserAccount channelUser) {
        channelUser.setSteamPersonalToken(null);
        channelUser.setSteamTokenShared(false);
        userAccountRepository.save(channelUser);
        steamApiKeyRepository.deleteByOwner(channelUser);
        rotator.ifPresent(SteamApiKeyRotator::refreshKeys);
        channelEventPublisher.steamProfileChanged(channelUser.getId(), steamDiagnostics.diagnose(channelUser));
    }
}
