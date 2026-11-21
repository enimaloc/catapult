package fr.enimaloc.catapult.service.enrichment.steamgriddb;

/** SteamGridDB cannot be queried right now (disabled, empty key pool or no usable key): not "the game is absent". */
public class SteamGridDbUnavailableException extends RuntimeException {

    public SteamGridDbUnavailableException(String message) {
        super(message);
    }
}
