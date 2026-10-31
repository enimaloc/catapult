package fr.enimaloc.catapult.chat.command.registry.steam;

import fr.enimaloc.catapult.chat.command.js.ChatCommandServiceGateway;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SteamGetGameFunctionTest {

    @Test
    void invokeReturnsTheResolvedGameData() throws Exception {
        ChatCommandServiceGateway gateway = mock(ChatCommandServiceGateway.class);
        when(gateway.steamGame("1091500", null))
            .thenReturn(Optional.of(Map.of("name", "Cyberpunk 2077", "type", "game")));

        SteamGetGameFunction fn = new SteamGetGameFunction(gateway);
        assertThat(fn.namespace()).isEqualTo("steam");
        assertThat(fn.name()).isEqualTo("getGame");
        assertThat(fn.parameterNames()).containsExactly("appId", "locale");
        assertThat(fn.optionalParameterNames()).containsExactly("locale");
        assertThat(fn.returnKeys()).contains("name", "short_description", "supported_languages");

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) fn.invoke(null, new Object[]{"1091500"});
        assertThat(result.get("name")).isEqualTo("Cyberpunk 2077");
    }

    @Test
    void invokePassesThroughAnExplicitLocale() throws Exception {
        ChatCommandServiceGateway gateway = mock(ChatCommandServiceGateway.class);
        when(gateway.steamGame("1091500", "french")).thenReturn(Optional.of(Map.of("name", "Jeu")));

        SteamGetGameFunction fn = new SteamGetGameFunction(gateway);
        fn.invoke(null, new Object[]{"1091500", "french"});

        verify(gateway).steamGame("1091500", "french");
    }

    @Test
    void invokeReturnsAnEmptyMapWhenGatewayFindsNothing() throws Exception {
        ChatCommandServiceGateway gateway = mock(ChatCommandServiceGateway.class);
        when(gateway.steamGame("0", null)).thenReturn(Optional.empty());

        SteamGetGameFunction fn = new SteamGetGameFunction(gateway);
        assertThat(fn.invoke(null, new Object[]{"0"})).isEqualTo(Map.of());
    }
}
