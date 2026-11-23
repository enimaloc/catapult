package fr.enimaloc.catapult.simulation.external;

import fr.enimaloc.catapult.service.enrichment.faceit.FaceitClient;
import fr.enimaloc.catapult.service.enrichment.faceit.FaceitModels.GameProfile;
import fr.enimaloc.catapult.service.enrichment.faceit.FaceitModels.LastMatch;
import fr.enimaloc.catapult.service.enrichment.faceit.FaceitModels.Lifetime;
import fr.enimaloc.catapult.service.enrichment.faceit.FaceitModels.Player;
import fr.enimaloc.catapult.service.enrichment.faceit.FaceitNotFoundException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * FACEIT in the mock profile: the nickname {@code Catapult} (any case) is a level 8 Counter-Strike 2 player at
 * 1 850 Elo whose last match was a 13 / 9 win on Mirage; any other nickname is unknown. The calls still need a key in
 * the pool (the source is off without one), but none leaves the process.
 */
@Primary
@Component
@ConditionalOnBooleanProperty(name = "catapult.simulation.enabled")
public class SimulatedFaceitClient implements FaceitClient {

    static final String NICKNAME = "Catapult";
    static final String PLAYER_ID = "00000000-0000-4000-8000-00000000fac1";

    @Override
    public Player playerByNickname(String nickname) {
        if (nickname == null || !NICKNAME.toLowerCase(Locale.ROOT).equals(nickname.toLowerCase(Locale.ROOT))) {
            throw new FaceitNotFoundException("simulated: unknown nickname");
        }
        return player();
    }

    @Override
    public Player player(String playerId) {
        if (!PLAYER_ID.equals(playerId)) {
            throw new FaceitNotFoundException("simulated: unknown player");
        }
        return player();
    }

    private static Player player() {
        return new Player(PLAYER_ID, NICKNAME, "https://example.invalid/faceit/catapult.png", "fr",
                "https://www.faceit.com/{lang}/players/" + NICKNAME, new GameProfile(8, 1850, "EU"));
    }

    @Override
    public Lifetime lifetime(String playerId, String game) {
        player(playerId);
        return new Lifetime(420, 231, 55, 1.12, 48, 2, 9, List.of(true, true, false, true, false));
    }

    @Override
    public Optional<LastMatch> lastMatch(String playerId, String game) {
        player(playerId);
        return Optional.of(new LastMatch("1-00000000-0000-4000-8000-000000000001", "de_mirage", "13 / 9", true, 21,
                15, 1.4, Instant.now().minusSeconds(1800)));
    }
}
