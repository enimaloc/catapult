package fr.enimaloc.catapult.simulation.external;

import fr.enimaloc.catapult.service.enrichment.bungie.BungieClient;
import fr.enimaloc.catapult.service.enrichment.bungie.BungieData;
import fr.enimaloc.catapult.service.enrichment.bungie.BungieExceptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Bungie in the mock profile: any refresh token is renewed (for the Bungie.net account {@link #MEMBERSHIP_ID}), which
 * has one Steam Destiny 2 membership ({@link #DESTINY_ID}) with two characters, the Hunter in a strike; the
 * membership {@code 0} does not exist. The season is the 28th. The Bungie source still needs an application set in
 * the admin Keys tab (any well-formed values).
 */
@Primary
@Component
@ConditionalOnBooleanProperty(name = "catapult.simulation.enabled")
public class SimulatedBungieClient implements BungieClient {

    public static final String MEMBERSHIP_ID = "4611686018400000001";
    public static final String DESTINY_ID = "4611686018467000001";
    private static final Instant WHEN = Instant.parse("2026-11-20T18:00:00Z");

    @Override
    public BungieData.TokenGrant refresh(String refreshToken) {
        return new BungieData.TokenGrant("simulated-access", Instant.now().plus(Duration.ofHours(1)),
                "simulated-refresh", Instant.now().plus(Duration.ofDays(90)), MEMBERSHIP_ID);
    }

    @Override
    public BungieData.CurrentUser currentUser(String accessToken) {
        return new BungieData.CurrentUser(MEMBERSHIP_ID, "Simulated#0042",
                List.of(new BungieData.DestinyMembership(3, DESTINY_ID, 0)), null);
    }

    @Override
    public BungieData.Profile profile(int membershipType, String membershipId, String accessToken) {
        if ("0".equals(membershipId)) {
            throw new BungieExceptions.NotFound();
        }
        return new BungieData.Profile(WHEN, 7, 2_758_726_560L, List.of(
                new BungieData.Character("2305843009300000001", 1, 2010, 61_440, WHEN, "/common/emblem/hunter.jpg"),
                new BungieData.Character("2305843009300000002", 0, 1990, 12_000, WHEN.minus(Duration.ofDays(3)),
                        null)),
                new BungieData.Activity("2305843009300000001", 1_111L, 2_222L, WHEN), false, false);
    }

    @Override
    public Integer triumphScore(int membershipType, String membershipId, String accessToken) {
        return 18_250;
    }

    @Override
    public BungieData.Definition definition(String entity, long hash, String locale) {
        return new BungieData.Definition(hash == 2_222L ? "Vanguard Ops" : "The Simulated Strike", null);
    }

    @Override
    public long currentSeasonHash() {
        return 2_758_726_560L;
    }

    @Override
    public BungieData.Season season(long hash, String locale) {
        return new BungieData.Season(28, "Monument of Triumph", Instant.parse("2025-12-02T17:00:00Z"),
                Instant.parse("2099-01-01T17:00:00Z"));
    }
}
