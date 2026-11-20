package fr.enimaloc.catapult.bgs.core;

/**
 * Observes the RPC exchanges of a session, for debugging. {@link #wants()} is asked when a frame is sent or
 * received: when it answers false nothing is kept for that exchange. {@link #onExchange} runs on the session
 * loop, so it must be quick; whatever it throws is logged and dropped.
 */
public interface WireTap {

    /** Whether the exchange starting now should be observed; called on the session loop for every frame. */
    boolean wants();

    void onExchange(WireExchange exchange);
}
