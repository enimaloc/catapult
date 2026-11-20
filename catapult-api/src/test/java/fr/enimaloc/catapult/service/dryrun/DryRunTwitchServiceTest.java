package fr.enimaloc.catapult.service.dryrun;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.debug.DebugExchange.Direction;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.service.ActivityLogService;
import fr.enimaloc.catapult.service.account.BotToggleService;
import fr.enimaloc.catapult.service.debug.DebugExchangeRecorder;
import fr.enimaloc.catapult.service.debug.DebugMemberRegistry;
import fr.enimaloc.catapult.service.debug.RawExchange;
import fr.enimaloc.catapult.service.metrics.ExternalApiObservations;
import fr.enimaloc.catapult.service.notification.CatapultCategoryChangeStateService;
import fr.enimaloc.catapult.service.notification.TwitchatEventDispatcher;
import fr.enimaloc.catapult.service.title.StreamTitleService;
import fr.enimaloc.catapult.service.twitch.ChannelUpdateOutcome;
import fr.enimaloc.catapult.service.twitch.TwitchCategory;
import fr.enimaloc.catapult.service.twitch.TwitchCategoryService;
import fr.enimaloc.catapult.service.twitch.TwitchEventSubService;
import fr.enimaloc.catapult.service.twitch.TwitchExtension;
import fr.enimaloc.catapult.service.twitch.TwitchExtensionPlacement;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import fr.enimaloc.catapult.service.twitch.TwitchServiceImpl;
import fr.enimaloc.catapult.service.twitch.TwitchTokenService;
import fr.enimaloc.catapult.site.SiteRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DryRunTwitchServiceTest {

    final DryRunRegistry registry = new DryRunRegistry(mock(), event -> { });
    final UserAccountRepository users = mock(UserAccountRepository.class);
    final UserSettingsRepository settingsRepository = mock(UserSettingsRepository.class);
    final SiteRole siteRole = mock(SiteRole.class);
    final ActivityLogService activityLog = mock(ActivityLogService.class);
    final DebugMemberRegistry debugMembers = mock(DebugMemberRegistry.class);
    final DebugExchangeRecorder recorder = mock(DebugExchangeRecorder.class);
    final StreamTitleService titles = mock(StreamTitleService.class);
    final TwitchEventSubService eventSub = mock(TwitchEventSubService.class);
    final TwitchatEventDispatcher twitchat = mock(TwitchatEventDispatcher.class);
    final DryRunGate gate = new DryRunGate(registry, users, settingsRepository, siteRole, activityLog, debugMembers,
            recorder, titles, provider(eventSub), provider(twitchat));
    final TwitchService delegate = mock(TwitchService.class);
    final DryRunTwitchService service = new DryRunTwitchService(delegate, () -> gate);

    final UUID member = UUID.randomUUID();
    final UserAccount account = new UserAccount();
    final UserSettings settings = new UserSettings();

    @BeforeEach
    void setUp() {
        account.setId(member);
        when(users.findById(member)).thenReturn(Optional.of(account));
        when(settingsRepository.findById(member)).thenReturn(Optional.of(settings));
        when(siteRole.isActive()).thenReturn(true);
        when(debugMembers.modeOf(any())).thenReturn(Optional.empty());
        when(eventSub.currentCategory(member)).thenReturn(Optional.empty());
        registry.activate(member);
    }

    @SuppressWarnings("unchecked")
    static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }

    static GameBinding binding(String id, String name) {
        GameBinding binding = new GameBinding();
        binding.setTwitchGameId(id);
        binding.setTwitchGameName(name);
        binding.setStatus(GameBinding.Status.AUTO);
        return binding;
    }

    @Test
    void blocksUpdateChannelReportingAndLogsIt() {
        GameBinding binding = binding("33214", "Factorio");
        when(titles.titleFor(eq(account), eq(settings), eq(binding), anyBoolean(), any()))
                .thenReturn(Optional.of(new StreamTitleService.PlannedTitle("Building the factory", Optional.empty())));

        assertThat(service.updateChannelReporting(account, binding)).isEqualTo(ChannelUpdateOutcome.DRY_RUN);

        verifyNoInteractions(delegate);
        verify(activityLog).addEntry(member, "INFO",
                "[dry run] would have changed the channel: category Factorio, title \"Building the factory\"");
    }

    @Test
    void blocksUpdateChannelReportingWithoutATitle() {
        when(titles.titleFor(any(), any(), any(), anyBoolean(), any())).thenReturn(Optional.empty());

        assertThat(service.updateChannelReporting(account, binding("33214", "Factorio")))
                .isEqualTo(ChannelUpdateOutcome.DRY_RUN);

        verify(activityLog).addEntry(member, "INFO", "[dry run] would have changed the channel: category Factorio");
    }

    @Test
    void anIgnoredBindingIsSkippedWithoutBeingLogged() {
        GameBinding binding = binding("33214", "Factorio");
        binding.setIgnored(true);

        assertThat(service.updateChannelReporting(account, binding)).isEqualTo(ChannelUpdateOutcome.SKIPPED);

        verifyNoInteractions(delegate, activityLog);
    }

    @Test
    void blocksSetCategoryAndLogsIt() {
        assertThat(service.setCategory(account, "33214", "Factorio")).isEqualTo(ChannelUpdateOutcome.DRY_RUN);

        verifyNoInteractions(delegate);
        verify(activityLog).addEntry(member, "INFO", "[dry run] would have changed the channel: category Factorio");
    }

    @Test
    void blocksResetToDefaultAndLogsIt() {
        settings.setNoGameTwitchGameId("509658");
        settings.setNoGameTwitchGameName("Just Chatting");

        assertThat(service.resetToDefault(account)).isEqualTo(ChannelUpdateOutcome.DRY_RUN);

        verifyNoInteractions(delegate);
        verify(activityLog).addEntry(member, "INFO", "[dry run] would have reset the category");
    }

    @Test
    void aResetWithoutADefaultCategoryIsSkippedWithoutBeingLogged() {
        assertThat(service.resetToDefault(account)).isEqualTo(ChannelUpdateOutcome.SKIPPED);

        verifyNoInteractions(delegate, activityLog);
    }

    @Test
    void blocksSetActiveExtensionsAndLogsIt() {
        TwitchExtensionPlacement placement = TwitchExtensionPlacement.empty();

        assertThat(service.setActiveExtensions(account, placement)).isSameAs(placement);

        verifyNoInteractions(delegate);
        verify(activityLog).addEntry(member, "INFO", "[dry run] would have set the extensions");
    }

    @Test
    void aBlockedChangeIsAnnouncedOnTwitchatAsCatapults() {
        when(eventSub.currentCategory(member))
                .thenReturn(Optional.of(new TwitchEventSubService.CurrentCategory("509658", "Just Chatting")));

        service.setCategory(account, "33214", "Factorio");

        verify(twitchat).onCategoryChangedByCatapult(account, "33214", "Factorio", "509658", "Just Chatting");
    }

    @Test
    void aBlockedChangeToTheCurrentCategoryIsNotAnnounced() {
        when(eventSub.currentCategory(member))
                .thenReturn(Optional.of(new TwitchEventSubService.CurrentCategory("33214", "Factorio")));

        service.setCategory(account, "33214", "Factorio");

        verifyNoInteractions(twitchat);
    }

    @Test
    void aBlockedCallIsRecordedInTheDebugJournalOfAMemberInDebug() {
        when(debugMembers.modeOf(member)).thenReturn(Optional.of(new DebugMemberRegistry.DebugMode(member, false)));

        service.setCategory(account, "33214", "Factorio");

        ArgumentCaptor<RawExchange> raw = ArgumentCaptor.forClass(RawExchange.class);
        verify(recorder).record(eq(member), eq(Direction.OUT), raw.capture(), eq("dry-run.setCategory"), eq(false),
                eq("twitch"));
        assertThat(raw.getValue().uri()).hasToString("twitch://dry-run");
        assertThat(new String(raw.getValue().requestBody(), StandardCharsets.UTF_8))
                .isEqualTo("[dry run] would have changed the channel: category Factorio");
    }

    @Test
    void aMemberNotInDebugHasNoDebugEntry() {
        service.setCategory(account, "33214", "Factorio");

        verifyNoInteractions(recorder);
    }

    @Test
    void aFailingLogNeverReachesTheCaller() {
        doThrow(new IllegalStateException("log down")).when(activityLog).addEntry(any(), anyString(), anyString());
        when(debugMembers.modeOf(member)).thenThrow(new IllegalStateException("registry down"));
        when(eventSub.currentCategory(member)).thenThrow(new IllegalStateException("state down"));
        when(titles.titleFor(any(), any(), any(), anyBoolean(), any())).thenThrow(new IllegalStateException("x"));

        assertThat(service.updateChannelReporting(account, binding("33214", "Factorio")))
                .isEqualTo(ChannelUpdateOutcome.DRY_RUN);
        assertThat(service.setCategory(account, "33214", "Factorio")).isEqualTo(ChannelUpdateOutcome.DRY_RUN);
        verifyNoInteractions(delegate);
    }

    @Test
    void readsPassThrough() {
        TwitchExtensionPlacement active = TwitchExtensionPlacement.empty();
        List<TwitchExtension> installed = List.of();
        List<TwitchCategory> categories = List.of();
        when(delegate.findCategoryIdByName(account, "Factorio")).thenReturn(Optional.of("33214"));
        when(delegate.searchCategories(account, "Fact")).thenReturn(categories);
        when(delegate.getModeratedChannelIds(account)).thenReturn(List.of("1"));
        when(delegate.getUserExtensions(account)).thenReturn(installed);
        when(delegate.getActiveExtensions(account)).thenReturn(active);

        assertThat(service.findCategoryIdByName(account, "Factorio")).contains("33214");
        assertThat(service.searchCategories(account, "Fact")).isSameAs(categories);
        assertThat(service.getModeratedChannelIds(account)).containsExactly("1");
        assertThat(service.getUserExtensions(account)).isSameAs(installed);
        assertThat(service.getActiveExtensions(account)).isSameAs(active);
        verifyNoInteractions(activityLog);
    }

    @Test
    void aMemberNotInDryRunIsUntouched() {
        registry.deactivate(member);
        GameBinding binding = binding("33214", "Factorio");
        TwitchExtensionPlacement placement = TwitchExtensionPlacement.empty();
        when(delegate.updateChannelReporting(account, binding)).thenReturn(ChannelUpdateOutcome.APPLIED);
        when(delegate.setCategory(account, "33214", "Factorio")).thenReturn(ChannelUpdateOutcome.APPLIED);
        when(delegate.resetToDefault(account)).thenReturn(ChannelUpdateOutcome.FAILED);
        when(delegate.setActiveExtensions(account, placement)).thenReturn(placement);

        assertThat(service.updateChannelReporting(account, binding)).isEqualTo(ChannelUpdateOutcome.APPLIED);
        assertThat(service.setCategory(account, "33214", "Factorio")).isEqualTo(ChannelUpdateOutcome.APPLIED);
        assertThat(service.resetToDefault(account)).isEqualTo(ChannelUpdateOutcome.FAILED);
        assertThat(service.setActiveExtensions(account, placement)).isSameAs(placement);

        verifyNoInteractions(activityLog, recorder, twitchat);
    }

    @Test
    void disabledBotStillSkipsBeforeTheGate() {
        account.setBotEnabled(false);
        when(delegate.setCategory(account, "33214", "Factorio")).thenReturn(ChannelUpdateOutcome.SKIPPED);
        when(delegate.setActiveExtensions(any(), any())).thenThrow(new IllegalStateException("bot disabled"));

        assertThat(service.setCategory(account, "33214", "Factorio")).isEqualTo(ChannelUpdateOutcome.SKIPPED);
        assertThatThrownBy(
                () -> service.setActiveExtensions(account, TwitchExtensionPlacement.empty()))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(activityLog, twitchat);
    }

    @Test
    void aStandbySiteStillRefusesBeforeTheGate() {
        when(siteRole.isActive()).thenReturn(false);
        when(delegate.resetToDefault(account)).thenThrow(new IllegalStateException("standby"));

        assertThatThrownBy(() -> service.resetToDefault(account))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(activityLog);
    }

    /** The real client under the gate: a blocked change neither reaches Twitch nor records the echo marker. */
    @Test
    void aBlockedChangeLeavesNoEchoMarker() {
        RestClient restClient = mock(RestClient.class);
        CatapultCategoryChangeStateService marker = mock(CatapultCategoryChangeStateService.class);
        OAuthTokenRepository tokens = mock(OAuthTokenRepository.class);
        TwitchServiceImpl real = new TwitchServiceImpl(tokens, users, settingsRepository, restClient,
                mock(TwitchCategoryService.class), mock(TwitchTokenService.class),
                mock(ExternalApiObservations.class), mock(BotToggleService.class), marker, siteRole, titles);
        DryRunTwitchService gated = new DryRunTwitchService(real, () -> gate);
        settings.setNoGameTwitchGameId("509658");
        settings.setNoGameTwitchGameName("Just Chatting");

        assertThat(gated.updateChannelReporting(account, binding("33214", "Factorio")))
                .isEqualTo(ChannelUpdateOutcome.DRY_RUN);
        assertThat(gated.setCategory(account, "33214", "Factorio")).isEqualTo(ChannelUpdateOutcome.DRY_RUN);
        assertThat(gated.resetToDefault(account)).isEqualTo(ChannelUpdateOutcome.DRY_RUN);

        verifyNoInteractions(marker, restClient, tokens);
        verify(activityLog, never()).addEntry(eq(member), eq("WARN"), anyString());
    }

    @Test
    void theLabelsSentShapeTheTitle() {
        GameBinding binding = binding("33214", "Factorio");
        binding.setCclEnabled(true);
        binding.setCcls(Set.of("ViolentGraphic"));
        when(titles.titleFor(any(), any(), any(), anyBoolean(), any())).thenReturn(Optional.empty());

        service.updateChannelReporting(account, binding);

        verify(titles).titleFor(eq(account), eq(settings), eq(binding), eq(true), any());
    }
}
