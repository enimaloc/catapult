package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.common.dto.twitchat.TwitchatActionType;
import fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The canonical French copy for every Twitchat notification Catapult sends — this is exactly
 * what Catapult sent before event actions were configurable. The source of the pre-filled event
 * actions (see {@link TwitchatEventDefaults}).
 */
public final class TwitchatDefaultPayloads {

    public static final Map<TwitchatNotificationEventType, TwitchatDefaultPayload> DEFAULTS = Map.ofEntries(
            Map.entry(TwitchatNotificationEventType.CATEGORY_CHANGED_BY_CATAPULT, new TwitchatDefaultPayload(
                    "Catapult a changé la catégorie en {{gameName}}.", "message", "change", "Catapult",
                    orderedActions(
                            TwitchatActionType.REVERT_CATEGORY, new TwitchatActionDefault("Revert", "secondary"),
                            TwitchatActionType.DISABLE_BOT, new TwitchatActionDefault("Désactiver le bot", "alert")
                    ))),
            Map.entry(TwitchatNotificationEventType.CATEGORY_CHANGED_MANUALLY, new TwitchatDefaultPayload(
                    "Catégorie changée manuellement en {{gameName}}.", "message", "user", "Catapult",
                    orderedActions(
                            TwitchatActionType.BIND_GAME_CATEGORY,
                            new TwitchatActionDefault("Définir par défaut pour ce jeu", "primary"),
                            TwitchatActionType.REVERT_TO_APP_CATEGORY,
                            new TwitchatActionDefault("Retour à la catégorie de l'app", "secondary")
                    ))),
            Map.entry(TwitchatNotificationEventType.STREAM_STARTED, new TwitchatDefaultPayload(
                    "Le bot Catapult est actif.", "message", "live", "Catapult",
                    orderedActions(TwitchatActionType.DISABLE_BOT, new TwitchatActionDefault("Désactiver le bot", "alert")))),
            Map.entry(TwitchatNotificationEventType.BOT_ENABLED, new TwitchatDefaultPayload(
                    "Le bot a été activé.", "message", "online", "Catapult",
                    orderedActions(TwitchatActionType.DISABLE_BOT,
                            new TwitchatActionDefault("Désactiver le bot", "secondary")))),
            Map.entry(TwitchatNotificationEventType.BOT_DISABLED, new TwitchatDefaultPayload(
                    "Le bot a été désactivé.", "message", "offline", "Catapult",
                    orderedActions(TwitchatActionType.ENABLE_BOT,
                            new TwitchatActionDefault("Réactiver le bot", "secondary")))),
            Map.entry(TwitchatNotificationEventType.NO_GAME_DETECTED, new TwitchatDefaultPayload(
                    "Aucun jeu détecté.", "message", "info", "Catapult",
                    new LinkedHashMap<>()))
    );

    // Preserves declaration order for the `actions` map of each event type, so prefill/UI
    // consumers iterating it (e.g. ApiTwitchatWidgetController) get a stable, deterministic
    // order instead of the JVM-randomized iteration of Map.of(...).
    private static Map<TwitchatActionType, TwitchatActionDefault> orderedActions(
            TwitchatActionType type, TwitchatActionDefault def) {
        Map<TwitchatActionType, TwitchatActionDefault> ordered = new LinkedHashMap<>();
        ordered.put(type, def);
        return ordered;
    }

    private static Map<TwitchatActionType, TwitchatActionDefault> orderedActions(
            TwitchatActionType type1, TwitchatActionDefault def1,
            TwitchatActionType type2, TwitchatActionDefault def2) {
        Map<TwitchatActionType, TwitchatActionDefault> ordered = new LinkedHashMap<>();
        ordered.put(type1, def1);
        ordered.put(type2, def2);
        return ordered;
    }

    private TwitchatDefaultPayloads() {
    }
}
