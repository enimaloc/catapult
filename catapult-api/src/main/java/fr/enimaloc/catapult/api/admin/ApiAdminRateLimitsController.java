package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.common.dto.admin.LearnedRateLimitCorrectionDto;
import fr.enimaloc.catapult.common.dto.admin.LearnedRateLimitDto;
import fr.enimaloc.catapult.ratelimit.LearnedRateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The rate limits Catapult learned from the platforms' 429s (hosts and their routes), an admin's correction of one
 * and its reset. {@code id}: the scope's ID ({@code HOST store.steampowered.com},
 * {@code GET store.steampowered.com/app/{id}}).
 */
@RestController
@RequestMapping(ApiPaths.ADMIN + "/rate-limits")
@RequiredArgsConstructor
public class ApiAdminRateLimitsController {

    private final LearnedRateLimiter limiter;

    @GetMapping
    public List<LearnedRateLimitDto> list() {
        return limiter.list();
    }

    @PutMapping("/entry")
    public LearnedRateLimitDto correct(@RequestParam String id, @RequestBody LearnedRateLimitCorrectionDto body) {
        return limiter.correct(id, body.limitCalls(), body.limitWindowSeconds(), body.resumeSeconds(), body.scope());
    }

    @DeleteMapping("/entry")
    public LearnedRateLimitDto reset(@RequestParam String id) {
        return limiter.reset(id);
    }
}
