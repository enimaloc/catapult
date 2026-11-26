package fr.enimaloc.catapult.service.title;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * What a title template is rendered with: its variables' values and its conditions' ({@link TitleTemplate#CONDITIONS},
 * an absent one being false). {@code title} and {@code originalTitle} are read only when a chosen branch uses them;
 * the caller memoizes them. {@code dropName} and {@code dropEnd} are null without a running campaign,
 * {@code guestStars} (the Guest Star guests on air, their names joined) without guests or outside its experiment.
 */
public record TitleContext(String game, String category, Supplier<Optional<String>> title,
                           Supplier<Optional<String>> originalTitle, Map<String, Boolean> conditions,
                           String dropName, String dropEnd, String guestStars) {

    /** Without guest stars. */
    public TitleContext(String game, String category, Supplier<Optional<String>> title,
                        Supplier<Optional<String>> originalTitle, Map<String, Boolean> conditions,
                        String dropName, String dropEnd) {
        this(game, category, title, originalTitle, conditions, dropName, dropEnd, null);
    }

    /** This context with {@code guestStars} as its guests, {@code HAS_GUEST_STARS} following them. */
    public TitleContext withGuestStars(String guestStars) {
        Map<String, Boolean> withGuests = new HashMap<>(conditions);
        withGuests.put("HAS_GUEST_STARS", guestStars != null && !guestStars.isBlank());
        return new TitleContext(game, category, title, originalTitle, withGuests, dropName, dropEnd, guestStars);
    }
}
