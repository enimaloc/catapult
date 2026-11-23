package fr.enimaloc.catapult.service.enrichment.speedrun;

/** speedrun.com cannot be queried right now (rate limited, down or refusing): not "nothing there". */
public class SpeedrunUnavailableException extends RuntimeException {

    public SpeedrunUnavailableException(String message) {
        super(message);
    }
}
