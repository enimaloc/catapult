package fr.enimaloc.catapult.service.epic;

import fr.enimaloc.catapult.service.epic.api.EpicAuthException;
import fr.enimaloc.catapult.service.epic.api.EpicCatalogApi;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SessionEpicCatalogClientTest {

    private final EpicSessionManager sessions = mock(EpicSessionManager.class);
    private final EpicCatalogApi api = mock(EpicCatalogApi.class);
    private final SessionEpicCatalogClient client = new SessionEpicCatalogClient(sessions, api);

    @Test
    void noOpenSession_answersNothing_withoutCallingTheCatalog() {
        when(sessions.anyOpenSessionToken()).thenReturn(Optional.empty());

        assertThat(client.priceTiers(List.of("o1"))).isEmpty();

        verifyNoInteractions(api);
    }

    @Test
    void openSession_asksTheCatalogWithItsToken() {
        when(sessions.anyOpenSessionToken()).thenReturn(Optional.of("tok"));
        when(api.priceTiers("tok", List.of("o1"))).thenReturn(Map.of("o1", "p_USD_0_1"));

        assertThat(client.priceTiers(List.of("o1"))).containsExactly(Map.entry("o1", "p_USD_0_1"));
    }

    @Test
    void noOfferIds_neverTouchesTheSessionsNorTheCatalog() {
        assertThat(client.priceTiers(List.of())).isEmpty();

        verifyNoInteractions(sessions, api);
    }

    @Test
    void longBatches_areSplitInChunksOfFifty_andMerged() {
        List<String> ids = IntStream.range(0, 120).mapToObj(i -> "o" + i).toList();
        when(sessions.anyOpenSessionToken()).thenReturn(Optional.of("tok"));
        when(api.priceTiers("tok", ids.subList(0, 50))).thenReturn(Map.of("o0", "t0"));
        when(api.priceTiers("tok", ids.subList(50, 100))).thenReturn(Map.of("o50", "t50"));
        when(api.priceTiers("tok", ids.subList(100, 120))).thenReturn(Map.of("o119", "t119"));

        assertThat(client.priceTiers(ids)).containsOnly(
                Map.entry("o0", "t0"), Map.entry("o50", "t50"), Map.entry("o119", "t119"));
    }

    @Test
    void rejectedToken_answersNothing() {
        when(sessions.anyOpenSessionToken()).thenReturn(Optional.of("tok"));
        when(api.priceTiers(anyString(), any()))
                .thenThrow(new EpicAuthException(EpicAuthException.Reason.ACCESS_REJECTED));

        assertThat(client.priceTiers(List.of("o1"))).isEmpty();
    }

    @Test
    void failingCatalog_answersNothing() {
        when(sessions.anyOpenSessionToken()).thenReturn(Optional.of("tok"));
        when(api.priceTiers(anyString(), any())).thenThrow(new RestClientException("HTTP 503"));

        assertThat(client.priceTiers(List.of("o1"))).isEmpty();
    }

    @Test
    void failedChunk_keepsTheTiersOfTheOthers() {
        List<String> ids = IntStream.range(0, 60).mapToObj(i -> "o" + i).toList();
        when(sessions.anyOpenSessionToken()).thenReturn(Optional.of("tok"));
        when(api.priceTiers("tok", ids.subList(0, 50))).thenThrow(new RestClientException("HTTP 503"));
        when(api.priceTiers("tok", ids.subList(50, 60))).thenReturn(Map.of("o55", "t55"));

        assertThat(client.priceTiers(ids)).containsOnly(Map.entry("o55", "t55"));
    }

    @Test
    void canCheck_onlyWithAnOpenSession() {
        when(sessions.anyOpenSessionToken()).thenReturn(Optional.empty(), Optional.of("tok"));

        assertThat(client.canCheck()).isFalse();
        assertThat(client.canCheck()).isTrue();
    }
}
