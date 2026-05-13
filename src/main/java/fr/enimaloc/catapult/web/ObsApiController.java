package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.ObsProcessCache;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequiredArgsConstructor
public class ObsApiController {

    private final ObsProcessCache obsProcessCache;

    public record ProcessesPayload(@Size(max = 500) List<String> processes) {}

    @PostMapping("/api/obs/processes")
    public ResponseEntity<Void> receiveProcesses(Authentication authentication,
                                                 @Valid @RequestBody ProcessesPayload payload) {
        if (!(authentication.getPrincipal() instanceof UserAccount user)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (payload.processes() == null || payload.processes().isEmpty()) {
            obsProcessCache.clear(user);
        } else {
            obsProcessCache.update(user, Set.copyOf(payload.processes()));
        }
        return ResponseEntity.ok().build();
    }
}
