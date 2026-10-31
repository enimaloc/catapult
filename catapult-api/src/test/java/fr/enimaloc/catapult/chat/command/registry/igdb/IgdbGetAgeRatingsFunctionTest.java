package fr.enimaloc.catapult.chat.command.registry.igdb;

import fr.enimaloc.catapult.chat.command.js.ChatCommandServiceGateway;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IgdbGetAgeRatingsFunctionTest {

    private static final List<Map<String, Object>> AGE_RATINGS =
        List.of(Map.of("organization", "ESRB", "rating", "M"));

    @Test
    void invokeResolvesIdFromEitherABareIdOrTheGameObject() throws Exception {
        ChatCommandServiceGateway gateway = mock(ChatCommandServiceGateway.class);
        when(gateway.igdbAgeRatings("1234")).thenReturn(Optional.of(AGE_RATINGS));

        IgdbGetAgeRatingsFunction fn = new IgdbGetAgeRatingsFunction(gateway);
        assertThat(fn.namespace()).isEqualTo("igdb");
        assertThat(fn.name()).isEqualTo("getAgeRatings");
        assertThat(fn.parameterNames()).containsExactly("game");

        assertThat(fn.invoke(null, new Object[]{"1234"})).isEqualTo(AGE_RATINGS);
        assertThat(fn.invoke(null, new Object[]{Map.of("id", "1234")})).isEqualTo(AGE_RATINGS);
    }

    @Test
    void invokeReturnsEmptyListWhenGatewayFindsNothing() throws Exception {
        ChatCommandServiceGateway gateway = mock(ChatCommandServiceGateway.class);
        when(gateway.igdbAgeRatings("9999")).thenReturn(Optional.empty());

        IgdbGetAgeRatingsFunction fn = new IgdbGetAgeRatingsFunction(gateway);

        assertThat(fn.invoke(null, new Object[]{"9999"})).isEqualTo(List.of());
    }
}
