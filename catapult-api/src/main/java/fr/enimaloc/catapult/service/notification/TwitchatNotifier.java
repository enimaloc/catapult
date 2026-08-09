package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.TwitchatActionType;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.BindingService;
import fr.enimaloc.catapult.service.GameStateService;
import fr.enimaloc.catapult.service.notification.dto.TwitchatAction;
import fr.enimaloc.catapult.service.notification.dto.TwitchatNotification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class TwitchatNotifier {

    private final TwitchatWidgetSettingsService widgetSettingsService;
    private final TwitchatActionTokenService actionTokenService;
    private final ChannelEventPublisher channelEventPublisher;
    private final GameStateService gameStateService;
    private final BindingService bindingService;

    @Value("${app.web-url:http://localhost:8081}")
    private String publicWebUrl;

    public void onCategoryChangedByCatapult(UserAccount user, String newGameId, String newGameName,
                                             String previousGameId) {
        safely(user, "onCategoryChangedByCatapult", () -> {
            if (!isWidgetEnabled(user)) return;
            List<TwitchatAction> actions = new ArrayList<>();
            // No previous category recorded → nothing to revert to; a "Revert" button here would
            // PATCH Twitch with an empty game_id and simply unset the category.
            if (previousGameId != null) {
                actions.add(button(user, "Revert", TwitchatActionType.REVERT_CATEGORY,
                        Map.of("gameId", previousGameId, "gameName", ""), "secondary"));
            }
            actions.add(button(user, "Désactiver le bot", TwitchatActionType.DISABLE_BOT, Map.of(), "alert"));
            publish(user, "Catapult a changé la catégorie en " + newGameName + ".", actions);
        });
    }

    public void onCategoryChangedManually(UserAccount user, String newGameId, String newGameName) {
        safely(user, "onCategoryChangedManually", () -> {
            if (!isWidgetEnabled(user)) return;
            List<TwitchatAction> actions = new ArrayList<>();
            // Read-only lookup on purpose: composing a notification must never create a binding.
            Optional<GameBinding> existing = gameStateService.getLastKnownGame(user)
                    .flatMap(detected -> bindingService.findBinding(user, detected));
            if (existing.isPresent()) {
                GameBinding binding = existing.get();
                actions.add(button(user, "Définir par défaut pour ce jeu", TwitchatActionType.BIND_GAME_CATEGORY,
                        Map.of("bindingId", binding.getId().toString(), "newGameId", newGameId, "newGameName", newGameName),
                        "primary"));
                String appGameId = binding.getTwitchGameId();
                if (appGameId != null && !appGameId.equals(newGameId)) {
                    actions.add(button(user, "Retour à la catégorie de l'app", TwitchatActionType.REVERT_TO_APP_CATEGORY,
                            Map.of("gameId", appGameId, "gameName", nullToEmpty(binding.getTwitchGameName())),
                            "secondary"));
                }
            }
            publish(user, "Catégorie changée manuellement en " + newGameName + ".", actions);
        });
    }

    public void onStreamStarted(UserAccount user) {
        safely(user, "onStreamStarted", () -> {
            if (!isWidgetEnabled(user)) return;
            List<TwitchatAction> actions = List.of(
                    button(user, "Désactiver le bot", TwitchatActionType.DISABLE_BOT, Map.of(), "alert"));
            publish(user, "Le bot Catapult est actif.", actions);
        });
    }

    public void onBotToggled(UserAccount user, boolean enabled) {
        safely(user, "onBotToggled", () -> {
            if (!isWidgetEnabled(user)) return;
            TwitchatActionType action = enabled ? TwitchatActionType.DISABLE_BOT : TwitchatActionType.ENABLE_BOT;
            String label = enabled ? "Désactiver le bot" : "Réactiver le bot";
            List<TwitchatAction> actions = List.of(button(user, label, action, Map.of(), "secondary"));
            publish(user, enabled ? "Le bot a été activé." : "Le bot a été désactivé.", actions);
        });
    }

    /**
     * Twitchat notifications are a cosmetic side-channel: they do DB writes and a Redis publish
     * on the EventSub listener thread and on the category-update path, so a failure here must
     * never abort stream-state bookkeeping or a channel update. Same precedent as
     * {@code RedisEventPublisher.publish}.
     */
    private void safely(UserAccount user, String hook, Runnable body) {
        try {
            body.run();
        } catch (Exception e) {
            log.warn("Twitchat notification {} failed for user {}: {}", hook, user.getId(), e.getMessage(), e);
        }
    }

    private boolean isWidgetEnabled(UserAccount user) {
        return widgetSettingsService.getOrCreate(user).isEnabled();
    }

    private TwitchatAction button(UserAccount user, String label, TwitchatActionType type,
                                   Map<String, String> payload, String theme) {
        UUID token = actionTokenService.generate(user.getId(), type, payload);
        String url = publicWebUrl + "/widget/twitchat/action/" + token;
        return TwitchatAction.urlButton(label, url, theme);
    }

    private void publish(UserAccount user, String message, List<TwitchatAction> actions) {
        channelEventPublisher.twitchatNotify(user.getId(), new TwitchatNotification(message, "message", actions));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
