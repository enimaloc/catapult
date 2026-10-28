package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.igdb.IgdbGameDetails;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.binding.GameBindingRepository;
import fr.enimaloc.catapult.service.account.WidgetTokenService;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.igdb.IgdbGameDetailsService;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import fr.enimaloc.catapult.service.steam.SteamStoreService;
import fr.enimaloc.catapult.service.tw.TwLabelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GameInfoAssemblerLinksTest {

    private static final String BASE = "https://catapult.test";

    private final WidgetTokenService widgetTokenService = mock(WidgetTokenService.class);
    private final GameStateService gameStateService = mock(GameStateService.class);
    private final GameBindingRepository gameBindingRepository = mock(GameBindingRepository.class);
    private final IgdbService igdbService = mock(IgdbService.class);
    private final IgdbGameDetailsService igdbGameDetailsService = mock(IgdbGameDetailsService.class);
    private final SteamStoreService steamStoreService = mock(SteamStoreService.class);
    private final DevBackdoorResolver devBackdoorResolver = mock(DevBackdoorResolver.class);
    private final TwLabelService twLabelService = mock(TwLabelService.class);

    private GameInfoAssembler assembler;
    private UserAccount user;
    private GameBinding binding;

    @BeforeEach
    void setUp() {
        assembler = new GameInfoAssembler(widgetTokenService, gameStateService, gameBindingRepository, igdbService,
                igdbGameDetailsService, steamStoreService, null, twLabelService, null, devBackdoorResolver,
                mock(MessageSource.class), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                new PublicBaseUrl(BASE));

        user = new UserAccount();
        user.setId(UUID.randomUUID());
        binding = new GameBinding();
        binding.setId(UUID.randomUUID());
        binding.setUser(user);

        DetectedGame detected = new DetectedGame("620", GameBinding.SourceType.STEAM, "Portal 2");
        when(gameStateService.getLastKnownGame(user)).thenReturn(Optional.of(detected));
        when(igdbService.findByExternalAppId(GameBinding.SourceType.STEAM, "620"))
                .thenReturn(Optional.of(new IgdbService.IgdbGame("72", "Portal 2")));
        IgdbGameDetails details = new IgdbGameDetails();
        details.setIgdbId("72");
        details.setSlug("portal-2");
        when(igdbGameDetailsService.getDetails("72")).thenReturn(Optional.of(details));
        SteamStoreService.SteamStorePage page = mock(SteamStoreService.SteamStorePage.class);
        when(steamStoreService.fetchData(anyString(), any(Locale.class))).thenReturn(Optional.of(page));
        when(steamStoreService.fetchIADisclosure(anyString(), any(Locale.class))).thenReturn(Optional.empty());
        when(gameBindingRepository.findByUserAndSourceIdAndSourceType(user, "620", GameBinding.SourceType.STEAM))
                .thenReturn(Optional.of(binding));
        when(twLabelService.resolve(anyString(), any(Locale.class))).thenReturn("label");
    }

    @Test
    void currentLinks_pointToGamesAndMeBindings() {
        GameInfoResponse response = assembler.gameInfo(user, Locale.ENGLISH);

        assertThat(response.igdb().more()).isEqualTo(BASE + "/api/games/igdb/72");
        assertThat(response.steam().more()).isEqualTo(BASE + "/api/games/steam/620");
        assertThat(response.catapult().more()).isEqualTo(BASE + "/api/me/bindings/" + binding.getId());
    }

    @Test
    void legacyLinks_stillPointToApiUser() {
        UUID token = UUID.randomUUID();
        when(devBackdoorResolver.resolve(token)).thenReturn(Optional.empty());
        when(widgetTokenService.resolve(token)).thenReturn(Optional.of(user));

        GameInfoResponse response = assembler.gameInfo(token, Locale.ENGLISH).orElseThrow();

        assertThat(response.igdb().more()).isEqualTo(BASE + "/api/user/igdb/72");
        assertThat(response.steam().more()).isEqualTo(BASE + "/api/user/steam/620");
        assertThat(response.catapult().more()).isEqualTo(BASE + "/api/user/catapult/" + binding.getId());
    }

    @Test
    void catapult_ofAnotherOwner_isEmpty() {
        UserAccount other = new UserAccount();
        other.setId(UUID.randomUUID());
        when(gameBindingRepository.findById(binding.getId())).thenReturn(Optional.of(binding));

        assertThat(assembler.catapult(binding.getId(), other, Locale.ENGLISH)).isEmpty();
        assertThat(assembler.catapult(binding.getId(), user, Locale.ENGLISH)).isPresent();
    }
}
