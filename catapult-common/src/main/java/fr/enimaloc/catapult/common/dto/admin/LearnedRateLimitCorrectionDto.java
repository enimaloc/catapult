package fr.enimaloc.catapult.common.dto.admin;

/**
 * An admin's correction of a learned rate limit: {@code limitCalls} per {@code limitWindowSeconds} (both or neither),
 * {@code resumeSeconds}, and for a route its {@code scope} (HOST or ROUTE). A null value leaves that part to the
 * learning; what is set stays until the scope is reset.
 */
public record LearnedRateLimitCorrectionDto(Integer limitCalls, Integer limitWindowSeconds, Integer resumeSeconds,
                                            String scope) {}
