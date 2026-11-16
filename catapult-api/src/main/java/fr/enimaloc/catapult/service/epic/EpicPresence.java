package fr.enimaloc.catapult.service.epic;

import tools.jackson.databind.JsonNode;

import java.util.Optional;

/**
 * Turns an Epic Connect {@code presence.v1.UPDATE} payload into the game being played, in three steps: the first
 * non-launcher entry with a product id (an EOS game); else the first non-launcher entry naming a non-EOS product
 * ({@code ns:<namespace>}); else, as the Epic launcher's social overlay does, the first connection of the launcher's
 * own entry that reports a {@code running_app_id} ({@code app:<running_app_id>}, an opaque string).
 */
public final class EpicPresence {

    private static final String LAUNCHER_NAMESPACE = "_";

    private EpicPresence() {
    }

    /** The game the payload reports, or empty when the account is offline or only in the launcher. */
    public static Optional<EpicGame> currentGame(JsonNode payload) {
        if (payload == null || "offline".equals(payload.path("status").asString(""))) {
            return Optional.empty();
        }
        JsonNode perNs = payload.path("perNs");
        if (!perNs.isArray() || perNs.isEmpty()) {
            return Optional.empty();
        }
        for (JsonNode entry : perNs) {
            if (isGameEntry(entry) && !entry.path("statusManual").asBoolean(false)
                    && !entry.path("productId").asString("").isEmpty()) {
                String productId = entry.path("productId").asString();
                String name = entry.path("props").path("EOS_ProductName").asString("");
                return Optional.of(new EpicGame(productId, name.isEmpty() ? productId : name, true));
            }
        }
        for (JsonNode entry : perNs) {
            if (isGameEntry(entry)) {
                String name = entry.path("props").path("X_NonEOS_ProductName").asString("");
                if (!name.isEmpty()) {
                    return Optional.of(new EpicGame("ns:" + entry.path("ns").asString(), name, false));
                }
            }
        }
        return launcherRunningApp(perNs);
    }

    private static Optional<EpicGame> launcherRunningApp(JsonNode perNs) {
        for (JsonNode entry : perNs) {
            if (!LAUNCHER_NAMESPACE.equals(entry.path("ns").asString("")) || entry.path("statusManual").asBoolean(false)) {
                continue;
            }
            for (JsonNode conn : entry.path("conns")) {
                String appId = conn.path("props").path("running_app_id").asString("");
                if (!appId.isBlank()) {
                    String name = conn.path("props").path("running_app_name").asString("");
                    return Optional.of(new EpicGame("app:" + appId, name.isBlank() ? appId : name, false));
                }
            }
        }
        return Optional.empty();
    }

    /** The Epic account id the payload is about, or an empty string. */
    public static String accountId(JsonNode payload) {
        return payload == null ? "" : payload.path("accountId").asString("");
    }

    private static boolean isGameEntry(JsonNode entry) {
        String ns = entry.path("ns").asString("");
        return !ns.isEmpty() && !LAUNCHER_NAMESPACE.equals(ns);
    }
}
