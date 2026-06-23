package fr.enimaloc.catapult.web.ws.auth;

/**
 * Thread-local holder for the JWT bound to the WebSocket session currently
 * being serviced. Set by the WS dispatch path before invoking any handler that
 * may reach upstream REST endpoints via {@code ApiClient}, cleared in a
 * {@code finally} block.
 *
 * <p>This lets {@code ApiClient.currentJwt()} stay session-aware without
 * depending on {@code RequestContextHolder} (which is empty on WS threads).</p>
 */
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
