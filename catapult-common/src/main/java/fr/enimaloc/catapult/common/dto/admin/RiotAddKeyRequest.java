package fr.enimaloc.catapult.common.dto.admin;

/**
 * A Riot API key to add to the shared pool, optionally kept to one routing cluster ({@code americas}, {@code asia},
 * {@code europe} or {@code sea}; absent for every cluster). The admin Keys tab sends {@code apiKey} only.
 */
public record RiotAddKeyRequest(String apiKey, String regionScope) {}
