package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.debug.ClientExchangeBatch;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Optional;

/**
 * Reads a batch of the browser's OBS and Twitchat exchanges (debug-capture.js) before it is relayed to catapult-api.
 * The body is bounded before anything is kept: one announced or read over {@link #MAX_BYTES} is no batch, without
 * the rest of it being read.
 */
final class ClientDebugBatches {
    /** catapult-api's own bound on a batch. */
    static final int MAX_BYTES = 256 * 1024;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ClientDebugBatches() {}

    /** The batch in {@code request}'s body, as captured by {@code source} whatever the body says; empty for none. */
    static Optional<ClientExchangeBatch> read(HttpServletRequest request, String source) {
        if (request.getContentLengthLong() > MAX_BYTES) {
            return Optional.empty();
        }
        try {
            // One byte over the bound is enough to know the body is over it
            byte[] body = request.getInputStream().readNBytes(MAX_BYTES + 1);
            if (body.length > MAX_BYTES) {
                return Optional.empty();
            }
            ClientExchangeBatch batch = JSON.readValue(body, ClientExchangeBatch.class);
            return batch == null ? Optional.empty()
                    : Optional.of(new ClientExchangeBatch(source, batch.sentAt(), batch.entries()));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
