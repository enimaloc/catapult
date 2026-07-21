package fr.enimaloc.catapult.chat.command.js;

import fr.enimaloc.catapult.domain.UserAccount;

import java.util.Optional;

/**
 * Curated access to external services from sandboxed command JS.
 * Phase 1 scope: read-only lookups, no writes, no arbitrary network access.
 */
public interface ChatCommandServiceGateway {
    Optional<String> igdbGameName(String query);
    Optional<String> twitchOwnDisplayName(UserAccount user);
    Optional<String> steamPrice(String appId);
}
