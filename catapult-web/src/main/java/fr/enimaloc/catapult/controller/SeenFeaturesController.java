package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

/**
 * The member opened a tab marked new (channel-page.js): catapult-api keeps it, so its dot stays
 * gone on every device. Only {@link NewFeatures}' keys go through (404 for any other).
 */
@Controller
@RequestMapping("/spa/seen-features")
@RequiredArgsConstructor
public class SeenFeaturesController {
    private final ApiService apiService;

    @PostMapping("/{key}")
    ResponseEntity<Void> seen(@PathVariable String key) {
        if (!ApiClient.hasSession()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (!NewFeatures.isKnown(key)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.status(apiService.markFeatureSeen(key)).build();
    }
}
