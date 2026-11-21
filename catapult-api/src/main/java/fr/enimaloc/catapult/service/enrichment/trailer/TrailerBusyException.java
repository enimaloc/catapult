package fr.enimaloc.catapult.service.enrichment.trailer;

import java.time.Duration;

/** Every ffmpeg slot of the instance is taken: the trailer can be asked again after {@link #retryAfter()}. */
public class TrailerBusyException extends RuntimeException {

    private final Duration retryAfter;

    public TrailerBusyException(Duration retryAfter) {
        super("Every trailer slot is busy, retry after " + retryAfter);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
