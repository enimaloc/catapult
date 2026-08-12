package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.domain.TwitchatActionType;
import fr.enimaloc.catapult.domain.TwitchatNotificationEventType;
import fr.enimaloc.catapult.service.notification.dto.TwitchatActionDefault;
import fr.enimaloc.catapult.service.notification.dto.TwitchatDefaultPayload;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The canonical French copy for every Twitchat notification Catapult sends — this is exactly
 * what {@code TwitchatNotifier} hardcoded before payload presets existed. Also the fallback used
 * whenever a user has no active preset for an event type, or their preset fails to parse/render.
 */
public final class TwitchatDefaultPayloads {

    public static final Map<TwitchatNotificationEventType, TwitchatDefaultPayload> DEFAULTS = Map.of(
            TwitchatNotificationEventType.CATEGORY_CHANGED_BY_CATAPULT, new TwitchatDefaultPayload(
                    "Catapult a changé la catégorie en {{gameName}}.", "message", "change", "Catapult",
                    orderedActions(
                            TwitchatActionType.REVERT_CATEGORY, new TwitchatActionDefault("Revert", "secondary"),
                            TwitchatActionType.DISABLE_BOT, new TwitchatActionDefault("Désactiver le bot", "alert")
                    )),
            TwitchatNotificationEventType.CATEGORY_CHANGED_MANUALLY, new TwitchatDefaultPayload(
                    "Catégorie changée manuellement en {{gameName}}.", "message", "user", "Catapult",
                    orderedActions(
                            TwitchatActionType.BIND_GAME_CATEGORY,
                            new TwitchatActionDefault("Définir par défaut pour ce jeu", "primary"),
                            TwitchatActionType.REVERT_TO_APP_CATEGORY,
                            new TwitchatActionDefault("Retour à la catégorie de l'app", "secondary")
                    )),
            TwitchatNotificationEventType.STREAM_STARTED, new TwitchatDefaultPayload(
                    "Le bot Catapult est actif.", "message", "live", "Catapult",
                    orderedActions(TwitchatActionType.DISABLE_BOT, new TwitchatActionDefault("Désactiver le bot", "alert"))),
            TwitchatNotificationEventType.BOT_ENABLED, new TwitchatDefaultPayload(
                    "Le bot a été activé.", "message", "online", "Catapult",
                    orderedActions(TwitchatActionType.DISABLE_BOT,
                            new TwitchatActionDefault("Désactiver le bot", "secondary"))),
            TwitchatNotificationEventType.BOT_DISABLED, new TwitchatDefaultPayload(
                    "Le bot a été désactivé.", "message", "offline", "Catapult",
                    orderedActions(TwitchatActionType.ENABLE_BOT,
                            new TwitchatActionDefault("Réactiver le bot", "secondary")))
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
