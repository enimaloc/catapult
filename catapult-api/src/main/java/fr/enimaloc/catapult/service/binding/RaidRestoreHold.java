package fr.enimaloc.catapult.service.binding;

import fr.enimaloc.catapult.domain.account.UserAccount;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Channels whose game an outgoing raid put back ({@link RaidRestoreGameService}): no "no game" fallback for them until
 * released, by the stream's end or a new game detected. In memory, like the game state.
 */
@Component
public class RaidRestoreHold {

    private final Set<UUID> holding = ConcurrentHashMap.newKeySet();

    /** True while a raid's restored game must not be replaced by the "no game" category. */
    public boolean isHolding(UserAccount user) {
        return holding.contains(user.getId());
    }

    public void hold(UserAccount user) {
        holding.add(user.getId());
    }

    /** Ends the hold: the stream ended, a new game was detected, or putting the game back failed. */
    public void release(UserAccount user) {
        holding.remove(user.getId());
    }
}
