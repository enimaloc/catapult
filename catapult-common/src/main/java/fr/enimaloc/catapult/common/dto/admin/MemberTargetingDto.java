package fr.enimaloc.catapult.common.dto.admin;

import java.util.List;

/** A member's custom flags and group keys, as catapult-api's {@code MemberTargeting}; a flag's value may be null. */
public record MemberTargetingDto(List<Flag> flags, List<String> groupKeys) {
    public record Flag(String key, String value) {}
}
