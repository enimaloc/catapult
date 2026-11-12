package fr.enimaloc.catapult.common.dto.channel;

import java.util.List;

/**
 * Why a binding has its TWs, as the detection tells it now. {@code tws}: each TW the binding has, or the detection
 * suggests ({@code active} false: the streamer turned it off), with where it comes from ({@code origin} DETECTED:
 * its {@code sources}; STREAMER: the streamer's own choice; ADMIN: an admin's game mapping; NO_SOURCE: nothing
 * suggests it any more, the next detection drops it). {@code keptOff}: the DTDD topics a TW's least severe level kept
 * it off for. {@code streamerChose}: the streamer edited the binding's TWs.
 */
public record TwDetailsDto(boolean streamerChose, List<Tw> tws, List<Source> keptOff) {

    public record Tw(String id, String label, boolean active, String origin, List<Source> sources) {}

    /**
     * One signal for the TW {@code twId} ({@code twLabel} its label): {@code type} IGDB_DESCRIPTOR, STEAM_CONTENT,
     * STEAM_KEYWORD or DTDD_TOPIC, {@code value} the descriptor's ID, the keyword or the topic; {@code severity} a
     * DTDD topic's verdict, null for the rest.
     */
    public record Source(String twId, String twLabel, String type, String value, Severity severity) {}

    /**
     * A DTDD topic's severity against its TW's least severe level: {@code reason} NO_THRESHOLD, NOT_CACHED, NO_LEVELS,
     * UNKNOWN_LEVEL, TOO_FEW_VOTES, VERIFIED, VOTES (the TW on), BELOW_VERIFIED, BELOW_VOTES (kept off);
     * {@code level} the reviewers' level, else the members' most voted; {@code percentAtOrAbove} the votes' share at
     * or above {@code minSeverity}.
     */
    public record Severity(String reason, String minSeverity, String level, int votes, int percentAtOrAbove) {}
}
