package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.repository.feature.SeenFeatureRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The new features (catapult-web's NewFeatures keys, e.g. {@code channel.commands}) the
 * logged-in user has opened: catapult-web drops a feature's "new" dot for them once it's here.
 */
@RestController
@RequestMapping(ApiPaths.USER + "/seen-features")
@RequiredArgsConstructor
public class ApiSeenFeaturesController {

    /** A key as catapult-web writes them; anything else is refused rather than stored. */
    private static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9.-]{0,99}");

    private final SeenFeatureRepository seenFeatureRepository;
    private final ApiUserResolver userResolver;

    @PreAuthorize("@authz.isSelf(#userId)")
    @GetMapping
    public List<String> seen(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        return seenFeatureRepository.findFeatureKeysByUserId(userResolver.viewer(jwt).getId());
    }

    @Transactional
    @PreAuthorize("@authz.isSelf(#userId)")
    @PutMapping("/{key}")
    public ResponseEntity<Void> markSeen(@PathVariable String key, @PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        if (!KEY.matcher(key).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        UUID viewerId = userResolver.viewer(jwt).getId();
        seenFeatureRepository.insertIfAbsent(viewerId, key, Instant.now());
        return ResponseEntity.noContent().build();
    }
}
