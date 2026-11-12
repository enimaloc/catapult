package fr.enimaloc.catapult.domain.ratelimit;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * What was learned of one scope's rate limit: a host (its shared budget; {@code method} and {@code path} null) or a
 * route ({@code scope} HOST: counted in its host's budget, ROUTE: its own). {@code override*}: an admin's correction.
 */
@Entity
@Table(name = "learned_rate_limits")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class LearnedRateLimit {

    @Id
    @Column(name = "id", nullable = false, length = 800)
    private String id;

    @Column(name = "host", nullable = false)
    private String host;

    @Column(name = "method")
    private String method;

    @Column(name = "path")
    private String path;

    @Column(name = "scope")
    private String scope;

    @Column(name = "scope_pinned", nullable = false)
    private boolean scopePinned;

    @Column(name = "limit_calls")
    private Integer limitCalls;

    @Column(name = "limit_window_seconds")
    private Integer limitWindowSeconds;

    @Column(name = "resume_ms", nullable = false)
    private long resumeMs;

    @Column(name = "resume_fail_ms")
    private Long resumeFailMs;

    @Column(name = "resume_ok_ms")
    private Long resumeOkMs;

    @Column(name = "last_fail_at")
    private Instant lastFailAt;

    @Column(name = "rate_limited_count", nullable = false)
    private int rateLimitedCount;

    @Column(name = "last_rate_limited_at")
    private Instant lastRateLimitedAt;

    @Column(name = "override_calls")
    private Integer overrideCalls;

    @Column(name = "override_window_seconds")
    private Integer overrideWindowSeconds;

    @Column(name = "override_resume_ms")
    private Long overrideResumeMs;

    /** The last admin correction or reset: an instance that hasn't taken it yet doesn't write over it. */
    @Column(name = "admin_changed_at")
    private Instant adminChangedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
