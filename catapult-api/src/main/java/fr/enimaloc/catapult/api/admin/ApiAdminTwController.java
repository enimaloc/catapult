package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.common.dto.admin.AdminTwCreateBody;
import fr.enimaloc.catapult.common.dto.admin.DtddTopicsBody;
import fr.enimaloc.catapult.common.dto.admin.IgdbDescriptorsBody;
import fr.enimaloc.catapult.common.dto.admin.SteamIdsBody;
import fr.enimaloc.catapult.common.dto.admin.SteamKeywordBody;
import fr.enimaloc.catapult.common.dto.admin.SteamKeywordsBody;
import fr.enimaloc.catapult.common.dto.admin.SteamSignalTestBody;
import fr.enimaloc.catapult.common.dto.admin.UpdateBody;
import fr.enimaloc.catapult.domain.tw.TwDefinition;
import fr.enimaloc.catapult.service.notification.AdminEventPublisher;
import fr.enimaloc.catapult.service.tw.AdminTwService;
import fr.enimaloc.catapult.service.tw.TwBackfillService;
import lombok.RequiredArgsConstructor;

import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/admin/tw")
@RequiredArgsConstructor
public class ApiAdminTwController {

    private final AdminTwService service;

    // Optional so the controller still loads when tw.enabled=false disables
    // the backfill bean — the rebuild endpoints then answer 503 rather than
    // crashing wiring.
    private final Optional<TwBackfillService> backfillService;

    private final AdminEventPublisher events;

    @GetMapping
    public List<TwDefinition> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public ResponseEntity<TwDefinition> get(@PathVariable String id) {
        return service.get(id)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown TW " + id));
    }

    @PostMapping
    public ResponseEntity<TwDefinition> create(@RequestBody AdminTwCreateBody b) {
        TwDefinition d = service.create(b.id(), b.label(), b.description(),
                b.sortOrder() == null ? 0 : b.sortOrder());
        events.twDefinitionAdded(Map.of(
                "id", d.getId(),
                "label", d.getLabel(),
                "enabled", d.isEnabled(),
                "sortOrder", d.getSortOrder()));
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
        return backfillService
                .map(backfill -> {
                    backfill.rebuildAllNonOverridden();
                    return ResponseEntity.accepted().<Void>build();
                })
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The TW backfill is not available"));
    }

    @PostMapping("/rebuild/force")
    public ResponseEntity<Void> rebuildMappings() {
        return backfillService
                .map(backfill -> {
                    backfill.rebuildAll();
                    return ResponseEntity.accepted().<Void>build();
                })
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The TW backfill is not available"));
    }

    @PostMapping("/rebuild/force-pinned")
    public ResponseEntity<Void> rebuildMappingsIncludingPinned() {
        return backfillService
                .map(backfill -> {
                    backfill.forceRebuildAllIncludingPinned();
                    return ResponseEntity.accepted().<Void>build();
                })
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The TW backfill is not available"));
    }
}
