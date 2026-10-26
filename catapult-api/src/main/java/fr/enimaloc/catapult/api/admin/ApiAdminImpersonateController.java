package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.common.dto.admin.ImpersonateRequest;
import fr.enimaloc.catapult.common.dto.admin.ImpersonateResponse;
import fr.enimaloc.catapult.security.ImpersonationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.ADMIN + "/impersonations")
@RequiredArgsConstructor
public class ApiAdminImpersonateController {

    private final ImpersonationService impersonation;

    @PostMapping
    public ImpersonateResponse impersonate(@AuthenticationPrincipal Jwt jwt,
                                           @RequestBody ImpersonateRequest body) {
        UUID adminId = UUID.fromString(ApiUserResolver.requireJwt(jwt).getSubject());
        return new ImpersonateResponse(impersonation.impersonate(adminId, body.username()));
    }
}
