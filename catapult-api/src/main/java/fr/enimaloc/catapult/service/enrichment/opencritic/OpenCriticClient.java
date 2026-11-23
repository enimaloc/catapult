package fr.enimaloc.catapult.service.enrichment.opencritic;

import java.util.List;
import java.util.Optional;

/** Reads critic scores from the OpenCritic API distributed on RapidAPI, authenticated with the shared key pool. */
public interface OpenCriticClient {

    /** A search result. */
    record Hit(long id, String name) {
    }

    /** Whether a request could be sent at all (the source is enabled and its key pool is not empty). */
    boolean isConfigured();

    /** Games whose name looks like {@code name}, best first; transport failures and 5xx propagate. */
    List<Hit> search(String name);

    /** The game {@code id}; empty when OpenCritic does not know it (404) or the answer is unreadable. */
    Optional<OpenCriticGame> game(long id);
}
