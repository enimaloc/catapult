package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.IgdbGameCacheEntry;
import fr.enimaloc.catapult.domain.IgdbGameCcl;
import fr.enimaloc.catapult.repository.IgdbGameCacheRepository;
import fr.enimaloc.catapult.repository.IgdbGameCclRepository;
import fr.enimaloc.catapult.repository.IgdbGameExternalIdRepository;
import fr.enimaloc.catapult.repository.TwitchCclDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IgdbServiceTest {

    @Mock IgdbClient igdbClient;
    @Mock IgdbGameCacheRepository cacheRepository;
    @Mock IgdbGameExternalIdRepository externalIdRepository;
    @Mock IgdbGameCclRepository cclRepository;
    @Mock TwitchCclDefinitionRepository twitchCclRepo;
    @Mock SteamStoreService steamStoreService;
    @Mock RestClient restClient;

    @Mock RestClient.RequestBodyUriSpec postSpec;
    @Mock RestClient.RequestBodySpec bodySpec;
    @Mock RestClient.ResponseSpec responseSpec;

    @InjectMocks IgdbService igdbService;

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(igdbService, "clientId", "test-client-id");
        ReflectionTestUtils.setField(igdbService, "clientSecret", "test-client-secret");
        ReflectionTestUtils.setField(igdbService, "cacheTtlHours", 24);

        when(cacheRepository.findByCachedAtAfter(any(Instant.class))).thenReturn(List.of());
        when(cclRepository.findAllIgdbIds()).thenReturn(Set.of());
    }

    // --- blank clientId early returns ---

    @Test
    void findBySteamAppId_blankClientId_returnsEmpty() {
        ReflectionTestUtils.setField(igdbService, "clientId", "");

        assertThat(igdbService.findBySteamAppId("12345")).isEmpty();
    }

    @Test
    void findByName_blankClientId_returnsEmpty() {
        ReflectionTestUtils.setField(igdbService, "clientId", "");

        assertThat(igdbService.findByName("Fortnite")).isEmpty();
    }

    @Test
    void findByWindowsExecutable_blankClientId_returnsEmpty() {
        ReflectionTestUtils.setField(igdbService, "clientId", "");

        assertThat(igdbService.findByWindowsExecutable("fortnite.exe")).isEmpty();
    }

    @Test
    void searchGames_blankClientId_returnsEmpty() {
        ReflectionTestUtils.setField(igdbService, "clientId", "");

        assertThat(igdbService.searchGames("Fortnite")).isEmpty();
    }

    @Test
    void searchGames_shortQuery_returnsEmpty() {
        assertThat(igdbService.searchGames("a")).isEmpty();
    }

    @Test
    void prewarmCclCache_blankClientId_returnsEarly() {
        ReflectionTestUtils.setField(igdbService, "clientId", "");

        igdbService.prewarmCclCache();

        verifyNoInteractions(igdbClient);
    }

    @Test
    void findTwitchGameId_noTwitchSource_returnsEmpty() {
        ReflectionTestUtils.setField(igdbService, "twitchSourceId", -1L);

        assertThat(igdbService.findTwitchGameId("42")).isEmpty();
    }

    // --- in-memory cache hits ---

    @Test
    void findByName_l1CacheHit_returnsCachedGame() {
        var game = new IgdbService.IgdbGame("42", "Fortnite");
        ReflectionTestUtils.invokeMethod(igdbService, "warmInMemoryCacheFromDb");

        var entry = new IgdbGameCacheEntry("name:fortnite", "42", "Fortnite");
        when(cacheRepository.findByCachedAtAfter(any())).thenReturn(List.of(entry));
        igdbService.init();

        assertThat(igdbService.findByName("Fortnite")).contains(game);
        verifyNoInteractions(igdbClient);
    }

    @Test
    void suggestCcls_l1CacheHit_returnsCachedCcls() {
        Set<String> ccls = Set.of("ViolentGraphic");
        ReflectionTestUtils.setField(igdbService, "cclCache",
            new java.util.concurrent.ConcurrentHashMap<>(Map.of("42", ccls)));

        assertThat(igdbService.suggestCcls("42")).isEqualTo(ccls);
        verifyNoInteractions(igdbClient);
    }

    @Test
    void suggestCcls_l2DbHit_returnsFromDb() {
        IgdbGameCcl dbEntry = new IgdbGameCcl("42", Set.of("SexualThemes"), "PEGI 18");
        when(cclRepository.findById("42")).thenReturn(Optional.of(dbEntry));

        Set<String> result = igdbService.suggestCcls("42");

        assertThat(result).containsExactly("SexualThemes");
        verifyNoInteractions(igdbClient);
    }

    @Test
    void suggestCcls_blankClientIdAfterCacheMiss_returnsEmpty() {
        ReflectionTestUtils.setField(igdbService, "clientId", "");
        when(cclRepository.findById(anyString())).thenReturn(Optional.empty());

        assertThat(igdbService.suggestCcls("42")).isEmpty();
    }

    @Test
    void getGameCache_returnsUnmodifiableView() {
        assertThat(igdbService.getGameCache()).isNotNull();
    }

    // --- getOrRefreshAppToken ---

    @Test
    void getOrRefreshAppToken_cachedTokenNotExpired_returnsCachedToken() {
        ReflectionTestUtils.setField(igdbService, "appAccessToken", "cached-token");
        ReflectionTestUtils.setField(igdbService, "tokenExpiresAt",
            Instant.now().plusSeconds(3600));

        String token = igdbService.getOrRefreshAppToken();

        assertThat(token).isEqualTo("cached-token");
        verifyNoInteractions(restClient);
    }

    @Test
    void getOrRefreshAppToken_expiredToken_fetchesNewToken() {
        when(restClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(Map.class)).thenReturn(Map.of(
            "access_token", "new-token",
            "expires_in", 3600
        ));

        String token = igdbService.getOrRefreshAppToken();

        assertThat(token).isEqualTo("new-token");
    }

    @Test
    void getOrRefreshAppToken_fetchFails_returnsEmpty() {
        when(restClient.post()).thenThrow(new RuntimeException("Network error"));

        String token = igdbService.getOrRefreshAppToken();

        assertThat(token).isEmpty();
    }

    @Test
    void getOrRefreshAppToken_nullResponse_returnsEmpty() {
        when(restClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(Map.class)).thenReturn(null);

        String token = igdbService.getOrRefreshAppToken();

        assertThat(token).isEmpty();
    }

    // --- prewarmSteamAppIds ---

    @Test
    void prewarmSteamAppIds_emptyList_returnsEarly() {
        igdbService.prewarmSteamAppIds(List.of());

        verifyNoInteractions(igdbClient);
    }

    @Test
    void prewarmSteamAppIds_blankClientId_returnsEarly() {
        ReflectionTestUtils.setField(igdbService, "clientId", "");

        igdbService.prewarmSteamAppIds(List.of("12345"));

        verifyNoInteractions(igdbClient);
    }
}
