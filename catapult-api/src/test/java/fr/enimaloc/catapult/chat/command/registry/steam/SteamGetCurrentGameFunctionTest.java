package fr.enimaloc.catapult.chat.command.registry.steam;

import fr.enimaloc.catapult.chat.command.js.ChatCommandServiceGateway;
import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.service.GameStateService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SteamGetCurrentGameFunctionTest {

    @Test
    void invokeResolvesAppIdFromTheCurrentlyDetectedSteamGame() throws Exception {
        GameStateService gameStateService = mock(GameStateService.class);
        ChatCommandServiceGateway gateway = mock(ChatCommandServiceGateway.class);
        UserAccount user = new UserAccount();
        when(gameStateService.getLastKnownGame(user)).thenReturn(
            Optional.of(new DetectedGame("1091500", GameBinding.SourceType.STEAM, "Cyberpunk 2077")));
        when(gateway.steamGame("1091500", null)).thenReturn(Optional.of(Map.of("name", "Cyberpunk 2077")));

        SteamGetCurrentGameFunction fn = new SteamGetCurrentGameFunction(gameStateService, new SteamGetGameFunction(gateway));
        assertThat(fn.namespace()).isEqualTo("steam");
        assertThat(fn.name()).isEqualTo("getCurrentGame");
        assertThat(fn.parameterNames()).containsExactly("locale");
        assertThat(fn.optionalParameterNames()).containsExactly("locale");

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) fn.invoke(user, new Object[0]);
        assertThat(result.get("name")).isEqualTo("Cyberpunk 2077");
        verify(gateway).steamGame("1091500", null);
    }

    @Test
    void invokePassesThroughAnExplicitLocale() throws Exception {
        GameStateService gameStateService = mock(GameStateService.class);
        ChatCommandServiceGateway gateway = mock(ChatCommandServiceGateway.class);
        UserAccount user = new UserAccount();
        when(gameStateService.getLastKnownGame(user)).thenReturn(
            Optional.of(new DetectedGame("1091500", GameBinding.SourceType.STEAM, "Cyberpunk 2077")));
        when(gateway.steamGame("1091500", "french")).thenReturn(Optional.of(Map.of("name", "Jeu")));

        SteamGetCurrentGameFunction fn = new SteamGetCurrentGameFunction(gameStateService, new SteamGetGameFunction(gateway));
        Object result = fn.invoke(user, new Object[]{"french"});

        assertThat(((Map<?, ?>) result).get("name")).isEqualTo("Jeu");
        verify(gateway).steamGame("1091500", "french");
    }

    @Test
    void invokeReturnsAnEmptyMapWhenCurrentGameIsNotFromSteam() throws Exception {
        GameStateService gameStateService = mock(GameStateService.class);
        ChatCommandServiceGateway gateway = mock(ChatCommandServiceGateway.class);
        UserAccount user = new UserAccount();
        when(gameStateService.getLastKnownGame(user)).thenReturn(
            Optional.of(new DetectedGame("123", GameBinding.SourceType.XBOX, "Halo")));

        SteamGetCurrentGameFunction fn = new SteamGetCurrentGameFunction(gameStateService, new SteamGetGameFunction(gateway));
        assertThat(fn.invoke(user, new Object[0])).isEqualTo(Map.of());
    }

    @Test
    void invokeReturnsAnEmptyMapWhenNoGameIsDetected() throws Exception {
        GameStateService gameStateService = mock(GameStateService.class);
        ChatCommandServiceGateway gateway = mock(ChatCommandServiceGateway.class);
        UserAccount user = new UserAccount();
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.empty());

        SteamGetCurrentGameFunction fn = new SteamGetCurrentGameFunction(gameStateService, new SteamGetGameFunction(gateway));
        assertThat(fn.invoke(user, new Object[0])).isEqualTo(Map.of());
    }
}
