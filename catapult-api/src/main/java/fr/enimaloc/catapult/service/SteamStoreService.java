package fr.enimaloc.catapult.service;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface SteamStoreService {
    Map<String, Set<String>> fetchCcls(Collection<String> appIds);

    // Returns the main game's app ID when the given app is a beta/demo/test build.
    Optional<String> resolveFullGameAppId(String appId);
}
