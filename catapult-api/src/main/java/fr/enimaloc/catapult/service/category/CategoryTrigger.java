package fr.enimaloc.catapult.service.category;

import fr.enimaloc.catapult.domain.binding.GameBinding;

/**
 * What asks {@link CategoryChain} for a channel's Twitch category: one record per trigger family, each with its own
 * rule list (see {@link CategoryChain#decide}).
 */
public sealed interface CategoryTrigger {

    /**
     * A detection of the game {@code binding} belongs to (or a custom binding's end with that game open): its binding,
     * or the ignored / incomplete fallback, applied when live, stored pending otherwise.
     */
    record GameDetected(GameBinding binding) implements CategoryTrigger {}

    /** The game was closed: the usual "no game" category, unless a custom binding is active. */
    record GameClosed() implements CategoryTrigger {}

    /** A custom binding ended with no game open: the usual "no game" category, the custom binding not read again. */
    record NoGameAfterCustomBinding() implements CategoryTrigger {}

    /**
     * {@code stream.online}: the active custom binding re-applied, else the default category when enabled, else the
     * binding kept pending while offline.
     */
    record StreamStarted() implements CategoryTrigger {}

    /** {@code stream.offline}: the default category when enabled; the custom binding is never read. */
    record StreamEnded() implements CategoryTrigger {}
}
