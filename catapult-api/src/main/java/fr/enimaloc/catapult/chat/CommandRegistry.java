package fr.enimaloc.catapult.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.service.TwitchChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Registre centralisé des commandes chat disponibles.
 * Toutes les implémentations de ChatCommand sont auto-découvertes par Spring.
 */
@Slf4j
@Component
public class CommandRegistry {

    private final Map<String, ChatCommand> commands;
    private final TwitchChatService twitchChatService;
    private final ObjectMapper objectMapper;

    public CommandRegistry(List<ChatCommand> commandList,
                           TwitchChatService twitchChatService,
                           ObjectMapper objectMapper) {
        this.commands = commandList.stream()
            .collect(Collectors.toMap(ChatCommand::getName, Function.identity()));
        this.twitchChatService = twitchChatService;
        this.objectMapper = objectMapper;
        log.info("Registered {} chat commands: {}", commands.size(), commands.keySet());
    }

    public void dispatch(ChatCommandEvent event) {
        ChatCommand command = commands.get(event.getCommand());
        if (command == null) {
            log.debug("Unknown command '{}' for user {}", event.getCommand(), event.getUser().getId());
            return;
        }

        if (!hasPermission(event.getSenderRole(), command.getRequiredPermission())) {
            log.debug("Permission denied for command '{}' — sender role: {}",
                event.getCommand(), event.getSenderRole());
            return;
        }

        try {
            Object result = command.execute(event.getUser(), event.getArgs());
            if (result != null) {
                twitchChatService.sendMessage(event.getUser(), serialize(result));
            }
        } catch (Exception e) {
            log.error("Error executing command '{}' for user {}",
                event.getCommand(), event.getUser().getId(), e);
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
