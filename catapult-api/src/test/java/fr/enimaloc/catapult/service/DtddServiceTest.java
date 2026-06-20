package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.DtddGameMapping;
import fr.enimaloc.catapult.domain.DtddTopicsCache;
import fr.enimaloc.catapult.getter.DtddApiClient;
import fr.enimaloc.catapult.getter.DtddApiClient.DtddTopics;
import fr.enimaloc.catapult.repository.DtddTopicsCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DtddServiceTest {

    @Mock DtddMappingService mappingService;
    @Mock DtddTopicsCacheRepository topicsRepo;
    @Mock DtddApiClient apiClient;

    DtddService service;

    @BeforeEach
    void setUp() {
        service = new DtddService(mappingService, topicsRepo, apiClient);
        service.setTopicsTtlHours(168);
    }

    @Test
    void getTopics_returnsEmpty_whenMappingHasNoDtddId() {
        when(mappingService.resolve("100", "Stardew")).thenReturn(
            new DtddGameMapping("100", null, 0.0, false, Instant.now()));

        assertThat(service.getTopics("100", "Stardew")).isEmpty();
        verifyNoInteractions(apiClient);
    }

    @Test
    void getTopics_returnsCachedFresh_withoutCallingApi() {
        when(mappingService.resolve("100", "Stardew")).thenReturn(
            new DtddGameMapping("100", 4521L, 0.92, true, Instant.now()));
        when(topicsRepo.findById(4521L)).thenReturn(Optional.of(new DtddTopicsCache(
            4521L, List.of("A dog dies"), List.of(), List.of(), Instant.now())));

        Optional<DtddTopics> topics = service.getTopics("100", "Stardew");

        assertThat(topics).isPresent();
        assertThat(topics.get().yesTopics()).containsExactly("A dog dies");
        verifyNoInteractions(apiClient);
    }

    @Test
    void getTopics_returnsCachedStale_immediately() {
        when(mappingService.resolve("100", "Stardew")).thenReturn(
            new DtddGameMapping("100", 4521L, 0.92, true, Instant.now()));
        DtddTopicsCache stale = new DtddTopicsCache(
            4521L, List.of("old"), List.of(), List.of(),
            Instant.now().minus(30, ChronoUnit.DAYS));
        when(topicsRepo.findById(4521L)).thenReturn(Optional.of(stale));

        Optional<DtddTopics> topics = service.getTopics("100", "Stardew");

        assertThat(topics).isPresent();
        assertThat(topics.get().yesTopics()).containsExactly("old");
    }

    @Test
    void getTopics_fetchesFromApi_andPersists_onCacheMiss() {
        when(mappingService.resolve("100", "Stardew")).thenReturn(
            new DtddGameMapping("100", 4521L, 0.92, true, Instant.now()));
        when(topicsRepo.findById(4521L)).thenReturn(Optional.empty());
        when(apiClient.fetchTopics(4521L)).thenReturn(Optional.of(
            new DtddTopics(List.of("A dog dies"), List.of(), List.of())));

        Optional<DtddTopics> topics = service.getTopics("100", "Stardew");

        assertThat(topics).isPresent();
        assertThat(topics.get().yesTopics()).containsExactly("A dog dies");
        verify(topicsRepo).save(any(DtddTopicsCache.class));
    }

    @Test
    void getTopics_returnsEmpty_onCacheMissAndApiFail() {
        when(mappingService.resolve("100", "Stardew")).thenReturn(
            new DtddGameMapping("100", 4521L, 0.92, true, Instant.now()));
        when(topicsRepo.findById(4521L)).thenReturn(Optional.empty());
        when(apiClient.fetchTopics(4521L)).thenReturn(Optional.empty());

        assertThat(service.getTopics("100", "Stardew")).isEmpty();
    }
}
