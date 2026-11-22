package fr.enimaloc.catapult.service.enrichment.blizzard;

import java.util.List;
import java.util.Map;

/** A StarCraft II profile; {@code ladder} is empty unless the detail was asked for. */
public record Sc2Profile(String displayName, String clanName, String clanTag, String portrait, int swarmLevel,
                         int achievementPoints, Map<String, Mode> modes, List<Sc2LadderEntry> ladder) {

    public Sc2Profile {
        modes = Map.copyOf(modes);
        ladder = List.copyOf(ladder);
    }

    /** The season snapshot of one mode ({@code 1v1}, {@code 2v2}, …); rank and league are null when unranked. */
    public record Mode(Integer rank, String leagueName, int totalGames, int totalWins) {
    }
}
