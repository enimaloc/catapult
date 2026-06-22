package fr.enimaloc.catapult.service;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface SteamStoreService {
    Map<String, Set<String>> fetchCcls(Collection<String> appIds);

    // Returns the main game's app ID when the given app is a beta/demo/test build.
    Optional<String> resolveFullGameAppId(String appId);

    /** Steam content_descriptors block flattened for TW resolution. */
    record SteamTwSignals(Set<Integer> contentDescriptorIds, String notesLowercase) {
        public static SteamTwSignals empty() {
            return new SteamTwSignals(Set.of(), "");
        }
    }

    Map<String, SteamTwSignals> fetchTwSignals(Collection<String> appIds);
}
