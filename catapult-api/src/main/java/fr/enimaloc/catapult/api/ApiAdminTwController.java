package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.TwDefinition;
import fr.enimaloc.catapult.service.AdminTwService;
import fr.enimaloc.catapult.service.TwBackfillService;
import fr.enimaloc.catapult.service.notification.AdminEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/admin/tw")
@PreAuthorize("hasRole('ADMIN') or hasIpAddress('127.0.0.1') or hasIpAddress('::1')")
@RequiredArgsConstructor
public class ApiAdminTwController {

    private final AdminTwService service;

    // Optional so the controller still loads when tw.enabled=false disables
    // the backfill bean — the rebuild endpoint then 404s rather than crashing
    // wiring.
    @Autowired(required = false)
    private TwBackfillService backfillService;

    @Autowired(required = false)
    private AdminEventPublisher events;

    public record CreateBody(String id, String label, String description, Integer sortOrder) {}
    public record UpdateBody(String label, String description, Integer sortOrder, Boolean enabled) {}
    public record DtddTopicsBody(Set<String> topics) {}
    public record IgdbDescriptorsBody(Set<Long> descriptorIds) {}
    public record SteamIdsBody(Set<Integer> ids) {}
    public record SteamKeywordsBody(Set<String> keywords) {}
    public record SteamKeywordBody(String keyword) {}
    public record SteamSignalTestBody(String appId, String draftKeyword) {}

    @GetMapping
    public List<TwDefinition> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public ResponseEntity<TwDefinition> get(@PathVariable String id) {
        return service.get(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<TwDefinition> create(@RequestBody CreateBody b) {
        TwDefinition d = service.create(b.id(), b.label(), b.description(),
                b.sortOrder() == null ? 0 : b.sortOrder());
        if (events != null) {
            events.twDefinitionAdded(Map.of(
                    "id", d.getId(),
                    "label", d.getLabel(),
                    "enabled", d.isEnabled(),
                    "sortOrder", d.getSortOrder()));
        }
        return ResponseEntity.status(201).body(d);
    }

    @PatchMapping("/{id}")
    public TwDefinition update(@PathVariable String id, @RequestBody UpdateBody b) {
        return service.update(id, b.label(), b.description(), b.sortOrder(), b.enabled());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/dtdd-topics")
    public ResponseEntity<Void> setDtdd(@PathVariable String id, @RequestBody DtddTopicsBody b) {
        service.replaceDtddTopics(id, b.topics() == null ? Set.of() : b.topics());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/igdb-descriptors")
    public ResponseEntity<Void> setIgdb(@PathVariable String id, @RequestBody IgdbDescriptorsBody b) {
        service.replaceIgdbDescriptors(id, b.descriptorIds() == null ? Set.of() : b.descriptorIds());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/steam-content-ids")
    public ResponseEntity<Void> setSteamIds(@PathVariable String id, @RequestBody SteamIdsBody b) {
        service.replaceSteamContentIds(id, b.ids() == null ? Set.of() : b.ids());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/steam-keywords")
    public ResponseEntity<Void> setSteamKw(@PathVariable String id, @RequestBody SteamKeywordsBody b) {
        service.replaceSteamKeywords(id, b.keywords() == null ? Set.of() : b.keywords());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/steam-keywords")
    public List<String> listSteamKw(@PathVariable String id) {
        return service.listSteamKeywords(id);
    }

    @PostMapping("/{id}/steam-keywords")
    public ResponseEntity<Void> addSteamKw(@PathVariable String id, @RequestBody SteamKeywordBody b) {
        service.addSteamKeyword(id, b.keyword());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/steam-keywords/{keyword}")
    public ResponseEntity<Void> removeSteamKw(@PathVariable String id, @PathVariable String keyword) {
        service.removeSteamKeyword(id, keyword);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/steam-keywords/test")
    public AdminTwService.SteamSignalTestResult testSteamKw(@PathVariable String id, @RequestBody SteamSignalTestBody b) {
        return service.testSteamSignals(id, b.appId(), b.draftKeyword());
    }

    @PostMapping("/rebuild")
    public ResponseEntity<Void> rebuildNonOverriddenMappings() {
        if (backfillService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        backfillService.rebuildAllNonOverridden();
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/rebuild/force")
    public ResponseEntity<Void> rebuildMappings() {
        if (backfillService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        backfillService.rebuildAll();
        return ResponseEntity.accepted().build();
    }
}
