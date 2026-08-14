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

    // Detection is intentionally permissive: it must catch any {{action:...}}-shaped text,
    // including malformed variants (wrong case, stray whitespace, hyphens, empty), so that
    // renderPresetActions can recognize it as an *attempted* placeholder and drop the entry
    // rather than silently ship the literal text to Twitchat. Strict validation of the type
    // name itself happens separately in parseActionType (case-sensitive TwitchatActionType.valueOf).
    private static final Pattern ACTION_PLACEHOLDER = Pattern.compile("\\{\\{\\s*action\\s*:\\s*([^}]*?)\\s*\\}\\}");

    // Dummy values used only by sendTestNotification — gameName is the only variable any event
    // type currently substitutes, and only for the two category-change event types.
    private static final Map<TwitchatNotificationEventType, Map<String, String>> TEST_VARIABLES = Map.of(
            TwitchatNotificationEventType.CATEGORY_CHANGED_BY_CATAPULT, Map.of("gameName", "Jeu de test"),
            TwitchatNotificationEventType.CATEGORY_CHANGED_MANUALLY, Map.of("gameName", "Jeu de test")
    );

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

    /**
     * Renders and publishes a preview notification from a preset's JSON as currently edited —
     * not necessarily saved. Every action type is treated as applicable (so the author sees every
     * button they defined at once, regardless of the account's real current state) and every
     * {{action:TYPE}} placeholder resolves to a random token that is NEVER written to
     * twitchat_action_token — a real click on a test notification's button therefore always fails
     * harmlessly through the same "unknown/expired token" path TwitchatActionExecutor already
     * handles for any other invalid token; it can never trigger a real action.
     */
    public void sendTestNotification(UserAccount user, TwitchatNotificationEventType eventType, String payloadJson) {
        if (!isWidgetEnabled(user)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "Le widget Twitchat est désactivé : active-le pour pouvoir tester une notification.");
        }
        TwitchatPresetPayload preset = payloadPresetService.parseAndValidate(payloadJson);
        TwitchatDefaultPayload defaults = TwitchatDefaultPayloads.DEFAULTS.get(eventType);
        Map<String, String> variables = TEST_VARIABLES.getOrDefault(eventType, Map.of());

        String message = substitute(nonBlankOr(preset.message(), defaults.message()), variables);
        String style = nonBlankOr(preset.style(), defaults.style());
        String icon = nonBlankOr(preset.icon(), defaults.icon());
        String authorName = nonBlankOr(preset.authorName(), defaults.authorName());
        List<TwitchatAction> actions = preset.actions() == null ? List.of() : renderTestActions(preset.actions());

        channelEventPublisher.twitchatNotify(user.getId(),
                new TwitchatNotification(message, style, icon, authorName, actions));
    }

    private List<TwitchatAction> renderTestActions(List<TwitchatRawAction> rawActions) {
        Map<TwitchatActionType, PendingAction> allTypesApplicable = new EnumMap<>(TwitchatActionType.class);
        for (TwitchatActionType type : TwitchatActionType.values()) {
            allTypesApplicable.put(type, new PendingAction(type, Map.of()));
        }
        Map<TwitchatActionType, String> tokenByType = new EnumMap<>(TwitchatActionType.class);
        return renderRawActions(rawActions, allTypesApplicable, tokenByType, type -> UUID.randomUUID().toString());
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
        return renderRawActions(rawActions, pendingByType, tokenByType,
                type -> actionTokenService.generate(user.getId(), type, pendingByType.get(type).payload())
                        .toString());
    }

    /**
     * The preset owns the full actions array. Each entry's `url` and `message` may reference
     * {@code {{action:TYPE}}} placeholders, resolved to that action type's token — but only if
     * the type is currently applicable (present in {@code pendingByType}). An entry referencing
     * an inapplicable or unrecognized type in EITHER field is dropped entirely rather than sent
     * with an unresolved placeholder. Entries with no placeholder in a given field keep that
     * field verbatim (including null). A token is minted at most once per action type, shared
     * across every entry that references it, via {@code tokenMinter} — production rendering
     * mints a real persisted token; test rendering (Task 2) mints an ephemeral, never-persisted
     * one, reusing this exact same resolution algorithm.
     */
    private List<TwitchatAction> renderRawActions(List<TwitchatRawAction> rawActions,
                                                    Map<TwitchatActionType, PendingAction> pendingByType,
                                                    Map<TwitchatActionType, String> tokenByType,
                                                    java.util.function.Function<TwitchatActionType, String> tokenMinter) {
        List<TwitchatAction> actions = new ArrayList<>();
        for (TwitchatRawAction raw : rawActions) {
            PlaceholderScan urlScan = scanPlaceholders(raw.url(), pendingByType);
            PlaceholderScan messageScan = scanPlaceholders(raw.message(), pendingByType);
            if (!urlScan.allApplicable() || !messageScan.allApplicable()) continue;

            String resolvedUrl = substituteActionPlaceholders(raw.url(), urlScan, tokenByType, tokenMinter);
            String resolvedMessage = substituteActionPlaceholders(raw.message(), messageScan, tokenByType, tokenMinter);
            actions.add(new TwitchatAction(raw.label(), raw.actionType() == null ? "url" : raw.actionType(),
                    resolvedUrl, resolvedMessage, raw.theme()));
        }
        return actions;
    }

    /**
     * Finds every {@code {{action:TYPE}}}-shaped occurrence in a single action field (url or
     * message). {@code allApplicable} becomes false as soon as one occurrence resolves to an
     * unknown or currently-inapplicable type — the caller must then drop the whole entry, never
     * send a partially resolved field. A null field trivially scans as "nothing to resolve".
     */
    private PlaceholderScan scanPlaceholders(String field, Map<TwitchatActionType, PendingAction> pendingByType) {
        if (field == null) return new PlaceholderScan(true, new ArrayList<>(), new ArrayList<>());
        List<TwitchatActionType> referenced = new ArrayList<>();
        List<String> matchedTexts = new ArrayList<>();
        Matcher matcher = ACTION_PLACEHOLDER.matcher(field);
        while (matcher.find()) {
            TwitchatActionType type = parseActionType(matcher.group(1));
            if (type == null || !pendingByType.containsKey(type)) {
                return new PlaceholderScan(false, referenced, matchedTexts);
            }
            referenced.add(type);
            matchedTexts.add(matcher.group(0));
        }
        return new PlaceholderScan(true, referenced, matchedTexts);
    }

    private String substituteActionPlaceholders(String field, PlaceholderScan scan,
                                                 Map<TwitchatActionType, String> tokenByType,
                                                 java.util.function.Function<TwitchatActionType, String> tokenMinter) {
        if (field == null) return null;
        String result = field;
        for (int i = 0; i < scan.referenced().size(); i++) {
            TwitchatActionType type = scan.referenced().get(i);
            String token = tokenByType.computeIfAbsent(type, tokenMinter);
            result = result.replace(scan.matchedTexts().get(i), token);
        }
        return result;
    }

    private record PlaceholderScan(boolean allApplicable, List<TwitchatActionType> referenced,
                                    List<String> matchedTexts) {
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
