package fr.enimaloc.catapult.service.ankama.chat;

/**
 * Observes the data frames of an {@link AnkamaChatClient} connection, for debugging: our requests with their
 * response, and the events the server pushes. The handshake, ping/pong and heartbeats never reach it.
 * {@link #wants()} is asked when a request is sent or an event received: when it answers false nothing is kept for
 * that frame. {@link #onExchange} runs on the caller's or the reader thread, so it must be quick; whatever it throws
 * is logged and dropped.
 */
public interface ChatWireTap {

    /** Whether the exchange starting now should be observed; called for every request sent and event received. */
    boolean wants();

    void onExchange(ChatWireExchange exchange);
}
