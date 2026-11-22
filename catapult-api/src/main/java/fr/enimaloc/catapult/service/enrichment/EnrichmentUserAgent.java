package fr.enimaloc.catapult.service.enrichment;

/**
 * The User-Agent every outbound enrichment request carries: who we are and where to reach us, never a browser's.
 * Upstreams that rate-limit or block by agent (speedrun.com, PCGamingWiki, Xbox Live...) can identify and contact us.
 */
public final class EnrichmentUserAgent {

    /** The public site, used where no configured base URL is at hand. */
    public static final String SITE = "https://catapult.enimaloc.fr";

    /** {@code Catapult/1.0 (+https://catapult.enimaloc.fr)}. */
    public static final String VALUE = of(SITE);

    private EnrichmentUserAgent() {
    }

    /** The agent pointing at {@code baseUrl} (the configured {@code app.base-url}). */
    public static String of(String baseUrl) {
        return "Catapult/1.0 (+" + baseUrl + ")";
    }
}
