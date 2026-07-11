package fr.enimaloc.catapult.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.TwitchChatService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CommandRegistryTest {

    private static final String OWNER_ID = "owner-123";

    private TwitchChatService chat;
    private DynamicCommandResolver dynamicResolver;
    private CommandRegistry registry;
    private UserAccount user;
    private ChatCommand staticCmd;

    @BeforeEach
    void setup() {
        chat = mock(TwitchChatService.class);
        dynamicResolver = mock(DynamicCommandResolver.class);
        staticCmd = mock(ChatCommand.class);
        when(staticCmd.getName()).thenReturn("!static");
        when(staticCmd.getRequiredPermission()).thenReturn(ChatCommandEvent.SenderRole.EVERYONE);
        when(staticCmd.execute(any(), any())).thenReturn("static result");

        registry = new CommandRegistry(List.of(staticCmd), chat, new ObjectMapper(),
            dynamicResolver, new SimpleMeterRegistry(), OWNER_ID);

        user = new UserAccount();
        user.setId(UUID.randomUUID());
    }

    @Test
    void static_command_takes_precedence() {
        ChatCommandEvent ev = new ChatCommandEvent(this, user, "!static", List.of(),
            ChatCommandEvent.SenderRole.EVERYONE);
        registry.dispatch(ev, true);

        verify(chat).sendMessage(eq(user), eq("static result"));
        verifyNoInteractions(dynamicResolver);
    }

    @Test
    void dynamic_consulted_only_when_static_miss() {
        ChatCommand dyn = mock(ChatCommand.class);
        when(dyn.getName()).thenReturn("!foo");
        when(dyn.getRequiredPermission()).thenReturn(ChatCommandEvent.SenderRole.EVERYONE);
        when(dyn.execute(any(), any())).thenReturn("dyn result");
        when(dynamicResolver.resolve(user, "!foo")).thenReturn(Optional.of(dyn));

        ChatCommandEvent ev = new ChatCommandEvent(this, user, "!foo", List.of(),
            ChatCommandEvent.SenderRole.EVERYONE);
        registry.dispatch(ev, true);

        verify(chat).sendMessage(eq(user), eq("dyn result"));
    }

    @Test
    void dynamic_skipped_when_dynamicAllowed_false() {
        ChatCommandEvent ev = new ChatCommandEvent(this, user, "!foo", List.of(),
            ChatCommandEvent.SenderRole.EVERYONE);
        registry.dispatch(ev, false);

        verifyNoInteractions(dynamicResolver);
        verify(chat, never()).sendMessage(any(), any());
    }

    @Test
    void null_result_does_not_send() {
        ChatCommand dyn = mock(ChatCommand.class);
        when(dyn.getName()).thenReturn("!foo");
        when(dyn.getRequiredPermission()).thenReturn(ChatCommandEvent.SenderRole.EVERYONE);
        when(dyn.execute(any(), any())).thenReturn(null);
        when(dynamicResolver.resolve(user, "!foo")).thenReturn(Optional.of(dyn));

        ChatCommandEvent ev = new ChatCommandEvent(this, user, "!foo", List.of(),
            ChatCommandEvent.SenderRole.EVERYONE);
        registry.dispatch(ev, true);

        verify(chat, never()).sendMessage(any(), any());
    }

    @Test
    void owner_only_denied_for_non_owner_sender() {
        when(staticCmd.isOwnerOnly()).thenReturn(true);

        ChatCommandEvent ev = new ChatCommandEvent(this, user, "!static", List.of(),
            ChatCommandEvent.SenderRole.BROADCASTER, "someone-else");
        registry.dispatch(ev, true);

        verify(staticCmd, never()).execute(any(), any());
        verify(chat, never()).sendMessage(any(), any());
    }

    @Test
    void owner_only_denied_without_sender_id() {
        when(staticCmd.isOwnerOnly()).thenReturn(true);

        ChatCommandEvent ev = new ChatCommandEvent(this, user, "!static", List.of(),
            ChatCommandEvent.SenderRole.BROADCASTER);
        registry.dispatch(ev, true);

        verify(staticCmd, never()).execute(any(), any());
    }

    @Test
    void owner_only_executes_for_app_owner() {
        when(staticCmd.isOwnerOnly()).thenReturn(true);

        ChatCommandEvent ev = new ChatCommandEvent(this, user, "!static", List.of(),
            ChatCommandEvent.SenderRole.EVERYONE, OWNER_ID);
        registry.dispatch(ev, true);

        verify(chat).sendMessage(eq(user), eq("static result"));
    }

    @Test
    void owner_only_denied_when_owner_id_unconfigured() {
        when(staticCmd.isOwnerOnly()).thenReturn(true);
        CommandRegistry noOwnerRegistry = new CommandRegistry(List.of(staticCmd), chat,
            new ObjectMapper(), dynamicResolver, new SimpleMeterRegistry(), "");

        ChatCommandEvent ev = new ChatCommandEvent(this, user, "!static", List.of(),
            ChatCommandEvent.SenderRole.BROADCASTER, "");
        noOwnerRegistry.dispatch(ev, true);

        verify(staticCmd, never()).execute(any(), any());
    }

    @Test
    void insufficient_permission_does_not_send() {
        ChatCommand dyn = mock(ChatCommand.class);
        when(dyn.getName()).thenReturn("!foo");
        when(dyn.getRequiredPermission()).thenReturn(ChatCommandEvent.SenderRole.MODERATOR);
        when(dynamicResolver.resolve(user, "!foo")).thenReturn(Optional.of(dyn));

        ChatCommandEvent ev = new ChatCommandEvent(this, user, "!foo", List.of(),
            ChatCommandEvent.SenderRole.EVERYONE);
        registry.dispatch(ev, true);

        verify(dyn, never()).execute(any(), any());
        verify(chat, never()).sendMessage(any(), any());
    }
}
