package fr.enimaloc.catapult.chat.command.registry.catapult;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.service.GameStateService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatapultGetGameFunctionTest {

    @Test
    void invokeReturnsTheCurrentlyDetectedGame() throws Exception {
        GameStateService gameStateService = mock(GameStateService.class);
        UserAccount user = new UserAccount();
        DetectedGame detected = new DetectedGame("3601890", GameBinding.SourceType.STEAM, "Cosmic Drift");
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.of(detected));

        CatapultGetGameFunction fn = new CatapultGetGameFunction(gameStateService);
        assertThat(fn.namespace()).isEqualTo("catapult");
        assertThat(fn.name()).isEqualTo("getGame");
        assertThat(fn.parameterNames()).isEmpty();
        assertThat(fn.returnKeys()).containsExactly("sourceId", "sourceType", "sourceName");

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) fn.invoke(user, new Object[0]);
        assertThat(result.get("sourceId")).isEqualTo("3601890");
        assertThat(result.get("sourceType")).isEqualTo("STEAM");
        assertThat(result.get("sourceName")).isEqualTo("Cosmic Drift");
    }

    @Test
    void invokeReturnsEmptyFieldsWhenNoGameIsDetected() throws Exception {
        GameStateService gameStateService = mock(GameStateService.class);
        UserAccount user = new UserAccount();
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.empty());

        CatapultGetGameFunction fn = new CatapultGetGameFunction(gameStateService);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) fn.invoke(user, new Object[0]);
        assertThat(result.get("sourceId")).isEqualTo("");
        assertThat(result.get("sourceType")).isEqualTo("");
        assertThat(result.get("sourceName")).isEqualTo("");
    }
}
