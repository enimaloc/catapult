package fr.enimaloc.catapult.service.binding;

import fr.enimaloc.catapult.service.category.ExtensionLayoutApplier;
import fr.enimaloc.catapult.common.dto.admin.DetectedGameDto;
import fr.enimaloc.catapult.common.dto.admin.GameMappingRequest;
import fr.enimaloc.catapult.common.dto.admin.GameMappingResult;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.binding.GameBinding.SourceType;
import fr.enimaloc.catapult.domain.binding.GameBinding.Status;
import fr.enimaloc.catapult.domain.binding.GameMapping;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.repository.binding.GameBindingRepository;
import fr.enimaloc.catapult.repository.binding.GameMappingRepository;
import fr.enimaloc.catapult.service.category.CategoryApplier;
import fr.enimaloc.catapult.service.category.CategoryChain;
import fr.enimaloc.catapult.service.category.CategoryOutcome;
import fr.enimaloc.catapult.service.category.CategoryRule;
import fr.enimaloc.catapult.service.category.CategoryTrigger.MappingChanged;
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
    @Mock RaidRestoreHold raidRestoreHold;
    @Mock PlatformTransactionManager transactionManager;

    BindingService bindingService;
    CategoryChain chain;
    CustomBindingActivations chainActivations;
    AdminGameMappingService service;
    UserAccount admin;
    final AtomicBoolean committed = new AtomicBoolean();
    final Map<UUID, GameMapping> mappings = new HashMap<>();

    @BeforeEach
    void setUp() {
        CustomBindingActivations activations = mock(CustomBindingActivations.class);
        chainActivations = activations;
        chain = spy(new CategoryChain(activations, streamStateService, userSettingsRepository,
                raidRestoreHold, gameStateService, mock(UserAccountRepository.class), new CategoryApplier(twitchService, streamStateService), mock(RaidRestoreGateService.class), mock(GameBindingLookup.class), mock(ExtensionLayoutApplier.class)));
        bindingService = spy(new BindingService(bindingRepo, igdbService, igdbGameDetailsService, twitchService,
                twResolverService, mappingRepo, new GameBindingLookup(bindingRepo), chain, mock(ExtensionLayoutApplier.class), org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class)));
        service = new AdminGameMappingService(mappingRepo, bindingRepo, userRepo, bindingService, igdbService, gameStateService,
                streamStateService, channelEventPublisher, chain, transactionManager);

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
    void mappingChange_isAttributedToEachChannel() {
        UserAccount first = user("first");
        UserAccount second = user("second");
        GameBinding firstBinding = binding(first, "730", Status.AUTO);
        GameBinding secondBinding = binding(second, "730", Status.AUTO);
        bindingsOfTheGame(firstBinding, secondBinding);
        liveOn(first, "730");
        liveOn(second, "730");
        Map<UUID, UUID> subjects = new HashMap<>();
        doAnswer(inv -> {
            subjects.put(inv.<UserAccount>getArgument(0).getId(),
                    fr.enimaloc.catapult.service.debug.DebugContext.subject().orElse(null));
            return ChannelUpdateOutcome.APPLIED;
        }).when(twitchService).updateChannelReporting(any(), any());

        fr.enimaloc.catapult.service.debug.DebugContext.runAs(admin.getId(),
                () -> service.create(request("730", "32399", true), admin));

        assertThat(subjects).containsExactlyInAnyOrderEntriesOf(Map.of(
                first.getId(), first.getId(), second.getId(), second.getId()));
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
    void aCarriedErrorCountsAsFailed() {
        UserAccount onIt = user("on-it");
        bindingsOfTheGame(binding(onIt, "730", Status.AUTO));
        liveOn(onIt, "730");
        doReturn(CategoryOutcome.error(CategoryRule.GAME_BINDING, new IllegalStateException("boom")))
                .when(chain).apply(any(), any(MappingChanged.class));

        GameMappingResult result = service.create(request("730", "32399", true), admin);

        assertThat(result.liveChannelsUpdated()).isZero();
        assertThat(result.liveChannelsFailed()).isEqualTo(1);
        assertThat(result.mapping()).isNotNull();
    }

    @Test
    void aDecisionErrorCountsAsFailed_andTheLoopContinues() {
        UserAccount first = user("first");
        UserAccount second = user("second");
        bindingsOfTheGame(binding(first, "730", Status.AUTO), binding(second, "730", Status.AUTO));
        liveOn(first, "730");
        liveOn(second, "730");
        when(gameStateService.lastKnownGames()).thenReturn(Map.of(
                first.getId(), new DetectedGame("730", SourceType.STEAM, "CS2"),
                second.getId(), new DetectedGame("730", SourceType.STEAM, "CS2")));
        when(chainActivations.active(first)).thenThrow(new IllegalStateException("db down"));
        when(chainActivations.active(second)).thenReturn(Optional.empty());

        GameMappingResult result = service.create(request("730", "32399", true), admin);

        assertThat(result.liveChannelsUpdated()).isEqualTo(1);
        assertThat(result.liveChannelsFailed()).isEqualTo(1);
        verify(twitchService, never()).updateChannelReporting(eq(first), any());
        verify(twitchService).updateChannelReporting(eq(second), any());
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
        when(igdbService.resolveUnmapped(eq(SourceType.STEAM), eq("730"), any()))
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

    // ---- Ignored by default

    private static GameMappingRequest ignoring(String sourceId, String twitchId, boolean applyLive) {
        return new GameMappingRequest("STEAM", sourceId, twitchId, twitchId == null ? null : "Category " + twitchId,
                null, null, applyLive, true, null);
    }

    @Test
    void ignoredByDefault_withoutCategory_ignoresTheBindings_andLeavesTheirCategoryToIgdb() {
        GameBinding auto = binding(user("a"), "730", Status.AUTO);
        GameBinding incomplete = binding(user("b"), "730", Status.INCOMPLETE);
        bindingsOfTheGame(auto, incomplete);

        GameMappingResult result = service.create(ignoring("730", null, false), admin);

        assertThat(result.bindingsUpdated()).isEqualTo(2);
        assertThat(List.of(auto, incomplete)).allSatisfy(b -> assertThat(b.isIgnored()).isTrue());
        assertThat(auto.getStatus()).isEqualTo(Status.AUTO);
        assertThat(auto.getTwitchGameId()).isEqualTo("old");
        assertThat(incomplete.getStatus()).isEqualTo(Status.INCOMPLETE);
        assertThat(result.mapping().ignoredByDefault()).isTrue();
        assertThat(result.mapping().twitchGameId()).isNull();
        // Nothing to ask IGDB for a mapping that sets no category
        verifyNoInteractions(igdbService);
    }

    @Test
    void ignoredByDefault_withACategory_mapsAndIgnores() {
        GameBinding auto = binding(user("a"), "730", Status.AUTO);
        bindingsOfTheGame(auto);

        GameMappingResult result = service.create(ignoring("730", "32399", false), admin);

        assertThat(result.bindingsUpdated()).isEqualTo(1);
        assertThat(auto.getStatus()).isEqualTo(Status.MAPPED);
        assertThat(auto.getTwitchGameId()).isEqualTo("32399");
        assertThat(auto.isIgnored()).isTrue();
    }

    @Test
    void ignoredByDefault_leavesManualBindingsAndTheStreamersChoiceAlone() {
        GameBinding manual = binding(user("a"), "730", Status.MANUAL);
        GameBinding chosen = binding(user("b"), "730", Status.AUTO);
        chosen.setIgnoredOverride(true);
        bindingsOfTheGame(manual, chosen);

        GameMappingResult result = service.create(ignoring("730", null, false), admin);

        assertThat(result.bindingsUpdated()).isZero();
        assertThat(manual.isIgnored()).isFalse();
        assertThat(chosen.isIgnored()).isFalse();
        verify(bindingRepo, never()).save(any(GameBinding.class));
    }

    @Test
    void withoutCategory_isRefused_unlessIgnoredByDefault() {
        assertThatThrownBy(() -> service.create(
                new GameMappingRequest("STEAM", "730", " ", null, null, null, false, false, null), admin))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Missing Twitch category");
        // Half a category is refused all the same
        assertThatThrownBy(() -> service.create(
                new GameMappingRequest("STEAM", "730", "32399", " ", null, null, false, true, null), admin))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Missing Twitch category");
    }

    @Test
    void turningTheIgnoreOff_stopsIgnoringOnlyWhatItIgnored() {
        GameMappingResult created = service.create(ignoring("730", null, false), admin);
        GameBinding byTheMapping = binding(user("a"), "730", Status.AUTO);
        byTheMapping.setIgnored(true);
        GameBinding byTheStreamer = binding(user("b"), "730", Status.AUTO);
        byTheStreamer.setIgnored(true);
        byTheStreamer.setIgnoredOverride(true);
        bindingsOfTheGame(byTheMapping, byTheStreamer);

        GameMappingResult result = service.update(created.mapping().id(), request("730", "32399", false), admin);

        assertThat(byTheMapping.isIgnored()).isFalse();
        assertThat(byTheMapping.getStatus()).isEqualTo(Status.MAPPED);
        assertThat(byTheStreamer.isIgnored()).isTrue();
        // Mapped, but still ignored by its streamer
        assertThat(byTheStreamer.getStatus()).isEqualTo(Status.MAPPED);
        assertThat(result.bindingsUpdated()).isEqualTo(2);
        assertThat(result.mapping().ignoredByDefault()).isFalse();
    }

    @Test
    void anUpdateKeepingTheIgnore_doesNotIgnoreAgainWhatTheStreamerStoppedIgnoring() {
        GameMappingResult created = service.create(ignoring("730", null, false), admin);
        // Ignored by the mapping, then not by its streamer
        GameBinding unignored = binding(user("a"), "730", Status.AUTO);
        unignored.setIgnoredOverride(true);
        bindingsOfTheGame(unignored);

        service.update(created.mapping().id(), ignoring("730", "32399", false), admin);

        assertThat(unignored.isIgnored()).isFalse();
    }

    @Test
    void anUpdateDroppingTheCategory_sendsTheMappedBindingsBackToIgdb() {
        GameMappingResult created = service.create(request("730", "32399", false), admin);
        GameBinding mapped = binding(user("a"), "730", Status.MAPPED);
        bindingsOfTheGame(mapped);

        service.update(created.mapping().id(), ignoring("730", null, false), admin);

        verify(bindingService).resolveWithoutMapping(mapped);
        // IGDB finds nothing here: incomplete again, and ignored
        assertThat(mapped.getStatus()).isEqualTo(Status.INCOMPLETE);
        assertThat(mapped.isIgnored()).isTrue();
    }

    @Test
    void deletingAnIgnoringMapping_stopsIgnoringItsBindings() {
        GameMappingResult created = service.create(ignoring("730", null, false), admin);
        GameBinding ignored = binding(user("a"), "730", Status.AUTO);
        ignored.setIgnored(true);
        bindingsOfTheGame(ignored);

        GameMappingResult result = service.delete(created.mapping().id(), false);

        assertThat(ignored.isIgnored()).isFalse();
        assertThat(result.bindingsUpdated()).isEqualTo(1);
    }

    @Test
    void movingAnIgnoringMapping_stopsIgnoringTheOldGame() {
        GameMappingResult created = service.create(ignoring("730", null, false), admin);
        GameBinding oldGame = binding(user("a"), "730", Status.AUTO);
        oldGame.setIgnored(true);
        GameBinding newGame = binding(user("b"), "570", Status.AUTO);
        bindingsOfTheGame(oldGame);
        when(bindingRepo.lockAllBySourceTypeAndSourceIdAndStatusIn(eq(SourceType.STEAM), eq("570"), anyCollection()))
                .thenReturn(new ArrayList<>(List.of(newGame)));

        service.update(created.mapping().id(), ignoring("570", null, false), admin);

        assertThat(oldGame.isIgnored()).isFalse();
        assertThat(newGame.isIgnored()).isTrue();
    }

    /** A binding it just ignored goes to the no-game fallback live, unlike one ignored already. */
    @Test
    void applyLive_pushesTheBindingsItJustIgnored() {
        UserAccount onIt = user("on-it");
        UserAccount alreadyIgnored = user("already");
        GameBinding justIgnored = binding(onIt, "730", Status.AUTO);
        GameBinding stillIgnored = binding(alreadyIgnored, "730", Status.AUTO);
        stillIgnored.setIgnored(true);
        stillIgnored.setIgnoredOverride(true);
        bindingsOfTheGame(justIgnored, stillIgnored);
        liveOn(onIt, "730");
        liveOn(alreadyIgnored, "730");

        service.create(ignoring("730", "32399", true), admin);

        verify(chain).apply(eq(onIt), any(MappingChanged.class));
        verify(chain, never()).apply(eq(alreadyIgnored), any());
    }

    // ---- IGDB game on a mapping

    private static GameMappingRequest withIgdb(String twitchId, String twitchName, boolean ignored, String igdbId) {
        return new GameMappingRequest("STEAM", "730", twitchId, twitchName, null, null, false, ignored, igdbId);
    }

    private void igdbKnows(String igdbId, String name) {
        when(igdbService.findById(igdbId)).thenReturn(new IgdbService.IgdbGame(igdbId, name));
    }

    @Test
    void typedCategoryWinsOverIgdb() {
        igdbKnows("42", "Counter-Strike 2");
        when(igdbService.findTwitchGameId("42")).thenReturn(Optional.of("999"));

        GameMappingResult result = service.create(withIgdb("32399", "Typed", false, "42"), admin);

        assertThat(result.mapping().twitchGameId()).isEqualTo("32399");
        assertThat(result.mapping().twitchGameName()).isEqualTo("Typed");
        assertThat(result.mapping().igdbId()).isEqualTo("42");
        verify(bindingService, never()).twitchCategoryFor(any(), any(), any());
    }

    @Test
    void emptyCategoryIsDeducedFromIgdb() {
        igdbKnows("42", "Counter-Strike 2");
        when(igdbService.findTwitchGameId("42")).thenReturn(Optional.of("32399"));

        GameMappingResult result = service.create(withIgdb(" ", null, false, "42"), admin);

        assertThat(result.mapping().twitchGameId()).isEqualTo("32399");
        assertThat(result.mapping().twitchGameName()).isEqualTo("Counter-Strike 2");
        GameMapping stored = mappings.values().iterator().next();
        assertThat(stored.getTwitchGameId()).isEqualTo("32399");
        assertThat(stored.getTwitchGameName()).isEqualTo("Counter-Strike 2");
        assertThat(stored.getIgdbId()).isEqualTo("42");
    }

    @Test
    void deductionFailureIsA400ForAnActiveMapping() {
        igdbKnows("42", "Counter-Strike 2");
        when(igdbService.findTwitchGameId("42")).thenReturn(Optional.empty());
        when(twitchService.findCategoryIdByName(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(withIgdb(null, null, false, "42"), admin))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(400);
                    assertThat(e.getReason()).isEqualTo("No Twitch category found for this IGDB game: enter one");
                });
        assertThat(mappings).isEmpty();
    }

    @Test
    void deductionFailureIsAcceptedForAnIgnoredMapping() {
        igdbKnows("42", "Counter-Strike 2");
        when(igdbService.findTwitchGameId("42")).thenReturn(Optional.empty());
        when(twitchService.findCategoryIdByName(any(), any())).thenReturn(Optional.empty());

        GameMappingResult result = service.create(withIgdb(null, null, true, "42"), admin);

        assertThat(result.mapping().twitchGameId()).isNull();
        assertThat(result.mapping().igdbId()).isEqualTo("42");
        assertThat(result.mapping().ignoredByDefault()).isTrue();
    }

    @Test
    void igdbUnavailableIsA400NotA500() {
        // The name cannot be resolved (token blank): no deduction
        igdbKnows("42", null);
        assertThatThrownBy(() -> service.create(withIgdb(null, null, false, "42"), admin))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));

        // The client throws
        when(igdbService.findById("43")).thenThrow(new IllegalStateException("igdb down"));
        assertThatThrownBy(() -> service.create(withIgdb(null, null, false, "43"), admin))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void listShowsTheCachedIgdbNameOrNull() {
        GameMapping known = new GameMapping();
        known.setId(UUID.randomUUID());
        known.setSourceType(SourceType.BATTLENET);
        known.setSourceId("Pro");
        known.setIgdbId("42");
        GameMapping unknown = new GameMapping();
        unknown.setId(UUID.randomUUID());
        unknown.setSourceType(SourceType.BATTLENET);
        unknown.setSourceId("Zeus");
        unknown.setIgdbId("43");
        when(mappingRepo.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(known, unknown)));
        when(igdbService.getGameCache()).thenReturn(Map.of("42", "Overwatch 2"));

        var rows = service.list(0, 25).getContent();

        assertThat(rows.get(0).igdbGameName()).isEqualTo("Overwatch 2");
        assertThat(rows.get(1).igdbGameName()).isNull();
        verify(igdbService, never()).findById(any());
    }

    @Test
    void createWithAnIgdbIdTakesItsSuggestionsFromThatId() {
        GameBinding auto = binding(user("a"), "730", Status.AUTO);
        bindingsOfTheGame(auto);
        // No mapping is stored yet: resolveDetected knows nothing, only the request's id leads to the game
        when(igdbService.resolveDetected(any(), any(), any())).thenReturn(Optional.empty());
        when(igdbService.suggestCcls("42")).thenReturn(Set.of("ViolentGraphic"));

        service.create(new GameMappingRequest("STEAM", "730", "32399", "CS", null, Set.of(), false, false, "42"),
                admin);

        assertThat(auto.getCcls()).containsExactly("ViolentGraphic");
        verify(igdbService).suggestCcls("42");
    }

    @Test
    void updateChangingTheIgdbIdTakesItsSuggestionsFromTheNewId() {
        GameMapping old = new GameMapping();
        old.setId(UUID.randomUUID());
        old.setSourceType(SourceType.STEAM);
        old.setSourceId("730");
        old.setTwitchGameId("old");
        old.setTwitchGameName("Old");
        old.setIgdbId("41");
        mappings.put(old.getId(), old);
        GameBinding mapped = binding(user("a"), "730", Status.MAPPED);
        bindingsOfTheGame(mapped);
        // What is persisted still says 41: the new id must win
        when(igdbService.resolveDetected(any(), any(), any()))
                .thenReturn(Optional.of(new IgdbService.IgdbGame("41", "Old game")));
        when(igdbService.suggestCcls("41")).thenReturn(Set.of("Old"));
        when(igdbService.suggestCcls("42")).thenReturn(Set.of("ViolentGraphic"));

        service.update(old.getId(),
                new GameMappingRequest("STEAM", "730", "32399", "CS", null, Set.of(), false, false, "42"), admin);

        assertThat(mapped.getCcls()).containsExactly("ViolentGraphic");
        assertThat(old.getIgdbId()).isEqualTo("42");
        verify(igdbService, never()).suggestCcls("41");
    }

    private GameMapping storedMapping(SourceType sourceType, String sourceId, String igdbId) {
        GameMapping m = new GameMapping();
        m.setId(UUID.randomUUID());
        m.setSourceType(sourceType);
        m.setSourceId(sourceId);
        m.setTwitchGameId("488552");
        m.setTwitchGameName("Overwatch 2");
        m.setIgdbId(igdbId);
        mappings.put(m.getId(), m);
        return m;
    }

    private static GameBinding battlenetBinding(UserAccount owner, Status status) {
        GameBinding b = binding(owner, "Pro", status);
        b.setSourceType(SourceType.BATTLENET);
        b.setSourceName("Overwatch");
        return b;
    }

    private void battlenetBindings(GameBinding... bindings) {
        when(bindingRepo.lockAllBySourceTypeAndSourceIdAndStatusIn(eq(SourceType.BATTLENET), eq("Pro"),
                anyCollection())).thenAnswer(inv -> new ArrayList<>(List.of(bindings)));
    }

    /** What the not-yet-updated mapping row says: the old IGDB game, through the mapping-aware resolution. */
    private void storedRowResolvesTo(String igdbId) {
        when(igdbService.resolveDetected(any(), any(), any()))
                .thenReturn(Optional.of(new IgdbService.IgdbGame(igdbId, "Old game")));
        when(igdbService.suggestCcls(igdbId)).thenReturn(Set.of("Old"));
        when(igdbService.findTwitchGameId(igdbId)).thenReturn(Optional.of("488552"));
    }

    @Test
    void updateClearingTheIgdbIdTakesNoSuggestionsFromTheOldOne() {
        GameMapping old = storedMapping(SourceType.BATTLENET, "Pro", "123");
        GameBinding mapped = battlenetBinding(user("a"), Status.MAPPED);
        mapped.getCcls().add("Old");
        battlenetBindings(mapped);
        storedRowResolvesTo("123");

        service.update(old.getId(), new GameMappingRequest("BATTLENET", "Pro", "488552", "Overwatch 2", null, null,
                false, false, null), admin);

        assertThat(old.getIgdbId()).isNull();
        // Battle.net without an IGDB game: no IGDB data at all (spec 3.4)
        assertThat(mapped.getCcls()).isEmpty();
        assertThat(mapped.getTws()).isEmpty();
        verify(igdbService, never()).suggestCcls("123");
        verify(igdbService, never()).fetchDescriptorIds("123");
    }

    @Test
    void updateClearingTheIgdbIdTakesSteamSuggestionsFromThePlatformId() {
        GameMapping old = storedMapping(SourceType.STEAM, "730", "41");
        GameBinding mapped = binding(user("a"), "730", Status.MAPPED);
        bindingsOfTheGame(mapped);
        storedRowResolvesTo("41");
        when(igdbService.resolveUnmapped(eq(SourceType.STEAM), eq("730"), any()))
                .thenReturn(Optional.of(new IgdbService.IgdbGame("42", "Counter-Strike 2")));
        when(igdbService.suggestCcls("42")).thenReturn(Set.of("ViolentGraphic"));

        service.update(old.getId(),
                new GameMappingRequest("STEAM", "730", "32399", "CS", null, Set.of(), false, false, " "), admin);

        assertThat(old.getIgdbId()).isNull();
        assertThat(mapped.getCcls()).containsExactly("ViolentGraphic");
        verify(igdbService, never()).suggestCcls("41");
    }

    @Test
    void movingAMappingResolvesTheOldGameWithoutItsIgdbGame() {
        GameMapping old = storedMapping(SourceType.BATTLENET, "Pro", "123");
        GameBinding oldGame = battlenetBinding(user("a"), Status.MAPPED);
        battlenetBindings(oldGame);
        storedRowResolvesTo("123");

        service.update(old.getId(), request("730", "32399", false), admin);

        assertThat(old.getSourceType()).isEqualTo(SourceType.STEAM);
        // No longer about Battle.net "Pro": its bindings get no IGDB game, hence incomplete
        assertThat(oldGame.getStatus()).isEqualTo(Status.INCOMPLETE);
        verify(igdbService, never()).suggestCcls("123");
    }

    @Test
    void aNonNumericIgdbIdIsA400ForAnActiveMapping() {
        assertThatThrownBy(() -> service.create(withIgdb("32399", "Typed", false, "abc"), admin))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(400);
                    assertThat(e.getReason()).isEqualTo("IGDB game id must be numeric");
                });
        assertThat(mappings).isEmpty();
        verify(mappingRepo, never()).saveAndFlush(any());
        verifyNoInteractions(igdbService);
    }

    @Test
    void aNonNumericIgdbIdIsA400ForAnIgnoredMapping() {
        GameMapping old = storedMapping(SourceType.STEAM, "730", "41");

        assertThatThrownBy(() -> service.update(old.getId(), withIgdb(null, null, true, "12a"), admin))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(400);
                    assertThat(e.getReason()).isEqualTo("IGDB game id must be numeric");
                });
        assertThatThrownBy(() -> service.create(withIgdb(null, null, true, "-1"), admin))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(old.getIgdbId()).isEqualTo("41");
        assertThat(mappings).hasSize(1);
        verify(mappingRepo, never()).saveAndFlush(any());
    }
}
