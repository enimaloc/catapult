package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.premium.PremiumStatusDto;
import fr.enimaloc.catapult.service.premium.PremiumService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** The caller's own premium status and what each tier gets. */
@RestController
@RequiredArgsConstructor
public class ApiMePremiumController {

    private final ApiUserResolver userResolver;
    private final PremiumService premiumService;

    @PreAuthorize("@authz.isSelf(#userId)")
    @GetMapping(ApiPaths.USER + "/premium")
    public PremiumStatusDto premium(@PathVariable UUID userId) {
        return premiumService.status(userResolver.user(userId));
    }
}
