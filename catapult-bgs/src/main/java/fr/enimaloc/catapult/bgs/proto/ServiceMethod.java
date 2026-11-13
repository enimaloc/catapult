package fr.enimaloc.catapult.bgs.proto;

import com.google.protobuf.Message;
import com.google.protobuf.Parser;

/**
 * One BGS RPC method: the service hash and method id that go in the frame header, and the parsers of
 * its request and response bodies. {@code responseParser} is null for a NO_RESPONSE method.
 */
public record ServiceMethod<Q extends Message, R extends Message>(
        String service, int serviceHash, int methodId, Parser<Q> requestParser, Parser<R> responseParser) {

    public boolean hasResponse() {
        return responseParser != null;
    }
}
