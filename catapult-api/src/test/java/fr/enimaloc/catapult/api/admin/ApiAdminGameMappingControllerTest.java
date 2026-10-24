package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.common.dto.admin.GameMappingRequest;
import fr.enimaloc.catapult.common.dto.admin.GameMappingResult;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.binding.GameMapping;
import fr.enimaloc.catapult.event.listener.GameEventListener;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.binding.GameBindingRepository;
import fr.enimaloc.catapult.repository.binding.GameMappingRepository;
import fr.enimaloc.catapult.service.binding.AdminGameMappingService;
import fr.enimaloc.catapult.service.binding.BindingService;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import fr.enimaloc.catapult.service.twitch.TwitchCategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApiAdminGameMappingControllerTest {

    @Mock GameMappingRepository mappingRepo;
    @Mock GameBindingRepository bindingRepo;
    @Mock UserAccountRepository userRepo;
    @Mock BindingService bindingService;
    @Mock GameStateService gameStateService;
    @Mock StreamStateService streamStateService;
    @Mock ChannelEventPublisher channelEventPublisher;
    @Mock GameEventListener gameEventListener;
    @Mock PlatformTransactionManager transactionManager;
    @Mock TwitchCategoryService categories;
    @Mock ApiUserResolver userResolver;
    @Mock Jwt jwt;

    ApiAdminGameMappingController controller;
    UserAccount admin;

    @BeforeEach
    void setUp() {
        AdminGameMappingService service = new AdminGameMappingService(mappingRepo, bindingRepo, userRepo,
                bindingService, gameStateService, streamStateService, channelEventPublisher, gameEventListener,
                transactionManager);
        controller = new ApiAdminGameMappingController(service, categories, userResolver);
        admin = new UserAccount();
        admin.setId(UUID.randomUUID());
        when(userResolver.viewer(jwt)).thenReturn(admin);
        when(mappingRepo.saveAndFlush(any(GameMapping.class))).thenAnswer(inv -> {
            GameMapping m = inv.getArgument(0);
            m.setId(UUID.randomUUID());
            return m;
        });
        when(gameStateService.lastKnownGames()).thenReturn(Map.of());
    }

    private static int status(Throwable e) {
        return ((ResponseStatusException) e).getStatusCode().value();
    }

    @Test
    void manualPlatform_isRejected() {
        GameMappingRequest request = new GameMappingRequest("MANUAL", "123", "1", "Cat", null, null, false);

        assertThatThrownBy(() -> controller.create(request, jwt))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(status(e)).isEqualTo(400));
        verify(mappingRepo, never()).saveAndFlush(any());
    }

    @Test
    void missingCategory_isRejected() {
        GameMappingRequest noId = new GameMappingRequest("STEAM", "730", " ", "Cat", null, null, false);
        GameMappingRequest noName = new GameMappingRequest("STEAM", "730", "1", null, null, null, false);

        assertThatThrownBy(() -> controller.create(noId, jwt)).satisfies(e -> assertThat(status(e)).isEqualTo(400));
        assertThatThrownBy(() -> controller.create(noName, jwt)).satisfies(e -> assertThat(status(e)).isEqualTo(400));
        verify(mappingRepo, never()).saveAndFlush(any());
    }

    @Test
    void duplicate_is409() {
        GameMappingRequest request = new GameMappingRequest("STEAM", "730", "1", "Cat", null, null, false);
        when(mappingRepo.findBySourceTypeAndSourceId(GameBinding.SourceType.STEAM, "730"))
                .thenReturn(Optional.of(new GameMapping()));

        assertThatThrownBy(() -> controller.create(request, jwt)).satisfies(e -> assertThat(status(e)).isEqualTo(409));

        // Two admins at once: the unique constraint is what catches the second.
        when(mappingRepo.findBySourceTypeAndSourceId(GameBinding.SourceType.STEAM, "730")).thenReturn(Optional.empty());
        when(mappingRepo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_game_mapping"));
        assertThatThrownBy(() -> controller.create(request, jwt)).satisfies(e -> assertThat(status(e)).isEqualTo(409));
    }

    @Test
    void create_returns201WithCounts() {
        GameBinding auto = new GameBinding();
        auto.setUser(admin);
        auto.setSourceType(GameBinding.SourceType.STEAM);
        auto.setSourceId("730");
        auto.setStatus(GameBinding.Status.AUTO);
        when(bindingRepo.lockAllBySourceTypeAndSourceIdAndStatusIn(any(), any(), anyCollection()))
                .thenReturn(List.of(auto));
        when(bindingService.applyMapping(any(), any(), any())).thenReturn(true);
        GameMappingRequest request = new GameMappingRequest("STEAM", "730", "32399", "Counter-Strike",
                Set.of(), null, false);

        ResponseEntity<GameMappingResult> response = controller.create(request, jwt);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().bindingsUpdated()).isEqualTo(1);
        assertThat(response.getBody().liveChannelsUpdated()).isZero();
        assertThat(response.getBody().mapping().twitchGameName()).isEqualTo("Counter-Strike");
        assertThat(response.getBody().mapping().ccls()).isEmpty();
        assertThat(response.getBody().mapping().tws()).isNull();
    }

    @Test
    void blankCategoryQuery_returnsEmpty() {
        assertThat(controller.categories("  ")).isEmpty();
        assertThat(controller.categories(null)).isEmpty();
        verifyNoInteractions(categories);
    }
}
