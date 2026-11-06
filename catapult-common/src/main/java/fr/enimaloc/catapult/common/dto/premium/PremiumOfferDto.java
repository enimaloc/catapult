package fr.enimaloc.catapult.common.dto.premium;

/**
 * What each tier gets: seconds between two polls of the account, in normal and in degraded mode,
 * and the custom bindings a free account may have (premium has no limit).
 */
public record PremiumOfferDto(int freeIntervalSeconds, int premiumIntervalSeconds, int degradedFreeIntervalSeconds,
                              int degradedPremiumIntervalSeconds, int freeCustomBindingLimit) {
}
