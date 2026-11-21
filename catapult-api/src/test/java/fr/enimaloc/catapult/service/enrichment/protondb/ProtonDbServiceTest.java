package fr.enimaloc.catapult.service.enrichment.protondb;

import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProtonDbServiceTest {

    private static final ProtonDbSummary SUMMARY = new ProtonDbSummary("gold", "gold", "platinum", "strong", 0.7,
            100, "https://www.protondb.com/app/570");

    private final EnrichmentCache cache = mock(EnrichmentCache.class);
    private final ProtonDbClient client = mock(ProtonDbClient.class);
    private final ProtonDbService service = new ProtonDbService(cache, client);

    @Test
    void disabled_empty() {
        when(cache.get(eq(EnrichmentSource.PROTONDB), eq("steam:570"), eq(ProtonDbSummary.class), any(), any()))
                .thenReturn(Optional.empty());

        assertThat(service.summary("570", FetchBudget.UNLIMITED)).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void delegatesToCacheWithSteamKey() {
        when(cache.get(any(), any(), any(), any(), any())).thenReturn(Optional.of(SUMMARY));
        when(client.fetchSummary("570")).thenReturn(Optional.of(SUMMARY));

        assertThat(service.summary("570", FetchBudget.UNLIMITED)).contains(SUMMARY);

        ArgumentCaptor<Supplier<Optional<ProtonDbSummary>>> fetch = ArgumentCaptor.forClass(Supplier.class);
        verify(cache).get(eq(EnrichmentSource.PROTONDB), eq("steam:570"), eq(ProtonDbSummary.class),
                eq(FetchBudget.UNLIMITED), fetch.capture());
        assertThat(fetch.getValue().get()).contains(SUMMARY);
    }
}
