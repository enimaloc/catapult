package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.DryRunStateDto;
import fr.enimaloc.catapult.service.dryrun.DryRunService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Whether the caller's own account is in dry run, and their way out of it; only an admin can put them back in. */
@RestController
@RequiredArgsConstructor
public class ApiMeDryRunController {

    private final DryRunService dryRunService;

    @PreAuthorize("@authz.isSelf(#userId)")
    @GetMapping(ApiPaths.USER + "/dry-run")
    public DryRunStateDto state(@PathVariable UUID userId) {
        return dryRunService.state(userId)
                .map(row -> new DryRunStateDto(true, row.getEnabledAt()))
                .orElse(DryRunStateDto.OFF);
    }

    /** No-op when not in dry run. */
    @PreAuthorize("@authz.isSelf(#userId)")
    @PostMapping(ApiPaths.USER + "/dry-run/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(@PathVariable UUID userId) {
        dryRunService.disable(userId, userId);
    }
}
