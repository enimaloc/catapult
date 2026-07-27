package fr.enimaloc.catapult.chat.command;

import fr.enimaloc.catapult.chat.ChatCommandEvent;
import fr.enimaloc.catapult.domain.ChatCommandDefinition;
import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ChatCommandDefinitionRepository;
import fr.enimaloc.catapult.service.TwitchService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reproduces (and locks in) !setgame's exact documented behavior, now that its required
 * permission is a genuine per-streamer setting {@code CommandRegistry} reads from its
 * {@code ChatCommandDefinition} row instead of always using {@link #getRequiredPermission()}'s
 * hardcoded default — the command's own execute() logic is untouched by that change, but had no
 * test coverage at all before.
 */
class SetGameCommandTest {

    @Test
    void nameAndDefaultPermission() {
        SetGameCommand command = new SetGameCommand(mock(TwitchService.class), mock(ChatCommandDefinitionRepository.class));
        assertThat(command.getName()).isEqualTo("!setgame");
        assertThat(command.getRequiredPermission()).isEqualTo(ChatCommandEvent.SenderRole.MODERATOR);
    }

    @Test
    void executeWithNoArgsReturnsUsage() {
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        UserAccount user = new UserAccount();
        when(repository.findByUserAndPresetKey(user, "builtin:setgame")).thenReturn(Optional.empty());
        SetGameCommand command = new SetGameCommand(mock(TwitchService.class), repository);

        Object result = command.execute(user, List.of());

        assertThat(result).isEqualTo("Usage : !setgame <nom du jeu>");
    }

    @Test
    void executeForcesAManualGameBindingAndReturnsTheDefaultTemplate() {
        TwitchService twitchService = mock(TwitchService.class);
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        UserAccount user = new UserAccount();
        when(repository.findByUserAndPresetKey(user, "builtin:setgame")).thenReturn(Optional.empty());
        SetGameCommand command = new SetGameCommand(twitchService, repository);

        Object result = command.execute(user, List.of("Cyberpunk", "2077"));

        assertThat(result).isEqualTo("Jeu mis à jour : Cyberpunk 2077");
        var captor = org.mockito.ArgumentCaptor.forClass(GameBinding.class);
        verify(twitchService).updateChannel(eq(user), captor.capture());
        GameBinding binding = captor.getValue();
        assertThat(binding.getUser()).isEqualTo(user);
        assertThat(binding.getSourceType()).isEqualTo(GameBinding.SourceType.MANUAL);
        assertThat(binding.getSourceName()).isEqualTo("Cyberpunk 2077");
        assertThat(binding.getTwitchGameName()).isEqualTo("Cyberpunk 2077");
        assertThat(binding.getStatus()).isEqualTo(GameBinding.Status.MANUAL);
        assertThat(binding.getCcls()).isEmpty();
    }

    @Test
    void executeUsesTheStreamerCustomizedResponseTemplateWhenOneExists() {
        TwitchService twitchService = mock(TwitchService.class);
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        UserAccount user = new UserAccount();
        ChatCommandDefinition def = new ChatCommandDefinition();
        def.setEnabled(true);
        def.setTemplate("Now playing {args}!");
        when(repository.findByUserAndPresetKey(user, "builtin:setgame")).thenReturn(Optional.of(def));
        SetGameCommand command = new SetGameCommand(twitchService, repository);

        Object result = command.execute(user, List.of("Valorant"));

        assertThat(result).isEqualTo("Now playing Valorant!");
    }

    @Test
    void executeNoOpsWhenTheStreamerHasDisabledTheBuiltinRow() {
        TwitchService twitchService = mock(TwitchService.class);
        ChatCommandDefinitionRepository repository = mock(ChatCommandDefinitionRepository.class);
        UserAccount user = new UserAccount();
        ChatCommandDefinition def = new ChatCommandDefinition();
        def.setEnabled(false);
        when(repository.findByUserAndPresetKey(user, "builtin:setgame")).thenReturn(Optional.of(def));
        SetGameCommand command = new SetGameCommand(twitchService, repository);

        Object result = command.execute(user, List.of("Valorant"));

        assertThat(result).isNull();
        verify(twitchService, never()).updateChannel(any(), any());
    }
}
