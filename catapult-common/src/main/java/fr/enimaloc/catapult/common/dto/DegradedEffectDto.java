package fr.enimaloc.catapult.common.dto;

/** A property the degraded profile changes: the value applied, and the one it replaces. */
public record DegradedEffectDto(String key, String value, String normalValue) {}
