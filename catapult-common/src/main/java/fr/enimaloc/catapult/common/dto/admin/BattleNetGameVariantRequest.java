package fr.enimaloc.catapult.common.dto.admin;

import java.util.List;

/**
 * Creates or changes a Battle.net game variant. {@code fourcc} and {@code variantKey} are only read on creation: a
 * change must repeat them unchanged. {@code patterns} are kept as typed (a trailing space counts).
 */
public record BattleNetGameVariantRequest(String fourcc, String variantKey, String matchType, List<String> patterns,
                                          String displayName, boolean enabled) {
}
