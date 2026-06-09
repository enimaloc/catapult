package fr.enimaloc.catapult.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.TwitchChatService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.*;

class CommandRegistryTest {

    @Test
    void dispatchSendsStringResponseToChat() {
        UserAccount user = new UserAccount();
        TwitchChatService chatService = mock(TwitchChatService.class);
        ObjectMapper objectMapper = new ObjectMapper();

        ChatCommand command = new ChatCommand() {
            @Override public String getName() { return "!test"; }
            @Override public ChatCommandEvent.SenderRole getRequiredPermission() { return ChatCommandEvent.SenderRole.EVERYONE; }
            @Override public Object execute(UserAccount u, List<String> args) { return "réponse"; }
        };

        CommandRegistry registry = new CommandRegistry(List.of(command), chatService, objectMapper);
        ChatCommandEvent event = new ChatCommandEvent(this, user, "!test", List.of(), ChatCommandEvent.SenderRole.EVERYONE);

        registry.dispatch(event);

        verify(chatService).sendMessage(user, "réponse");
    }

    @Test
    void dispatchSkipsSendWhenResultIsNull() {
        UserAccount user = new UserAccount();
        TwitchChatService chatService = mock(TwitchChatService.class);
        ObjectMapper objectMapper = new ObjectMapper();

        ChatCommand command = new ChatCommand() {
            @Override public String getName() { return "!silent"; }
            @Override public ChatCommandEvent.SenderRole getRequiredPermission() { return ChatCommandEvent.SenderRole.EVERYONE; }
            @Override public Object execute(UserAccount u, List<String> args) { return null; }
        };

        CommandRegistry registry = new CommandRegistry(List.of(command), chatService, objectMapper);
        ChatCommandEvent event = new ChatCommandEvent(this, user, "!silent", List.of(), ChatCommandEvent.SenderRole.EVERYONE);

        registry.dispatch(event);

        verify(chatService, never()).sendMessage(any(), any());
    }

    @Test
    void dispatchSerializesObjectResultToJson() {
        UserAccount user = new UserAccount();
        TwitchChatService chatService = mock(TwitchChatService.class);
        ObjectMapper objectMapper = new ObjectMapper();

        record Info(String name) {}
        ChatCommand command = new ChatCommand() {
            @Override public String getName() { return "!info"; }
            @Override public ChatCommandEvent.SenderRole getRequiredPermission() { return ChatCommandEvent.SenderRole.EVERYONE; }
            @Override public Object execute(UserAccount u, List<String> args) { return new Info("Minecraft"); }
        };

        CommandRegistry registry = new CommandRegistry(List.of(command), chatService, objectMapper);
        ChatCommandEvent event = new ChatCommandEvent(this, user, "!info", List.of(), ChatCommandEvent.SenderRole.EVERYONE);

        registry.dispatch(event);

        verify(chatService).sendMessage(user, "{\"name\":\"Minecraft\"}");
    }
}
