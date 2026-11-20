package fr.enimaloc.catapult.service.ankama.chat;

import java.time.Instant;

/**
 * One chat exchange seen by a {@link ChatWireTap}: our request and its response, or an event the server pushed.
 *
 * @param initiatedLocally true for a request we sent, false for a pushed event
 * @param at               when the request was sent, or the event received
 * @param durationNanos    until the response (or the failure) for a request; 0 for an event
 * @param request          our {@link ChatFrames.Request}, or the pushed {@link ChatFrames.Event}
 * @param requestBytes     the encoded transport frame of {@code request}, as written or read
 * @param response         the response to our request; null for an event or a request that got none
 * @param responseBytes    the encoded transport frame of {@code response}; null when there is none
 * @param error            why the request got no response (timeout, connection closed); null otherwise
 */
public record ChatWireExchange(boolean initiatedLocally, Instant at, long durationNanos, ChatFrames.Frame request,
                               byte[] requestBytes, ChatFrames.Response response, byte[] responseBytes,
                               String error) {
}
