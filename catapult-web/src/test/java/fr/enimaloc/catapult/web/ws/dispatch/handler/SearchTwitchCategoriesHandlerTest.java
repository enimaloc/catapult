package fr.enimaloc.catapult.web.ws.dispatch.handler;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.ws.WsSession;
import fr.enimaloc.catapult.web.ws.dispatch.WsBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.core.ParameterizedTypeReference;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchTwitchCategoriesHandlerTest {

    private ApiClient api;
    private SearchTwitchCategoriesHandler handler;

    @BeforeEach
    void setUp() {
        api = mock(ApiClient.class);
        handler = new SearchTwitchCategoriesHandler(api);
    }

    @Test
    void returns_results_from_api() throws Exception {
        when(api.get(ArgumentMatchers.eq("/api/channels/{channelId}/games/search?q={q}"),
                ArgumentMatchers.<ParameterizedTypeReference<List<Map<String, Object>>>>any(),
                ArgumentMatchers.eq("abc"),
                ArgumentMatchers.eq("skyr")))
                .thenReturn(List.of(
                        Map.of("id", "12345", "name", "Skyrim", "boxArtUrl", "url"),
                        Map.of("id", "67890", "name", "Skyrim Special", "boxArtUrl", "url2")
                ));

        Object out = handler.handle((WsSession) null,
                Map.of("channelId", "abc", "q", "skyr", "limit", 8));

        assertThat(out).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .hasSize(2);
    }

    @Test
    void truncates_to_limit() throws Exception {
        when(api.get(ArgumentMatchers.<String>any(),
                ArgumentMatchers.<ParameterizedTypeReference<List<Map<String, Object>>>>any(),
                ArgumentMatchers.<Object>any(),
                ArgumentMatchers.<Object>any()))
                .thenReturn(List.of(
                        Map.of("id", "1"), Map.of("id", "2"),
                        Map.of("id", "3"), Map.of("id", "4")));

        @SuppressWarnings("unchecked")
        List<Object> out = (List<Object>) handler.handle((WsSession) null,
                Map.of("channelId", "abc", "q", "x", "limit", 2));
        assertThat(out).hasSize(2);
    }

    @Test
    void empty_query_returns_empty_list() throws Exception {
        Object out = handler.handle((WsSession) null, Map.of("channelId", "abc", "q", ""));
        assertThat(out).isEqualTo(List.of());
    }

    @Test
    void null_query_returns_empty_list() throws Exception {
        Object out = handler.handle((WsSession) null, Map.of("channelId", "abc"));
        assertThat(out).isEqualTo(List.of());
    }

    @Test
    void missing_channelId_rejected() {
        assertThatThrownBy(() -> handler.handle((WsSession) null, Map.of("q", "x")))
                .isInstanceOf(WsBusinessException.class);
    }

    @Test
    void query_too_long_rejected() {
        String tooLong = "a".repeat(300);
        assertThatThrownBy(() -> handler.handle((WsSession) null,
                Map.of("channelId", "abc", "q", tooLong)))
                .isInstanceOf(WsBusinessException.class);
    }

    @Test
    void api_returning_null_yields_empty_list() throws Exception {
        when(api.get(ArgumentMatchers.<String>any(),
                ArgumentMatchers.<ParameterizedTypeReference<List<Map<String, Object>>>>any(),
                ArgumentMatchers.<Object>any(),
                ArgumentMatchers.<Object>any()))
                .thenReturn(null);
        Object out = handler.handle((WsSession) null, Map.of("channelId", "abc", "q", "x"));
        assertThat(out).isEqualTo(List.of());
    }

    @Test
    void action_and_auth_metadata() {
        assertThat(handler.action()).isEqualTo("search.twitch.categories");
        assertThat(handler.requiresAuth()).isFalse();
        assertThat(handler.requiresAdmin()).isFalse();
    }
}
