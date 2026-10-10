package fr.enimaloc.catapult.common.dto.admin;

public record CacheEntryDetailDto(String key, Object detail, Long expiresInSeconds) {}
