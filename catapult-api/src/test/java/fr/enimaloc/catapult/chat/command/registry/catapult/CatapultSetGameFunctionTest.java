package fr.enimaloc.catapult.chat.command.registry.catapult;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.service.GameStateService;
import fr.enimaloc.catapult.service.IgdbService;
import fr.enimaloc.catapult.service.TwitchService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CatapultSetGameFunctionTest {

    @Test
    void nameAndParameters() {
        CatapultSetGameFunction fn = new CatapultSetGameFunction(
            mock(TwitchService.class), mock(GameStateService.class), mock(IgdbService.class));
        assertThat(fn.namespace()).isEqualTo("catapult");
        assertThat(fn.name()).isEqualTo("setGame");
        assertThat(fn.parameterNames()).containsExactly("name", "igdbId");
        assertThat(fn.optionalParameterNames()).containsExactly("igdbId");
        assertThat(fn.isAction()).isTrue();
    }

    @Test
    void invokeWithoutAnIgdbIdResolvesTheTwitchCategoryByNameSearch() throws Exception {
        TwitchService twitchService = mock(TwitchService.class);
        GameStateService gameStateService = mock(GameStateService.class);
        IgdbService igdbService = mock(IgdbService.class);
        UserAccount user = new UserAccount();
        when(twitchService.findCategoryIdByName(user, "Cyberpunk 2077")).thenReturn(Optional.of("1877"));
        CatapultSetGameFunction fn = new CatapultSetGameFunction(twitchService, gameStateService, igdbService);

        Object result = fn.invoke(user, new Object[]{"Cyberpunk 2077"});

        assertThat(result).isEqualTo("");
        var captor = org.mockito.ArgumentCaptor.forClass(GameBinding.class);
        verify(twitchService).updateChannel(eq(user), captor.capture());
        GameBinding binding = captor.getValue();
        assertThat(binding.getUser()).isEqualTo(user);
        assertThat(binding.getSourceType()).isEqualTo(GameBinding.SourceType.MANUAL);
        assertThat(binding.getSourceName()).isEqualTo("Cyberpunk 2077");
        assertThat(binding.getTwitchGameName()).isEqualTo("Cyberpunk 2077");
        assertThat(binding.getStatus()).isEqualTo(GameBinding.Status.MANUAL);
        assertThat(binding.getCcls()).isEmpty();
        assertThat(binding.getSourceId()).isNull();
        assertThat(binding.getTwitchGameId()).isEqualTo("1877");
        verifyNoInteractions(igdbService);
        // No igdbId given — nothing to feed the in-memory cache with, no fabricated event.
        verifyNoInteractions(gameStateService);
    }

    @Test
    void invokeWithAnIgdbIdResolvesTheTwitchCategoryViaIgdbAndPinsTheInMemoryGameState() throws Exception {
        TwitchService twitchService = mock(TwitchService.class);
        GameStateService gameStateService = mock(GameStateService.class);
        IgdbService igdbService = mock(IgdbService.class);
        UserAccount user = new UserAccount();
        when(igdbService.findTwitchGameId("1877")).thenReturn(Optional.of("1877"));
        CatapultSetGameFunction fn = new CatapultSetGameFunction(twitchService, gameStateService, igdbService);

        fn.invoke(user, new Object[]{"Cyberpunk 2077", "1877"});

        var bindingCaptor = org.mockito.ArgumentCaptor.forClass(GameBinding.class);
        verify(twitchService).updateChannel(eq(user), bindingCaptor.capture());
        GameBinding binding = bindingCaptor.getValue();
        assertThat(binding.getSourceId()).isEqualTo("1877");
        assertThat(binding.getTwitchGameId()).isEqualTo("1877");
        // findCategoryIdByName never needed — the IGDB mapping alone resolved the Twitch id.
        verify(twitchService, never()).findCategoryIdByName(any(), any());

        var detectedCaptor = org.mockito.ArgumentCaptor.forClass(DetectedGame.class);
        verify(gameStateService).updateState(eq(user), detectedCaptor.capture());
        DetectedGame detected = detectedCaptor.getValue();
        assertThat(detected.getSourceId()).isEqualTo("1877");
        assertThat(detected.getSourceType()).isEqualTo(GameBinding.SourceType.MANUAL);
        assertThat(detected.getSourceName()).isEqualTo("Cyberpunk 2077");
    }

    @Test
    void invokeFallsBackToNameSearchWhenTheIgdbIdHasNoTwitchMapping() throws Exception {
        TwitchService twitchService = mock(TwitchService.class);
        GameStateService gameStateService = mock(GameStateService.class);
        IgdbService igdbService = mock(IgdbService.class);
        UserAccount user = new UserAccount();
        when(igdbService.findTwitchGameId("1877")).thenReturn(Optional.empty());
        when(twitchService.findCategoryIdByName(user, "Cyberpunk 2077")).thenReturn(Optional.of("1877"));
        CatapultSetGameFunction fn = new CatapultSetGameFunction(twitchService, gameStateService, igdbService);

        fn.invoke(user, new Object[]{"Cyberpunk 2077", "1877"});

        var captor = org.mockito.ArgumentCaptor.forClass(GameBinding.class);
        verify(twitchService).updateChannel(eq(user), captor.capture());
        assertThat(captor.getValue().getTwitchGameId()).isEqualTo("1877");
    }

    @Test
    void invokeTreatsABlankIgdbIdAsAbsent() throws Exception {
        TwitchService twitchService = mock(TwitchService.class);
        GameStateService gameStateService = mock(GameStateService.class);
        IgdbService igdbService = mock(IgdbService.class);
        UserAccount user = new UserAccount();
        when(twitchService.findCategoryIdByName(user, "Cyberpunk 2077")).thenReturn(Optional.empty());
        CatapultSetGameFunction fn = new CatapultSetGameFunction(twitchService, gameStateService, igdbService);

        fn.invoke(user, new Object[]{"Cyberpunk 2077", ""});

        verifyNoInteractions(gameStateService);
        verifyNoInteractions(igdbService);
        var captor = org.mockito.ArgumentCaptor.forClass(GameBinding.class);
        verify(twitchService).updateChannel(eq(user), captor.capture());
        assertThat(captor.getValue().getSourceId()).isNull();
        assertThat(captor.getValue().getTwitchGameId()).isNull();
    }
}
