package fr.enimaloc.catapult.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Whether catapult runs degraded, with every property the degraded profile changes.
 * The effects are left out when not degraded.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DegradedStatusDto(boolean degraded, List<DegradedEffectDto> effects) {}
