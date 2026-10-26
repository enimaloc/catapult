package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns a request's JWT and {@code {userId}} path variable into accounts,
 * failing the request the way every API controller does: 401 for an unknown caller, 404 for an
 * unknown channel or account. Who may reach a channel is {@link fr.enimaloc.catapult.security.Authz}'s call.
 * <p>
 * Each account found is remembered for the rest of the current request (an attribute of the request, by id and by
 * Twitch id), so {@code @authz} checks and the handler read it once. Nothing is remembered outside a request
 * (schedulers, {@code @Async}, EventSub) or on an async or error re-dispatch, and a miss is never remembered: the
 * next lookup reads the repository again and fails the same way.
 */
@Component
@RequiredArgsConstructor
public class ApiUserResolver {
    private static final String MEMO_ATTRIBUTE = ApiUserResolver.class.getName() + ".accounts";
    private static final String ID_KEY = "id:";
    private static final String TWITCH_KEY = "twitch:";

    private final UserAccountRepository userAccountRepository;

    /** The caller, by the JWT's subject (their account id). */
    public UserAccount viewer(Jwt jwt) {
        return byId(UUID.fromString(requireJwt(jwt).getSubject()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    /** The caller, by the JWT's {@code twitchId} claim. */
    public UserAccount viewerByTwitchId(Jwt jwt) {
        String twitchId = requireJwt(jwt).getClaimAsString("twitchId");
        UserAccount remembered = twitchId == null ? null : remembered(TWITCH_KEY + twitchId);
        if (remembered != null && twitchId.equals(remembered.getTwitchId())) {
            return remembered;
        }
        return remember(userAccountRepository.findByTwitchId(twitchId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    /** The caller by the JWT's {@code twitchId} claim, or empty when there is no token or no such account. */
    public Optional<UserAccount> viewerByTwitchIdIfAny(Jwt jwt) {
        if (jwt == null) {
            return Optional.empty();
        }
        String twitchId = jwt.getClaimAsString("twitchId");
        UserAccount remembered = twitchId == null ? null : remembered(TWITCH_KEY + twitchId);
        if (remembered != null && twitchId.equals(remembered.getTwitchId())) {
            return Optional.of(remembered);
        }
        return remember(userAccountRepository.findByTwitchId(twitchId));
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

    /** The owner of the personal API token, by its principal (their account id), or 401 when unknown. */
    public UserAccount tokenOwner(UUID userId) {
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return byId(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    /** The account {@code userId} (a channel, or a member's own), or 404. */
    public UserAccount user(UUID userId) {
        return byId(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** The account whose Twitch username is exactly {@code twitchUsername} (case included), or 404. */
    public UserAccount byTwitchUsername(String twitchUsername) {
        return remember(userAccountRepository.findByTwitchUsername(twitchUsername))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private Optional<UserAccount> byId(UUID id) {
        UserAccount remembered = remembered(ID_KEY + id);
        if (remembered != null && id.equals(remembered.getId())) {
            return Optional.of(remembered);
        }
        return remember(userAccountRepository.findById(id));
    }

    /** The account the current request already found under {@code key}, or {@code null}. */
    private static UserAccount remembered(String key) {
        Map<String, UserAccount> memo = memo();
        return memo == null ? null : memo.get(key);
    }

    /** {@code found}, remembered for the current request under its id and its Twitch id; a miss is not. */
    private static Optional<UserAccount> remember(Optional<UserAccount> found) {
        Map<String, UserAccount> memo = memo();
        if (memo != null) {
            found.ifPresent(account -> {
                memo.put(ID_KEY + account.getId(), account);
                if (account.getTwitchId() != null) {
                    memo.put(TWITCH_KEY + account.getTwitchId(), account);
                }
            });
        }
        return found;
    }

    /**
     * The current request's accounts, created on first use, or {@code null} outside a request's own dispatch: on
     * another thread, or on an async or error re-dispatch of the request, which reads the repository again.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, UserAccount> memo() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)
                || attributes.getRequest().getDispatcherType() != DispatcherType.REQUEST) {
            return null;
        }
        Object memo = attributes.getAttribute(MEMO_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (memo == null) {
            memo = new ConcurrentHashMap<String, UserAccount>();
            attributes.setAttribute(MEMO_ATTRIBUTE, memo, RequestAttributes.SCOPE_REQUEST);
        }
        return (Map<String, UserAccount>) memo;
    }
}
