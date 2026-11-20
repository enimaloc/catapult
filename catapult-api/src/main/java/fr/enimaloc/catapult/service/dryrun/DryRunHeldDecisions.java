package fr.enimaloc.catapult.service.dryrun;

import fr.enimaloc.catapult.event.ChannelCategoryChangedEvent;
import fr.enimaloc.catapult.service.category.CategoryRule;
import fr.enimaloc.catapult.service.category.CategoryTrigger;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per member in dry run, the last channel decision the dry run held back ({@code CategoryChain} got
 * {@code DRY_RUN} back), so that the exit only puts back what Catapult would have put. A category change seen on the
 * channel meanwhile is the streamer's own (Catapult sends nothing in dry run): it replaces whatever was held. Taken,
 * and so forgotten, when the member leaves dry run.
 */
@Component
public class DryRunHeldDecisions {

    /** A decision held back: what asked for it and the rule that decided. */
    public record Held(CategoryTrigger trigger, CategoryRule rule) {

        /** Whether it was the "no game" category, what closing the game puts. */
        public boolean noGame() {
            return rule == CategoryRule.NO_GAME_FALLBACK;
        }
    }

    private final Map<UUID, Held> held = new ConcurrentHashMap<>();

    /** {@code trigger}'s decision, by {@code rule}, held back for {@code userId}. */
    public void record(UUID userId, CategoryTrigger trigger, CategoryRule rule) {
        held.put(userId, new Held(trigger, rule));
    }

    /** The last decision held back for {@code userId} and not overridden by hand since. */
    public Optional<Held> lastHeld(UUID userId) {
        return Optional.ofNullable(held.get(userId));
    }

    /** {@link #lastHeld}, forgotten. */
    public Optional<Held> take(UUID userId) {
        return Optional.ofNullable(held.remove(userId));
    }

    /** A category set by hand on the channel: what was held no longer stands. */
    @EventListener
    public void onCategoryChanged(ChannelCategoryChangedEvent event) {
        if (event.getUser() != null && event.getUser().getId() != null) {
            held.remove(event.getUser().getId());
        }
    }
}
