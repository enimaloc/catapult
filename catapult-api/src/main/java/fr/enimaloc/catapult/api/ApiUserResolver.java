package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.ChannelAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Turns a request's JWT and {@code {username}} path variable into accounts, failing the
 * request the way every API controller does: 401 for an unknown caller, 404 for an unknown
 * channel, 403 for a channel the caller may not see or doesn't own.
 */
@Component
@RequiredArgsConstructor
public class ApiUserResolver {
    private final UserAccountRepository userAccountRepository;
    private final ChannelAccessService channelAccessService;

    /** The caller, by the JWT's subject (their account id). */
    public UserAccount viewer(Jwt jwt) {
        return userAccountRepository.findById(UUID.fromString(jwt.getSubject()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    /** The caller, by the JWT's {@code twitchId} claim. */
    public UserAccount viewerByTwitchId(Jwt jwt) {
        return userAccountRepository.findByTwitchId(jwt.getClaimAsString("twitchId"))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    /** The channel named {@code username}. */
    public UserAccount channel(String username) {
        return userAccountRepository.findByTwitchUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** The channel named {@code username}, provided {@code viewer} may access it. */
    public UserAccount accessibleChannel(String username, UserAccount viewer) {
        UserAccount channel = channel(username);
        if (!channelAccessService.canAccess(viewer, channel)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return channel;
    }

    /** The channel named {@code username}, provided the JWT's caller may access it. */
    public UserAccount accessibleChannel(String username, Jwt jwt) {
        return accessibleChannel(username, viewer(jwt));
    }

    /** The channel named {@code username}, provided it is the JWT's caller's own. */
    public UserAccount ownChannel(String username, Jwt jwt) {
        UserAccount viewer = viewer(jwt);
        UserAccount channel = channel(username);
        requireOwner(viewer, channel);
        return channel;
    }

    /** Fails with 403 unless {@code viewer} is {@code channel}'s owner. */
    public static void requireOwner(UserAccount viewer, UserAccount channel) {
        if (!viewer.getId().equals(channel.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }
}
