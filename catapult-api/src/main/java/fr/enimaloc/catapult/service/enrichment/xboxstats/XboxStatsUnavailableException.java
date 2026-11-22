package fr.enimaloc.catapult.service.enrichment.xboxstats;

/** Xbox Live cannot answer now (no session, no rate budget, 429, 503, a redirect, an unreadable body): never "absent". */
public class XboxStatsUnavailableException extends RuntimeException {

    public XboxStatsUnavailableException(String message) {
        super(message);
    }
}
