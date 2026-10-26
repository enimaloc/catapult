package fr.enimaloc.catapult.service.category;

import fr.enimaloc.catapult.domain.binding.CustomBinding;
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

    /**
     * A game binding was edited (and saved), or re-resolved to another category: when it is the game played now and
     * no custom binding is active, the detection's rules apply (the binding when live, kept pending offline, the
     * ignored / incomplete fallback).
     */
    record BindingEdited(GameBinding binding) implements CategoryTrigger {}

    /**
     * An admin game mapping changed {@code binding}, the channel playing it: the detection's rules for a live channel
     * only (nothing kept pending), skipped while a custom binding is active.
     */
    record MappingChanged(GameBinding binding) implements CategoryTrigger {}

    /**
     * A custom binding was triggered (or re-applied after an edit): its category is sent, live or not, unless the bot
     * is off or the account inactive (then nothing is sent).
     */
    record CustomBindingTriggered(CustomBinding binding) implements CategoryTrigger {}

    /**
     * {@code !setgame}: the chosen category sent as a transient MANUAL binding without CCLs, live or not, a custom
     * binding or not (the caller ends it afterwards).
     */
    record ManualCategory(GameBinding binding) implements CategoryTrigger {}

    /**
     * A Twitchat revert action: the payload's category set by its game id alone, live or not, a custom binding or not
     * (the caller ends it afterwards).
     */
    record RevertCategory(String twitchGameId, String twitchGameName) implements CategoryTrigger {}

    /**
     * The no-game settings were saved: the default category applies at once when no game is detected and no custom
     * binding is active. No live check, no {@code applyDefaultOnNoGame} and no raid hold: the streamer just asked
     * for it.
     */
    record NoGameSettingsSaved() implements CategoryTrigger {}

    /**
     * The channel raided out: the game detected within the streamer's delay put back, unless the channel is already
     * on it. No live check. {@code currentCategoryId} is the category the channel is on, null when unknown.
     */
    record RaidedOut(String currentCategoryId) implements CategoryTrigger {}
}
