package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.MyDebugDto;
import fr.enimaloc.catapult.domain.debug.MemberDebugSession;
import fr.enimaloc.catapult.service.debug.MemberDebugService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Whether the caller's own debug mode is on; nothing else about it is exposed to the member. */
@RestController
@RequiredArgsConstructor
public class ApiMeDebugController {

    private final MemberDebugService debugService;

    @PreAuthorize("@authz.isSelf(#userId)")
    @GetMapping(ApiPaths.USER + "/debug")
    public MyDebugDto debug(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        return new MyDebugDto(debugService.state(UUID.fromString(jwt.getSubject()))
                .map(MemberDebugSession::isActive).orElse(false));
    }
}
