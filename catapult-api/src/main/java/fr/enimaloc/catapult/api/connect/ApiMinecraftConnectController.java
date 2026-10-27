package fr.enimaloc.catapult.api.connect;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.common.dto.connect.LinkStateResponse;
import fr.enimaloc.catapult.service.minecraft.MinecraftConnectService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.USER + "/links/minecraft")
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("minecraft.enabled")
public class ApiMinecraftConnectController {

    private final MinecraftConnectService minecraft;

    @PreAuthorize("@authz.isSelf(#userId)")
    @PostMapping("/sync")
    public LinkStateResponse syncNow(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        return minecraft.sync(caller(jwt));
    }

    @PreAuthorize("@authz.isSelf(#userId)")
    @GetMapping
    public LinkStateResponse status(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        return minecraft.status(caller(jwt));
    }

    @PreAuthorize("@authz.isSelf(#userId)")
    @PutMapping
    public LinkStateResponse enroll(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt, @RequestBody Map<String, String> body) {
        return minecraft.enroll(caller(jwt), body.get("name"));
    }

    @PreAuthorize("@authz.isSelf(#userId)")
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unenroll(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        minecraft.unenroll(caller(jwt));
    }

    private static UUID caller(Jwt jwt) {
        return UUID.fromString(ApiUserResolver.requireJwt(jwt).getSubject());
    }
}
