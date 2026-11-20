package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.common.dto.DryRunStateDto;
import fr.enimaloc.catapult.domain.dryrun.MemberDryRun;
import fr.enimaloc.catapult.service.dryrun.DryRunService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** An admin's switch for a member's dry run (Catapult then writes nothing public on the member's Twitch channel). */
@RestController
@RequestMapping(ApiPaths.ADMIN + "/members/{id}/dry-run")
@RequiredArgsConstructor
public class ApiAdminMemberDryRunController {

    private final DryRunService dryRunService;

    /** 404 for an unknown member, 409 when already on. The JWT is required: it names the admin who switched it on. */
    @PostMapping("/enable")
    public DryRunStateDto enable(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        MemberDryRun row = dryRunService.enable(UUID.fromString(jwt.getSubject()), id);
        return new DryRunStateDto(true, row.getEnabledAt());
    }

    /** 404 for an unknown member; no-op when not in dry run. */
    @PostMapping("/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        dryRunService.disable(jwt == null ? null : UUID.fromString(jwt.getSubject()), id);
    }
}
