package fr.enimaloc.catapult.bgs.core;

import bgs.protocol.RpcProto.Header;
import com.google.protobuf.ByteString;
import fr.enimaloc.catapult.bgs.proto.ServiceMethod;

import java.time.Instant;

/**
 * One RPC exchange seen by a {@link WireTap}: our call and its reply, or a server call and our ack.
 *
 * @param initiatedLocally true for a call we sent, false for a call the server sent us
 * @param at               when the request was sent, or the server call received
 * @param durationNanos    until the reply (or the failure) for a call of ours awaiting one; otherwise the time
 *                         taken to write our ack, 0 when there is none
 * @param requestHeader    the header of the call
 * @param requestBody      the raw body of the call
 * @param replyHeader      the header of the reply (its status included) or of our ack; null when there is none
 * @param replyBody        the raw body of the reply or of our ack; null when there is none
 * @param method           the method of the call when known; null for raw calls and unknown server calls
 * @param error            why the call failed (non-zero status, session closed); null otherwise
 */
public record WireExchange(boolean initiatedLocally, Instant at, long durationNanos, Header requestHeader,
                           ByteString requestBody, Header replyHeader, ByteString replyBody,
                           ServiceMethod<?, ?> method, String error) {
}
