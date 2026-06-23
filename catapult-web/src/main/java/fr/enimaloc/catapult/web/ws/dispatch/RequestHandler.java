package fr.enimaloc.catapult.web.ws.dispatch;

import fr.enimaloc.catapult.web.ws.WsSession;

/**
 * A typed action backing a {@code request} or {@code command} WS frame.
 * Each handler advertises its action name plus its auth requirements and is
 * registered by {@link WsRequestDispatcher} at startup.
 */
public interface RequestHandler {

    /** Wire-level action name (e.g. {@code "search.twitch.categories"}). */
    String action();

    /** When true, the WS session must have called {@code auth} successfully. */
    boolean requiresAuth();

    /** When true, on top of {@link #requiresAuth()} the session must carry {@code ROLE_ADMIN}. */
    boolean requiresAdmin();

    /**
     * Invoked by the dispatcher. {@code params} is the deserialised {@code params}
     * field of the incoming frame (usually a {@link java.util.Map} produced by
     * Jackson; convert via {@link tools.jackson.databind.ObjectMapper#convertValue}).
     */
    Object handle(WsSession session, Object params) throws Exception;
}
