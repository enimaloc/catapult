package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.common.dto.admin.CacheEntryDetailDto;
import fr.enimaloc.catapult.common.dto.admin.CacheEntryDto;
import fr.enimaloc.catapult.common.dto.admin.CacheSummaryDto;
import fr.enimaloc.catapult.service.cache.AdminCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Inspect and selectively evict the in-memory caches (see {@link AdminCacheService}). */
@RestController
@RequestMapping(ApiPaths.ADMIN + "/caches")
@RequiredArgsConstructor
public class ApiAdminCacheController {

    private final AdminCacheService caches;

    @GetMapping
    public List<CacheSummaryDto> list() {
        return caches.list();
    }

    @GetMapping("/{name}")
    public Page<CacheEntryDto> entries(
            @PathVariable String name,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return caches.entries(name, q, page, size);
    }

    @GetMapping("/{name}/entry")
    public CacheEntryDetailDto entryDetail(@PathVariable String name, @RequestParam String key) {
        return caches.entry(name, key);
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> deleteEntry(@PathVariable String name, @RequestParam String key) {
        caches.evict(name, key);
        return ResponseEntity.noContent().build();
    }
}
