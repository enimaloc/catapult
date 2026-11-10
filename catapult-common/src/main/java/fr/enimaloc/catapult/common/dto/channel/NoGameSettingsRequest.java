package fr.enimaloc.catapult.common.dto.channel;

import java.util.Set;

/** {@code title}: the stream title sent with it; null leaves it as it is (a card without the field), blank clears it. */
public record NoGameSettingsRequest(
        String twitchGameId, String twitchGameName, Set<String> ccls,
        boolean applyOnStreamStart, boolean applyOnNoGame, boolean applyOnStreamEnd, String title) {

    /** A request leaving the title as it is. */
    public NoGameSettingsRequest(String twitchGameId, String twitchGameName, Set<String> ccls,
                                 boolean applyOnStreamStart, boolean applyOnNoGame, boolean applyOnStreamEnd) {
        this(twitchGameId, twitchGameName, ccls, applyOnStreamStart, applyOnNoGame, applyOnStreamEnd, null);
    }
}
