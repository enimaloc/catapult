package fr.enimaloc.catapult.service.battlenet;

import java.util.Map;

/** Display names of the Blizzard games, by the fourcc BGS reports in the presence. */
public final class BattleNetTitles {

    private static final Map<String, String> NAMES = Map.of(
            "WTCG", "Hearthstone",
            "Pro", "Overwatch 2",
            "WoW", "World of Warcraft",
            "Fen", "Diablo IV",
            "D3", "Diablo III",
            "OSI", "Diablo II: Resurrected",
            "S2", "StarCraft II",
            "Hero", "Heroes of the Storm",
            "W3", "Warcraft III");

    private BattleNetTitles() {
    }

    /** The game's name, or the fourcc itself for a game missing from the table. */
    public static String displayName(String fourcc) {
        return NAMES.getOrDefault(fourcc, fourcc);
    }
}
