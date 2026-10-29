package fr.enimaloc.catapult.simulation.panel;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import fr.enimaloc.catapult.service.twitch.TwitchCategory;
import fr.enimaloc.catapult.service.twitch.TwitchEventSubService;
import fr.enimaloc.catapult.simulation.CallKey;
import fr.enimaloc.catapult.simulation.CallSetting;
import fr.enimaloc.catapult.simulation.GameSourceResolver;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import fr.enimaloc.catapult.simulation.SimulatedData;
import fr.enimaloc.catapult.simulation.SimulationActions;
import fr.enimaloc.catapult.simulation.SimulationGate;
import fr.enimaloc.catapult.simulation.SimulationJournal;
import fr.enimaloc.catapult.simulation.SimulationRegistry;
import fr.enimaloc.catapult.simulation.account.FictitiousAccountService;
import fr.enimaloc.catapult.simulation.panel.MockPanelDtos.AccountView;
import fr.enimaloc.catapult.simulation.panel.MockPanelDtos.ApiCalls;
import fr.enimaloc.catapult.simulation.panel.MockPanelDtos.BotRequest;
import fr.enimaloc.catapult.simulation.panel.MockPanelDtos.CallView;
import fr.enimaloc.catapult.simulation.panel.MockPanelDtos.CreateAccount;
import fr.enimaloc.catapult.simulation.panel.MockPanelDtos.DataUpdate;
import fr.enimaloc.catapult.simulation.panel.MockPanelDtos.DetectedGameView;
import fr.enimaloc.catapult.simulation.panel.MockPanelDtos.LiveRequest;
import fr.enimaloc.catapult.simulation.panel.MockPanelDtos.ManualCategoryRequest;
import fr.enimaloc.catapult.simulation.panel.MockPanelDtos.PlayRequest;
import fr.enimaloc.catapult.simulation.panel.MockPanelDtos.RaidRequest;
import fr.enimaloc.catapult.simulation.panel.MockPanelDtos.SettingRequest;
import fr.enimaloc.catapult.simulation.panel.MockPanelDtos.SettingView;
import fr.enimaloc.catapult.simulation.panel.MockPanelDtos.StateView;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The control panel's JSON API: the accounts, each call's mode set one at a time (per account, or once for the
 * global ones), the simulated data, the actions of {@link SimulationActions}, and the journal, live too.
 * <p>
 * {@code /api/internal/**} lets a loopback caller in without a token ({@code ApiSecurityConfig}); on top of it, every
 * route answers a 404 unless {@link MockPanelAccess} lets the client in, and a write needs the panel's
 * {@value #PANEL_HEADER} header: a page of another origin cannot add one without a CORS preflight, which nothing
 * here allows, so it cannot drive the panel from the developer's browser (no CSRF token on this stateless chain).
 * Only under the {@code mock} profile with the simulation on.
 */
@Slf4j
@RestController
@Profile("mock")
@ConditionalOnBooleanProperty("catapult.simulation.enabled")
@DependsOn("mockGuard")
@RequestMapping(MockPanelApiController.PATH)
public class MockPanelApiController {

    static final String PATH = ApiPaths.INTERNAL + "/mock";
    static final String PANEL_HEADER = "X-Mock-Panel";
    private static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS");
    /** Longer would only hold a request thread for nothing: a timeout is a mode of its own. */
    private static final int MAX_LATENCY_MS = 60_000;
    private static final Map<String, CallKey> KEYS = SimulatedCalls.all().stream()
            .collect(Collectors.toUnmodifiableMap(CallKey::id, Function.identity()));
    private static final String EVENTSUB_API = SimulatedCalls.TWITCH_EVENTSUB_STREAM_ONLINE.api();

    private final FictitiousAccountService accounts;
    private final SimulationRegistry registry;
    private final SimulationJournal journal;
    private final SimulationActions actions;
    private final GameStateService gameState;
    private final StreamStateService streamState;
    private final ObjectProvider<TwitchEventSubService> eventSub;
    private final GameSourceResolver games;
    /** {@code app.owner-id}: the real account whose login gets {@code ROLE_ADMIN}. */
    private final String ownerId;

    public MockPanelApiController(FictitiousAccountService accounts, SimulationRegistry registry,
                                  SimulationJournal journal, SimulationActions actions,
                                  GameStateService gameState, StreamStateService streamState,
                                  ObjectProvider<TwitchEventSubService> eventSub, GameSourceResolver games,
                                  @Value("${app.owner-id:}") String ownerId) {
        this.accounts = accounts;
        this.registry = registry;
        this.journal = journal;
        this.actions = actions;
        this.gameState = gameState;
        this.streamState = streamState;
        this.eventSub = eventSub;
        this.games = games;
        this.ownerId = ownerId;
    }

    /** Runs before every route, before its arguments are read: who may use the panel, and how it writes. */
    @ModelAttribute
    void gate(HttpServletRequest request) {
        if (!MockPanelAccess.allowed(request)) {
            throw new ApiProblem(ErrorCode.NOT_FOUND, "Not found");
        }
        if (!READS.contains(request.getMethod()) && request.getHeader(PANEL_HEADER) == null) {
            throw new ApiProblem(ErrorCode.FORBIDDEN, "Writes come from the mock control panel only");
        }
    }

    /**
     * What the registry, the accounts and the actions refuse ({@code REAL} not allowed, a taken username, Steam
     * without a Steam id…): a 400 whose detail the panel shows.
     */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    ResponseEntity<ProblemDetail> refused(RuntimeException e) {
        log.warn("Mock panel request refused: {}", e.toString());
        ApiProblem problem = new ApiProblem(ErrorCode.INVALID, e.getMessage() == null ? "Refused" : e.getMessage());
        return ResponseEntity.status(problem.getStatusCode()).body(problem.getBody());
    }

    // ---------------------------------------------------------------- accounts

    @GetMapping("/accounts")
    public List<AccountView> accounts() {
        List<UserAccount> all = accounts.list();
        Map<String, String> usernames = usernamesByTwitchId(all);
        return all.stream().map(account -> view(account, usernames)).toList();
    }

    @PostMapping("/accounts")
    public AccountView create(@RequestBody CreateAccount body) {
        UserAccount created = accounts.create(body.username(), body.admin(),
                body.moderates() == null ? List.of() : body.moderates(),
                body.botEnabled() == null || body.botEnabled());
        Map<String, String> usernames = usernamesByTwitchId(accounts.list());
        return view(created, usernames);
    }

    /** Fictitious accounts only: a real one is refused (400). */
    @DeleteMapping("/accounts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        account(id);
        accounts.delete(id);
    }

    @GetMapping("/accounts/{id}/state")
    public StateView state(@PathVariable UUID id) {
        UserAccount account = account(id);
        SimulatedData data = registry.data(id);
        DetectedGameView game = gameState.getLastKnownGame(account)
                .map(g -> new DetectedGameView(g.getSourceId(), String.valueOf(g.getSourceType()), g.getSourceName()))
                .orElse(null);
        return new StateView(id, streamState.isLive(account), account.isBotEnabled(), game,
                data.currentCategoryId(), data.currentCategoryName(), data);
    }

    // ---------------------------------------------------------------- actions

    @PostMapping("/accounts/{id}/live")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void live(@PathVariable UUID id, @RequestBody LiveRequest body) {
        actions.setLive(account(id), body.live());
    }

    @PostMapping("/accounts/{id}/bot")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void bot(@PathVariable UUID id, @RequestBody BotRequest body) {
        actions.setBot(account(id), body.enabled());
    }

    @PostMapping("/accounts/{id}/play")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void play(@PathVariable UUID id, @RequestBody PlayRequest body) {
        UserAccount account = account(id);
        if (body.provider() == null || blank(body.sourceId()) || blank(body.name())) {
            throw new ApiProblem(ErrorCode.INVALID, "A platform, the game's id on it and its name are needed");
        }
        actions.play(account, body.provider(), body.sourceId().trim(), body.name().trim());
    }

    @PostMapping("/accounts/{id}/stop-playing")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stopPlaying(@PathVariable UUID id) {
        actions.stopPlaying(account(id));
    }

    @PostMapping("/accounts/{id}/manual-category")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void manualCategory(@PathVariable UUID id, @RequestBody ManualCategoryRequest body) {
        UserAccount account = account(id);
        if (blank(body.categoryId()) || blank(body.categoryName())) {
            throw new ApiProblem(ErrorCode.INVALID, "A category id and name are needed");
        }
        actions.manualCategoryChange(account, body.categoryId().trim(), body.categoryName().trim());
    }

    @PostMapping("/accounts/{id}/raid-out")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void raidOut(@PathVariable UUID id, @RequestBody RaidRequest body) {
        UserAccount account = account(id);
        if (blank(body.toLogin())) {
            throw new ApiProblem(ErrorCode.INVALID, "The raided channel's login is needed");
        }
        actions.raidOut(account, body.toLogin().trim());
    }

    /**
     * The id the platform's getter would report for a picked game, resolved by Catapult ({@link GameSourceResolver});
     * {@code sourceId} is {@code null} with a {@code reason} when nothing resolves, and the panel asks for it.
     */
    @GetMapping("/games/resolve")
    public GameSourceResolver.Resolution resolveGame(@RequestParam GetterConfig.Provider provider,
                                                     @RequestParam String name) {
        if (name.isBlank()) {
            throw new ApiProblem(ErrorCode.INVALID, "A game name is needed");
        }
        return games.resolve(provider, name.trim());
    }

    /** The games "Play" offers: the Twitch category search, in its own mode. */
    @GetMapping("/accounts/{id}/games")
    public List<TwitchCategory> games(@PathVariable UUID id, @RequestParam(defaultValue = "") String q) {
        UserAccount account = account(id);
        return q.isBlank() ? List.of() : actions.searchGames(account, q.trim());
    }

    // ---------------------------------------------------------------- settings

    @GetMapping("/calls")
    public List<ApiCalls> calls() {
        Map<String, List<CallView>> byApi = new LinkedHashMap<>();
        for (CallKey key : SimulatedCalls.all()) {
            byApi.computeIfAbsent(key.api(), api -> new ArrayList<>())
                    .add(new CallView(key.id(), key.call(), key.scope()));
        }
        return byApi.entrySet().stream().map(e -> new ApiCalls(e.getKey(), List.copyOf(e.getValue()))).toList();
    }

    @GetMapping("/accounts/{id}/settings")
    public List<SettingView> accountSettings(@PathVariable UUID id) {
        account(id);
        return settings(id, CallKey.Scope.ACCOUNT);
    }

    /**
     * Sets one of the account's calls. When an EventSub event turns {@code REAL} or stops being so, the account's
     * EventSub connection is opened again so the real subscriptions follow ({@link TwitchEventSubService#connect}),
     * if its bot is on: a bot that is off has no connection, and its next start reads the setting. Reconnecting
     * also forgets the channel's live state and any binding waiting for the stream to go live, as a bot restart does.
     */
    @PutMapping("/accounts/{id}/settings/{key}")
    public SettingView setAccountSetting(@PathVariable UUID id, @PathVariable String key,
                                         @RequestBody SettingRequest body) {
        UserAccount account = account(id);
        CallKey call = key(key, CallKey.Scope.ACCOUNT);
        boolean wasReal = registry.setting(id, call).mode() == CallSetting.Mode.REAL;
        registry.set(id, call, setting(body));
        boolean isReal = registry.setting(id, call).mode() == CallSetting.Mode.REAL;
        if (EVENTSUB_API.equals(call.api()) && wasReal != isReal) {
            reconnectEventSub(account);
        }
        return view(id, call);
    }

    @GetMapping("/global/settings")
    public List<SettingView> globalSettings() {
        return settings(null, CallKey.Scope.GLOBAL);
    }

    @PutMapping("/global/settings/{key}")
    public SettingView setGlobalSetting(@PathVariable String key, @RequestBody SettingRequest body) {
        CallKey call = key(key, CallKey.Scope.GLOBAL);
        registry.set(null, call, setting(body));
        return view(null, call);
    }

    // ---------------------------------------------------------------- simulated data

    @PutMapping("/accounts/{id}/data")
    public SimulatedData data(@PathVariable UUID id, @RequestBody DataUpdate body) {
        account(id);
        registry.update(id, data -> {
            SimulatedData changed = data;
            if (body.installed() != null) {
                changed = changed.withInstalled(body.installed());
            }
            if (body.active() != null) {
                changed = changed.withActive(body.active());
            }
            if (body.moderatedChannelIds() != null) {
                changed = changed.withModeratedChannelIds(body.moderatedChannelIds().stream()
                        .filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList());
            }
            if (body.steamProfilePublic() != null) {
                changed = changed.withSteamProfilePublic(body.steamProfilePublic());
            }
            if (body.ccls() != null) {
                changed = changed.withCcls(body.ccls());
            }
            return changed;
        });
        return registry.data(id);
    }

    // ---------------------------------------------------------------- journal

    /** The kept entries, oldest first; only the account's own when one is given. */
    @GetMapping("/journal")
    public List<SimulationJournal.Entry> journal(@RequestParam(required = false) @Nullable UUID account) {
        return journal.recent(account);
    }

    @GetMapping("/journal/stream")
    public SseEmitter journalStream() {
        return journal.subscribe();
    }

    // ---------------------------------------------------------------- helpers

    /** Any account but the system one; 404 for an unknown one. */
    private UserAccount account(UUID id) {
        return accounts.account(id)
                .orElseThrow(() -> new ApiProblem(ErrorCode.NOT_FOUND, "No account " + id));
    }

    /** 404 for a key the catalogue does not hold, 400 for one of the other scope. */
    private static CallKey key(String id, CallKey.Scope scope) {
        CallKey key = KEYS.get(id);
        if (key == null) {
            throw new ApiProblem(ErrorCode.NOT_FOUND, "No simulated call " + id);
        }
        if (key.scope() != scope) {
            throw new ApiProblem(ErrorCode.INVALID, id + " is set " + (scope == CallKey.Scope.GLOBAL
                    ? "per account" : "once for everyone"));
        }
        return key;
    }

    /** {@link SimulationRegistry#set} refuses a disallowed {@code REAL} and an {@code ERROR} without an error status. */
    private static CallSetting setting(SettingRequest body) {
        if (body.mode() == null) {
            throw new ApiProblem(ErrorCode.INVALID, "A mode is needed");
        }
        int latency = body.latencyMs() == null ? 0 : body.latencyMs();
        if (latency < 0 || latency > MAX_LATENCY_MS) {
            throw new ApiProblem(ErrorCode.INVALID, "The latency is between 0 and " + MAX_LATENCY_MS + " ms");
        }
        int status = body.mode() == CallSetting.Mode.ERROR && body.httpStatus() != null ? body.httpStatus() : 0;
        return new CallSetting(body.mode(), status, 0).withLatency(latency);
    }

    private List<SettingView> settings(@Nullable UUID accountId, CallKey.Scope scope) {
        return SimulatedCalls.all().stream()
                .filter(key -> key.scope() == scope)
                .map(key -> view(accountId, key))
                .toList();
    }

    private SettingView view(@Nullable UUID accountId, CallKey key) {
        CallSetting setting = registry.setting(accountId, key);
        return new SettingView(key.id(), key.api(), key.call(), key.scope(), setting.mode(), setting.httpStatus(),
                setting.latencyMs(), registry.realAllowed(accountId, key), registry.forcedReal(accountId, key));
    }

    private AccountView view(UserAccount account, Map<String, String> usernamesByTwitchId) {
        List<String> moderates = registry.data(account.getId()).moderatedChannelIds().stream()
                .map(twitchId -> usernamesByTwitchId.getOrDefault(twitchId, twitchId))
                .toList();
        boolean fictitious = SimulationGate.isFictitious(account);
        return new AccountView(account.getId(), account.getTwitchUsername(), account.getTwitchId(), fictitious,
                fictitious ? accounts.isAdmin(account.getId()) : isOwner(account), account.isBotEnabled(),
                String.valueOf(account.getStatus()), moderates);
    }

    private void reconnectEventSub(UserAccount account) {
        TwitchEventSubService service = eventSub.getIfAvailable();
        if (service == null || !account.isBotEnabled() || account.getStatus() != UserAccount.Status.ACTIVE) {
            return;
        }
        log.info("EventSub settings of {} changed between real and simulated — reconnecting", account.getId());
        service.connect(account);
    }

    /** The real account {@code app.owner-id} names: its login is given {@code ROLE_ADMIN}. */
    private boolean isOwner(UserAccount account) {
        return !ownerId.isBlank() && ownerId.equals(account.getTwitchId());
    }

    /** Each account's username by Twitch id, for the "mod of" badges. */
    private static Map<String, String> usernamesByTwitchId(List<UserAccount> all) {
        Map<String, String> usernames = new LinkedHashMap<>();
        all.forEach(account -> usernames.putIfAbsent(account.getTwitchId(), name(account)));
        return usernames;
    }

    private static String name(UserAccount account) {
        return account.getTwitchUsername() == null ? account.getTwitchId() : account.getTwitchUsername();
    }

    private static boolean blank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
