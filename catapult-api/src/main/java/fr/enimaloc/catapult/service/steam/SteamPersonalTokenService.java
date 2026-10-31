package fr.enimaloc.catapult.service.steam;

import fr.enimaloc.catapult.common.dto.channel.SteamTokenRequest;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.getter.steam.SteamApiKeyRotator;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.connections.SteamProfileDiagnostics;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * A member's own Steam API key: saved on the account, kept in step with the shared key pool, then published to the
 * dashboards. Each of {@link #save}, {@link #updateSharing} and {@link #delete} writes through
 * {@link SteamPersonalTokenStore}'s transaction, then refreshes the pool and publishes once it has committed.
 */
@Service
@RequiredArgsConstructor
public class SteamPersonalTokenService {

    private final SteamPersonalTokenStore store;
    private final TokenEncryptionService tokenEncryptionService;
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

    public void delete(UserAccount channelUser) {
        store.delete(channelUser);
        rotator.ifPresent(SteamApiKeyRotator::refreshKeys);
        channelEventPublisher.steamProfileChanged(channelUser.getId(), steamDiagnostics.diagnose(channelUser));
    }
}
