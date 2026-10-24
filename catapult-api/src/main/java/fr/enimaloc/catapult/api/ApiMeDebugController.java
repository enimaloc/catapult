package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.MyDebugDto;
import fr.enimaloc.catapult.domain.debug.MemberDebugSession;
import fr.enimaloc.catapult.service.debug.MemberDebugService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Whether the caller's own debug mode is on; nothing else about it is exposed to the member. */
@RestController
@RequiredArgsConstructor
public class ApiMeDebugController {

    private final MemberDebugService debugService;

    @GetMapping("/api/me/debug")
    public MyDebugDto debug(@AuthenticationPrincipal Jwt jwt) {
        // No JWT on the unauthenticated localhost calls the security config lets through.
        if (jwt == null) {
            return new MyDebugDto(false);
        }
        return new MyDebugDto(debugService.state(UUID.fromString(jwt.getSubject()))
                .map(MemberDebugSession::isActive).orElse(false));
    }
}
