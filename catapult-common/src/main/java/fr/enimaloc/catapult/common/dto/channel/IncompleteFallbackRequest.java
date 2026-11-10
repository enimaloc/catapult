package fr.enimaloc.catapult.common.dto.channel;

import java.util.Set;

/** {@code title}: the stream title sent with it; null leaves it as it is (a card without the field), blank clears it. */
public record IncompleteFallbackRequest(String twitchGameId, String twitchGameName, Set<String> ccls, String title) {

    /** A request leaving the title as it is. */
    public IncompleteFallbackRequest(String twitchGameId, String twitchGameName, Set<String> ccls) {
        this(twitchGameId, twitchGameName, ccls, null);
    }
}
