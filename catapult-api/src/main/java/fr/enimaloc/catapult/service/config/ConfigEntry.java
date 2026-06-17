package fr.enimaloc.catapult.service.config;

public record ConfigEntry(
        String key,
        String value,
        boolean secret,
        boolean restartRequired,
        boolean overridden,
        boolean taboo,
        String source
) {}
