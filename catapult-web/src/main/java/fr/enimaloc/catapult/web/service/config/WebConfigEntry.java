package fr.enimaloc.catapult.web.service.config;

public record WebConfigEntry(
        String key,
        String value,
        boolean secret,
        boolean restartRequired,
        boolean overridden,
        boolean taboo,
        String source
) {}
