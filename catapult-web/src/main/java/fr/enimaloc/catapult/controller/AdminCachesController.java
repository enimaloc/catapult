package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.service.ApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The admin caches tab's eviction, forwarded to catapult-api with its status (400 for a read-only cache). */
@RestController
@RequestMapping("/admin/caches/{name}")
@RequiredArgsConstructor
public class AdminCachesController {
    private final ApiService apiService;
    private final ModelFiller filler;

    @PostMapping("/delete")
    public ResponseEntity<Void> delete(@PathVariable String name, @RequestParam String key) {
        AdminAccess.require(filler);
        return ResponseEntity.status(apiService.adminDeleteCacheEntry(name, key)).build();
    }
}
