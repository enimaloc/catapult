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

class SearchDtddHandlerTest {

    private ApiClient api;
    private SearchDtddHandler handler;

    @BeforeEach
    void setUp() {
        api = mock(ApiClient.class);
        handler = new SearchDtddHandler(api);
    }

    @Test
    @SuppressWarnings("unchecked")
    void returns_results_from_api() throws Exception {
        when(api.get(ArgumentMatchers.eq("/api/channel/dtdd-mapping/search?q={q}"),
                ArgumentMatchers.<ParameterizedTypeReference<Map<String, Object>>>any(),
                ArgumentMatchers.eq("inception")))
                .thenReturn(Map.of("results", List.of(
                        Map.of("dtddId", 1L, "name", "Inception", "mediaType", "movie"),
                        Map.of("dtddId", 2L, "name", "Inception 2", "mediaType", "movie")
                )));

        Map<String, Object> out = (Map<String, Object>) handler.handle((WsSession) null,
                Map.of("q", "inception"));
        List<?> results = (List<?>) out.get("results");
        assertThat(results).hasSize(2);
    }

    @Test
    void empty_query_short_circuits() throws Exception {
        Object out = handler.handle((WsSession) null, Map.of("q", ""));
        assertThat(out).isEqualTo(Map.of("results", List.of()));
    }

    @Test
    void null_params_yields_empty_results() throws Exception {
        Object out = handler.handle((WsSession) null, null);
        assertThat(out).isEqualTo(Map.of("results", List.of()));
    }

    @Test
    void query_too_long_rejected() {
        assertThatThrownBy(() -> handler.handle((WsSession) null, Map.of("q", "a".repeat(300))))
                .isInstanceOf(WsBusinessException.class);
    }

    @Test
    void api_null_response_yields_empty_results() throws Exception {
        when(api.get(ArgumentMatchers.<String>any(),
                ArgumentMatchers.<ParameterizedTypeReference<Map<String, Object>>>any(),
                ArgumentMatchers.<Object>any()))
                .thenReturn(null);
        Object out = handler.handle((WsSession) null, Map.of("q", "anything"));
        assertThat(out).isEqualTo(Map.of("results", List.of()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void truncates_to_limit() throws Exception {
        when(api.get(ArgumentMatchers.<String>any(),
                ArgumentMatchers.<ParameterizedTypeReference<Map<String, Object>>>any(),
                ArgumentMatchers.<Object>any()))
                .thenReturn(Map.of("results", List.of(
                        Map.of("dtddId", 1L), Map.of("dtddId", 2L),
                        Map.of("dtddId", 3L), Map.of("dtddId", 4L))));
        Map<String, Object> out = (Map<String, Object>) handler.handle((WsSession) null,
                Map.of("q", "x", "limit", 2));
        assertThat((List<?>) out.get("results")).hasSize(2);
    }

    @Test
    void action_metadata() {
        assertThat(handler.action()).isEqualTo("search.dtdd");
        assertThat(handler.requiresAuth()).isFalse();
        assertThat(handler.requiresAdmin()).isFalse();
    }
}
