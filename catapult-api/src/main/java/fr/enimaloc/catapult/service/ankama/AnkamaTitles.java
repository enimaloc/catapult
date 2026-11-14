package fr.enimaloc.catapult.service.ankama;

import java.util.Map;
import java.util.Set;

/** Display names of the Ankama games, by the application id the launcher chat reports in the endpoints. */
public final class AnkamaTitles {

    /** The chat and the launcher themselves: endpoints that are never a game. */
    public static final Set<Integer> IGNORED = Set.of(99, 102);

    private static final Map<Integer, String> NAMES = Map.of(
            1, "Dofus",
            3, "Wakfu",
            17, "Krosmaga",
            22, "Waven");

    private AnkamaTitles() {
    }

    /** The game's name, or a generic one for an application id missing from the table. */
    public static String displayName(int applicationId) {
        return NAMES.getOrDefault(applicationId, "Ankama game " + applicationId);
    }
}
