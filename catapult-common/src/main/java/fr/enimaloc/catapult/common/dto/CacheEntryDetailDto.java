package fr.enimaloc.catapult.common.dto;

public record CacheEntryDetailDto(String key, Object detail, Long expiresInSeconds) {}
