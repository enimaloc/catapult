package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.ExperimentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/experiments/me")
@RequiredArgsConstructor
public class ApiUserExperimentsController {

    private final ExperimentService experimentService;
    private final UserAccountRepository userAccountRepository;

    /**
     * Returns the variant key assigned to the current user for the given experiment.
     * Triggers assignment if the user is eligible but not yet assigned.
     * Returns {"variant": null} when no assignment applies (experiment paused/ended/excluded).
     */
    @GetMapping("/variant/{key}")
    public VariantResponse getVariant(@PathVariable String key, @AuthenticationPrincipal Jwt jwt) {
        UserAccount user = userAccountRepository.findById(UUID.fromString(jwt.getSubject()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return new VariantResponse(
            experimentService.getVariant(user, key).map(v -> v.getKey()).orElse(null)
        );
    }

    public record VariantResponse(String variant) {}
}
