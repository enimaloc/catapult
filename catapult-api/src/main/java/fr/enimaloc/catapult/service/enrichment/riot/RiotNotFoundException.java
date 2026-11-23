package fr.enimaloc.catapult.service.enrichment.riot;

/** Riot answered its JSON 404 ({@code {"status":{"status_code":404}}}): the account or summoner does not exist. */
public class RiotNotFoundException extends RuntimeException {

    public RiotNotFoundException(String message) {
        super(message);
    }
}
