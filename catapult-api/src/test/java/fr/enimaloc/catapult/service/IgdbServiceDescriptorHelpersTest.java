package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.IgdbGameCacheEntry;
import fr.enimaloc.catapult.domain.IgdbGameCcl;
import fr.enimaloc.catapult.repository.IgdbGameCacheRepository;
import fr.enimaloc.catapult.repository.IgdbGameCclRepository;
import fr.enimaloc.catapult.repository.IgdbGameExternalIdRepository;
import fr.enimaloc.catapult.repository.TwitchCclDefinitionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IgdbServiceDescriptorHelpersTest {

    @Mock IgdbClient igdbClient;
    @Mock IgdbGameCacheRepository cacheRepository;
    @Mock IgdbGameExternalIdRepository externalIdRepository;
    @Mock IgdbGameCclRepository cclRepository;
    @Mock TwitchCclDefinitionRepository twitchCclRepo;
    @Mock SteamStoreService steamStoreService;
    @Mock RestClient restClient;
    @Mock MeterRegistry meterRegistry;

    @Spy @InjectMocks IgdbService igdbService;

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(igdbService, "clientId", "test-client-id");
        ReflectionTestUtils.setField(igdbService, "clientSecret", "test-client-secret");
        ReflectionTestUtils.setField(igdbService, "cacheTtlHours", 24);
        ReflectionTestUtils.setField(igdbService, "meterRegistry", new SimpleMeterRegistry());

        when(cacheRepository.findByCachedAtAfter(any(Instant.class))).thenReturn(List.of());
        when(cclRepository.findAllIgdbIds()).thenReturn(Set.of());
    }

    @Test
    void fetchDescriptorIds_readsFromCacheIfPresent() {
        IgdbGameCcl cached = new IgdbGameCcl();
        cached.setIgdbId("42");
        cached.setCcls(Set.of("ViolentGraphic"));
        cached.setDescriptorIdsJson("[10,11,12]");
        when(cclRepository.findById("42")).thenReturn(Optional.of(cached));

        assertThat(igdbService.fetchDescriptorIds("42"))
            .containsExactlyInAnyOrder(10L, 11L, 12L);
    }

    @Test
    void fetchDescriptorIds_returnsEmptyWhenCacheMissAndNoToken() {
        ReflectionTestUtils.setField(igdbService, "clientId", "");
        when(cclRepository.findById(any())).thenReturn(Optional.empty());

        assertThat(igdbService.fetchDescriptorIds("missing")).isEmpty();
    }

    @Test
    void resolveIgdbIdForBinding_steamFirst_thenNameFallback() {
        IgdbService.IgdbGame steamHit = new IgdbService.IgdbGame("igdb_steam_100", "Stardew Valley");
        doReturn(Optional.of(steamHit)).when(igdbService).findBySteamAppId("100");

        GameBinding b = new GameBinding();
        b.setSourceType(GameBinding.SourceType.STEAM);
        b.setSourceId("100");
        b.setSourceName("Stardew Valley");

        assertThat(igdbService.resolveIgdbIdForBinding(b)).hasValue("igdb_steam_100");
    }
}
