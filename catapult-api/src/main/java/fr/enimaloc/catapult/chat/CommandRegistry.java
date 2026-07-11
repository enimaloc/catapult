package fr.enimaloc.catapult.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final MeterRegistry meterRegistry;
    private final String appOwnerId;

    public CommandRegistry(List<ChatCommand> commandList,
                           TwitchChatService twitchChatService,
                           ObjectMapper objectMapper,
                           DynamicCommandResolver dynamicCommandResolver,
                           MeterRegistry meterRegistry,
                           @Value("${app.owner-id:}") String appOwnerId) {
        this.commands = commandList.stream()
            .collect(Collectors.toMap(ChatCommand::getName, Function.identity()));
        this.twitchChatService = twitchChatService;
        this.objectMapper = objectMapper;
        this.dynamicCommandResolver = dynamicCommandResolver;
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

        if (!hasPermission(event.getSenderRole(), command.getRequiredPermission())) {
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

    private boolean hasPermission(ChatCommandEvent.SenderRole senderRole,
                                  ChatCommandEvent.SenderRole required) {
        return switch (required) {
            case EVERYONE -> true;
            case MODERATOR -> senderRole == ChatCommandEvent.SenderRole.MODERATOR
                || senderRole == ChatCommandEvent.SenderRole.BROADCASTER;
            case BROADCASTER -> senderRole == ChatCommandEvent.SenderRole.BROADCASTER;
        };
    }
}
