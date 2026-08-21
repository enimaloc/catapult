package fr.enimaloc.catapult.service;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface SteamStoreService {
    Map<String, Set<String>> fetchCcls(Collection<String> appIds);

    /** Base game the given appId belongs to, when appId is a demo/beta/playtest. */
    record ResolvedParentApp(String appId, String name) {}

    Optional<ResolvedParentApp> resolveEffectiveApp(String appId);

    /** Steam content_descriptors block flattened for TW resolution. */
    record SteamTwSignals(Set<Integer> contentDescriptorIds, String notesLowercase) {
        public static SteamTwSignals empty() {
            return new SteamTwSignals(Set.of(), "");
        }
    }

    Map<String, SteamTwSignals> fetchTwSignals(Collection<String> appIds);
}
