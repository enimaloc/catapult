package fr.enimaloc.catapult.service.enrichment.opencritic;

/**
 * The OpenCritic critic score of a game. {@code topCriticScore} and {@code percentRecommended} are rounded to whole
 * numbers and null when OpenCritic has none yet (-1 or missing). {@code numReviews} counts the top critics' reviews
 * (OpenCritic's {@code numTopCriticReviews}, its {@code numReviews} only when that is missing).
 */
public record OpenCriticGame(long id, String name, Integer topCriticScore, Integer percentRecommended, String tier,
                             int numReviews, String url) {
}
