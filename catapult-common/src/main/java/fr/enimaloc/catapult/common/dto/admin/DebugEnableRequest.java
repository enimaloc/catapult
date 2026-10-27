package fr.enimaloc.catapult.common.dto.admin;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Switching a member's debug mode on; {@code revealSecrets} false masks the secrets in what is recorded, and
 * {@code replayRate} is the share of the member's pages the browser replays whole, from 0 to 1.
 * A missing (or null) field reads as its default: secrets masked, every page replayed.
 */
public record DebugEnableRequest(boolean revealSecrets, double replayRate) {
    public static final double DEFAULT_REPLAY_RATE = 1.0;

    public DebugEnableRequest {
        if (!(replayRate >= 0 && replayRate <= 1)) {
            throw new IllegalArgumentException("replayRate must be between 0 and 1");
        }
    }

    @JsonCreator
    static DebugEnableRequest read(@JsonProperty("revealSecrets") Boolean revealSecrets,
                                   @JsonProperty("replayRate") Double replayRate) {
        return new DebugEnableRequest(Boolean.TRUE.equals(revealSecrets),
                replayRate == null ? DEFAULT_REPLAY_RATE : replayRate);
    }
}
