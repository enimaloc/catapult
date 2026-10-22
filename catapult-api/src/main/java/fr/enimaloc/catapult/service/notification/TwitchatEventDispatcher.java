package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.common.dto.twitchat.TwitchatActionType;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatDispatch;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatEventActionDto;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatPayload;
import fr.enimaloc.catapult.common.twitchat.TwitchatPayloadRenderer;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.twitchat.TwitchatEventAction;
import fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType;
import fr.enimaloc.catapult.event.NoGameDetectedEvent;
import fr.enimaloc.catapult.service.binding.BindingService;
import fr.enimaloc.catapult.service.binding.GameStateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType.BOT_DISABLED;
import static fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType.BOT_ENABLED;
import static fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType.CATEGORY_CHANGED_BY_CATAPULT;
import static fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType.CATEGORY_CHANGED_MANUALLY;
import static fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType.NOTIFICATION;
import static fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType.NO_GAME_DETECTED;
import static fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType.STREAM_STARTED;

/**
 * Sends each Catapult event's payloads to Twitchat: renders the account's enabled payloads
 * (TwitchatPayloadRenderer) with the event's variables and the Catapult actions applicable right
 * now, then publishes them as one TwitchatDispatch for the owner's pages to send.
 */
@Slf4j
@Component
public class TwitchatEventDispatcher {

    private static final Map<String, String> TEST_GAMES = Map.of("gameName", "Jeu de test", "previousGameName", "Ancien jeu");

    private final TwitchatWidgetSettingsService widgetSettingsService;
    private final TwitchatActionTokenService actionTokenService;
    private final TwitchatEventActionService eventActionService;
    private final ChannelEventPublisher channelEventPublisher;
    private final GameStateService gameStateService;
    private final BindingService bindingService;

    @Value("${app.web-url:http://localhost:8081}")
    private String publicWebUrl;

    // BindingService (transitively) depends back on TwitchServiceImpl, upstream of this
    // dispatcher in the bean graph: @Lazy defers its resolution to first use.
    public TwitchatEventDispatcher(TwitchatWidgetSettingsService widgetSettingsService,
                                   TwitchatActionTokenService actionTokenService,
                                   TwitchatEventActionService eventActionService,
                                   ChannelEventPublisher channelEventPublisher,
                                   GameStateService gameStateService,
                                   @Lazy BindingService bindingService) {
        this.widgetSettingsService = widgetSettingsService;
        this.actionTokenService = actionTokenService;
        this.eventActionService = eventActionService;
        this.channelEventPublisher = channelEventPublisher;
        this.gameStateService = gameStateService;
        this.bindingService = bindingService;
    }

    public void onCategoryChangedByCatapult(UserAccount user, String newGameId, String newGameName,
                                            String previousGameId, String previousGameName) {
        safely(user, "onCategoryChangedByCatapult", () -> {
            Map<TwitchatActionType, Map<String, String>> applicable = new LinkedHashMap<>();
            // No previous category recorded → nothing to revert to.
            if (previousGameId != null) {
                applicable.put(TwitchatActionType.REVERT_CATEGORY,
                        Map.of("gameId", previousGameId, "gameName", nullToEmpty(previousGameName)));
            }
            applicable.put(TwitchatActionType.DISABLE_BOT, Map.of());
            dispatch(user, CATEGORY_CHANGED_BY_CATAPULT, games(newGameName, previousGameName), applicable);
        });
    }

    public void onCategoryChangedManually(UserAccount user, String newGameId, String newGameName, String previousGameName) {
        safely(user, "onCategoryChangedManually", () -> {
            Map<TwitchatActionType, Map<String, String>> applicable = new LinkedHashMap<>();
            // Read-only lookup on purpose: composing a dispatch must never create a binding.
            Optional<GameBinding> existing = gameStateService.getLastKnownGame(user)
                    .flatMap(detected -> bindingService.findBinding(user, detected));
            if (existing.isPresent()) {
                GameBinding binding = existing.get();
                applicable.put(TwitchatActionType.BIND_GAME_CATEGORY, Map.of("bindingId", binding.getId().toString(),
                        "newGameId", newGameId, "newGameName", nullToEmpty(newGameName)));
                String appGameId = binding.getTwitchGameId();
                if (appGameId != null && !appGameId.equals(newGameId)) {
                    applicable.put(TwitchatActionType.REVERT_TO_APP_CATEGORY,
                            Map.of("gameId", appGameId, "gameName", nullToEmpty(binding.getTwitchGameName())));
                }
            }
            dispatch(user, CATEGORY_CHANGED_MANUALLY, games(newGameName, previousGameName), applicable);
        });
    }

    public void onStreamStarted(UserAccount user) {
        safely(user, "onStreamStarted", () ->
                dispatch(user, STREAM_STARTED, Map.of(), Map.of(TwitchatActionType.DISABLE_BOT, Map.of())));
    }

    public void onBotToggled(UserAccount user, boolean enabled) {
        safely(user, "onBotToggled", () -> dispatch(user, enabled ? BOT_ENABLED : BOT_DISABLED, Map.of(),
                Map.of(enabled ? TwitchatActionType.DISABLE_BOT : TwitchatActionType.ENABLE_BOT, Map.of())));
    }

    /**
     * A Catapult notification, for a member who opted in to Twitchat: {@code body} as plain text,
     * {@code ctaUrl}/{@code ctaLabel} empty without a call to action. Unlike the other hooks it
     * lets failures through and says whether anything was published, for its caller to count it
     * or keep it pending.
     */
    public boolean onNotification(UserAccount user, String title, String body, String ctaUrl, String ctaLabel) {
        Map<String, String> variables = Map.of("title", nullToEmpty(title), "body", nullToEmpty(body),
                "ctaUrl", nullToEmpty(ctaUrl), "ctaLabel", nullToEmpty(ctaLabel));
        return dispatch(user, NOTIFICATION, variables, Map.of());
    }

    @EventListener
    public void onNoGameDetected(NoGameDetectedEvent event) {
        UserAccount user = event.getUser();
        safely(user, "onNoGameDetected", () -> dispatch(user, NO_GAME_DETECTED, Map.of(), Map.of()));
    }

    /**
     * Publishes payloads as currently edited, saved or not, disabled ones included: every
     * Catapult action counts as applicable (so every button shows) and resolves to a random
     * token never written to twitchat_action_token — a click on it fails harmlessly as an
     * unknown token, it can never trigger a real action.
     */
    public void sendTest(UserAccount user, TwitchatNotificationEventType type, List<TwitchatEventActionDto> payloads) {
        if (!widgetSettingsService.isEnabled(user)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Le widget Twitchat est désactivé : active-le pour pouvoir tester.");
        }
        eventActionService.validate(payloads);
        TwitchatPayloadRenderer renderer = new TwitchatPayloadRenderer(variables(user, TEST_GAMES),
                EnumSet.allOf(TwitchatActionType.class), action -> UUID.randomUUID().toString());
        List<TwitchatPayload> rendered = new ArrayList<>();
        for (TwitchatEventActionDto payload : payloads) {
            renderer.render(payload.action(), eventActionService.parseData(payload.dataJson())).ifPresent(rendered::add);
        }
        publish(user, type, rendered);
    }

    /** Whether a dispatch was published: none without an enabled widget or payload. */
    private boolean dispatch(UserAccount user, TwitchatNotificationEventType type, Map<String, String> eventVariables,
                             Map<TwitchatActionType, Map<String, String>> applicable) {
        if (!widgetSettingsService.isEnabled(user)) {
            return false;
        }
        List<TwitchatEventAction> rows = eventActionService.enabledActions(user, type);
        if (rows.isEmpty()) {
            return false;
        }
        TwitchatPayloadRenderer renderer = new TwitchatPayloadRenderer(variables(user, eventVariables), applicable.keySet(),
                action -> actionTokenService.generate(user.getId(), action, applicable.get(action)).toString());
        List<TwitchatPayload> rendered = new ArrayList<>();
        for (TwitchatEventAction row : rows) {
            // A stored row that no longer parses (saved before a check existed, edited in the
            // database) only loses its own payload, never the event's others.
            Map<String, Object> data;
            try {
                data = eventActionService.parseData(row.getDataJson());
            } catch (IllegalArgumentException e) {
                log.warn("Skipping unreadable Twitchat payload {} ({}) of {} for user {}: {}",
                        row.getPosition(), row.getAction(), type, user.getId(), e.getMessage());
                continue;
            }
            renderer.render(row.getAction(), data).ifPresent(rendered::add);
        }
        if (type == NOTIFICATION) {
            rendered.replaceAll(TwitchatEventDispatcher::withoutTrailingSpace);
        }
        return publish(user, type, rendered);
    }

    private boolean publish(UserAccount user, TwitchatNotificationEventType type, List<TwitchatPayload> payloads) {
        if (payloads.isEmpty()) {
            return false;
        }
        channelEventPublisher.twitchatDispatch(user.getId(), new TwitchatDispatch(user.getTwitchUsername(), type.name(), payloads));
        return true;
    }

    private Map<String, String> variables(UserAccount user, Map<String, String> eventVariables) {
        Map<String, String> variables = new HashMap<>(eventVariables);
        variables.put("baseUrl", stripTrailingSlash(publicWebUrl));
        variables.put("channel", nullToEmpty(user.getTwitchUsername()));
        return variables;
    }

    /**
     * A chat message's text without trailing whitespace: a notification without a call to action
     * leaves the default message's {@code {{ctaUrl}}} empty after its space.
     */
    private static TwitchatPayload withoutTrailingSpace(TwitchatPayload payload) {
        if (!TwitchatPayloadRenderer.CHAT_MESSAGE.equals(payload.action())
                || !(payload.data().get("message") instanceof String message)) {
            return payload;
        }
        Map<String, Object> data = new LinkedHashMap<>(payload.data());
        data.put("message", message.stripTrailing());
        return new TwitchatPayload(payload.action(), data);
    }

    private static Map<String, String> games(String gameName, String previousGameName) {
        return Map.of("gameName", nullToEmpty(gameName), "previousGameName", nullToEmpty(previousGameName));
    }

    /**
     * Twitchat dispatches are a cosmetic side-channel, run on the EventSub listener thread and
     * the category-update path: a failure must never abort them. Same precedent as
     * {@code RedisEventPublisher.publish}.
     */
    private void safely(UserAccount user, String hook, Runnable body) {
        try {
            body.run();
        } catch (Exception e) {
            log.warn("Twitchat dispatch {} failed for user {}: {}", hook, user.getId(), e.getMessage(), e);
        }
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
