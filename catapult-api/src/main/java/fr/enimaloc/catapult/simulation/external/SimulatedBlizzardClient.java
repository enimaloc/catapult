package fr.enimaloc.catapult.simulation.external;

import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardClient;
import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardNotFoundException;
import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardRegion;
import fr.enimaloc.catapult.service.enrichment.blizzard.D3Hero;
import fr.enimaloc.catapult.service.enrichment.blizzard.D3Profile;
import fr.enimaloc.catapult.service.enrichment.blizzard.HearthstoneDeck;
import fr.enimaloc.catapult.service.enrichment.blizzard.HearthstoneSet;
import fr.enimaloc.catapult.service.enrichment.blizzard.Sc2LadderEntry;
import fr.enimaloc.catapult.service.enrichment.blizzard.Sc2Profile;
import fr.enimaloc.catapult.service.enrichment.blizzard.Sc2ProfileRef;
import fr.enimaloc.catapult.service.enrichment.blizzard.WowAchievements;
import fr.enimaloc.catapult.service.enrichment.blizzard.WowCharacter;
import fr.enimaloc.catapult.service.enrichment.blizzard.WowTokenPrice;
import fr.enimaloc.catapult.service.enrichment.blizzard.WowUserCharacter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Blizzard in the mock profile: two EU characters ({@code simulatedmage}, level 90, and {@code alt}, level 70;
 * the name {@code gone} does not exist), one StarCraft II profile, one Diablo III profile, a WoW Token price, one
 * Hearthstone set and one decodable deck. Any user token is accepted.
 */
@Primary
@Component
@ConditionalOnBooleanProperty(name = "catapult.simulation.enabled")
public class SimulatedBlizzardClient implements BlizzardClient {

    /** The one deck code the simulation decodes. */
    public static final String DECK_CODE = "AAECAZICBsOcA/0F7QKKB8cJ+AQBnw0A";

    private static final Instant WHEN = Instant.parse("2026-11-20T18:00:00Z");

    @Override
    public WowCharacter wowCharacter(BlizzardRegion region, String realmSlug, String name, String locale) {
        String lower = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        return switch (lower) {
            case "simulatedmage" -> new WowCharacter(1001, "Simulatedmage", realmSlug, "Simulated Realm", 90,
                    "Mage", "Frost", "Human", "Alliance", "Simulated Guild", 28_755, 640, 638, WHEN, 2_410.0, null);
            case "alt" -> new WowCharacter(1002, "Alt", realmSlug, "Simulated Realm", 70, "Rogue", "Outlaw",
                    "Orc", "Horde", null, 5_120, 410, 405, WHEN, null, null);
            default -> throw new BlizzardNotFoundException("no such character");
        };
    }

    @Override
    public WowAchievements wowAchievements(BlizzardRegion region, String realmSlug, String name, String locale) {
        WowCharacter character = wowCharacter(region, realmSlug, name, locale);
        return new WowAchievements(character.achievementPoints(), List.of(
                new WowAchievements.Recent("1", "Simulated Glory", WHEN),
                new WowAchievements.Recent("2", "Simulated Explorer", WHEN.minusSeconds(86_400))));
    }

    @Override
    public long userAccountId(String userAccessToken) {
        return 424_242L;
    }

    @Override
    public List<WowUserCharacter> wowUserCharacters(BlizzardRegion region, String userAccessToken, String locale) {
        return List.of(
                new WowUserCharacter(1001, "Simulatedmage", "simulated-realm", "Simulated Realm", "Mage",
                        "Alliance", 90),
                new WowUserCharacter(1002, "Alt", "simulated-realm", "Simulated Realm", "Rogue", "Horde", 70));
    }

    @Override
    public List<Sc2ProfileRef> sc2Profiles(long bnetAccountId) {
        return List.of(new Sc2ProfileRef(2, 1, 7_777_777L, "SimulatedZerg"));
    }

    @Override
    public Sc2Profile sc2Profile(BlizzardRegion host, Sc2ProfileRef ref, String locale, boolean detail) {
        return new Sc2Profile("SimulatedZerg", "Simulated Clan", "SIM", null, 1_200, 3_500,
                Map.of("1v1", new Sc2Profile.Mode(12, "Diamond", 40, 25)),
                detail ? List.of(new Sc2LadderEntry("1v1", "Diamond", "Simulated Division", 12, 25, 15))
                        : List.of());
    }

    @Override
    public D3Profile d3Profile(BlizzardRegion region, String battleTag, String locale) {
        return new D3Profile(battleTag, 1_633, 900, 120, "Simulated Guild", 9_876_543L, 123_456L, 7001L,
                List.of(new D3Hero(7001, "Simbarb", "barbarian", 70, 900, false, true, false, WHEN)));
    }

    @Override
    public WowTokenPrice wowToken(BlizzardRegion region) {
        return new WowTokenPrice(470_531L, WHEN);
    }

    @Override
    public List<HearthstoneSet> hearthstoneSets(String locale) {
        return List.of(new HearthstoneSet(1, "Simulated Expansion", "simulated-expansion", "expansion", 145));
    }

    @Override
    public HearthstoneDeck hearthstoneDeck(String code, String locale) {
        if (!DECK_CODE.equals(code)) {
            throw new BlizzardNotFoundException("no such deck");
        }
        return new HearthstoneDeck(DECK_CODE, "standard", "Jaina Proudmoore", "Mage", 30, List.of(
                new HearthstoneDeck.Card(1, "Simulated Bolt", 1, null),
                new HearthstoneDeck.Card(2, "Simulated Elemental", 4, null)));
    }

    @Override
    public int d3CurrentSeason(BlizzardRegion region) {
        return 34;
    }
}
