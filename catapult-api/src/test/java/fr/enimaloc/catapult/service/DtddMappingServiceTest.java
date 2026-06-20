package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.DtddGameCache;
import fr.enimaloc.catapult.domain.DtddGameMapping;
import fr.enimaloc.catapult.domain.DtddSearchCache;
import fr.enimaloc.catapult.getter.DtddApiClient;
import fr.enimaloc.catapult.getter.DtddApiClient.DtddSearchResult;
import fr.enimaloc.catapult.repository.DtddGameCacheRepository;
import fr.enimaloc.catapult.repository.DtddGameMappingRepository;
import fr.enimaloc.catapult.repository.DtddSearchCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DtddMappingServiceTest {

    @Mock DtddGameMappingRepository mappingRepo;
    @Mock DtddGameCacheRepository gameRepo;
    @Mock DtddSearchCacheRepository searchRepo;
    @Mock DtddApiClient apiClient;

    DtddMappingService service;

    @BeforeEach
    void setUp() {
        service = new DtddMappingService(mappingRepo, gameRepo, searchRepo, apiClient);
        service.setSearchTtlDays(30);
        service.setMinConfidence(0.85);
        service.setMinCandidateScore(0.30);
        service.setWeightName(0.7);
        service.setWeightMediaType(0.3);
    }

    @Test
    void resolve_returnsExistingMapping_whenPresent() {
        DtddGameMapping existing = new DtddGameMapping("100", 4521L, 0.92, true, Instant.now());
        when(mappingRepo.findById("100")).thenReturn(Optional.of(existing));

        DtddGameMapping result = service.resolve("100", "Stardew Valley");
        assertThat(result).isSameAs(existing);
        verifyNoInteractions(apiClient);
    }

    @Test
    void resolve_searchesViaApi_andPicksBestMatch_whenNoMapping() {
        when(mappingRepo.findById("100")).thenReturn(Optional.empty());
        when(searchRepo.findById("stardew valley")).thenReturn(Optional.empty());
        when(apiClient.search("Stardew Valley")).thenReturn(Optional.of(List.of(
            new DtddSearchResult(4521L, "Stardew Valley", "stardew-valley", "Video Game", null),
            new DtddSearchResult(9999L, "Stardew Valley Bobblehead", "bobble", "Toy", null)
        )));
        when(mappingRepo.save(any(DtddGameMapping.class))).thenAnswer(inv -> inv.getArgument(0));

        DtddGameMapping result = service.resolve("100", "Stardew Valley");

        assertThat(result.getDtddId()).isEqualTo(4521L);
        assertThat(result.isVerified()).isTrue(); // confidence > 0.85
        verify(gameRepo, times(2)).save(any(DtddGameCache.class));
        verify(searchRepo).save(any(DtddSearchCache.class));
        verify(mappingRepo).save(any(DtddGameMapping.class));
    }

    @Test
    void resolve_persistsNegativeMapping_whenNoCandidateScoresEnough() {
        when(mappingRepo.findById("100")).thenReturn(Optional.empty());
        when(searchRepo.findById("zxqwerty")).thenReturn(Optional.empty());
        when(apiClient.search("zxqwerty")).thenReturn(Optional.of(List.of()));
        when(mappingRepo.save(any(DtddGameMapping.class))).thenAnswer(inv -> inv.getArgument(0));

        DtddGameMapping result = service.resolve("100", "zxqwerty");

        assertThat(result.getDtddId()).isNull();
        assertThat(result.isVerified()).isFalse();
        verify(mappingRepo).save(any(DtddGameMapping.class));
    }

    @Test
    void resolve_reusesSearchCache_whenFresh() {
        when(mappingRepo.findById("100")).thenReturn(Optional.empty());
        DtddSearchCache cache = new DtddSearchCache("stardew valley", List.of(4521L), Instant.now());
        when(searchRepo.findById("stardew valley")).thenReturn(Optional.of(cache));
        when(gameRepo.findAllById(List.of(4521L))).thenReturn(List.of(
            new DtddGameCache(4521L, "Stardew Valley", "stardew-valley", "Video Game", null, Instant.now())
        ));
        when(mappingRepo.save(any(DtddGameMapping.class))).thenAnswer(inv -> inv.getArgument(0));

        DtddGameMapping result = service.resolve("100", "Stardew Valley");

        assertThat(result.getDtddId()).isEqualTo(4521L);
        verifyNoInteractions(apiClient);
    }

    @Test
    void resolve_returnsNegativeMapping_whenApiUnavailable() {
        when(mappingRepo.findById("100")).thenReturn(Optional.empty());
        when(searchRepo.findById("stardew valley")).thenReturn(Optional.empty());
        when(apiClient.search("Stardew Valley")).thenReturn(Optional.empty());

        DtddGameMapping result = service.resolve("100", "Stardew Valley");

        assertThat(result.getDtddId()).isNull();
        // not persisted: avoid blocking future retries when API recovers
        verify(mappingRepo, never()).save(any());
    }
}
