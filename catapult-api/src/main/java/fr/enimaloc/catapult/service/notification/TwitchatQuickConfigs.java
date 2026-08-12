package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.domain.TwitchatNotificationEventType;
import fr.enimaloc.catapult.service.notification.dto.TwitchatQuickConfig;
import fr.enimaloc.catapult.service.notification.dto.TwitchatQuickConfigParam;

import java.util.List;

/**
 * Fixed, server-authored catalog of parameterized preset templates a streamer can pick from
 * instead of writing a preset's JSON by hand. Not user-created, not persisted — see
 * ApiTwitchatWidgetController for how {{baseUrl}} gets resolved before this reaches an HTTP
 * response, and the "+ Nouveau preset" UI for how {{param:KEY}} gets resolved client-side.
 */
public final class TwitchatQuickConfigs {

    public static final List<TwitchatQuickConfig> ALL = List.of(
            new TwitchatQuickConfig(
                    "stream-started-disable-link",
                    "Bouton lien : désactiver le bot",
                    "Un bouton classique (lien) pour désactiver le bot quand le stream démarre.",
                    TwitchatNotificationEventType.STREAM_STARTED.name(),
                    List.of(),
                    "{\"message\":\"Le bot Catapult est actif.\",\"style\":\"message\",\"icon\":\"live\","
                            + "\"authorName\":\"Catapult\",\"actions\":[{\"label\":\"Désactiver le bot\","
                            + "\"actionType\":\"url\","
                            + "\"url\":\"{{baseUrl}}/widget/twitchat/action/{{action:DISABLE_BOT}}\","
                            + "\"theme\":\"alert\"}]}"
            ),
            new TwitchatQuickConfig(
                    "stream-started-disable-chat",
                    "Bouton chat : commande personnalisée",
                    "Un bouton qui envoie une commande chat de ton choix (ex: /so) suivie du token "
                            + "de l'action, pour désactiver le bot quand le stream démarre.",
                    TwitchatNotificationEventType.STREAM_STARTED.name(),
                    List.of(new TwitchatQuickConfigParam("command", "Nom de la commande (sans le /)", "so")),
                    "{\"message\":\"Le bot Catapult est actif.\",\"style\":\"message\",\"icon\":\"live\","
                            + "\"authorName\":\"Catapult\",\"actions\":[{\"label\":\"Désactiver le bot (chat)\","
                            + "\"actionType\":\"chat\",\"message\":\"/{{param:command}} {{action:DISABLE_BOT}}\","
                            + "\"theme\":\"secondary\"}]}"
            )
    );

    private TwitchatQuickConfigs() {
    }
}
