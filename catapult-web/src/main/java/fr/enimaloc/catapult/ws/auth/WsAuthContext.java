package fr.enimaloc.catapult.ws.auth;

public final class WsAuthContext {

    private static final ThreadLocal<String> JWT = new ThreadLocal<>();

    private WsAuthContext() {
    }

    /** Bind {@code jwt} (may be {@code null}) to the current thread. */
    public static void set(String jwt) {
        if (jwt == null || jwt.isBlank()) {
            JWT.remove();
        } else {
            JWT.set(jwt);
        }
    }

    /** Returns the JWT bound to the current thread, or {@code null} if none. */
    public static String get() {
        return JWT.get();
    }

    /** Clears the binding for the current thread. */
    public static void clear() {
        JWT.remove();
    }
}
