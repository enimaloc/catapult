package fr.enimaloc.catapult.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.domain.ChatCommandDefinition;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ChatCommandDefinitionRepository;
import fr.enimaloc.catapult.service.TwitchChatService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Registre centralisé des commandes chat disponibles.
 * Toutes les implémentations de ChatCommand sont auto-découvertes par Spring.
 * Les commandes statiques sont prioritaires sur les commandes dynamiques (data-driven).
 */
@Slf4j
@Component
public class CommandRegistry {

    private final Map<String, ChatCommand> commands;
    private final TwitchChatService twitchChatService;
    private final ObjectMapper objectMapper;
    private final DynamicCommandResolver dynamicCommandResolver;
    private final ChatCommandDefinitionRepository definitionRepository;
    private final MeterRegistry meterRegistry;
    private final String appOwnerId;

    public CommandRegistry(List<ChatCommand> commandList,
                           TwitchChatService twitchChatService,
                           ObjectMapper objectMapper,
                           DynamicCommandResolver dynamicCommandResolver,
                           ChatCommandDefinitionRepository definitionRepository,
                           MeterRegistry meterRegistry,
                           @Value("${app.owner-id:}") String appOwnerId) {
        this.commands = commandList.stream()
            .collect(Collectors.toMap(ChatCommand::getName, Function.identity()));
        this.twitchChatService = twitchChatService;
        this.objectMapper = objectMapper;
        this.dynamicCommandResolver = dynamicCommandResolver;
        this.definitionRepository = definitionRepository;
        this.meterRegistry = meterRegistry;
        this.appOwnerId = appOwnerId;
        log.info("Registered {} static chat commands: {}", commands.size(), commands.keySet());
    }

    public void dispatch(ChatCommandEvent event, boolean dynamicAllowed) {
        ChatCommand command = commands.get(event.getCommand());
        if (command == null && dynamicAllowed) {
            command = dynamicCommandResolver.resolve(event.getUser(), event.getCommand()).orElse(null);
        }
        if (command == null) {
            log.debug("Unknown command '{}' for user {}", event.getCommand(), event.getUser().getId());
            return;
        }

        if (command.isOwnerOnly() && !isAppOwner(event.getSenderTwitchId())) {
            log.debug("Owner-only command '{}' denied — sender twitchId: {}",
                event.getCommand(), event.getSenderTwitchId());
            meterRegistry.counter("catapult.chat.commands.dispatch",
                "name", event.getCommand(), "outcome", "forbidden").increment();
            return;
        }

        ChatCommandEvent.SenderRole required = requiredPermission(event.getUser(), command);
        if (!hasPermission(event, required)) {
            log.debug("Permission denied for command '{}' — sender role: {}",
                event.getCommand(), event.getSenderRole());
            meterRegistry.counter("catapult.chat.commands.dispatch",
                "name", event.getCommand(), "outcome", "forbidden").increment();
            return;
        }

        try {
            Object result = command.execute(event.getUser(), event.getArgs());
            if (result != null) {
                twitchChatService.sendMessage(event.getUser(), serialize(result));
                meterRegistry.counter("catapult.chat.commands.dispatch",
                    "name", event.getCommand(), "outcome", "success").increment();
            } else {
                meterRegistry.counter("catapult.chat.commands.dispatch",
                    "name", event.getCommand(), "outcome", "skipped").increment();
            }
        } catch (Exception e) {
            log.error("Error executing command '{}' for user {}",
                event.getCommand(), event.getUser().getId(), e);
            meterRegistry.counter("catapult.chat.commands.dispatch",
                "name", event.getCommand(), "outcome", "error").increment();
        }
    }

    private String serialize(Object result) {
        if (result instanceof String s) return s;
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.warn("Could not serialize command result to JSON, falling back to toString: {}", e.getMessage());
            return result.toString();
        }
    }

    private boolean isAppOwner(String senderTwitchId) {
        return appOwnerId != null && !appOwnerId.isBlank() && appOwnerId.equals(senderTwitchId);
    }

    /**
     * A static/builtin {@link ChatCommand} bean always reports its own hardcoded default — {@link
     * ChatCommandPresetCatalog} seeds an editable {@link ChatCommandDefinition} row per
     * non-owner-only one specifically so the streamer can override that default from the command
     * editor, so that row's permission (once present) must win here. {@code !debug} is currently
     * the only static bean left (owner-only, so it never gets such a row and always falls
     * through to its hardcoded default) — {@code !setgame} moved to being a fully data-driven
     * preset ({@link ChatCommandPresetCatalog}'s {@code setgame} entry), so this override lookup
     * is dormant rather than removed, ready for whichever future static command needs it next. A
     * data-driven ({@link DynamicChatCommand}) command already reads its own definition's
     * permission directly in {@code getRequiredPermission()} — looking it up again by presetKey
     * here would just waste a query per dispatch for no benefit, so it's skipped for anything
     * that isn't one of the static beans this registry was built from.
     */
    private ChatCommandEvent.SenderRole requiredPermission(UserAccount user, ChatCommand command) {
        if (!commands.containsValue(command)) {
            return command.getRequiredPermission();
        }
        String presetKey = ChatCommandPresetCatalog.BUILTIN_PRESET_KEY_PREFIX
            + command.getName().replaceFirst("^!", "");
        return definitionRepository.findByUserAndPresetKey(user, presetKey)
            .map(ChatCommandDefinition::getPermission)
            .orElseGet(command::getRequiredPermission);
    }

    /**
     * A higher tier always satisfies a lower requirement ({@link ChatCommandEvent.SenderRole} is
     * ordered least to most privileged), except FOLLOWERS: Twitch chat badges have no "is a
     * follower" signal the way broadcaster/moderator/vip/subscriber do, so a sender whose
     * badge-derived role is the baseline VIEWERS needs a live Helix lookup before FOLLOWERS-gated
     * commands can be denied outright — done only for that one borderline case, not on every
     * message, to avoid an extra API call per dispatch.
     */
    private boolean hasPermission(ChatCommandEvent event, ChatCommandEvent.SenderRole required) {
        ChatCommandEvent.SenderRole senderRole = event.getSenderRole();
        if (senderRole.ordinal() >= required.ordinal()) {
            return true;
        }
        if (required == ChatCommandEvent.SenderRole.FOLLOWERS
                && senderRole == ChatCommandEvent.SenderRole.VIEWERS
                && event.getSenderTwitchId() != null && !event.getSenderTwitchId().isBlank()) {
            return twitchChatService.getFollowedAtById(event.getUser(), event.getSenderTwitchId()).isPresent();
        }
        return false;
    }
}
