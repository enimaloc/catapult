package fr.enimaloc.catapult.service.enrichment.blizzard;

import java.util.List;

/** A Diablo III profile (per region). The played-time proportions are not exposed: they are not hours. */
public record D3Profile(String battleTag, int paragonLevel, int paragonLevelSeason, int paragonLevelHardcore,
                        String guildName, long monstersKilled, long elitesKilled, Long lastHeroPlayed,
                        List<D3Hero> heroes) {

    public D3Profile {
        heroes = List.copyOf(heroes);
    }
}
