package fr.enimaloc.catapult.service.binding;

import fr.enimaloc.catapult.common.dto.admin.DetectedGameDto;
import fr.enimaloc.catapult.common.dto.admin.GameMappingRequest;
import fr.enimaloc.catapult.common.dto.admin.GameMappingResult;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.binding.GameBinding.SourceType;
import fr.enimaloc.catapult.domain.binding.GameBinding.Status;
import fr.enimaloc.catapult.domain.binding.GameMapping;
import fr.enimaloc.catapult.event.listener.GameEventListener;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.repository.binding.GameBindingRepository;
import fr.enimaloc.catapult.repository.binding.GameMappingRepository;
import fr.enimaloc.catapult.service.igdb.IgdbGameDetailsService;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import fr.enimaloc.catapult.service.tw.TwResolverService;
import fr.enimaloc.catapult.service.twitch.ChannelUpdateOutcome;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminGameMappingServiceTest {

    @Mock GameMappingRepository mappingRepo;
    @Mock GameBindingRepository bindingRepo;
    @Mock UserAccountRepository userRepo;
    @Mock IgdbService igdbService;
    @Mock IgdbGameDetailsService igdbGameDetailsService;
    @Mock TwitchService twitchService;
    @Mock TwResolverService twResolverService;
    @Mock GameStateService gameStateService;
    @Mock StreamStateService streamStateService;
    @Mock ChannelEventPublisher channelEventPublisher;
    @Mock UserSettingsRepository userSettingsRepository;
    @Mock RaidRestoreGameService raidRestoreGameService;
    @Mock PlatformTransactionManager transactionManager;

    BindingService bindingService;
    AdminGameMappingService service;
    UserAccount admin;
    final AtomicBoolean committed = new AtomicBoolean();
    final Map<UUID, GameMapping> mappings = new HashMap<>();

    @BeforeEach
    void setUp() {
        bindingService = spy(new BindingService(bindingRepo, igdbService, igdbGameDetailsService, twitchService,
                twResolverService, gameStateService, mappingRepo));
        GameEventListener listener = new GameEventListener(bindingService, twitchService, userSettingsRepository,
                streamStateService, channelEventPublisher, raidRestoreGameService);
        service = new AdminGameMappingService(mappingRepo, bindingRepo, userRepo, bindingService, gameStateService,
                streamStateService, channelEventPublisher, listener, transactionManager);

        admin = user("admin");
        doAnswer(inv -> { committed.set(true); return null; }).when(transactionManager).commit(any());
        when(mappingRepo.saveAndFlush(any(GameMapping.class))).thenAnswer(inv -> {
            GameMapping m = inv.getArgument(0);
            if (m.getId() == null) m.setId(UUID.randomUUID());
            mappings.put(m.getId(), m);
            return m;
        });
        when(mappingRepo.findById(any())).thenAnswer(inv -> Optional.ofNullable(mappings.get(inv.<UUID>getArgument(0))));
        when(mappingRepo.findBySourceTypeAndSourceId(any(), any())).thenAnswer(inv -> mappings.values().stream()
                .filter(m -> m.getSourceType() == inv.getArgument(0) && m.getSourceId().equals(inv.getArgument(1)))
                .findFirst());
        when(bindingRepo.save(any(GameBinding.class))).thenAnswer(inv -> inv.getArgument(0));
        when(gameStateService.lastKnownGames()).thenReturn(Map.of());
        when(twitchService.updateChannelReporting(any(), any())).thenReturn(ChannelUpdateOutcome.APPLIED);
    }

    private static UserAccount user(String login) {
        UserAccount u = new UserAccount();
        u.setId(UUID.randomUUID());
        u.setTwitchUsername(login);
        return u;
    }

    private static GameBinding binding(UserAccount owner, String sourceId, Status status) {
        GameBinding b = new GameBinding();
        b.setId(UUID.randomUUID());
        b.setUser(owner);
        b.setSourceType(SourceType.STEAM);
        b.setSourceId(sourceId);
        b.setSourceName("Counter-Strike 2");
        b.setStatus(status);
        b.setTwitchGameId(status == Status.INCOMPLETE ? null : "old");
        b.setTwitchGameName(status == Status.INCOMPLETE ? null : "Old");
        return b;
    }

    private static GameMappingRequest request(String sourceId, String twitchId, boolean applyLive) {
        return new GameMappingRequest("STEAM", sourceId, twitchId, "Category " + twitchId, Set.of("Gambling"),
                Set.of(), applyLive);
    }

    private void bindingsOfTheGame(GameBinding... bindings) {
        when(bindingRepo.lockAllBySourceTypeAndSourceIdAndStatusIn(eq(SourceType.STEAM), eq("730"), anyCollection()))
                .thenReturn(new ArrayList<>(List.of(bindings)));
    }

    private void liveOn(UserAccount owner, String sourceId) {
        when(streamStateService.isLive(owner)).thenReturn(true);
        Map<UUID, DetectedGame> games = new HashMap<>(gameStateService.lastKnownGames());
        games.put(owner.getId(), new DetectedGame(sourceId, SourceType.STEAM, "Some game"));
        when(gameStateService.lastKnownGames()).thenReturn(games);
    }

    @Test
    void create_mapsAutoAndIncompleteBindings() {
        GameBinding auto1 = binding(user("a"), "730", Status.AUTO);
        GameBinding auto2 = binding(user("b"), "730", Status.AUTO);
        GameBinding incomplete = binding(user("c"), "730", Status.INCOMPLETE);
        GameBinding manual = binding(user("d"), "730", Status.MANUAL);
        bindingsOfTheGame(auto1, auto2, incomplete, manual);

        GameMappingResult result = service.create(request("730", "32399", false), admin);

        assertThat(result.bindingsUpdated()).isEqualTo(3);
        assertThat(List.of(auto1, auto2, incomplete)).allSatisfy(b -> {
            assertThat(b.getStatus()).isEqualTo(Status.MAPPED);
            assertThat(b.getTwitchGameId()).isEqualTo("32399");
            assertThat(b.getCcls()).containsExactly("Gambling");
        });
        verify(bindingRepo).save(auto1);
        verify(bindingRepo).save(incomplete);
        assertThat(result.mapping().sourceId()).isEqualTo("730");
        assertThat(result.mapping().updatedBy()).isEqualTo("admin");
        assertThat(mappings.values()).singleElement().satisfies(m -> assertThat(m.getUpdatedBy()).isEqualTo(admin.getId()));
    }

    @Test
    void manualBinding_isNeverTouched() {
        GameBinding manual = binding(user("d"), "730", Status.MANUAL);
        bindingsOfTheGame(manual);
        AtomicBoolean manualUntouchedInTx = new AtomicBoolean();
        doAnswer(inv -> {
            manualUntouchedInTx.set(manual.getStatus() == Status.MANUAL && "old".equals(manual.getTwitchGameId()));
            committed.set(true);
            return null;
        }).when(transactionManager).commit(any());

        GameMappingResult result = service.create(request("730", "32399", true), admin);

        assertThat(manualUntouchedInTx).isTrue();
        assertThat(result.bindingsUpdated()).isZero();
        assertThat(manual.getStatus()).isEqualTo(Status.MANUAL);
        assertThat(manual.getTwitchGameId()).isEqualTo("old");
        verify(bindingRepo, never()).save(manual);
        verify(channelEventPublisher, never()).bindingUpserted(any(), eq(manual));
        // Only the bindings a mapping may change are read.
        ArgumentCaptor<java.util.Collection<Status>> statuses = ArgumentCaptor.captor();
        verify(bindingRepo).lockAllBySourceTypeAndSourceIdAndStatusIn(eq(SourceType.STEAM), eq("730"), statuses.capture());
        assertThat(statuses.getValue()).doesNotContain(Status.MANUAL);
    }

    @Test
    void update_movesMappedBindingsToTheNewCategory() {
        GameMappingResult created = service.create(request("730", "32399", false), admin);
        GameBinding mapped = binding(user("a"), "730", Status.MAPPED);
        mapped.setTwitchGameId("32399");
        mapped.setTwitchGameName("Category 32399");
        mapped.getCcls().add("Gambling");
        bindingsOfTheGame(mapped);

        GameMappingResult result = service.update(created.mapping().id(), request("730", "12345", false), admin);

        assertThat(result.bindingsUpdated()).isEqualTo(1);
        assertThat(mapped.getStatus()).isEqualTo(Status.MAPPED);
        assertThat(mapped.getTwitchGameId()).isEqualTo("12345");
        assertThat(mapped.getTwitchGameName()).isEqualTo("Category 12345");
        assertThat(result.mapping().twitchGameId()).isEqualTo("12345");
    }

    @Test
    void delete_returnsMappedBindingsToIgdb() {
        GameMappingResult created = service.create(request("730", "32399", false), admin);
        GameBinding mapped1 = binding(user("a"), "730", Status.MAPPED);
        GameBinding mapped2 = binding(user("b"), "730", Status.MAPPED);
        when(bindingRepo.lockAllBySourceTypeAndSourceIdAndStatusIn(SourceType.STEAM, "730", List.of(Status.MAPPED)))
                .thenReturn(List.of(mapped1, mapped2));

        GameMappingResult result = service.delete(created.mapping().id(), false);

        verify(bindingService).resolveWithoutMapping(mapped1);
        verify(bindingService).resolveWithoutMapping(mapped2);
        verify(mappingRepo).delete(any(GameMapping.class));
        assertThat(result.mapping()).isNull();
        assertThat(result.bindingsUpdated()).isEqualTo(2);
        // IGDB finds nothing here: they are incomplete again.
        assertThat(mapped1.getStatus()).isEqualTo(Status.INCOMPLETE);
    }

    @Test
    void duplicate_isRejected() {
        service.create(request("730", "32399", false), admin);

        assertThatThrownBy(() -> service.create(request("730", "999", false), admin))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
    }

    @Test
    void sourceId_isTrimmed() {
        bindingsOfTheGame();

        GameMappingResult result = service.create(request(" 730 ", "32399", false), admin);

        assertThat(result.mapping().sourceId()).isEqualTo("730");
        verify(mappingRepo).findBySourceTypeAndSourceId(SourceType.STEAM, "730");
        verify(bindingRepo).lockAllBySourceTypeAndSourceIdAndStatusIn(eq(SourceType.STEAM), eq("730"), anyCollection());
    }

    @Test
    void applyLive_updatesOnlyLiveChannelsOnThisGame() {
        UserAccount onIt = user("on-it");
        UserAccount offline = user("offline");
        GameBinding live = binding(onIt, "730", Status.AUTO);
        GameBinding notLive = binding(offline, "730", Status.AUTO);
        bindingsOfTheGame(live, notLive);
        liveOn(onIt, "730");
        when(gameStateService.lastKnownGames()).thenReturn(Map.of(
                onIt.getId(), new DetectedGame("730", SourceType.STEAM, "CS2"),
                offline.getId(), new DetectedGame("730", SourceType.STEAM, "CS2")));
        AtomicBoolean calledAfterCommit = new AtomicBoolean();
        doAnswer(inv -> { calledAfterCommit.set(committed.get()); return ChannelUpdateOutcome.APPLIED; })
                .when(twitchService).updateChannelReporting(any(), any());

        GameMappingResult result = service.create(request("730", "32399", true), admin);

        verify(twitchService).updateChannelReporting(onIt, live);
        verify(twitchService, never()).updateChannelReporting(eq(offline), any());
        assertThat(calledAfterCommit).isTrue();
        assertThat(result.liveChannelsUpdated()).isEqualTo(1);
        assertThat(result.liveChannelsFailed()).isZero();
    }

    @Test
    void applyLive_skipsChannelsOnAnotherGame() {
        UserAccount elsewhere = user("elsewhere");
        bindingsOfTheGame(binding(elsewhere, "730", Status.AUTO));
        liveOn(elsewhere, "570");

        GameMappingResult result = service.create(request("730", "32399", true), admin);

        verify(twitchService, never()).updateChannelReporting(any(), any());
        assertThat(result.bindingsUpdated()).isEqualTo(1);
        assertThat(result.liveChannelsUpdated()).isZero();
    }

    @Test
    void withoutApplyLive_noTwitchCall() {
        UserAccount onIt = user("on-it");
        bindingsOfTheGame(binding(onIt, "730", Status.AUTO));
        liveOn(onIt, "730");

        GameMappingResult result = service.create(request("730", "32399", false), admin);

        verify(twitchService, never()).updateChannelReporting(any(), any());
        assertThat(result.liveChannelsUpdated()).isZero();
    }

    @Test
    void twitchRefusesTheUpdate_isCountedAsFailed_notRolledBack() {
        UserAccount onIt = user("on-it");
        bindingsOfTheGame(binding(onIt, "730", Status.AUTO));
        liveOn(onIt, "730");
        when(twitchService.updateChannelReporting(any(), any())).thenReturn(ChannelUpdateOutcome.FAILED);

        GameMappingResult result = service.create(request("730", "32399", true), admin);

        assertThat(result.liveChannelsFailed()).isEqualTo(1);
        assertThat(result.liveChannelsUpdated()).isZero();
        assertThat(result.mapping()).isNotNull();
        assertThat(mappings).hasSize(1);
        verify(transactionManager, never()).rollback(any());
    }

    @Test
    void twitchUpdateSkipped_isNeitherUpdatedNorFailed() {
        // e.g. the streamer's bot is disabled: nothing was sent to Twitch.
        UserAccount onIt = user("on-it");
        bindingsOfTheGame(binding(onIt, "730", Status.AUTO));
        liveOn(onIt, "730");
        when(twitchService.updateChannelReporting(any(), any())).thenReturn(ChannelUpdateOutcome.SKIPPED);

        GameMappingResult result = service.create(request("730", "32399", true), admin);

        assertThat(result.bindingsUpdated()).isEqualTo(1);
        assertThat(result.liveChannelsUpdated()).isZero();
        assertThat(result.liveChannelsFailed()).isZero();
    }

    @Test
    void unexpectedErrorPushingALiveChannel_isCountedAsFailed() {
        UserAccount onIt = user("on-it");
        bindingsOfTheGame(binding(onIt, "730", Status.AUTO));
        liveOn(onIt, "730");
        when(twitchService.updateChannelReporting(any(), any())).thenThrow(new IllegalStateException("boom"));

        GameMappingResult result = service.create(request("730", "32399", true), admin);

        assertThat(result.liveChannelsFailed()).isEqualTo(1);
        assertThat(result.liveChannelsUpdated()).isZero();
        verify(transactionManager, never()).rollback(any());
    }

    @Test
    void incompleteAfterDelete_withoutFallback_isNeitherUpdatedNorFailed() {
        UserAccount onIt = user("on-it");
        GameMapping mapping = new GameMapping();
        mapping.setId(UUID.randomUUID());
        mapping.setSourceType(SourceType.STEAM);
        mapping.setSourceId("730");
        mapping.setTwitchGameId("32399");
        mapping.setTwitchGameName("Counter-Strike");
        mappings.put(mapping.getId(), mapping);
        GameBinding mapped = binding(onIt, "730", Status.MAPPED);
        bindingsOfTheGame(mapped);
        liveOn(onIt, "730");
        // Back to IGDB, which knows no category for it: INCOMPLETE, and the streamer set no incomplete fallback.
        doAnswer(inv -> {
            GameBinding b = inv.getArgument(0);
            b.setStatus(Status.INCOMPLETE);
            b.setTwitchGameId(null);
            b.setTwitchGameName(null);
            return b;
        }).when(bindingService).resolveWithoutMapping(any());
        when(userSettingsRepository.findById(onIt.getId())).thenReturn(Optional.empty());

        GameMappingResult result = service.delete(mapping.getId(), true);

        assertThat(result.bindingsUpdated()).isEqualTo(1);
        assertThat(result.liveChannelsUpdated()).isZero();
        assertThat(result.liveChannelsFailed()).isZero();
        verify(twitchService, never()).updateChannelReporting(any(), any());
    }

    @Test
    void everyChangedBinding_isPushedToItsDashboard() {
        UserAccount a = user("a");
        UserAccount b = user("b");
        GameBinding first = binding(a, "730", Status.AUTO);
        GameBinding second = binding(b, "730", Status.INCOMPLETE);
        bindingsOfTheGame(first, second);

        service.create(request("730", "32399", false), admin);

        verify(channelEventPublisher).bindingUpserted(a.getId(), first);
        verify(channelEventPublisher).bindingUpserted(b.getId(), second);
        verify(channelEventPublisher, times(2)).bindingUpserted(any(), any());
    }

    @Test
    void missing_listsIncompleteGamesWithoutMapping_byStreamerCount() {
        Pageable paging = PageRequest.of(0, 25);
        when(bindingRepo.findGamesMissingAMapping(paging)).thenReturn(new PageImpl<>(List.of(
                new Object[]{"STEAM", "730", 3L},
                new Object[]{"XBOX", "x1", 1L}), paging, 2));
        when(bindingRepo.countSourceNames(anyCollection(), anyCollection())).thenReturn(List.of(
                new Object[]{SourceType.STEAM, "730", "CS2", 1L},
                new Object[]{SourceType.STEAM, "730", "Counter-Strike 2", 2L},
                new Object[]{SourceType.XBOX, "x1", "Halo", 1L},
                // Same ID on another platform: not this game's name.
                new Object[]{SourceType.STEAM, "x1", "Other", 9L}));

        List<DetectedGameDto> missing = service.missing(0, 25).getContent();

        assertThat(missing).containsExactly(
                new DetectedGameDto("STEAM", "730", "Counter-Strike 2", 3),
                new DetectedGameDto("XBOX", "x1", "Halo", 1));
        // Narrowed to the listed games' platforms, for the (source_type, source_id) index.
        verify(bindingRepo).countSourceNames(Set.of(SourceType.STEAM, SourceType.XBOX), Set.of("730", "x1"));
    }

    @Test
    void detected_matchesIdPrefixOrNameAndCapsAt20() {
        when(bindingRepo.findDetectedGames(eq(SourceType.STEAM), any(), any(), any())).thenReturn(List.<Object[]>of(
                new Object[]{SourceType.STEAM, "730", 4L}));
        when(bindingRepo.countSourceNames(anyCollection(), anyCollection())).thenReturn(List.<Object[]>of(
                new Object[]{SourceType.STEAM, "730", "Counter-Strike 2", 4L}));

        List<DetectedGameDto> found = service.detected("STEAM", " Co_ ");

        assertThat(found).containsExactly(new DetectedGameDto("STEAM", "730", "Counter-Strike 2", 4));
        ArgumentCaptor<Pageable> paging = ArgumentCaptor.forClass(Pageable.class);
        verify(bindingRepo).findDetectedGames(eq(SourceType.STEAM), eq("co\\_%"), eq("%co\\_%"), paging.capture());
        assertThat(paging.getValue().getPageSize()).isEqualTo(20);
        assertThatThrownBy(() -> service.detected("MANUAL", "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void bindingFoundManualOnceLocked_isLeftUntouched() {
        // Listed by status, then a streamer chose MANUAL: the locked re-read shows it, the mapping skips it.
        GameBinding nowManual = binding(user("d"), "730", Status.MANUAL);
        bindingsOfTheGame(nowManual);
        GameMappingResult created = service.create(request("730", "32399", false), admin);
        when(bindingRepo.lockAllBySourceTypeAndSourceIdAndStatusIn(SourceType.STEAM, "730", List.of(Status.MAPPED)))
                .thenReturn(List.of(nowManual));

        GameMappingResult deleted = service.delete(created.mapping().id(), false);

        assertThat(created.bindingsUpdated()).isZero();
        assertThat(deleted.bindingsUpdated()).isZero();
        assertThat(nowManual.getStatus()).isEqualTo(Status.MANUAL);
        assertThat(nowManual.getTwitchGameId()).isEqualTo("old");
        verify(bindingService, never()).applyMapping(eq(nowManual), any(), any());
        verify(bindingService, never()).resolveWithoutMapping(any());
        verify(bindingRepo, never()).save(nowManual);
    }

    @Test
    void igdbSuggestions_areResolvedBeforeTheTransaction() {
        GameBinding auto = binding(user("a"), "730", Status.AUTO);
        bindingsOfTheGame(auto);
        when(bindingRepo.countSourceNames(anyCollection(), anyCollection())).thenReturn(List.<Object[]>of(
                new Object[]{SourceType.STEAM, "730", "Counter-Strike 2", 1L}));
        when(igdbService.findByExternalAppId(SourceType.STEAM, "730"))
                .thenReturn(Optional.of(new IgdbService.IgdbGame("42", "Counter-Strike 2")));
        when(igdbService.suggestCcls("42")).thenReturn(Set.of("ViolentGraphic"));

        service.create(new GameMappingRequest("STEAM", "730", "32399", "CS", null, Set.of(), false), admin);

        assertThat(auto.getCcls()).containsExactly("ViolentGraphic");
        org.mockito.InOrder order = inOrder(igdbService, transactionManager, bindingRepo);
        order.verify(igdbService).suggestCcls("42");
        order.verify(transactionManager).getTransaction(any());
        order.verify(bindingRepo).lockAllBySourceTypeAndSourceIdAndStatusIn(eq(SourceType.STEAM), eq("730"), anyCollection());
        // Once for the game, not again per binding.
        verify(igdbService, times(1)).suggestCcls("42");
    }

    @Test
    void streamOfflineAfterTheCommit_isSkipped_neitherCountedNorPending() {
        UserAccount onIt = user("on-it");
        GameBinding auto = binding(onIt, "730", Status.AUTO);
        bindingsOfTheGame(auto);
        liveOn(onIt, "730");
        // Live when the transaction picks it, offline by the time the Twitch update runs.
        when(streamStateService.isLive(onIt)).thenReturn(true, false);

        GameMappingResult result = service.create(request("730", "32399", true), admin);

        assertThat(result.bindingsUpdated()).isEqualTo(1);
        assertThat(result.liveChannelsUpdated()).isZero();
        assertThat(result.liveChannelsFailed()).isZero();
        verify(twitchService, never()).updateChannelReporting(any(), any());
        verify(streamStateService, never()).storePending(any(), any());
    }
}
