package fr.enimaloc.catapult.repository.ratelimit;

import fr.enimaloc.catapult.domain.ratelimit.LearnedRateLimit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LearnedRateLimitRepository extends JpaRepository<LearnedRateLimit, String> {
}
