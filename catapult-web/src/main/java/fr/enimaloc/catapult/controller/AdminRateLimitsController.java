package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.LearnedRateLimitCorrectionDto;
import fr.enimaloc.catapult.service.ApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin rate limits tab's actions, forwarded to catapult-api with the session's JWT: a learned limit corrected
 * ({@code id}: the scope's), or reset. They answer catapult-api's status; 502 when it is unreachable.
 */
@RestController
@RequestMapping("/admin/rate-limits")
@RequiredArgsConstructor
public class AdminRateLimitsController {
    private final ApiService apiService;
    private final ModelFiller filler;

    @PostMapping("/correct")
    public ResponseEntity<Void> correct(@RequestParam String id, @RequestBody LearnedRateLimitCorrectionDto body) {
        AdminAccess.require(filler);
        return ResponseEntity.status(apiService.adminCorrectRateLimit(id, body)).build();
    }

    @PostMapping("/reset")
    public ResponseEntity<Void> reset(@RequestParam String id) {
        AdminAccess.require(filler);
        return ResponseEntity.status(apiService.adminResetRateLimit(id)).build();
    }
}
