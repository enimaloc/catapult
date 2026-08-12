package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.TwitchatActionType;
import fr.enimaloc.catapult.domain.TwitchatNotificationEventType;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.BindingService;
import fr.enimaloc.catapult.service.GameStateService;
import fr.enimaloc.catapult.service.notification.dto.TwitchatAction;
import fr.enimaloc.catapult.service.notification.dto.TwitchatActionDefault;
import fr.enimaloc.catapult.service.notification.dto.TwitchatDefaultPayload;
import fr.enimaloc.catapult.service.notification.dto.TwitchatNotification;
import fr.enimaloc.catapult.service.notification.dto.TwitchatPresetPayload;
import fr.enimaloc.catapult.service.notification.dto.TwitchatRawAction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class TwitchatNotifier {

    private static final Pattern ACTION_PLACEHOLDER = Pattern.compile("\\{\\{action:([A-Z_]+)\\}\\}");

    private final TwitchatWidgetSettingsService widgetSettingsService;
    private final TwitchatActionTokenService actionTokenService;
    private final TwitchatPayloadPresetService payloadPresetService;
    private final ChannelEventPublisher channelEventPublisher;
    private final GameStateService gameStateService;
    private final BindingService bindingService;

    @Value("${app.web-url:http://localhost:8081}")
    private String publicWebUrl;

    // BindingService (transitively) depends back on TwitchServiceImpl, which sits upstream of
    // this notifier in the bean graph — @Lazy breaks that cycle by deferring BindingService
    // resolution to first use instead of construction time.
    public TwitchatNotifier(TwitchatWidgetSettingsService widgetSettingsService,
                             TwitchatActionTokenService actionTokenService,
                             TwitchatPayloadPresetService payloadPresetService,
                             ChannelEventPublisher channelEventPublisher,
                             GameStateService gameStateService,
                             @Lazy BindingService bindingService) {
        this.widgetSettingsService = widgetSettingsService;
        this.actionTokenService = actionTokenService;
        this.payloadPresetService = payloadPresetService;
        this.channelEventPublisher = channelEventPublisher;
        this.gameStateService = gameStateService;
        this.bindingService = bindingService;
    }

    public void onCategoryChangedByCatapult(UserAccount user, String newGameId, String newGameName,
                                             String previousGameId) {
        safely(user, "onCategoryChangedByCatapult", () -> {
            if (!isWidgetEnabled(user)) return;
            List<PendingAction> actions = new ArrayList<>();
            // No previous category recorded → nothing to revert to; a "Revert" button here would
            // PATCH Twitch with an empty game_id and simply unset the category.
            if (previousGameId != null) {
                actions.add(new PendingAction(TwitchatActionType.REVERT_CATEGORY,
                        Map.of("gameId", previousGameId, "gameName", "")));
            }
            actions.add(new PendingAction(TwitchatActionType.DISABLE_BOT, Map.of()));
            publish(user, TwitchatNotificationEventType.CATEGORY_CHANGED_BY_CATAPULT,
                    Map.of("gameName", newGameName), actions);
        });
    }

    public void onCategoryChangedManually(UserAccount user, String newGameId, String newGameName) {
        safely(user, "onCategoryChangedManually", () -> {
            if (!isWidgetEnabled(user)) return;
            List<PendingAction> actions = new ArrayList<>();
            // Read-only lookup on purpose: composing a notification must never create a binding.
            Optional<GameBinding> existing = gameStateService.getLastKnownGame(user)
                    .flatMap(detected -> bindingService.findBinding(user, detected));
            if (existing.isPresent()) {
                GameBinding binding = existing.get();
                actions.add(new PendingAction(TwitchatActionType.BIND_GAME_CATEGORY,
                        Map.of("bindingId", binding.getId().toString(), "newGameId", newGameId,
                                "newGameName", newGameName)));
                String appGameId = binding.getTwitchGameId();
                if (appGameId != null && !appGameId.equals(newGameId)) {
                    actions.add(new PendingAction(TwitchatActionType.REVERT_TO_APP_CATEGORY,
                            Map.of("gameId", appGameId, "gameName", nullToEmpty(binding.getTwitchGameName()))));
                }
            }
            publish(user, TwitchatNotificationEventType.CATEGORY_CHANGED_MANUALLY,
                    Map.of("gameName", newGameName), actions);
        });
    }

    public void onStreamStarted(UserAccount user) {
        safely(user, "onStreamStarted", () -> {
            if (!isWidgetEnabled(user)) return;
            List<PendingAction> actions = List.of(new PendingAction(TwitchatActionType.DISABLE_BOT, Map.of()));
            publish(user, TwitchatNotificationEventType.STREAM_STARTED, Map.of(), actions);
        });
    }

    public void onBotToggled(UserAccount user, boolean enabled) {
        safely(user, "onBotToggled", () -> {
            if (!isWidgetEnabled(user)) return;
            TwitchatActionType action = enabled ? TwitchatActionType.DISABLE_BOT : TwitchatActionType.ENABLE_BOT;
            List<PendingAction> actions = List.of(new PendingAction(action, Map.of()));
            publish(user,
                    enabled ? TwitchatNotificationEventType.BOT_ENABLED : TwitchatNotificationEventType.BOT_DISABLED,
                    Map.of(), actions);
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

    private void publish(UserAccount user, TwitchatNotificationEventType eventType,
                          Map<String, String> variables, List<PendingAction> pendingActions) {
        TwitchatDefaultPayload defaults = TwitchatDefaultPayloads.DEFAULTS.get(eventType);
        TwitchatPresetPayload preset = payloadPresetService.findActivePresetPayload(user, eventType).orElse(null);

        String messageTemplate = nonBlankOr(preset == null ? null : preset.message(), defaults.message());
        String message = substitute(messageTemplate, variables);
        String style = nonBlankOr(preset == null ? null : preset.style(), defaults.style());
        String icon = nonBlankOr(preset == null ? null : preset.icon(), defaults.icon());
        String authorName = nonBlankOr(preset == null ? null : preset.authorName(), defaults.authorName());

        List<TwitchatAction> actions = preset != null && preset.actions() != null
                ? renderPresetActions(user, preset.actions(), pendingActions)
                : renderDefaultActions(user, defaults, pendingActions);

        channelEventPublisher.twitchatNotify(user.getId(),
                new TwitchatNotification(message, style, icon, authorName, actions));
    }

    /**
     * No active preset (or preset has no `actions` field): one TwitchatAction per pending action,
     * default label/theme for this event type, URL fully computed by the server.
     */
    private List<TwitchatAction> renderDefaultActions(UserAccount user, TwitchatDefaultPayload defaults,
                                                        List<PendingAction> pendingActions) {
        List<TwitchatAction> actions = new ArrayList<>();
        for (PendingAction pending : pendingActions) {
            TwitchatActionDefault def = defaults.actions().get(pending.type());
            UUID token = actionTokenService.generate(user.getId(), pending.type(), pending.payload());
            String url = stripTrailingSlash(publicWebUrl) + "/widget/twitchat/action/" + token;
            actions.add(TwitchatAction.urlButton(def.label(), url, def.theme()));
        }
        return actions;
    }

    /**
     * The preset owns the full actions array. Each entry's `url` may reference
     * {@code {{action:TYPE}}} placeholders, resolved to that action type's raw token — but only
     * if the type is currently applicable (present in {@code pendingActions}). An entry
     * referencing an inapplicable or unrecognized type is dropped entirely rather than sent with
     * an unresolved placeholder. Entries with no placeholder (static/custom URLs) are always
     * kept verbatim. A token is generated only for types actually referenced by a kept-or-being-
     * evaluated entry, never for types the preset doesn't reference at all.
     */
    private List<TwitchatAction> renderPresetActions(UserAccount user, List<TwitchatRawAction> rawActions,
                                                       List<PendingAction> pendingActions) {
        Map<TwitchatActionType, PendingAction> pendingByType = new EnumMap<>(TwitchatActionType.class);
        for (PendingAction pending : pendingActions) {
            pendingByType.put(pending.type(), pending);
        }

        Map<TwitchatActionType, String> tokenByType = new EnumMap<>(TwitchatActionType.class);
        List<TwitchatAction> actions = new ArrayList<>();
        for (TwitchatRawAction raw : rawActions) {
            if (raw.url() == null) continue;
            List<TwitchatActionType> referenced = new ArrayList<>();
            boolean allApplicable = true;
            Matcher matcher = ACTION_PLACEHOLDER.matcher(raw.url());
            while (matcher.find()) {
                TwitchatActionType type = parseActionType(matcher.group(1));
                if (type == null || !pendingByType.containsKey(type)) {
                    allApplicable = false;
                    break;
                }
                referenced.add(type);
            }
            if (!allApplicable) continue;

            String resolvedUrl = raw.url();
            for (TwitchatActionType type : referenced) {
                String token = tokenByType.computeIfAbsent(type, t -> {
                    PendingAction pending = pendingByType.get(t);
                    return actionTokenService.generate(user.getId(), t, pending.payload()).toString();
                });
                resolvedUrl = resolvedUrl.replace("{{action:" + type.name() + "}}", token);
            }
            actions.add(new TwitchatAction(raw.label(), raw.actionType() == null ? "url" : raw.actionType(),
                    resolvedUrl, raw.theme()));
        }
        return actions;
    }

    private static TwitchatActionType parseActionType(String name) {
        try {
            return TwitchatActionType.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String substitute(String template, Map<String, String> variables) {
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue() == null ? "" : entry.getValue());
        }
        return result;
    }

    private static String nonBlankOr(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record PendingAction(TwitchatActionType type, Map<String, String> payload) {
    }
}
