package fr.enimaloc.catapult.service.debug;

import fr.enimaloc.catapult.api.connect.ApiSteamConnectController;
import fr.enimaloc.catapult.chat.ChatCommandEvent;
import fr.enimaloc.catapult.chat.ChatCommandListener;
import fr.enimaloc.catapult.chat.CommandRegistry;
import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.chat.command.registry.ServiceFunctionRegistry;
import fr.enimaloc.catapult.chat.command.js.SandboxExecutor;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.binding.GameMapping;
import fr.enimaloc.catapult.domain.feedback.FeedbackSubmission;
import fr.enimaloc.catapult.domain.notification.Notification;
import fr.enimaloc.catapult.domain.notification.NotificationDelivery;
import fr.enimaloc.catapult.domain.notification.NotificationDeliveryId;
import fr.enimaloc.catapult.event.ChannelRaidedOutEvent;
import fr.enimaloc.catapult.event.SteamLinkedEvent;
import fr.enimaloc.catapult.event.StreamOnlineEvent;
import fr.enimaloc.catapult.getter.GameGetterChain;
import fr.enimaloc.catapult.getter.steam.SteamApiClient;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.repository.binding.GameBindingRepository;
import fr.enimaloc.catapult.repository.binding.GameMappingRepository;
import fr.enimaloc.catapult.repository.feedback.FeedbackSubmissionRepository;
import fr.enimaloc.catapult.repository.notification.NotificationDeliveryRepository;
import fr.enimaloc.catapult.repository.config.AppStateRepository;
import fr.enimaloc.catapult.security.DebugSubjectFilter;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.ActivityLogService;
import fr.enimaloc.catapult.service.binding.BindingService;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.binding.RaidRestoreGameService;
import fr.enimaloc.catapult.service.binding.RaidRestoreGateService;
import fr.enimaloc.catapult.service.binding.SchedulerService;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import fr.enimaloc.catapult.service.feedback.FeedbackNotificationService;
import fr.enimaloc.catapult.service.feedback.GitLabClient;
import fr.enimaloc.catapult.service.igdb.IgdbGameDetailsService;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import fr.enimaloc.catapult.service.notification.NotificationDeliveryDispatcher;
import fr.enimaloc.catapult.service.notification.NotificationRenderer;
import fr.enimaloc.catapult.service.notification.TwitchatEventDispatcher;
import fr.enimaloc.catapult.service.notification.TwitchatWidgetSettingsService;
import fr.enimaloc.catapult.service.steam.SteamLibraryCacheService;
import fr.enimaloc.catapult.service.tw.TwBackfillService;
import fr.enimaloc.catapult.service.tw.TwResolverService;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import fr.enimaloc.catapult.service.twitch.TwitchChatService;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import fr.enimaloc.catapult.common.dto.notification.NotificationChannel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Each entry point that works for a given member runs that work with the member as {@link DebugContext#subject()}:
 * a collaborator called during the work notes the subject it sees.
 */
class DebugAttributionTest {

    /** Subjects seen by collaborators, in call order ({@code null} when none was set). */
    private final List<UUID> seen = new CopyOnWriteArrayList<>();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        assertThat(DebugContext.subject()).as("no subject leaks out of an entry point").isEmpty();
    }

    private void note() {
        seen.add(DebugContext.subject().orElse(null));
    }

    private static UserAccount member() {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        user.setTwitchUsername("streamer");
        user.setBotEnabled(true);
        return user;
    }

    // --- Requests ----------------------------------------------------------------------------------

    private void filter(MockHttpServletRequest request) throws Exception {
        new DebugSubjectFilter().doFilter(request, new MockHttpServletResponse(), (req, res) -> note());
    }

    private static void authenticate(String subject) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(subject).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @Test
    void request_isAttributedToTheJwtSubject() throws Exception {
        UUID member = UUID.randomUUID();
        authenticate(member.toString());

        filter(new MockHttpServletRequest("GET", "/api/channel/settings"));

        assertThat(seen).containsExactly(member);
    }

    @Test
    void request_withANonUuidSubject_isNotAttributed() throws Exception {
        authenticate("service-account");

        filter(new MockHttpServletRequest("GET", "/api/channel/settings"));

        assertThat(seen).containsExactly((UUID) null);
    }

    @Test
    void adminActionOnMember_isAttributedToThatMember() throws Exception {
        UUID admin = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        authenticate(admin.toString());

        filter(new MockHttpServletRequest("POST", "/api/admin/members/" + member + "/debug"));
        filter(new MockHttpServletRequest("GET", "/api/admin/members/" + member));
        filter(new MockHttpServletRequest("GET", "/api/admin/members"));

        assertThat(seen).containsExactly(member, member, admin);
    }

    @Test
    void steamCallback_isAttributedToTheMemberOfItsNonce() {
        UserAccount user = member();
        UserAccountRepository accounts = mock(UserAccountRepository.class);
        when(accounts.findById(user.getId())).thenReturn(Optional.of(user));
        RestClient.Builder builder = RestClient.builder().requestInterceptor((req, body, exec) -> {
            note();
            return exec.execute(req, body);
        });
        MockRestServiceServer steam = MockRestServiceServer.bindTo(builder).build();
        steam.expect(requestTo("https://steamcommunity.com/openid/login"))
                .andRespond(withSuccess("is_valid:true\n", MediaType.TEXT_PLAIN));
        ApiSteamConnectController controller = new ApiSteamConnectController(accounts,
                mock(ApplicationEventPublisher.class), mock(ChannelEventPublisher.class), builder.build());
        ReflectionTestUtils.setField(controller, "apiBaseUrl", "http://api.test");
        ReflectionTestUtils.setField(controller, "webBaseUrl", "http://web.test");
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(user.getId().toString()).build();
        String returnTo = UriComponentsBuilder.fromUriString(controller.start(jwt).redirectUrl()).build()
                .getQueryParams().getFirst("openid.return_to");
        Map<String, String> params = new HashMap<>();
        params.put("nonce", returnTo.substring(returnTo.indexOf("nonce=") + "nonce=".length()));
        params.put("openid.mode", "id_res");
        params.put("openid.claimed_id", "https://steamcommunity.com/openid/id/76561198000000000");

        controller.callback(params);

        assertThat(seen).containsExactly(user.getId());
    }

    // --- Detection cycle -----------------------------------------------------------------------------

    private SchedulerService scheduler(UserAccountRepository accounts, GameGetterChain chain) {
        GameStateService state = mock(GameStateService.class);
        when(state.getLastKnownGame(any())).thenReturn(Optional.empty());
        return new SchedulerService(accounts, chain, state, mock(ApplicationEventPublisher.class),
                new SimpleMeterRegistry(), Optional.empty(), Optional.empty(), mock(BindingService.class));
    }

    @Test
    void pollOfTwoMembers_eachCallAttributedToItsOwnMember() {
        UserAccount first = member();
        UserAccount second = member();
        UserAccountRepository accounts = mock(UserAccountRepository.class);
        when(accounts.findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE)).thenReturn(List.of(first, second));
        GameGetterChain chain = mock(GameGetterChain.class);
        when(chain.resolve(any())).thenAnswer(inv -> {
            note();
            return Optional.empty();
        });

        scheduler(accounts, chain).poll();

        assertThat(seen).containsExactly(first.getId(), second.getId());
    }

    @Test
    void manualCheck_isAttributedToTheMember() {
        UserAccount user = member();
        GameGetterChain chain = mock(GameGetterChain.class);
        when(chain.resolve(any())).thenAnswer(inv -> {
            note();
            return Optional.empty();
        });

        scheduler(mock(UserAccountRepository.class), chain).triggerManualCheck(user);

        assertThat(seen).containsExactly(user.getId());
    }

    @Test
    void incompleteBindingRefresh_attributesEachBindingToItsOwner() {
        UserAccount first = member();
        UserAccount second = member();
        GameBindingRepository bindings = mock(GameBindingRepository.class);
        when(bindings.findAllByStatusAndIgnoredFalse(GameBinding.Status.INCOMPLETE))
                .thenReturn(List.of(binding(first, "1"), binding(second, "2")));
        when(bindings.save(any())).thenAnswer(inv -> inv.getArgument(0));
        GameMappingRepository mappings = mock(GameMappingRepository.class);
        when(mappings.findBySourceTypeAndSourceId(any(), anyString())).thenAnswer(inv -> {
            note();
            return Optional.of(new GameMapping());
        });
        BindingService service = new BindingService(bindings, mock(IgdbService.class),
                mock(IgdbGameDetailsService.class), mock(TwitchService.class), mock(TwResolverService.class),
                mock(GameStateService.class), mappings);

        service.refreshIncompleteBindings();

        assertThat(seen).containsExactly(first.getId(), second.getId());
    }

    private static GameBinding binding(UserAccount owner, String sourceId) {
        GameBinding binding = new GameBinding();
        binding.setId(UUID.randomUUID());
        binding.setUser(owner);
        binding.setSourceType(GameBinding.SourceType.STEAM);
        binding.setSourceId(sourceId);
        binding.setSourceName("Game " + sourceId);
        // A MANUAL binding is saved as is once its mapping is found: no IGDB lookup to stub
        binding.setStatus(GameBinding.Status.MANUAL);
        return binding;
    }

    @Test
    void twBackfill_attributesEachBindingToItsOwner() {
        UserAccount first = member();
        UserAccount second = member();
        AppStateRepository appState = mock(AppStateRepository.class);
        when(appState.findById("tw_backfill_completed")).thenReturn(Optional.empty());
        GameBindingRepository bindings = mock(GameBindingRepository.class);
        when(bindings.findCandidatesForTwBackfill(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(binding(first, "1"), binding(second, "2"))));
        IgdbService igdb = mock(IgdbService.class);
        when(igdb.resolveIgdbIdForBinding(any())).thenAnswer(inv -> {
            note();
            return Optional.empty();
        });
        TwResolverService resolver = mock(TwResolverService.class);
        when(resolver.suggest(any())).thenReturn(java.util.Set.of());
        TwBackfillService service = new TwBackfillService(appState, bindings, mock(GameMappingRepository.class),
                igdb, resolver, new SimpleMeterRegistry());
        service.setThrottle(1000);
        service.setBatchSize(50);

        service.run();

        assertThat(seen).containsExactly(first.getId(), second.getId());
    }

    // --- Steam library cache -------------------------------------------------------------------------

    private SteamLibraryCacheService steamCache(SteamApiClient steam, UserAccountRepository accounts) {
        when(steam.getOwnedGameIds(anyString(), any())).thenAnswer(inv -> {
            note();
            return CompletableFuture.completedFuture(List.of());
        });
        return new SteamLibraryCacheService(steam, mock(IgdbService.class), accounts, mock(TokenEncryptionService.class));
    }

    @Test
    void steamLibraryPreload_attributesEachLibraryToItsMember() {
        UserAccount first = member();
        first.setSteamId("1");
        UserAccount second = member();
        second.setSteamId("2");
        UserAccountRepository accounts = mock(UserAccountRepository.class);
        when(accounts.findBySteamIdNotNull()).thenReturn(List.of(first, second));

        steamCache(mock(SteamApiClient.class), accounts).preloadAllUserLibraries();

        assertThat(seen).containsExactly(first.getId(), second.getId());
    }

    @Test
    void steamLinked_isAttributedToTheMember() {
        UserAccount user = member();
        user.setSteamId("1");

        steamCache(mock(SteamApiClient.class), mock(UserAccountRepository.class))
                .onSteamLinked(new SteamLinkedEvent(this, user));

        assertThat(seen).containsExactly(user.getId());
    }

    // --- Events --------------------------------------------------------------------------------------

    @Test
    void chatCommand_isAttributedToTheChannelOwner() {
        UserAccount owner = member();
        CommandRegistry registry = mock(CommandRegistry.class);
        doAnswer(inv -> {
            note();
            return null;
        }).when(registry).dispatch(any(), any(Boolean.class));

        new ChatCommandListener(registry, mock(ExperimentService.class))
                .onChatCommand(new ChatCommandEvent(this, owner, "!game", List.of(), ChatCommandEvent.SenderRole.VIEWERS));

        assertThat(seen).containsExactly(owner.getId());
    }

    @Test
    void raidRestore_isAttributedToTheRaidingMember() {
        UserAccount user = member();
        UserSettingsRepository settings = mock(UserSettingsRepository.class);
        when(settings.findById(user.getId())).thenAnswer(inv -> {
            note();
            return Optional.of(new UserSettings());
        });
        RaidRestoreGameService service = new RaidRestoreGameService(settings, mock(RaidRestoreGateService.class),
                mock(GameStateService.class), mock(BindingService.class), mock(TwitchService.class));

        service.onRaidedOut(new ChannelRaidedOutEvent(this, user, "509658"));

        assertThat(seen).containsExactly(user.getId());
    }

    @Test
    void streamStartNotifications_arePostedAsTheMember_evenFromTheSchedulerThread() {
        UserAccount user = member();
        Notification n = new Notification();
        n.setId(UUID.randomUUID());
        n.setTitle("T");
        n.setBody("b");
        n.setCreatedAt(Instant.now());
        NotificationDelivery delivery = new NotificationDelivery();
        delivery.setId(new NotificationDeliveryId(n.getId(), user.getId(), NotificationChannel.STREAM_START_CHAT));
        delivery.setNotification(n);
        NotificationDeliveryRepository deliveries = mock(NotificationDeliveryRepository.class);
        when(deliveries.findPending(user.getId(), NotificationChannel.STREAM_START_CHAT)).thenReturn(List.of(delivery));
        when(deliveries.claim(any(), any(), any())).thenReturn(1);
        List<Runnable> scheduled = new ArrayList<>();
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(inv -> {
            scheduled.add(inv.getArgument(0));
            return null;
        });
        TwitchChatService chat = mock(TwitchChatService.class);
        doAnswer(inv -> {
            note();
            return null;
        }).when(chat).sendMessage(eq(user), anyString());
        NotificationDeliveryDispatcher dispatcher = new NotificationDeliveryDispatcher(deliveries,
                mock(TwitchatEventDispatcher.class), mock(TwitchatWidgetSettingsService.class), chat,
                taskScheduler, new NotificationRenderer());

        dispatcher.onStreamOnline(new StreamOnlineEvent(this, user));
        // The scheduler runs the task later, on its own thread, without the subject
        scheduled.forEach(Runnable::run);

        assertThat(seen).containsExactly(user.getId());
    }

    @Test
    void feedbackPoll_attributesEachSubmissionToItsAuthor() {
        UserAccount first = member();
        UserAccount second = member();
        FeedbackSubmissionRepository repository = mock(FeedbackSubmissionRepository.class);
        when(repository.findBySubscribedTrue()).thenReturn(List.of(submission(first, 1), submission(second, 2)));
        GitLabClient gitLab = mock(GitLabClient.class);
        when(gitLab.getIssue(any(Integer.class))).thenAnswer(inv -> {
            note();
            return new GitLabClient.IssueState(inv.getArgument(0), "opened", Instant.EPOCH);
        });

        new FeedbackNotificationService(repository, gitLab, mock(ActivityLogService.class)).pollUpdates();

        assertThat(seen).containsExactly(first.getId(), second.getId());
    }

    private static FeedbackSubmission submission(UserAccount author, int iid) {
        FeedbackSubmission submission = new FeedbackSubmission();
        submission.setUser(author);
        submission.setGitlabIssueIid(iid);
        submission.setLastKnownUpdatedAt(Instant.EPOCH);
        return submission;
    }

    // --- Executors -----------------------------------------------------------------------------------

    @Test
    void chatCommandSandbox_carriesTheMemberToItsWorkerThread() {
        UserAccount user = member();
        ServiceFunctionRegistry registry = new ServiceFunctionRegistry();
        registry.register(new ServiceFunction() {
            @Override public String namespace() { return "test"; }
            @Override public String name() { return "probe"; }
            @Override public List<String> parameterNames() { return List.of(); }
            @Override public Object invoke(UserAccount u, Object[] args) {
                note();
                return "ok";
            }
        });
        SandboxExecutor sandbox = new SandboxExecutor();

        DebugContext.runAs(user.getId(), () -> {
            sandbox.execute("return test.probe();", path -> null, name -> List.of(), registry, user, null,
                    Duration.ofSeconds(5));
            sandbox.executeWithTrace("return test.probe();", path -> null, name -> List.of(), registry, user, null,
                    Duration.ofSeconds(5));
        });

        assertThat(seen).containsExactly(user.getId(), user.getId());
    }
}
