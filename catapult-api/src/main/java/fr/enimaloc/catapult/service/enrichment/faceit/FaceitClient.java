package fr.enimaloc.catapult.service.enrichment.faceit;

import fr.enimaloc.catapult.service.enrichment.faceit.FaceitModels.LastMatch;
import fr.enimaloc.catapult.service.enrichment.faceit.FaceitModels.Lifetime;
import fr.enimaloc.catapult.service.enrichment.faceit.FaceitModels.Player;

import java.util.Optional;

/**
 * The FACEIT Data API v4, with a key of the shared pool. Every call first asks the {@link FaceitRateLimiter}; what
 * FACEIT does not know is {@link FaceitNotFoundException} (only its JSON 404), anything else that cannot be read is
 * {@link FaceitUnavailableException}. {@code game} is FACEIT's game id ({@code cs2}).
 */
public interface FaceitClient {

    /** The player of that nickname. */
    Player playerByNickname(String nickname);

    /** The player of that id. */
    Player player(String playerId);

    /** The player's lifetime stats on {@code game}; not found when they have none there. */
    Lifetime lifetime(String playerId, String game);

    /** The player's last match on {@code game}; empty when they played none. */
    Optional<LastMatch> lastMatch(String playerId, String game);
}
