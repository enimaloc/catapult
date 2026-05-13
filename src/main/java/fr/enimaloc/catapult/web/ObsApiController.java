package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.ObsProcessCache;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequiredArgsConstructor
public class ObsApiController {

    private final ObsProcessCache obsProcessCache;

    public record ProcessesPayload(List<String> processes) {}

    @PostMapping("/api/obs/processes")
    public ResponseEntity<Void> receiveProcesses(Authentication authentication,
                                                 @RequestBody ProcessesPayload payload) {
        UserAccount user = (UserAccount) authentication.getPrincipal();
        if (payload.processes() == null || payload.processes().isEmpty()) {
            obsProcessCache.clear(user);
        } else {
            obsProcessCache.update(user, Set.copyOf(payload.processes()));
        }
        return ResponseEntity.ok().build();
    }
}
