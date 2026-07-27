package fr.enimaloc.catapult.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ChatCommandDefinitionRepository;
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
    private ChatCommandDefinitionRepository definitionRepository;
    private CommandRegistry registry;
    private UserAccount user;
    private ChatCommand staticCmd;

    @BeforeEach
    void setup() {
        chat = mock(TwitchChatService.class);
        dynamicResolver = mock(DynamicCommandResolver.class);
        definitionRepository = mock(ChatCommandDefinitionRepository.class);
        staticCmd = mock(ChatCommand.class);
        when(staticCmd.getName()).thenReturn("!static");
        when(staticCmd.getRequiredPermission()).thenReturn(ChatCommandEvent.SenderRole.VIEWERS);
        when(staticCmd.execute(any(), any())).thenReturn("static result");

        registry = new CommandRegistry(List.of(staticCmd), chat, new ObjectMapper(),
            dynamicResolver, definitionRepository, new SimpleMeterRegistry(), OWNER_ID);

        user = new UserAccount();
        user.setId(UUID.randomUUID());
    }

    @Test
    void static_command_takes_precedence() {
        ChatCommandEvent ev = new ChatCommandEvent(this, user, "!static", List.of(),
            ChatCommandEvent.SenderRole.VIEWERS);
        registry.dispatch(ev, true);

        verify(chat).sendMessage(eq(user), eq("static result"));
        verifyNoInteractions(dynamicResolver);
    }

    @Test
    void dynamic_consulted_only_when_static_miss() {
        ChatCommand dyn = mock(ChatCommand.class);
        when(dyn.getName()).thenReturn("!foo");
        when(dyn.getRequiredPermission()).thenReturn(ChatCommandEvent.SenderRole.VIEWERS);
        when(dyn.execute(any(), any())).thenReturn("dyn result");
        when(dynamicResolver.resolve(user, "!foo")).thenReturn(Optional.of(dyn));

        ChatCommandEvent ev = new ChatCommandEvent(this, user, "!foo", List.of(),
            ChatCommandEvent.SenderRole.VIEWERS);
        registry.dispatch(ev, true);

        verify(chat).sendMessage(eq(user), eq("dyn result"));
    }

    @Test
    void dynamic_skipped_when_dynamicAllowed_false() {
        ChatCommandEvent ev = new ChatCommandEvent(this, user, "!foo", List.of(),
            ChatCommandEvent.SenderRole.VIEWERS);
        registry.dispatch(ev, false);

        verifyNoInteractions(dynamicResolver);
        verify(chat, never()).sendMessage(any(), any());
    }

    @Test
    void null_result_does_not_send() {
        ChatCommand dyn = mock(ChatCommand.class);
        when(dyn.getName()).thenReturn("!foo");
        when(dyn.getRequiredPermission()).thenReturn(ChatCommandEvent.SenderRole.VIEWERS);
        when(dyn.execute(any(), any())).thenReturn(null);
        when(dynamicResolver.resolve(user, "!foo")).thenReturn(Optional.of(dyn));

        ChatCommandEvent ev = new ChatCommandEvent(this, user, "!foo", List.of(),
            ChatCommandEvent.SenderRole.VIEWERS);
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
            ChatCommandEvent.SenderRole.VIEWERS, OWNER_ID);
        registry.dispatch(ev, true);

        verify(chat).sendMessage(eq(user), eq("static result"));
    }

    @Test
    void owner_only_denied_when_owner_id_unconfigured() {
        when(staticCmd.isOwnerOnly()).thenReturn(true);
        CommandRegistry noOwnerRegistry = new CommandRegistry(List.of(staticCmd), chat,
            new ObjectMapper(), dynamicResolver, definitionRepository, new SimpleMeterRegistry(), "");

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
            ChatCommandEvent.SenderRole.VIEWERS);
        registry.dispatch(ev, true);

        verify(dyn, never()).execute(any(), any());
        verify(chat, never()).sendMessage(any(), any());
    }

    @Test
    void a_higher_tier_satisfies_a_lower_requirement() {
        ChatCommand dyn = mock(ChatCommand.class);
        when(dyn.getName()).thenReturn("!foo");
        when(dyn.getRequiredPermission()).thenReturn(ChatCommandEvent.SenderRole.SUBS);
        when(dyn.execute(any(), any())).thenReturn("dyn result");
        when(dynamicResolver.resolve(user, "!foo")).thenReturn(Optional.of(dyn));

        // VIP outranks SUBS in the hierarchy — must satisfy a SUBS-gated command.
        ChatCommandEvent ev = new ChatCommandEvent(this, user, "!foo", List.of(),
            ChatCommandEvent.SenderRole.VIP);
        registry.dispatch(ev, true);

        verify(chat).sendMessage(eq(user), eq("dyn result"));
    }

    @Test
    void followers_gated_command_deniedWhenSenderIsNotAFollowerAndHasNoOtherBadge() {
        ChatCommand dyn = mock(ChatCommand.class);
        when(dyn.getName()).thenReturn("!foo");
        when(dyn.getRequiredPermission()).thenReturn(ChatCommandEvent.SenderRole.FOLLOWERS);
        when(dynamicResolver.resolve(user, "!foo")).thenReturn(Optional.of(dyn));
        when(chat.getFollowedAtById(user, "sender-1")).thenReturn(Optional.empty());

        ChatCommandEvent ev = new ChatCommandEvent(this, user, "!foo", List.of(),
            ChatCommandEvent.SenderRole.VIEWERS, "sender-1");
        registry.dispatch(ev, true);

        verify(dyn, never()).execute(any(), any());
    }

    @Test
    void followers_gated_command_allowedWhenLiveFollowerLookupSucceeds() {
        ChatCommand dyn = mock(ChatCommand.class);
        when(dyn.getName()).thenReturn("!foo");
        when(dyn.getRequiredPermission()).thenReturn(ChatCommandEvent.SenderRole.FOLLOWERS);
        when(dyn.execute(any(), any())).thenReturn("dyn result");
        when(dynamicResolver.resolve(user, "!foo")).thenReturn(Optional.of(dyn));
        when(chat.getFollowedAtById(user, "sender-1")).thenReturn(Optional.of(java.time.Instant.now()));

        ChatCommandEvent ev = new ChatCommandEvent(this, user, "!foo", List.of(),
            ChatCommandEvent.SenderRole.VIEWERS, "sender-1");
        registry.dispatch(ev, true);

        verify(chat).sendMessage(eq(user), eq("dyn result"));
    }

    @Test
    void followers_gated_command_neverCallsTheApiWhenSenderAlreadyHasAHigherBadge() {
        ChatCommand dyn = mock(ChatCommand.class);
        when(dyn.getName()).thenReturn("!foo");
        when(dyn.getRequiredPermission()).thenReturn(ChatCommandEvent.SenderRole.FOLLOWERS);
        when(dyn.execute(any(), any())).thenReturn("dyn result");
        when(dynamicResolver.resolve(user, "!foo")).thenReturn(Optional.of(dyn));

        ChatCommandEvent ev = new ChatCommandEvent(this, user, "!foo", List.of(),
            ChatCommandEvent.SenderRole.SUBS, "sender-1");
        registry.dispatch(ev, true);

        verify(chat).sendMessage(eq(user), eq("dyn result"));
        verify(chat, never()).getFollowedAtById(any(), any());
    }

    @Test
    void builtin_commandUsesTheStreamerConfiguredPermissionInsteadOfTheHardcodedDefault() {
        // staticCmd's own hardcoded default is VIEWERS, but the streamer raised it to
        // BROADCASTER via the editor — CommandRegistry must read that override.
        fr.enimaloc.catapult.domain.ChatCommandDefinition def = new fr.enimaloc.catapult.domain.ChatCommandDefinition();
        def.setPermission(ChatCommandEvent.SenderRole.BROADCASTER);
        when(definitionRepository.findByUserAndPresetKey(user, "builtin:static")).thenReturn(Optional.of(def));

        ChatCommandEvent ev = new ChatCommandEvent(this, user, "!static", List.of(),
            ChatCommandEvent.SenderRole.MODERATOR);
        registry.dispatch(ev, true);

        verify(staticCmd, never()).execute(any(), any());
    }

    @Test
    void builtin_commandFallsBackToItsHardcodedDefaultWhenNoOverrideRowExists() {
        when(definitionRepository.findByUserAndPresetKey(user, "builtin:static")).thenReturn(Optional.empty());

        ChatCommandEvent ev = new ChatCommandEvent(this, user, "!static", List.of(),
            ChatCommandEvent.SenderRole.VIEWERS);
        registry.dispatch(ev, true);

        verify(chat).sendMessage(eq(user), eq("static result"));
    }

    @Test
    void dynamic_commandNeverConsultsTheDefinitionRepositoryByPresetKey() {
        // DynamicChatCommand already reads its own definition's permission directly in
        // getRequiredPermission() — looking it up again here would be a wasted query on
        // every single dispatch.
        ChatCommand dyn = mock(ChatCommand.class);
        when(dyn.getName()).thenReturn("!foo");
        when(dyn.getRequiredPermission()).thenReturn(ChatCommandEvent.SenderRole.VIEWERS);
        when(dyn.execute(any(), any())).thenReturn("dyn result");
        when(dynamicResolver.resolve(user, "!foo")).thenReturn(Optional.of(dyn));

        ChatCommandEvent ev = new ChatCommandEvent(this, user, "!foo", List.of(),
            ChatCommandEvent.SenderRole.VIEWERS);
        registry.dispatch(ev, true);

        verifyNoInteractions(definitionRepository);
    }
}
