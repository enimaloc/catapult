package fr.enimaloc.catapult.bgs.core;

/** A BGS call answered with a non-zero status (for example 7 = ERROR_INVALID_ARGS). */
public final class BgsRpcException extends RuntimeException {

    private final int status;

    public BgsRpcException(int status) {
        super("BGS call failed with status " + status);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
