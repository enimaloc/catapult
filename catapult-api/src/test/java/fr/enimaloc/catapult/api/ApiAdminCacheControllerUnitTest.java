package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.chat.DynamicCommandResolver;
import fr.enimaloc.catapult.chat.TwPlaceholderRegistry;
import fr.enimaloc.catapult.common.dto.CacheEntryDto;
import fr.enimaloc.catapult.common.dto.CacheSummaryDto;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.igdb.IgdbGameDetails;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.chatcommand.ChatCommandDefinitionRepository;
import fr.enimaloc.catapult.repository.igdb.IgdbGameCclRepository;
import fr.enimaloc.catapult.repository.igdb.IgdbGameDetailsRepository;
import fr.enimaloc.catapult.repository.tw.TwDefinitionRepository;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The cache inspector called directly: every cache's listing, detail and eviction. */
class ApiAdminCacheControllerUnitTest {

    private final IgdbService igdb = mock(IgdbService.class);
    private final DynamicCommandResolver commands = mock(DynamicCommandResolver.class);
    private final TwPlaceholderRegistry tws = mock(TwPlaceholderRegistry.class);
    private final IgdbGameDetailsRepository details = mock(IgdbGameDetailsRepository.class);
    private final IgdbGameCclRepository ccls = mock(IgdbGameCclRepository.class);
    private final TwDefinitionRepository twDefinitions = mock(TwDefinitionRepository.class);
    private final ChatCommandDefinitionRepository commandDefinitions = mock(ChatCommandDefinitionRepository.class);
    private final UserAccountRepository accounts = mock(UserAccountRepository.class);
    private final ApiAdminCacheController controller = new ApiAdminCacheController(
            igdb, commands, tws, details, ccls, twDefinitions, commandDefinitions, accounts);
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "detailsCacheTtlHours", 168);
        IgdbService.IgdbGame doom = new IgdbService.IgdbGame("7", "DOOM");
        when(igdb.getGameCache()).thenReturn(Map.of("7", "DOOM"));
        when(igdb.getNameIndex()).thenReturn(Map.of("doom", doom));
        when(igdb.getExeIndex()).thenReturn(Map.of("doom.exe", doom));
        when(igdb.getCclCache()).thenReturn(Map.of("7", Set.of("ViolentGraphic")));
        when(commands.cacheSnapshot()).thenReturn(Map.of(userId, Set.of("!so")));
        when(tws.getKnownPaths()).thenReturn(Set.of("gore"));
        when(tws.getAllOptions()).thenReturn(List.of(Map.of("id", "gore", "label", "Gore")));
    }

    private static void assertStatus(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(status));
    }

    @Test
    void list_sizesEveryCache_andMarksTheReadOnlyOnes() {
        assertThat(controller.list()).extracting(CacheSummaryDto::name, CacheSummaryDto::size, CacheSummaryDto::deletable)
                .contains(org.assertj.core.groups.Tuple.tuple("igdb-exe-index", 1, true),
                        org.assertj.core.groups.Tuple.tuple("tw-all-options", 1, false));
    }

    @Test
    void entries_ofEveryCache() {
        assertThat(controller.entries("igdb-game-cache", null, 0, 25)).containsExactly(new CacheEntryDto("7", "DOOM"));
        assertThat(controller.entries("igdb-exe-index", null, 0, 25)).containsExactly(new CacheEntryDto("doom.exe", "7 / DOOM"));
        assertThat(controller.entries("igdb-ccl-cache", null, 0, 25)).containsExactly(new CacheEntryDto("7", "ViolentGraphic"));
        assertThat(controller.entries("chat-command-user-cache", null, 0, 25))
                .containsExactly(new CacheEntryDto(userId.toString(), "!so"));
        assertThat(controller.entries("tw-known-paths", " ", 0, 25)).containsExactly(new CacheEntryDto("gore", "gore"));
        assertThat(controller.entries("tw-all-options", null, 0, 25)).containsExactly(new CacheEntryDto("gore", "Gore"));
        assertStatus(() -> controller.entries("nope", null, 0, 25), HttpStatus.NOT_FOUND);
    }

    @Test
    void pagesBeyondTheEnd_areEmpty() {
        assertThat(controller.entries("igdb-game-cache", null, 5, 25).getContent()).isEmpty();
    }

    @Test
    void detail_ofIgdbEntries_includesTheirRemainingTtl() {
        IgdbGameDetails game = new IgdbGameDetails();
        game.setFetchedAt(Instant.now().minus(68, ChronoUnit.HOURS));
        when(details.findById("7")).thenReturn(Optional.of(game));

        var detail = controller.entryDetail("igdb-exe-index", "doom.exe");

        assertThat(detail.detail()).isSameAs(game);
        assertThat(detail.expiresInSeconds()).isBetween(100L * 3600 - 5, 100L * 3600);
        game.setFetchedAt(null);
        assertThat(controller.entryDetail("igdb-game-cache", "7").expiresInSeconds()).isNull();
    }

    @Test
    void detail_ofOtherCaches_hasNoTtl() {
        UserAccount user = new UserAccount();
        when(accounts.findById(userId)).thenReturn(Optional.of(user));
        when(commandDefinitions.findByUser(user)).thenReturn(List.of());
        when(twDefinitions.findById("gore")).thenReturn(Optional.empty());

        assertThat(controller.entryDetail("chat-command-user-cache", userId.toString()).expiresInSeconds()).isNull();
        assertThat(controller.entryDetail("tw-known-paths", "gore").detail()).isNull();
    }

    @Test
    void detail_errors() {
        assertStatus(() -> controller.entryDetail("igdb-exe-index", "unknown.exe"), HttpStatus.NOT_FOUND);
        assertStatus(() -> controller.entryDetail("chat-command-user-cache", "not-a-uuid"), HttpStatus.BAD_REQUEST);
        assertStatus(() -> controller.entryDetail("chat-command-user-cache", UUID.randomUUID().toString()), HttpStatus.NOT_FOUND);
        assertStatus(() -> controller.entryDetail("nope", "x"), HttpStatus.NOT_FOUND);
    }

    @Test
    void eviction_ofEveryDeletableCache() {
        when(igdb.evictGameCache("7")).thenReturn(true);
        when(igdb.evictNameIndex("doom")).thenReturn(true);
        when(igdb.evictExeIndex("doom.exe")).thenReturn(false);
        when(igdb.evictCclCache("7")).thenReturn(true);
        when(commands.evictUser(userId)).thenReturn(true);

        assertThat(controller.deleteEntry("igdb-game-cache", "7").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(controller.deleteEntry("igdb-name-index", "doom").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(controller.deleteEntry("igdb-exe-index", "doom.exe").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.deleteEntry("igdb-ccl-cache", "7").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(controller.deleteEntry("chat-command-user-cache", userId.toString()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void eviction_refusesReadOnlyAndUnknownCaches() {
        assertStatus(() -> controller.deleteEntry("tw-known-paths", "gore"), HttpStatus.BAD_REQUEST);
        assertStatus(() -> controller.deleteEntry("nope", "x"), HttpStatus.NOT_FOUND);
    }
}
