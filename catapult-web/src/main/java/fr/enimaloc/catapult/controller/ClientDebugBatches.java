package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.debug.ClientExchangeBatch;
import fr.enimaloc.catapult.common.dto.debug.ClientExchangeEntry;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads a batch of the browser's OBS and Twitchat exchanges (debug-capture.js) before it is relayed to catapult-api.
 * The body is bounded before anything is kept: one announced or read over {@link #MAX_BYTES} is no batch, without
 * the rest of it being read. As catapult-api does, an entry that does not read as one is left out, not the batch.
 */
final class ClientDebugBatches {
    /** catapult-api's own bound on a batch. */
    static final int MAX_BYTES = 256 * 1024;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ClientDebugBatches() {}

    /**
     * The batch in {@code request}'s body, as captured by {@code source} whatever the body says, its unreadable
     * entries left out (an unreadable time is no time); empty when the body is no batch.
     */
    static Optional<ClientExchangeBatch> read(HttpServletRequest request, String source) {
        if (request.getContentLengthLong() > MAX_BYTES) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            // One byte over the bound is enough to know the body is over it
            byte[] body = request.getInputStream().readNBytes(MAX_BYTES + 1);
            if (body.length > MAX_BYTES) {
                return Optional.empty();
            }
            root = JSON.readTree(body);
        } catch (IOException | JacksonException e) {
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        JsonNode items = root.path("entries");
        if (!items.isMissingNode() && !items.isNull() && !items.isArray()) {
            return Optional.empty();
        }
        List<ClientExchangeEntry> entries = new ArrayList<>();
        for (JsonNode item : items) {
            ClientExchangeEntry entry = item.isObject() ? convert(item, ClientExchangeEntry.class) : null;
            if (entry != null) {
                entries.add(entry);
            }
        }
        return Optional.of(new ClientExchangeBatch(source, convert(root.path("sentAt"), Instant.class), entries));
    }

    /** {@code node} read as {@code type}, or null when it is missing or does not read as one. */
    private static <T> T convert(JsonNode node, Class<T> type) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        try {
            return JSON.treeToValue(node, type);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
