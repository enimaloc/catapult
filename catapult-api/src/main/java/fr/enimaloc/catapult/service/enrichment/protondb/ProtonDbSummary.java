package fr.enimaloc.catapult.service.enrichment.protondb;

/**
 * ProtonDB's community compatibility summary for a Steam game.
 *
 * @param tier             overall tier (platinum, gold, silver, bronze, borked, pending)
 * @param trendingTier     tier of the most recent reports
 * @param bestReportedTier best tier any report reached
 * @param confidence       how much the tier can be trusted (strong, good, moderate, low, inadequate)
 * @param score            ProtonDB's numeric score, 0 to 1
 * @param total            number of reports behind the summary
 * @param url              the game's ProtonDB page
 */
public record ProtonDbSummary(String tier, String trendingTier, String bestReportedTier, String confidence,
                              double score, int total, String url) {
}
