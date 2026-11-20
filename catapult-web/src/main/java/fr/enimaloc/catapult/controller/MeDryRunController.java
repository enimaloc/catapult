package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.dryrun.DryRunMembers;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The logged-in member's way out of dry run (the banner's "Turn off", dry-run.js), relayed to catapult-api: its
 * outcome (see {@link ApiStatus}), 401 logged out. What this instance remembers of the member's state is forgotten
 * whatever catapult-api answers, so their next page shows it as it is now. Only an admin puts them back in.
 */
@RestController
@RequiredArgsConstructor
public class MeDryRunController {
    private final ApiService apiService;
    private final DryRunMembers dryRunMembers;

    @PostMapping("/me/dry-run/disable")
    public ResponseEntity<Void> disable() {
        UUID me = apiService.currentUserId();
        if (me == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            return ApiStatus.answer(apiService.disableMyDryRun());
        } finally {
            dryRunMembers.evict(me);
        }
    }
}
