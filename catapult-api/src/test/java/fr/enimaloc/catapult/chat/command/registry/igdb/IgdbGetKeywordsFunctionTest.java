package fr.enimaloc.catapult.chat.command.registry.igdb;

import fr.enimaloc.catapult.chat.command.js.ChatCommandServiceGateway;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IgdbGetKeywordsFunctionTest {

    @Test
    void invokeAcceptsABareIgdbId() throws Exception {
        ChatCommandServiceGateway gateway = mock(ChatCommandServiceGateway.class);
        when(gateway.igdbKeywords("1234")).thenReturn(Optional.of(List.of("post-apocalyptic")));

        IgdbGetKeywordsFunction fn = new IgdbGetKeywordsFunction(gateway);
        assertThat(fn.namespace()).isEqualTo("igdb");
        assertThat(fn.name()).isEqualTo("getKeywords");
        assertThat(fn.parameterNames()).containsExactly("game");

        assertThat(fn.invoke(null, new Object[]{"1234"})).isEqualTo(List.of("post-apocalyptic"));
    }

    @Test
    void invokeAcceptsTheGetGameResultObject() throws Exception {
        ChatCommandServiceGateway gateway = mock(ChatCommandServiceGateway.class);
        when(gateway.igdbKeywords("1234")).thenReturn(Optional.of(List.of("post-apocalyptic")));
        Map<String, Object> gameResult = Map.of("id", "1234", "name", "VALORANT");

        IgdbGetKeywordsFunction fn = new IgdbGetKeywordsFunction(gateway);

        assertThat(fn.invoke(null, new Object[]{gameResult})).isEqualTo(List.of("post-apocalyptic"));
    }

    @Test
    void invokeReturnsEmptyListWhenGatewayFindsNothing() throws Exception {
        ChatCommandServiceGateway gateway = mock(ChatCommandServiceGateway.class);
        when(gateway.igdbKeywords("9999")).thenReturn(Optional.empty());

        IgdbGetKeywordsFunction fn = new IgdbGetKeywordsFunction(gateway);

        assertThat(fn.invoke(null, new Object[]{"9999"})).isEqualTo(List.of());
    }
}
