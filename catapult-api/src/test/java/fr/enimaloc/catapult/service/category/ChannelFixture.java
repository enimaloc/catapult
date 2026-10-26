package fr.enimaloc.catapult.service.category;

import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.api.channel.ApiChannelActionsController;
import fr.enimaloc.catapult.chat.command.registry.catapult.CatapultSetGameFunction;
import fr.enimaloc.catapult.common.dto.channel.NoGameSettingsRequest;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingRequest;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatActionType;
import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.binding.CustomBinding;
import fr.enimaloc.catapult.domain.binding.CustomBindingActivation;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.twitchat.TwitchatActionToken;
import fr.enimaloc.catapult.event.ChannelRaidedOutEvent;
import fr.enimaloc.catapult.event.GameDetectedEvent;
import fr.enimaloc.catapult.event.NoGameDetectedEvent;
import fr.enimaloc.catapult.event.StreamOfflineEvent;
import fr.enimaloc.catapult.event.StreamOnlineEvent;
import fr.enimaloc.catapult.event.listener.GameEventListener;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.repository.binding.CustomBindingActivationRepository;
import fr.enimaloc.catapult.repository.binding.CustomBindingRepository;
import fr.enimaloc.catapult.repository.binding.GameBindingRepository;
import fr.enimaloc.catapult.repository.binding.GameMappingRepository;
import fr.enimaloc.catapult.repository.steam.SteamApiKeyRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.account.AccountService;
import fr.enimaloc.catapult.service.account.BotToggleService;
import fr.enimaloc.catapult.service.binding.BindingService;
import fr.enimaloc.catapult.service.binding.CustomBindingActivations;
import fr.enimaloc.catapult.service.binding.CustomBindingService;
import fr.enimaloc.catapult.service.binding.CustomBindingService.ActivationResult;
import fr.enimaloc.catapult.service.binding.CustomBindingService.UpdateResult;
import fr.enimaloc.catapult.service.binding.GameBindingLookup;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.binding.RaidRestoreGameService;
import fr.enimaloc.catapult.service.binding.RaidRestoreHold;
import fr.enimaloc.catapult.service.binding.SchedulerService;
import fr.enimaloc.catapult.service.binding.RaidRestoreGateService;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import fr.enimaloc.catapult.service.connections.SteamProfileDiagnostics;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import fr.enimaloc.catapult.service.igdb.IgdbGameDetailsService;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import fr.enimaloc.catapult.service.notification.TwitchatActionExecutor;
import fr.enimaloc.catapult.service.notification.TwitchatActionExecutor.Result;
import fr.enimaloc.catapult.service.notification.TwitchatActionTokenService;
import fr.enimaloc.catapult.service.notification.TwitchatEventActionService;
import fr.enimaloc.catapult.service.notification.TwitchatEventDispatcher;
import fr.enimaloc.catapult.service.notification.TwitchatWidgetSettingsService;
import fr.enimaloc.catapult.service.tw.TwResolverService;
import fr.enimaloc.catapult.service.twitch.ChannelUpdateOutcome;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * One channel, wired with the real services that decide and apply its Twitch category, over in-memory repository
 * answers; the only Twitch is a {@link RecordingTwitch}. The dashboard events that matter to the category (custom
 * binding changed, binding upserted) and every Twitch write land, in order, in {@link #journal()}.
 *
 * <p>Tests drive it through its trigger methods only, so a refactor that moves the category rules re-targets this
 * wiring, never a test.</p>
 */
public class ChannelFixture {

    public final UserAccount user = new UserAccount();
    public final UserSettings settings = new UserSettings();
    private final List<String> journal = new ArrayList<>();
    public final RecordingTwitch twitch = new RecordingTwitch(journal);

    private final Map<UUID, GameBinding> bindings = new HashMap<>();
    private final Map<UUID, CustomBinding> customBindings = new HashMap<>();
    private CustomBindingActivation activation;
    private final AtomicInteger activationReads = new AtomicInteger();
    private final AtomicInteger gateChecks = new AtomicInteger();
    private boolean raidGateOpen = true;
    private Runnable onSettingsRead = () -> { };
    private TwitchatActionToken actionToken;
    private Map<String, String> actionPayload = Map.of();

    private final ChannelEventPublisher publisher = mock(ChannelEventPublisher.class);
    private final TwitchatEventDispatcher dispatcher = mock(TwitchatEventDispatcher.class);

    private final GameStateService gameStateService;
    private final StreamStateService streamStateService;
    private final RaidRestoreHold raidRestoreHold = new RaidRestoreHold();
    private final RaidRestoreGameService raidRestoreGameService;
    private final GameEventListener listener;
    private final BindingService bindingService;
    private final CustomBindingService customBindingService;
    private final CatapultSetGameFunction setGameFunction;
    private final TwitchatActionExecutor twitchatActionExecutor;
    private final ApiChannelActionsController channelActionsController;

    public ChannelFixture() {
        user.setId(UUID.randomUUID());
        user.setTwitchId("twitch-" + user.getId());
        user.setTwitchUsername("streamer");
        user.setBotEnabled(true);
        user.setStatus(UserAccount.Status.ACTIVE);

        settings.setUserId(user.getId());
        settings.setUser(user);
        settings.setNoGameTwitchGameId("509658");
        settings.setNoGameTwitchGameName("Just Chatting");
        settings.getNoGameCcls().add("Gambling");
        settings.setIncompleteFallbackTwitchGameId("1469308723");
        settings.setIncompleteFallbackTwitchGameName("Software and Game Development");
        settings.getIncompleteFallbackCcls().add("ProfanityVulgarity");
        settings.setRaidRestoreGameMinutes(10);

        doAnswer(inv -> journal.add("event:customBindingChanged " + inv.getArgument(1)))
                .when(publisher).customBindingChanged(any(), any());
        doAnswer(inv -> journal.add("event:bindingUpserted " + inv.<GameBinding>getArgument(1).getSourceId()))
                .when(publisher).bindingUpserted(any(), any());

        GameBindingRepository bindingRepository = mock(GameBindingRepository.class);
        when(bindingRepository.findByIdAndUser(any(), any()))
                .thenAnswer(inv -> Optional.ofNullable(bindings.get(inv.<UUID>getArgument(0))));
        when(bindingRepository.findByUserAndSourceIdAndSourceType(any(), any(), any()))
                .thenAnswer(inv -> bindings.values().stream()
                        .filter(b -> b.getSourceId() != null && b.getSourceId().equals(inv.getArgument(1))
                                && b.getSourceType() == inv.getArgument(2))
                        .findFirst());
        when(bindingRepository.save(any())).thenAnswer(inv -> {
            GameBinding b = inv.getArgument(0);
            if (b.getId() == null) b.setId(UUID.randomUUID());
            bindings.put(b.getId(), b);
            return b;
        });

        CustomBindingRepository customBindingRepository = mock(CustomBindingRepository.class);
        when(customBindingRepository.findByIdAndUser(any(), any()))
                .thenAnswer(inv -> Optional.ofNullable(customBindings.get(inv.<UUID>getArgument(0))));
        when(customBindingRepository.findByUserAndNameIgnoreCase(any(), any()))
                .thenAnswer(inv -> customBindings.values().stream()
                        .filter(c -> c.getName().equalsIgnoreCase(inv.getArgument(1))).findFirst());
        when(customBindingRepository.save(any())).thenAnswer(inv -> {
            CustomBinding c = inv.getArgument(0);
            if (c.getId() == null) c.setId(UUID.randomUUID());
            customBindings.put(c.getId(), c);
            return c;
        });
        doAnswer(inv -> customBindings.remove(inv.<CustomBinding>getArgument(0).getId()))
                .when(customBindingRepository).delete(any());

        CustomBindingActivationRepository activationRepository = mock(CustomBindingActivationRepository.class);
        when(activationRepository.findById(any())).thenAnswer(inv -> {
            activationReads.incrementAndGet();
            return Optional.ofNullable(activation);
        });
        when(activationRepository.existsById(any())).thenAnswer(inv -> activation != null);
        doAnswer(inv -> activation = null).when(activationRepository).deleteById(any());
        when(activationRepository.save(any())).thenAnswer(inv -> activation = inv.getArgument(0));

        UserSettingsRepository settingsRepository = mock(UserSettingsRepository.class);
        when(settingsRepository.findById(any())).thenAnswer(inv -> {
            onSettingsRead.run();
            return Optional.of(settings);
        });
        UserAccountRepository accountRepository = mock(UserAccountRepository.class);
        when(accountRepository.findById(any())).thenAnswer(inv -> Optional.of(user));
        OAuthTokenRepository tokenRepository = mock(OAuthTokenRepository.class);
        OAuthToken token = new OAuthToken();
        token.setAccessToken("encrypted-token");
        when(tokenRepository.findByUserAndProvider(any(), any())).thenReturn(Optional.of(token));

        // Every experiment gate open: the custom bindings and the raid restore
        ExperimentService experimentService = mock(ExperimentService.class);
        when(experimentService.evaluateGate(any(), anyString())).thenReturn(true);
        when(experimentService.evaluateGate(any(), eq(CustomBindingService.EXPERIMENT_KEY))).thenAnswer(inv -> {
            gateChecks.incrementAndGet();
            return true;
        });
        when(experimentService.isRolledOut(any(), anyString())).thenAnswer(inv -> raidGateOpen);

        TwitchatActionTokenService actionTokenService = mock(TwitchatActionTokenService.class);
        when(actionTokenService.consume(any())).thenAnswer(inv -> Optional.ofNullable(actionToken));
        when(actionTokenService.readPayload(any())).thenAnswer(inv -> actionPayload);

        IgdbService igdbService = mock(IgdbService.class);
        GameBindingLookup lookup = new GameBindingLookup(bindingRepository);
        CustomBindingActivations activations =
                new CustomBindingActivations(activationRepository, experimentService, publisher);
        gameStateService = new GameStateService(publisher);
        streamStateService = new StreamStateService(publisher, dispatcher);
        bindingService = new BindingService(bindingRepository, igdbService, mock(IgdbGameDetailsService.class),
                twitch, mock(TwResolverService.class), gameStateService, mock(GameMappingRepository.class),
                activations, lookup);
        raidRestoreGameService = new RaidRestoreGameService(settingsRepository,
                new RaidRestoreGateService(experimentService), gameStateService, bindingService, twitch, activations,
                raidRestoreHold);
        CategoryChain categoryChain = new CategoryChain(activations, streamStateService, settingsRepository,
                raidRestoreHold, new CategoryApplier(twitch, streamStateService));
        listener = new GameEventListener(bindingService, twitch, settingsRepository, streamStateService, publisher,
                raidRestoreHold, activations, categoryChain);
        customBindingService = new CustomBindingService(customBindingRepository, activations, twitch,
                experimentService, gameStateService, streamStateService, settingsRepository, accountRepository,
                tokenRepository, listener, publisher);
        setGameFunction = new CatapultSetGameFunction(twitch, gameStateService, igdbService, activations);
        twitchatActionExecutor = new TwitchatActionExecutor(actionTokenService, accountRepository, twitch,
                bindingService, mock(BotToggleService.class), activations);
        channelActionsController = new ApiChannelActionsController(accountRepository, settingsRepository,
                new ApiUserResolver(accountRepository), bindingService, mock(BotToggleService.class), twitch,
                gameStateService, mock(AccountService.class), mock(SchedulerService.class),
                mock(TokenEncryptionService.class), mock(SteamApiKeyRepository.class), publisher,
                mock(TwitchatWidgetSettingsService.class), mock(TwitchatEventActionService.class), dispatcher,
                mock(SteamProfileDiagnostics.class), new RaidRestoreGateService(experimentService), activations,
                Optional.empty());
    }

    /** The category events and Twitch writes, in order. */
    public List<String> journal() {
        return List.copyOf(journal);
    }

    public TwitchatEventDispatcher dispatcher() {
        return dispatcher;
    }

    // ---- setup ----

    /** A saved game binding of a STEAM game {@code sourceId}, named {@code twitchName} like its category. */
    public GameBinding binding(String sourceId, GameBinding.Status status, String twitchId, String twitchName) {
        GameBinding b = new GameBinding();
        b.setId(UUID.randomUUID());
        b.setUser(user);
        b.setSourceId(sourceId);
        b.setSourceType(GameBinding.SourceType.STEAM);
        b.setSourceName(twitchName);
        b.setTwitchGameId(twitchId);
        b.setTwitchGameName(twitchName);
        b.setStatus(status);
        bindings.put(b.getId(), b);
        return b;
    }

    /** The game a detection of {@code binding}'s game reports. */
    public DetectedGame gameOf(GameBinding binding) {
        return new DetectedGame(binding.getSourceId(), binding.getSourceType(), binding.getSourceName());
    }

    public void live(boolean live) {
        streamStateService.setLive(user, live);
    }

    /** The game known as open now, without any detection event. */
    public void playing(DetectedGame game) {
        gameStateService.updateState(user, game);
    }

    /** A saved custom binding, without CCLs. */
    public CustomBinding custom(String name, String twitchId, String twitchName, boolean uninterruptible) {
        CustomBinding c = new CustomBinding();
        c.setId(UUID.randomUUID());
        c.setUser(user);
        c.setName(name);
        c.setTwitchGameId(twitchId);
        c.setTwitchGameName(twitchName);
        c.setUninterruptible(uninterruptible);
        customBindings.put(c.getId(), c);
        return c;
    }

    /** {@code custom} is the active custom binding (the activation row only: no event, no read counted). */
    public void active(CustomBinding custom) {
        activation = new CustomBindingActivation(user.getId(), custom, Instant.now());
    }

    /** The active custom binding as stored (no read counted). */
    public Optional<CustomBinding> activeCustom() {
        return Optional.ofNullable(activation).map(CustomBindingActivation::getCustomBinding);
    }

    /** A raid has put a game back: the no-game fallback holds off. */
    public void hold() {
        raidRestoreHold.hold(user);
    }

    public boolean holding() {
        return raidRestoreHold.isHolding(user);
    }

    public void pending(GameBinding binding) {
        streamStateService.storePending(user, binding);
    }

    public Optional<GameBinding> pending() {
        return streamStateService.getPending(user);
    }

    /** How many times the activation row has been read so far. */
    public int activationReads() {
        return activationReads.get();
    }

    /** How many times the custom bindings' experiment gate has been evaluated so far. */
    public int gateChecks() {
        return gateChecks.get();
    }

    /** Whether the raid restore's experiment is rolled out to the channel (it is unless told otherwise). */
    public void raidGate(boolean open) {
        this.raidGateOpen = open;
    }

    /** Runs {@code hook} at every read of the channel's settings. */
    public void whenSettingsRead(Runnable hook) {
        this.onSettingsRead = hook;
    }

    /** Whether the bot is on (the account stays ACTIVE). */
    public void bot(boolean enabled) {
        user.setBotEnabled(enabled);
    }

    // ---- triggers ----

    /** A poll that found {@code game}, new for the channel (no debug subject of its own). */
    public void detect(DetectedGame game, boolean firstSinceStart) {
        gameStateService.updateState(user, game);
        listener.onGameDetected(new GameDetectedEvent(this, user, game, firstSinceStart));
    }

    /** A poll that found no game any more. */
    public void closeGame() {
        gameStateService.clearState(user);
        listener.onNoGameDetected(new NoGameDetectedEvent(this, user));
    }

    public void streamOnline() {
        streamStateService.setLive(user, true);
        listener.onStreamOnline(new StreamOnlineEvent(this, user));
    }

    public void streamOffline() {
        streamStateService.setLive(user, false);
        listener.onStreamOffline(new StreamOfflineEvent(this, user));
    }

    public void setTwitchGame(GameBinding binding, String twitchId, String twitchName) {
        bindingService.setTwitchGame(user, binding.getId(), twitchId, twitchName);
    }

    public void toggleIgnored(GameBinding binding, boolean ignored) {
        bindingService.toggleIgnored(user, binding.getId(), ignored);
    }

    public void resetTws(GameBinding binding) {
        bindingService.resetTws(user, binding.getId());
    }

    /** An admin game mapping changed {@code binding}, the channel playing it. */
    public ChannelUpdateOutcome mappingChanged(GameBinding binding) {
        return listener.applyBindingIfLive(user, binding);
    }

    public ActivationResult activate(CustomBinding custom) {
        return customBindingService.activate(user, custom);
    }

    /** Saves {@code custom} unchanged, as the edit form does. */
    public UpdateResult update(CustomBinding custom) {
        return customBindingService.update(user, custom.getId(), new CustomBindingRequest(custom.getName(),
                custom.getTwitchGameId(), custom.getTwitchGameName(), Set.copyOf(custom.getCcls()),
                Set.copyOf(custom.getTws()), custom.isUninterruptible()));
    }

    public void deactivate() {
        customBindingService.deactivate(user);
    }

    public void delete(CustomBinding custom) {
        customBindingService.delete(user, custom.getId());
    }

    /** {@code !setgame name [igdbId]}. */
    public void setGame(String name, String igdbId) {
        setGameFunction.invoke(user, igdbId == null ? new Object[]{name} : new Object[]{name, igdbId});
    }

    /** A Twitchat action button of the channel clicked. */
    public Result twitchat(TwitchatActionType type, Map<String, String> payload) {
        actionToken = new TwitchatActionToken();
        actionToken.setToken(UUID.randomUUID());
        actionToken.setUserId(user.getId());
        actionToken.setActionType(type);
        actionToken.setPayloadJson("{}");
        actionPayload = payload;
        return twitchatActionExecutor.execute(actionToken.getToken());
    }

    /** The no-game settings form saved with the values {@link #settings} holds now. */
    public void noGameSettingsSaved() {
        channelActionsController.saveNoGameSettings(user.getId(), new NoGameSettingsRequest(
                settings.getNoGameTwitchGameId(), settings.getNoGameTwitchGameName(),
                Set.copyOf(settings.getNoGameCcls()), settings.isApplyDefaultOnStreamStart(),
                settings.isApplyDefaultOnNoGame(), settings.isApplyDefaultOnStreamEnd()));
    }

    /** The streamer raided out, the channel last seen on {@code currentCategoryId}. */
    public void raid(String currentCategoryId) {
        raidRestoreGameService.onRaidedOut(new ChannelRaidedOutEvent(this, user, currentCategoryId));
    }
}
