package fr.enimaloc.catapult.common.dto.premium;

import java.time.LocalDate;

/**
 * An account's premium status. {@code premium} is false once the end date has passed; {@code until}
 * is the last premium day (null: no end); {@code customBindingLimit} is null when unlimited.
 */
public record PremiumStatusDto(boolean premium, LocalDate until, int customBindingCount, Integer customBindingLimit,
                               PremiumOfferDto offer) {
}
