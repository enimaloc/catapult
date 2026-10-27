package fr.enimaloc.catapult.common.dto.admin;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Switching a member's debug mode on; {@code revealSecrets} false masks the secrets in what is recorded.
 * A missing (or null) field reads as false, the masked default.
 */
public record DebugEnableRequest(boolean revealSecrets) {
    @JsonCreator
    static DebugEnableRequest read(@JsonProperty("revealSecrets") Boolean revealSecrets) {
        return new DebugEnableRequest(Boolean.TRUE.equals(revealSecrets));
    }
}
