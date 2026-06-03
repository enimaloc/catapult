package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.ExperimentAssignment;
import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.domain.UserSettings;
import fr.enimaloc.catapult.getter.SteamApiClient;
import fr.enimaloc.catapult.repository.GameBindingRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.repository.UserSettingsRepository;
import fr.enimaloc.catapult.security.CatapultOAuth2User;
import fr.enimaloc.catapult.service.AccountService;
import fr.enimaloc.catapult.service.ActivityLogService;
import fr.enimaloc.catapult.service.AdminCclService;
import fr.enimaloc.catapult.service.BindingService;
import fr.enimaloc.catapult.service.ChannelAccessService;
import fr.enimaloc.catapult.service.ExperimentService;
import fr.enimaloc.catapult.service.ConnectionEventService;
import fr.enimaloc.catapult.service.GameStateService;
import fr.enimaloc.catapult.service.StreamStateService;
import fr.enimaloc.catapult.service.EventSubService;
import fr.enimaloc.catapult.service.TwitchCategory;
import fr.enimaloc.catapult.service.TwitchService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class ChannelController {

    public static final String ATTR_CHANNEL_USERNAME = "channelUsername";
    public static final String ATTR_IS_OWNER = "isOwner";
    public static final String ATTR_AVAILABLE_CCL = "availableCcls";

    public static final String REDIRECT_CHANNEL = "redirect:/channels/";

    @Value("${steam.api-key:}")
    private String steamApiKey;

    private final UserAccountRepository userAccountRepository;
    private final ChannelAccessService channelAccessService;
    private final GameStateService gameStateService;
    private final GameBindingRepository gameBindingRepository;
    private final BindingService bindingService;
    private final TwitchService twitchService;
    private final AdminCclService adminCclService;
    private final AccountService accountService;
    private final StreamStateService streamStateService;
    private final EventSubService twitchEventSubService;
    private final UserSettingsRepository userSettingsRepository;
    private final ActivityLogService activityLogService;
    private final ConnectionEventService connectionEventService;
    private final ExperimentService experimentService;
    private final Optional<SteamApiClient> steamApiClient;

    // -------------------------------------------------------------------------
    // Model attributes
    // -------------------------------------------------------------------------

    @ModelAttribute("activeExperimentAssignments")
    public List<ExperimentAssignment> activeExperimentAssignments(
            @AuthenticationPrincipal CatapultOAuth2User principal,
            HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        if (accept != null && (accept.contains("text/event-stream") || accept.contains("application/json"))) {
            return List.of();
        }
        if (principal == null) return List.of();
        return experimentService.getActiveAssignments(principal.getUserAccount());
    }

    // -------------------------------------------------------------------------
    // Main channel page
    // -------------------------------------------------------------------------

    @GetMapping("/channels/{username}")
    public String channelPage(
            @PathVariable String username,
            @AuthenticationPrincipal CatapultOAuth2User principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String source,
            Model model) {

        UserAccount channelUser = resolveAndCheck(username, principal);
        UserAccount viewer = principal.getUserAccount();
        boolean isOwner = viewer.getId().equals(channelUser.getId());

        model.addAttribute("channelUser", channelUser);
        model.addAttribute(ATTR_CHANNEL_USERNAME, username);
        model.addAttribute(ATTR_IS_OWNER, isOwner);

        model.addAttribute("currentGame", gameStateService.getLastKnownGame(channelUser).orElse(null));
        model.addAttribute("botEnabled", channelUser.isBotEnabled());
        model.addAttribute("isLive", streamStateService.isLive(channelUser));

        PageRequest pageRequest = PageRequest.of(page, 20);
        Page<GameBinding> bindings;
        if (status != null && !status.isBlank()) {
            bindings = gameBindingRepository.findByUserAndStatus(
                channelUser, GameBinding.Status.valueOf(status.toUpperCase()), pageRequest);
        } else if (source != null && !source.isBlank()) {
            bindings = gameBindingRepository.findByUserAndSourceType(
                channelUser, GameBinding.SourceType.valueOf(source.toUpperCase()), pageRequest);
        } else {
            bindings = gameBindingRepository.findByUser(channelUser, pageRequest);
        }
        model.addAttribute("bindings", bindings);
        model.addAttribute(ATTR_AVAILABLE_CCL, adminCclService.getAllCcls());
        model.addAttribute("filterStatus", status);
        model.addAttribute("filterSource", source);
        model.addAttribute("hasSteamProvider", !steamApiKey.isBlank());
        model.addAttribute("hasSteam", !steamApiKey.isBlank() && channelUser.getSteamId() != null);

        return "app";
    }

    // -------------------------------------------------------------------------
    // Fragment endpoints (HTMX polling)
    // -------------------------------------------------------------------------

    @GetMapping("/channels/{username}/fragments/status")
    public String fragmentStatus(
            @PathVariable String username,
            @AuthenticationPrincipal CatapultOAuth2User principal,
            Model model) {
        UserAccount channelUser = resolveAndCheck(username, principal);
        model.addAttribute(ATTR_CHANNEL_USERNAME, username);
        model.addAttribute("currentGame", gameStateService.getLastKnownGame(channelUser).orElse(null));
        model.addAttribute("botEnabled", channelUser.isBotEnabled());
        model.addAttribute("isLive", streamStateService.isLive(channelUser));
        return "fragments/status :: status";
    }

    @GetMapping("/channels/{username}/fragments/bindings")
    public String fragmentBindings(
            @PathVariable String username,
            @AuthenticationPrincipal CatapultOAuth2User principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String source,
            Model model) {
        UserAccount channelUser = resolveAndCheck(username, principal);
        UserAccount viewer = principal.getUserAccount();
        model.addAttribute(ATTR_CHANNEL_USERNAME, username);
        model.addAttribute(ATTR_IS_OWNER, viewer.getId().equals(channelUser.getId()));

        PageRequest pageRequest = PageRequest.of(page, 20);
        Page<GameBinding> bindings;
        if (status != null && !status.isBlank()) {
            bindings = gameBindingRepository.findByUserAndStatus(
                channelUser, GameBinding.Status.valueOf(status.toUpperCase()), pageRequest);
        } else if (source != null && !source.isBlank()) {
            bindings = gameBindingRepository.findByUserAndSourceType(
                channelUser, GameBinding.SourceType.valueOf(source.toUpperCase()), pageRequest);
        } else {
            bindings = gameBindingRepository.findByUser(channelUser, pageRequest);
        }
        model.addAttribute("bindings", bindings);
        model.addAttribute(ATTR_AVAILABLE_CCL, adminCclService.getAllCcls());
        model.addAttribute("filterStatus", status);
        model.addAttribute("filterSource", source);
        return "fragments/bindings :: bindings";
    }

    @GetMapping("/channels/{username}/fragments/connections")
    public String fragmentConnections(
            @PathVariable String username,
            @AuthenticationPrincipal CatapultOAuth2User principal,
            Model model) {
        UserAccount channelUser = resolveAndCheck(username, principal);
        UserAccount viewer = principal.getUserAccount();
        boolean isOwner = viewer.getId().equals(channelUser.getId());
        boolean hasSteam = !steamApiKey.isBlank() && channelUser.getSteamId() != null;

        boolean steamProfilePrivate = false;
        if (hasSteam && isOwner) {
            steamProfilePrivate = steamApiClient
                .map(c -> !c.isProfilePublic(channelUser.getSteamId()))
                .orElse(false);
        }

        model.addAttribute(ATTR_CHANNEL_USERNAME, username);
        model.addAttribute(ATTR_IS_OWNER, isOwner);
        model.addAttribute("hasSteamProvider", !steamApiKey.isBlank());
        model.addAttribute("hasSteam", hasSteam);
        model.addAttribute("steamProfilePrivate", steamProfilePrivate);
        return "fragments/connections :: connections";
    }

    @GetMapping("/channels/{username}/fragments/no-game-settings")
    public String fragmentNoGameSettings(
            @PathVariable String username,
            @AuthenticationPrincipal CatapultOAuth2User principal,
            Model model) {
        UserAccount channelUser = resolveAndCheck(username, principal);
        model.addAttribute(ATTR_CHANNEL_USERNAME, username);
        UserSettings settings = userSettingsRepository.findById(channelUser.getId())
            .orElseGet(UserSettings::new);
        model.addAttribute("noGameSettings", settings);
        model.addAttribute(ATTR_AVAILABLE_CCL, adminCclService.getAllCcls());
        return "fragments/no-game-settings :: no-game-settings";
    }

    // -------------------------------------------------------------------------
    // SSE endpoints
    // -------------------------------------------------------------------------

    @GetMapping(value = "/channels/{username}/logs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter streamLogs(
            @PathVariable String username,
            @AuthenticationPrincipal CatapultOAuth2User principal) {
        UserAccount channelUser = resolveAndCheck(username, principal);
        return activityLogService.subscribe(channelUser.getId());
    }

    @GetMapping(value = "/channels/{username}/connections", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter streamConnections(
            @PathVariable String username,
            @AuthenticationPrincipal CatapultOAuth2User principal) {
        UserAccount channelUser = resolveAndCheck(username, principal);
        return connectionEventService.subscribe(channelUser.getId());
    }

    // -------------------------------------------------------------------------
    // Game search
    // -------------------------------------------------------------------------

    @GetMapping(value = "/channels/{username}/api/games/search", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<TwitchCategory> searchGames(
            @PathVariable String username,
            @AuthenticationPrincipal CatapultOAuth2User principal,
            @RequestParam String q) {
        resolveAndCheck(username, principal);
        if (q.isBlank()) return List.of();
        return twitchService.searchCategories(principal.getUserAccount(), q);
    }

    // -------------------------------------------------------------------------
    // Binding actions
    // -------------------------------------------------------------------------

    @PostMapping("/channels/{username}/bindings/{id}")
    public String updateBinding(
            @PathVariable String username,
            @AuthenticationPrincipal CatapultOAuth2User principal,
            @PathVariable UUID id,
            @RequestParam(required = false) String twitchGameId,
            @RequestParam(required = false) String twitchGameName,
            @RequestParam(required = false, defaultValue = "false") boolean ignored,
            @RequestParam(required = false) Set<String> ccls) {
        UserAccount channelUser = resolveAndCheck(username, principal);
        Set<String> cclSet = ccls == null ? Set.of() : new HashSet<>(ccls);
        bindingService.updateBinding(channelUser, id, twitchGameId, twitchGameName, cclSet, ignored);
        return REDIRECT_CHANNEL + channelUser.getTwitchUsername(); // nosemgrep
    }

    @PostMapping("/channels/{username}/bindings/{id}/ccl-toggle")
    public String toggleCclEnabled(
            @PathVariable String username,
            @AuthenticationPrincipal CatapultOAuth2User principal,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean enabled) {
        UserAccount channelUser = resolveAndCheck(username, principal);
        bindingService.toggleCclEnabled(channelUser, id, enabled);
        return REDIRECT_CHANNEL + channelUser.getTwitchUsername(); // nosemgrep
    }

    @PostMapping("/channels/{username}/bindings/{id}/ignored-toggle")
    public String toggleIgnored(
            @PathVariable String username,
            @AuthenticationPrincipal CatapultOAuth2User principal,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean ignored) {
        UserAccount channelUser = resolveAndCheck(username, principal);
        bindingService.toggleIgnored(channelUser, id, ignored);
        return REDIRECT_CHANNEL + channelUser.getTwitchUsername(); // nosemgrep
    }

    @PostMapping("/channels/{username}/bindings/{id}/delete")
    public String deleteBinding(
            @PathVariable String username,
            @AuthenticationPrincipal CatapultOAuth2User principal,
            @PathVariable UUID id) {
        UserAccount channelUser = resolveAndCheck(username, principal);
        bindingService.deleteBinding(channelUser, id);
        return REDIRECT_CHANNEL + channelUser.getTwitchUsername(); // nosemgrep
    }

    // -------------------------------------------------------------------------
    // Settings actions
    // -------------------------------------------------------------------------

    @PostMapping("/channels/{username}/settings/bot")
    public String toggleBot(
            @PathVariable String username,
            @AuthenticationPrincipal CatapultOAuth2User principal) {
        UserAccount user = resolveAndCheck(username, principal);
        boolean newState = !user.isBotEnabled();
        user.setBotEnabled(newState);
        userAccountRepository.save(user);
        if (newState) {
            twitchEventSubService.connect(user);
        } else {
            twitchEventSubService.disconnect(user);
        }
        return REDIRECT_CHANNEL + user.getTwitchUsername(); // nosemgrep
    }

    @PostMapping("/channels/{username}/settings/no-game")
    public String saveNoGameSettings(
            @PathVariable String username,
            @AuthenticationPrincipal CatapultOAuth2User principal,
            @RequestParam(required = false) String twitchGameId,
            @RequestParam(required = false) String twitchGameName,
            @RequestParam(required = false) Set<String> ccls,
            @RequestParam(required = false, defaultValue = "false") boolean applyOnStreamStart,
            @RequestParam(required = false, defaultValue = "false") boolean applyOnNoGame,
            @RequestParam(required = false, defaultValue = "false") boolean applyOnStreamEnd) {
        UserAccount channelUser = resolveAndCheck(username, principal);
        UserSettings settings = userSettingsRepository.findById(channelUser.getId()).orElse(null);
        if (settings == null) {
            settings = new UserSettings();
            settings.setUser(channelUser);
        }
        settings.setNoGameTwitchGameId(twitchGameId);
        settings.setNoGameTwitchGameName(twitchGameName);
        settings.getNoGameCcls().clear();
        if (ccls != null) settings.getNoGameCcls().addAll(ccls);
        settings.setApplyDefaultOnStreamStart(applyOnStreamStart);
        settings.setApplyDefaultOnNoGame(applyOnNoGame);
        settings.setApplyDefaultOnStreamEnd(applyOnStreamEnd);
        userSettingsRepository.save(settings);
        if (gameStateService.getLastKnownGame(channelUser).isEmpty()) {
            twitchService.resetToDefault(channelUser);
        }
        return REDIRECT_CHANNEL + channelUser.getTwitchUsername(); // nosemgrep
    }

    @GetMapping("/channels/{username}/fragments/incomplete-fallback-settings")
    public String fragmentIncompleteFallbackSettings(
            @PathVariable String username,
            @AuthenticationPrincipal CatapultOAuth2User principal,
            Model model) {
        UserAccount channelUser = resolveAndCheck(username, principal);
        model.addAttribute(ATTR_CHANNEL_USERNAME, username);
        UserSettings settings = userSettingsRepository.findById(channelUser.getId())
            .orElseGet(UserSettings::new);
        model.addAttribute("incompleteFallbackSettings", settings);
        model.addAttribute(ATTR_AVAILABLE_CCL, adminCclService.getAllCcls());
        return "fragments/incomplete-fallback-settings :: incomplete-fallback-settings";
    }

    @PostMapping("/channels/{username}/settings/incomplete-fallback")
    public String saveIncompleteFallbackSettings(
            @PathVariable String username,
            @AuthenticationPrincipal CatapultOAuth2User principal,
            @RequestParam(required = false) String twitchGameId,
            @RequestParam(required = false) String twitchGameName,
            @RequestParam(required = false) Set<String> ccls) {
        UserAccount channelUser = resolveAndCheck(username, principal);
        UserSettings settings = userSettingsRepository.findById(channelUser.getId()).orElse(null);
        if (settings == null) {
            settings = new UserSettings();
            settings.setUser(channelUser);
        }
        settings.setIncompleteFallbackTwitchGameId(twitchGameId);
        settings.setIncompleteFallbackTwitchGameName(twitchGameName);
        settings.getIncompleteFallbackCcls().clear();
        if (ccls != null) settings.getIncompleteFallbackCcls().addAll(ccls);
        userSettingsRepository.save(settings);
        return REDIRECT_CHANNEL + channelUser.getTwitchUsername(); // nosemgrep
    }

    // -------------------------------------------------------------------------
    // Owner-only actions
    // -------------------------------------------------------------------------

    @PostMapping("/channels/{username}/settings/delete-account")
    public String deleteAccount(
            @PathVariable String username,
            @AuthenticationPrincipal CatapultOAuth2User principal,
            @RequestParam String confirmUsername) {
        UserAccount channelUser = resolveAndCheck(username, principal);
        requireOwner(principal.getUserAccount(), channelUser);
        if (channelUser.getTwitchUsername().equalsIgnoreCase(confirmUsername)) {
            accountService.initiateAccountDeletion(channelUser);
        }
        return REDIRECT_CHANNEL + channelUser.getTwitchUsername(); // nosemgrep
    }

    @PostMapping("/channels/{username}/settings/cancel-deletion")
    public String cancelDeletion(
            @PathVariable String username,
            @AuthenticationPrincipal CatapultOAuth2User principal) {
        UserAccount channelUser = resolveAndCheck(username, principal);
        requireOwner(principal.getUserAccount(), channelUser);
        accountService.cancelAccountDeletion(channelUser);
        return REDIRECT_CHANNEL + channelUser.getTwitchUsername(); // nosemgrep
    }

    @PostMapping("/channels/{username}/settings/disconnect")
    public String disconnectProvider(
            @PathVariable String username,
            @AuthenticationPrincipal CatapultOAuth2User principal,
            @RequestParam String provider) {
        UserAccount channelUser = resolveAndCheck(username, principal);
        requireOwner(principal.getUserAccount(), channelUser);
        OAuthToken.Provider p = OAuthToken.Provider.valueOf(provider.toUpperCase());
        accountService.disconnectProvider(channelUser, p);
        return REDIRECT_CHANNEL + channelUser.getTwitchUsername(); // nosemgrep
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private UserAccount resolveAndCheck(String username, CatapultOAuth2User principal) {
        UserAccount channelUser = userAccountRepository.findByTwitchUsername(username)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        UserAccount viewer = principal.getUserAccount();
        if (!channelAccessService.canAccess(viewer, channelUser)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return channelUser;
    }

    private void requireOwner(UserAccount viewer, UserAccount channelUser) {
        if (!viewer.getId().equals(channelUser.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }
}
