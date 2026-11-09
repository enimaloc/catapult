package fr.enimaloc.catapult.getter;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

/**
 * A getter's provider did not answer in time: its silence says nothing about what the user is playing,
 * so the detection cycle drops its answer instead of reading it as "no game".
 */
public class DetectionTimeoutException extends RuntimeException {

    public DetectionTimeoutException(String provider, Throwable cause) {
        super(provider + " did not answer in time", cause);
    }

    /** Whether {@code e}, or anything it wraps, is a timeout (a future's, a socket's or the HTTP client's). */
    public static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof DetectionTimeoutException || t instanceof TimeoutException
                || t instanceof SocketTimeoutException || t instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
