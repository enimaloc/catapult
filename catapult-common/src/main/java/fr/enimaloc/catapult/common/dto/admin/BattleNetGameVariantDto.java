package fr.enimaloc.catapult.common.dto.admin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An admin's Battle.net game variant: while a friend plays {@code fourcc} with an activity text matching one of
 * {@code patterns} ({@code matchType} PREFIX or CONTAINS, case ignored), the game detected is {@code sourceId}
 * ({@code fourcc:variantKey}), named {@code displayName}. {@code updatedBy}: the admin who last saved it, null for the
 * seeded ones.
 */
public record BattleNetGameVariantDto(UUID id, String fourcc, String variantKey, String sourceId, String matchType,
                                      List<String> patterns, String displayName, boolean enabled, Instant updatedAt,
                                      String updatedBy) {
}
