package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.igdb.IgdbGameDetails;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.binding.GameBindingRepository;
import fr.enimaloc.catapult.service.account.WidgetTokenService;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.enrichment.GameEnrichment;
import fr.enimaloc.catapult.service.enrichment.GameEnrichmentAssembler;
import fr.enimaloc.catapult.service.enrichment.protondb.ProtonDbSummary;
import fr.enimaloc.catapult.service.igdb.IgdbGameDetailsService;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import fr.enimaloc.catapult.service.steam.SteamStoreService;
import fr.enimaloc.catapult.service.tw.TwLabelService;
import fr.enimaloc.catapult.service.xbox.XboxStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The v3 game info: the v2 aggregation completed with the enrichment sources. */
class GameInfoAssemblerV3Test {

    private static final String BASE = "https://catapult.test";

    private final GameStateService gameStateService = mock(GameStateService.class);
    private final GameBindingRepository gameBindingRepository = mock(GameBindingRepository.class);
    private final IgdbService igdbService = mock(IgdbService.class);
    private final IgdbGameDetailsService igdbGameDetailsService = mock(IgdbGameDetailsService.class);
    private final SteamStoreService steamStoreService = mock(SteamStoreService.class);
    private final GameEnrichmentAssembler enrichmentAssembler = mock(GameEnrichmentAssembler.class);

    private GameInfoAssembler assembler;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        RequestContextHolder.resetRequestAttributes();
        assembler = new GameInfoAssembler(mock(WidgetTokenService.class), gameStateService, gameBindingRepository,
                igdbService, igdbGameDetailsService, steamStoreService, mock(XboxStoreService.class),
                mock(TwLabelService.class), null, mock(DevBackdoorResolver.class), mock(MessageSource.class),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), new PublicBaseUrl(BASE));
        assembler.setGameEnrichmentAssembler(enrichmentAssembler);
        user = new UserAccount();
        user.setId(UUID.randomUUID());
    }

    @Test
    void nothingDetected_notInGame_noEnrichment() {
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.empty());

        GameInfoV3Response response = assembler.gameInfoV3(user, Locale.ENGLISH);

        assertThat(response.inGame()).isFalse();
        assertThat(response.hltb()).isNull();
        verifyNoInteractions(enrichmentAssembler);
    }

    @Test
    void steamGame_enrichedWithItsAppIdAndIgdbId_currentLinks() {
        DetectedGame detected = new DetectedGame("620", GameBinding.SourceType.STEAM, "Portal 2");
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.of(detected));
        when(igdbService.resolveDetected(eq(GameBinding.SourceType.STEAM), eq("620"), any()))
                .thenReturn(Optional.of(new IgdbService.IgdbGame("72", "Portal 2")));
        IgdbGameDetails details = new IgdbGameDetails();
        details.setIgdbId("72");
        details.setSlug("portal-2");
        when(igdbGameDetailsService.getDetails("72")).thenReturn(Optional.of(details));
        when(steamStoreService.fetchData(anyString(), any(Locale.class))).thenReturn(Optional.empty());
        when(steamStoreService.fetchIADisclosure(anyString(), any(Locale.class))).thenReturn(Optional.empty());
        when(gameBindingRepository.findByUserAndSourceIdAndSourceType(any(), any(), any()))
                .thenReturn(Optional.empty());
        ProtonDbSummary proton = new ProtonDbSummary("gold", "gold", "gold", "good", 0.7, 10, "https://p");
        when(enrichmentAssembler.enrich(any(), any(), any(), any(), any()))
                .thenReturn(new GameEnrichment(null, proton, null, null, null, null, "620"));

        GameInfoV3Response response = assembler.gameInfoV3(user, Locale.ENGLISH);

        verify(enrichmentAssembler).enrich(eq(detected), eq(Optional.of("72")), eq(Optional.of("620")),
                eq("Portal 2"), eq(new MoreLinks(BASE, GameInfoAssembler.LinkStyle.CURRENT)));
        assertThat(response.inGame()).isTrue();
        assertThat(response.igdb().more()).isEqualTo(BASE + "/api/games/igdb/72");
        assertThat(response.protondb().more()).isEqualTo(BASE + "/api/games/protondb/620");
        assertThat(response.protonTier()).isEqualTo("gold");
        assertThat(response.name()).isEqualTo("Portal 2");
    }
}
