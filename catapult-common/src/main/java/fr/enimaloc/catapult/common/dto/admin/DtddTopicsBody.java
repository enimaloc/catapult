package fr.enimaloc.catapult.common.dto.admin;

import java.util.Map;
import java.util.Set;

/**
 * A TW's DTDD topics, replacing them all; {@code minSeverities} the least severe level of each that turns the TW on,
 * by topic (a topic absent, or null: any level).
 */
public record DtddTopicsBody(Set<String> topics, Map<String, String> minSeverities) {

    /** Topics without thresholds. */
    public DtddTopicsBody(Set<String> topics) {
        this(topics, null);
    }
}
