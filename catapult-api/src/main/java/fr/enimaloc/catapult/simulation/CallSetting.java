package fr.enimaloc.catapult.simulation;

/**
 * How one call behaves for one account (or globally): the mode, the HTTP status an {@link Mode#ERROR} answers with,
 * and a latency added before the call, whatever the mode.
 */
public record CallSetting(Mode mode, int httpStatus, int latencyMs) {

    public enum Mode {
        /** The real client is called. */
        REAL,
        /** A plausible answer is made up. */
        OK,
        /** The call fails with {@code httpStatus}. */
        ERROR,
        /** The call fails the way a socket timeout does. */
        TIMEOUT
    }

    public static CallSetting ok() {
        return new CallSetting(Mode.OK, 0, 0);
    }

    public static CallSetting real() {
        return new CallSetting(Mode.REAL, 0, 0);
    }

    public static CallSetting error(int httpStatus) {
        return new CallSetting(Mode.ERROR, httpStatus, 0);
    }

    public static CallSetting timeout() {
        return new CallSetting(Mode.TIMEOUT, 0, 0);
    }

    public CallSetting withLatency(int latencyMs) {
        return new CallSetting(mode, httpStatus, Math.max(0, latencyMs));
    }
}
