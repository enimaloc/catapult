package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST endpoints for JWT-based authentication used by catapult-web.
 * /api/auth/validate is called by catapult-web to confirm a JWT is valid and retrieve user info.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class ApiAuthController {

    private final UserAccountRepository userAccountRepository;

    @GetMapping("/validate")
    public UserInfoResponse validate(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        UserAccount account = userAccountRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("User not found: " + userId));

        @SuppressWarnings("unchecked")
        List<String> roles = jwt.getClaim("roles");

        return new UserInfoResponse(
                account.getId(),
                account.getTwitchId(),
                account.getTwitchUsername(),
                account.getProfileImageUrl(),
                account.getStatus().name(),
                roles != null ? roles : List.of(),
                account.getDeletionRequestedAt() != null
                        ? account.getDeletionRequestedAt().toString() : null
        );
    }

    @GetMapping("/me")
    public UserInfoResponse me(@AuthenticationPrincipal Jwt jwt) {
        return validate(jwt);
    }

    public record UserInfoResponse(
            UUID id,
            String twitchId,
            String username,
            String profileImageUrl,
            String status,
            List<String> roles,
            String deletionRequestedAt
    ) {}

    public static Map<String, Object> toClaimsMap(UserInfoResponse r) {
        return Map.of(
                "id", r.id().toString(),
                "twitchId", r.twitchId(),
                "username", r.username(),
                "status", r.status(),
                "roles", r.roles()
        );
    }
}
