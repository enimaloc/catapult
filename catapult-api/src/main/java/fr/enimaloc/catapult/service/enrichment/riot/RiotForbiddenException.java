package fr.enimaloc.catapult.service.enrichment.riot;

/**
 * Riot does not let the key use these routes (403: a League of Legends product key on the Teamfight Tactics ones,
 * say). Only that game is affected: the key stays in use for the others.
 */
public class RiotForbiddenException extends RuntimeException {

    public RiotForbiddenException(String message) {
        super(message);
    }
}
