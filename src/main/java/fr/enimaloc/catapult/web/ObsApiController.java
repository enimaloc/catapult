package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.ProcessBinding;
import fr.enimaloc.catapult.domain.ProcessNames;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ProcessBindingRepository;
import fr.enimaloc.catapult.service.ObsProcessCache;
import fr.enimaloc.catapult.service.ObsSession;
import fr.enimaloc.catapult.service.TwitchService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@Validated
public class ObsApiController {

    private final ObsProcessCache obsProcessCache;
    private final TwitchService twitchService;
    private final ProcessBindingRepository processBindingRepository;

    public record ProcessesPayload(
            String os,
            Map<String, String> environment,
            List<ProcessEntry> processes
    ) {
        public record ProcessEntry(String name, String workingDirectory, String cmdline) {}
    }

    public record BindingPayload(
        @NotBlank @Size(max = 255) String processName,
        @NotBlank @Size(max = 50)  String twitchGameId,
                  @Size(max = 255) String twitchGameName
    ) {}

    @PostMapping("/api/obs/processes")
    public ResponseEntity<Void> receiveProcesses(Authentication authentication,
                                                 @Valid @RequestBody ProcessesPayload payload) {
        if (!(authentication.getPrincipal() instanceof UserAccount user)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (payload.processes() == null || payload.processes().isEmpty()) {
            obsProcessCache.clear(user);
        } else {
            Set<ObsSession.ProcessSnapshot> snapshots = payload.processes().stream()
                    .filter(e -> e.name() != null && !e.name().isBlank())
                    .map(e -> new ObsSession.ProcessSnapshot(e.name(), e.workingDirectory(), e.cmdline()))
                    .collect(Collectors.toSet());
            obsProcessCache.update(user, new ObsSession(
                    payload.os(),
                    payload.environment() != null ? payload.environment() : Map.of(),
                    snapshots));
        }
        return ResponseEntity.ok().build();
    }

    @GetMapping("/api/obs/suggest")
    public ResponseEntity<List<TwitchService.TwitchCategory>> suggestGame(
            Authentication authentication,
            @RequestParam @NotBlank @Size(max = 255) String process) {
        if (!(authentication.getPrincipal() instanceof UserAccount user)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String query = process.replaceAll("(?i)\\.exe$", "").trim();
        if (query.isBlank()) return ResponseEntity.ok(List.of());
        return ResponseEntity.ok(twitchService.searchCategories(user, query));
    }

    @PostMapping("/api/obs/process-bindings")
    public ResponseEntity<Void> addProcessBinding(
            Authentication authentication,
            @Valid @RequestBody BindingPayload payload) {
        if (!(authentication.getPrincipal() instanceof UserAccount user)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        ProcessBinding binding = new ProcessBinding();
        binding.setUser(user);
        binding.setProcessName(ProcessNames.normalize(payload.processName()));
        binding.setTwitchGameId(payload.twitchGameId());
        binding.setTwitchGameName(payload.twitchGameName());
        processBindingRepository.save(binding);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
