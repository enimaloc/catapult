package fr.enimaloc.catapult.service.connections;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.getter.SteamApiClient;
import fr.enimaloc.catapult.getter.SteamApiKeyRotator;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * A channel's Steam connection as the dashboard shows it, including whether its profile is
 * currently readable: shared by the dashboard page and the {@code steam.profile.changed} event
 * so both always tell the same story.
 */
@Component
@RequiredArgsConstructor
public class SteamProfileDiagnostics {
    /** Profile-cache TTL reported when no Steam client is configured. */
    static final long DEFAULT_PROFILE_CACHE_TTL_MINUTES = 15L;
    private static final long PROFILE_TIMEOUT_SECONDS = 2;
    private static final SteamApiClient.SteamProfileStatus PROFILE_UNREACHABLE =
            new SteamApiClient.SteamProfileStatus(false, false);

    private final Optional<SteamApiClient> steamApiClient;
    // Absent under the mock profile or with steam.enabled off.
    private final Optional<SteamApiKeyRotator> keyRotator;
    private final TokenEncryptionService tokenEncryptionService;

    /** Whether Steam is available at all (a client is configured). */
    public boolean available() {
        return steamApiClient.isPresent();
    }

    /** {@link #snapshot(UserAccount, boolean)}, probing the profile. */
    public SteamProfileDto diagnose(UserAccount channel) {
        return snapshot(channel, true);
    }

    /**
     * The channel's Steam state. When {@code probeProfile} and the channel is connected, asks
     * Steam whether its profile is readable (giving up after 2 seconds, then reported private);
     * a private profile seen while every API key is rate-limited is reported as rate limiting,
     * since Steam answers both the same way.
     */
    public SteamProfileDto snapshot(UserAccount channel, boolean probeProfile) {
        boolean connected = steamApiClient.isPresent() && channel.getSteamId() != null;
        boolean hasPersonalToken = channel.getSteamPersonalToken() != null;
        long ttlMinutes = steamApiClient.map(client -> client.getProfileCacheTtl().toMinutes())
                .orElse(DEFAULT_PROFILE_CACHE_TTL_MINUTES);

        boolean profilePrivate = false;
        boolean offlineMode = false;
        boolean rateLimited = false;
        if (connected && probeProfile) {
            SteamApiClient client = steamApiClient.get();
            String personalToken = hasPersonalToken
                    ? tokenEncryptionService.decrypt(channel.getSteamPersonalToken()) : null;
            SteamApiClient.SteamProfileStatus profile = profileStatus(client, channel.getSteamId(), personalToken);
            profilePrivate = !profile.profilePublic();
            offlineMode = profile.offlineMode();
            if (profilePrivate && isRateLimited(client)) {
                rateLimited = true;
                profilePrivate = false;
            }
        }
        return new SteamProfileDto(connected, profilePrivate, offlineMode, rateLimited, ttlMinutes,
                hasPersonalToken, channel.isSteamTokenShared());
    }

    private boolean isRateLimited(SteamApiClient client) {
        return client.isRateLimited() || keyRotator.map(SteamApiKeyRotator::isAllKeysBlocked).orElse(false);
    }

    private static SteamApiClient.SteamProfileStatus profileStatus(SteamApiClient client, String steamId,
                                                                   String personalToken) {
        try {
            return client.getProfileStatus(steamId, personalToken)
                    .orTimeout(PROFILE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .exceptionally(e -> PROFILE_UNREACHABLE)
                    .join();
        } catch (Exception e) {
            return PROFILE_UNREACHABLE;
        }
    }
}
