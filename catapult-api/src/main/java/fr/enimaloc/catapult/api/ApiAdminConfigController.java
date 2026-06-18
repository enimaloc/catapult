package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.ConfigAudit;
import fr.enimaloc.catapult.domain.ConfigOverride;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ConfigAuditRepository;
import fr.enimaloc.catapult.repository.ConfigOverrideRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.config.ConfigCatalogService;
import fr.enimaloc.catapult.service.config.ConfigEntry;
import fr.enimaloc.catapult.service.config.ConfigOverrideService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/config")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class ApiAdminConfigController {

    private final ConfigCatalogService catalogService;
    private final ConfigOverrideService overrideService;
    private final UserAccountRepository userRepo;
    private final ConfigAuditRepository auditRepo;
    private final ConfigOverrideRepository overrideRepo;

    @GetMapping
    public List<ConfigEntry> catalog() {
        return catalogService.catalog();
    }

    @PutMapping("/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void apply(@PathVariable String key, @RequestBody ApplyRequest body, @AuthenticationPrincipal Jwt jwt) {
        overrideService.apply(key, body.value(), currentUser(jwt));
    }

    @DeleteMapping("/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@PathVariable String key, @AuthenticationPrincipal Jwt jwt) {
        overrideService.clear(key, currentUser(jwt));
    }

    @GetMapping("/audit")
    public List<ConfigAudit> audit(@RequestParam String key) {
        return auditRepo.findByKeyOrderByChangedAtDesc(key);
    }

    @GetMapping("/module/{module}")
    public List<ModuleOverrideDto> moduleOverrides(@PathVariable String module) {
        return overrideRepo.findByIdModule(module).stream()
                .map(o -> new ModuleOverrideDto(
                        o.getKey(),
                        o.isSecret() ? null : o.getValue(),
                        o.isSecret(),
                        o.getUpdatedAt(),
                        o.getUpdatedBy()))
                .toList();
    }

    @PutMapping("/module/{module}/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void applyForModule(@PathVariable String module,
                               @PathVariable String key,
                               @RequestBody ApplyRequest body,
                               @AuthenticationPrincipal Jwt jwt) {
        overrideService.apply(module, key, body.value(), currentUser(jwt));
    }

    @DeleteMapping("/module/{module}/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearForModule(@PathVariable String module,
                               @PathVariable String key,
                               @AuthenticationPrincipal Jwt jwt) {
        overrideService.clear(module, key, currentUser(jwt));
    }

    @GetMapping("/module/{module}/audit")
    public List<ConfigAudit> moduleAudit(@PathVariable String module, @RequestParam String key) {
        return auditRepo.findByModuleAndKeyOrderByChangedAtDesc(module, key);
    }

    private UserAccount currentUser(Jwt jwt) {
        String twitchId = jwt.getClaimAsString("twitchId");
        return userRepo.findByTwitchId(twitchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    public record ApplyRequest(String value) {}

    public record ModuleOverrideDto(
            String key,
            String value,
            boolean secret,
            Instant updatedAt,
            UUID updatedBy) {}
}
