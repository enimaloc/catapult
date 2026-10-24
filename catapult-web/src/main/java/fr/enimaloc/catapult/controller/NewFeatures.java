package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.service.ApiService;

import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The tabs that get a "new" dot: each one by its key ({@code <page>.<tab value>}, as the templates
 * test it in {@code newFeatures}) and the day it shipped. The dot shows for {@link #SHOWN_FOR}
 * after that day, until the member opens the tab (channel-page.js tells catapult-api, which keeps
 * it per account). Marking a tab is a line here; an expired one can stay or go.
 */
public final class NewFeatures {

    static final Map<String, LocalDate> RELEASES = Map.of(
            "channel.commands", LocalDate.of(2026, 10, 21));

    static final Period SHOWN_FOR = Period.ofMonths(1);

    private NewFeatures() {
    }

    /** The keys whose dot the logged-in member should see now; call it with a session only. */
    public static Set<String> unseen(ApiService apiService) {
        return unseen(apiService, RELEASES, LocalDate.now(ZoneOffset.UTC));
    }

    static Set<String> unseen(ApiService apiService, Map<String, LocalDate> releases, LocalDate today) {
        Set<String> current = releases.entrySet().stream()
                .filter(release -> today.isBefore(release.getValue().plus(SHOWN_FOR)))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        // Nothing new: spare the call to catapult-api on every page.
        if (current.isEmpty()) return Set.of();
        List<String> seen = apiService.seenFeatures();
        // catapult-api down: no dot, rather than one opening the tab couldn't clear.
        if (seen == null) return Set.of();
        current.removeAll(seen);
        return current;
    }

    /** Whether {@code key} is one of the features above: catapult-web forwards no other. */
    public static boolean isKnown(String key) {
        return RELEASES.containsKey(key);
    }
}
