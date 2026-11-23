package fr.enimaloc.catapult.service.enrichment.riot;

/**
 * The stored PUUID cannot be used any more: the key that resolved it left the pool (Riot's ids are encrypted per key
 * project), or Riot answered 400 "Exception decrypting" for it. Not an outage: the PUUID is to be resolved again from
 * the Riot ID.
 */
public class RiotStaleIdException extends RuntimeException {

    public RiotStaleIdException(String message) {
        super(message);
    }
}
