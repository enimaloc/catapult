package fr.enimaloc.catapult.common.dto.premium;

import java.time.LocalDate;

/** {@code until} is the last premium day; null grants premium with no end date. */
public record GrantPremiumRequest(LocalDate until) {
}
