package fr.enimaloc.catapult.web.ws.dispatch;

import fr.enimaloc.catapult.web.ws.WsSession;

/**
 * Hook called immediately after a successful {@code sub.ok} acknowledgement.
 * Implementations may push an initial payload (snapshot, hydration event, etc.)
 * to the subscribing session without the client having to send a separate request.
 *
 * <p>Spring collects all {@code SubscriptionInitializer} beans and injects them
 * into {@link fr.enimaloc.catapult.web.ws.WsHub}; the hub dispatches by
 * {@link #publicChannel()} so each hook only fires for its own channel.</p>
 *
 * <p>Implementations must be idempotent and tolerant of failures — an exception
 * is logged and swallowed by the hub so that the subscription itself is never
 * rolled back because a snapshot push failed.</p>
 */
public interface SubscriptionInitializer {

    /** Public channel name this hook applies to (e.g. {@code "notifications.user"}). */
    String publicChannel();

    /**
     * Called after the hub has sent {@code sub.ok} for {@link #publicChannel()}.
     *
     * @param session the authenticated (or anonymous) session that just subscribed
     */
    void onSubscribe(WsSession session);
}
