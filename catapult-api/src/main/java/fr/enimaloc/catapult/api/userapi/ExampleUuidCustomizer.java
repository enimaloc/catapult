package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.GameBindingRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.GameStateService;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.parameters.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Fills in a real, "try it out"-able value for every {@code uuid} path parameter's Swagger example
 * when the request generating the spec carries the caller's own dashboard JWT (attached client-side
 * by {@link SwaggerUiJwtTransformer}'s requestInterceptor) — falls back to the generic backdoor
 * example (see {@link DevBackdoorResolver}) otherwise, or if the authenticated user has no
 * Steam/Xbox binding to encode.
 */
@Component
@RequiredArgsConstructor
public class ExampleUuidCustomizer implements OperationCustomizer {

    private static final String GENERIC_BACKDOOR_UUID = "00000000-0000-0000-0001-000000000190";
    private static final Set<GameBinding.SourceType> ENCODABLE =
            Set.of(GameBinding.SourceType.STEAM, GameBinding.SourceType.XBOX);

    private final UserAccountRepository userAccountRepository;
    private final GameStateService gameStateService;
    private final GameBindingRepository gameBindingRepository;
    private final DevBackdoorResolver devBackdoorResolver;
    private final JwtDecoder jwtDecoder;

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        List<Parameter> parameters = operation.getParameters();
        if (parameters == null) {
            return operation;
        }
        parameters.stream()
                .filter(p -> "uuid".equals(p.getName()) && p.getSchema() != null)
                .forEach(p -> p.getSchema().setExample(resolveExampleUuid()));
        return operation;
    }

    private String resolveExampleUuid() {
        return currentUser()
                .flatMap(this::resolveUuidForUser)
                .orElse(GENERIC_BACKDOOR_UUID);
    }

    /**
     * ApiSecurityConfig's bearerTokenResolver deliberately ignores the Authorization header on
     * /api/v3/api-docs (a stale/malformed JWT there would otherwise 401 the whole docs page), so
     * {@link org.springframework.security.core.context.SecurityContextHolder} is always anonymous
     * for this request — the token is decoded here instead, tolerating any failure by falling back
     * to the generic example rather than surfacing an error.
     */
    private Optional<UserAccount> currentUser() {
        return currentRequest()
                .map(req -> req.getHeader("Authorization"))
                .filter(header -> header != null && header.regionMatches(true, 0, "Bearer ", 0, 7))
                .map(header -> header.substring(7).trim())
                .flatMap(this::decodeAndFindUser);
    }

    private Optional<HttpServletRequest> currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return Optional.of(servletAttributes.getRequest());
        }
        return Optional.empty();
    }

    private Optional<UserAccount> decodeAndFindUser(String token) {
        try {
            Jwt jwt = jwtDecoder.decode(token);
            return userAccountRepository.findById(UUID.fromString(jwt.getSubject()));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private Optional<String> resolveUuidForUser(UserAccount user) {
        return gameStateService.getLastKnownGame(user)
                .filter(game -> ENCODABLE.contains(game.getSourceType()))
                .or(() -> randomEncodableBinding(user).map(this::toDetectedGame))
                .flatMap(game -> devBackdoorResolver.encode(game.getSourceType(), game.getSourceId()))
                .map(UUID::toString);
    }

    private DetectedGame toDetectedGame(GameBinding binding) {
        return new DetectedGame(binding.getSourceId(), binding.getSourceType(), binding.getSourceName());
    }

    private Optional<GameBinding> randomEncodableBinding(UserAccount user) {
        List<GameBinding> candidates = gameBindingRepository.findByUser(user).stream()
                .filter(b -> ENCODABLE.contains(b.getSourceType()))
                .toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())));
    }
}
