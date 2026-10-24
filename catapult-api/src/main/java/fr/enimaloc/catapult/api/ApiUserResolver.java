package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Turns a request's JWT and {@code {username}} path variable into accounts, failing the
 * request the way every API controller does: 401 for an unknown caller, 404 for an unknown
 * channel. Who may reach a channel is {@link fr.enimaloc.catapult.security.Authz}'s call.
 */
@Component
@RequiredArgsConstructor
public class ApiUserResolver {
    private final UserAccountRepository userAccountRepository;

    /** The caller, by the JWT's subject (their account id). */
    public UserAccount viewer(Jwt jwt) {
        return userAccountRepository.findById(UUID.fromString(requireJwt(jwt).getSubject()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    /** The caller, by the JWT's {@code twitchId} claim. */
    public UserAccount viewerByTwitchId(Jwt jwt) {
        return userAccountRepository.findByTwitchId(requireJwt(jwt).getClaimAsString("twitchId"))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    /**
     * {@code jwt}, or 401 when the request carries none — the chain lets token-less requests
     * through in dev and from localhost, so a missing token reaches the controllers.
     */
    public static Jwt requireJwt(Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "A token is required");
        }
        return jwt;
    }

    /** The channel named {@code username}. */
    public UserAccount channel(String username) {
        return userAccountRepository.findByTwitchUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
