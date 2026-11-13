package fr.enimaloc.catapult.bgs.client;

import bgs.protocol.account.v2.GameAccountHandleOuterClass.GameAccountHandle;

import fr.enimaloc.catapult.bgs.proto.Fourcc;

import java.util.List;
import java.util.function.Predicate;

/** Picks the game account to select before presence works (spec 5.1). */
public final class GameAccountSelector {

    private static final int LAUNCHER = Fourcc.toId("App");
    /** Europe: the region of eu.actual.battle.net. Region 1 (US) is refused there. */
    private static final int DEFAULT_REGION = 2;

    private GameAccountSelector() {
    }

    /**
     * The first candidate matching, in order: title {@code App} in the connected region, the connected region, title
     * {@code App}, then any candidate. A connected region of 0 (unknown) counts as 2 (EU).
     *
     * @return the chosen handle, or null when there is no candidate
     */
    public static GameAccountHandle select(List<GameAccountHandle> candidates, int connectedRegion) {
        int region = connectedRegion == 0 ? DEFAULT_REGION : connectedRegion;
        List<Predicate<GameAccountHandle>> rules = List.of(
                h -> h.getTitleId() == LAUNCHER && h.getRegion() == region,
                h -> h.getRegion() == region,
                h -> h.getTitleId() == LAUNCHER,
                h -> true);
        for (Predicate<GameAccountHandle> rule : rules) {
            for (GameAccountHandle candidate : candidates) {
                if (rule.test(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }
}
