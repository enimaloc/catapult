package fr.enimaloc.catapult.service.enrichment.hltb;

import java.util.Optional;

/** Looks a game up on HowLongToBeat, which has no public API. */
public interface HltbScraper {

    /**
     * The time to beat of the game best matching {@code name} (origin {@link TimeToBeat.Origin#HLTB}, with its
     * page URL); empty when HowLongToBeat has no close enough match.
     *
     * @throws UnavailableException when the site could not be asked (paused after repeated failures, error, or an
     *                              answer in an unexpected shape)
     */
    Optional<TimeToBeat> search(String name);

    /** HowLongToBeat could not answer; nothing is known about the game, it must not be cached as absent. */
    class UnavailableException extends RuntimeException {
        public UnavailableException(String message) {
            super(message);
        }

        public UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
