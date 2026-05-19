package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.domain.UserSettings;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.GameBindingRepository;
import fr.enimaloc.catapult.repository.UserSettingsRepository;
import fr.enimaloc.catapult.security.CatapultOAuth2User;
import fr.enimaloc.catapult.service.AccountService;
import fr.enimaloc.catapult.service.ActivityLogService;
import fr.enimaloc.catapult.service.AdminCclService;
import fr.enimaloc.catapult.service.BindingService;
import fr.enimaloc.catapult.service.GameStateService;
import fr.enimaloc.catapult.service.StreamStateService;
import fr.enimaloc.catapult.service.EventSubService;
import fr.enimaloc.catapult.service.TwitchService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

@Controller
@RequiredArgsConstructor
public class AppController {

    @Value("${steam.api-key:}")
    private String steamApiKey;

    private final GameStateService gameStateService;
    private final ActivityLogService activityLogService;
    private final GameBindingRepository gameBindingRepository;
    private final BindingService bindingService;
    private final TwitchService twitchService;
    private final AdminCclService adminCclService;
    private final AccountService accountService;
    private final StreamStateService streamStateService;
    private final EventSubService twitchEventSubService;
    private final UserSettingsRepository userSettingsRepository;

    // -------------------------------------------------------------------------
    // Old URL redirects
    // -------------------------------------------------------------------------

    @GetMapping({"/dashboard", "/settings"})
    public String redirectToApp() {
        return "redirect:/app";
    }

    @GetMapping("/bindings")
    public String redirectBindings(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String source) {
        StringBuilder url = new StringBuilder("redirect:/app");
        List<String> params = new ArrayList<>();
        if (page > 0)       params.add("page=" + page);
        if (status != null) params.add("status=" + status);
        if (source != null) params.add("source=" + source);
        if (!params.isEmpty()) url.append("?").append(String.join("&", params));
        return url.toString();
    }

    // -------------------------------------------------------------------------
    // Main unified page
    // -------------------------------------------------------------------------

    @GetMapping("/app")
    public String app(
            @AuthenticationPrincipal CatapultOAuth2User principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String source,
            Model model) {

        UserAccount user = principal.getUserAccount();

        // Status
        Optional<DetectedGame> currentGame = gameStateService.getLastKnownGame(user);
        model.addAttribute("currentGame", currentGame.orElse(null));
        model.addAttribute("botEnabled", user.isBotEnabled());
        model.addAttribute("isLive", streamStateService.isLive(user));

        // Bindings
        PageRequest pageRequest = PageRequest.of(page, 20);
        Page<GameBinding> bindings;
        if (status != null && !status.isBlank()) {
            bindings = gameBindingRepository.findByUserAndStatus(
                user, GameBinding.Status.valueOf(status.toUpperCase()), pageRequest);
        } else if (source != null && !source.isBlank()) {
            bindings = gameBindingRepository.findByUserAndSourceType(
                user, GameBinding.SourceType.valueOf(source.toUpperCase()), pageRequest);
        } else {
            bindings = gameBindingRepository.findByUser(user, pageRequest);
        }
        model.addAttribute("bindings", bindings);
        model.addAttribute("availableCcls", adminCclService.getAllCcls());
        model.addAttribute("filterStatus", status);
        model.addAttribute("filterSource", source);

        // Settings
        model.addAttribute("hasSteamProvider", !steamApiKey.isBlank());
        model.addAttribute("hasSteam", !steamApiKey.isBlank() && user.getSteamId() != null);

        // Common
        model.addAttribute("user", user);
        model.addAttribute("isPendingDeletion", user.getStatus() == UserAccount.Status.PENDING_DELETION);

        return "app";
    }

    // -------------------------------------------------------------------------
    // Fragment endpoints (HTMX polling)
    // -------------------------------------------------------------------------

    @GetMapping("/fragments/status")
    public String fragmentStatus(
            @AuthenticationPrincipal CatapultOAuth2User principal,
            Model model) {
        UserAccount user = principal.getUserAccount();
        model.addAttribute("currentGame", gameStateService.getLastKnownGame(user).orElse(null));
        model.addAttribute("botEnabled", user.isBotEnabled());
        model.addAttribute("isLive", streamStateService.isLive(user));
        return "fragments/status :: status";
    }

    @GetMapping("/fragments/bindings")
    public String fragmentBindings(
            @AuthenticationPrincipal CatapultOAuth2User principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String source,
            Model model) {
        UserAccount user = principal.getUserAccount();
        PageRequest pageRequest = PageRequest.of(page, 20);
        Page<GameBinding> bindings;
        if (status != null && !status.isBlank()) {
            bindings = gameBindingRepository.findByUserAndStatus(
                user, GameBinding.Status.valueOf(status.toUpperCase()), pageRequest);
        } else if (source != null && !source.isBlank()) {
            bindings = gameBindingRepository.findByUserAndSourceType(
                user, GameBinding.SourceType.valueOf(source.toUpperCase()), pageRequest);
        } else {
            bindings = gameBindingRepository.findByUser(user, pageRequest);
        }
        model.addAttribute("bindings", bindings);
        model.addAttribute("availableCcls", adminCclService.getAllCcls());
        model.addAttribute("filterStatus", status);
        model.addAttribute("filterSource", source);
        return "fragments/bindings :: bindings";
    }

    @GetMapping("/fragments/connections")
    public String fragmentConnections(
            @AuthenticationPrincipal CatapultOAuth2User principal,
            Model model) {
        UserAccount user = principal.getUserAccount();
        model.addAttribute("hasSteamProvider", !steamApiKey.isBlank());
        model.addAttribute("hasSteam", !steamApiKey.isBlank() && user.getSteamId() != null);
        return "fragments/connections :: connections";
    }

    @GetMapping("/fragments/no-game-settings")
    public String fragmentNoGameSettings(
            @AuthenticationPrincipal CatapultOAuth2User principal,
            Model model) {
        UserAccount user = principal.getUserAccount();
        // orElseGet(UserSettings::new) is safe for read-only display — userId null won't be persisted
        UserSettings settings = userSettingsRepository.findById(user.getId())
                .orElseGet(UserSettings::new);
        model.addAttribute("noGameSettings", settings);
        model.addAttribute("availableCcls", adminCclService.getAllCcls());
        return "fragments/no-game-settings :: no-game-settings";
    }

    // -------------------------------------------------------------------------
    // SSE (activity log)
    // -------------------------------------------------------------------------

    @GetMapping(value = "/app/logs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter streamLogs(@AuthenticationPrincipal CatapultOAuth2User principal) {
        return activityLogService.subscribe(principal.getUserAccount().getId());
    }

    // -------------------------------------------------------------------------
    // Bindings actions
    // -------------------------------------------------------------------------

    @PostMapping("/bindings/{id}")
    public String updateBinding(@AuthenticationPrincipal CatapultOAuth2User principal,
                                @PathVariable UUID id,
                                @RequestParam(required = false) String twitchGameId,
                                @RequestParam(required = false) String twitchGameName,
                                @RequestParam(required = false, defaultValue = "false") boolean ignored,
                                @RequestParam(required = false) Set<String> ccls) {
        Set<String> cclSet = ccls == null ? Set.of() : new HashSet<>(ccls);
        bindingService.updateBinding(principal.getUserAccount(), id, twitchGameId, twitchGameName, cclSet, ignored);
        return "redirect:/app";
    }

    @PostMapping("/bindings/{id}/ccl-toggle")
    public String toggleCclEnabled(@AuthenticationPrincipal CatapultOAuth2User principal,
                                   @PathVariable UUID id,
                                   @RequestParam(defaultValue = "false") boolean enabled) {
        bindingService.toggleCclEnabled(principal.getUserAccount(), id, enabled);
        return "redirect:/app";
    }

    @PostMapping("/bindings/{id}/ignored-toggle")
    public String toggleIgnored(@AuthenticationPrincipal CatapultOAuth2User principal,
                                @PathVariable UUID id,
                                @RequestParam(defaultValue = "false") boolean ignored) {
        bindingService.toggleIgnored(principal.getUserAccount(), id, ignored);
        return "redirect:/app";
    }

    @PostMapping("/bindings/{id}/delete")
    public String deleteBinding(@PathVariable UUID id) {
        bindingService.deleteBinding(id);
        return "redirect:/app";
    }

    @GetMapping(value = "/api/games/search", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<TwitchService.TwitchCategory> searchGames(
            @AuthenticationPrincipal CatapultOAuth2User principal,
            @RequestParam String q) {
        if (q.isBlank()) return List.of();
        return twitchService.searchCategories(principal.getUserAccount(), q);
    }

    // -------------------------------------------------------------------------
    // Settings actions
    // -------------------------------------------------------------------------

    @PostMapping("/settings/bot")
    public String toggleBot(@AuthenticationPrincipal CatapultOAuth2User principal,
                            @RequestParam boolean enabled) {
        UserAccount user = principal.getUserAccount();
        user.setBotEnabled(enabled);
        if (enabled) {
            twitchEventSubService.connect(user);
        } else {
            twitchEventSubService.disconnect(user);
        }
        return "redirect:/app";
    }

    @PostMapping("/settings/delete-account")
    public String deleteAccount(@AuthenticationPrincipal CatapultOAuth2User principal,
                                @RequestParam String confirmUsername) {
        UserAccount user = principal.getUserAccount();
        if (user.getTwitchUsername().equalsIgnoreCase(confirmUsername)) {
            accountService.initiateAccountDeletion(user);
        }
        return "redirect:/app";
    }

    @PostMapping("/settings/cancel-deletion")
    public String cancelDeletion(@AuthenticationPrincipal CatapultOAuth2User principal) {
        accountService.cancelAccountDeletion(principal.getUserAccount());
        return "redirect:/app";
    }

    @PostMapping("/settings/disconnect")
    public String disconnectProvider(@AuthenticationPrincipal CatapultOAuth2User principal,
                                     @RequestParam String provider) {
        OAuthToken.Provider p = OAuthToken.Provider.valueOf(provider.toUpperCase());
        accountService.disconnectProvider(principal.getUserAccount(), p);
        return "redirect:/app";
    }

    @PostMapping("/settings/no-game")
    public String saveNoGameSettings(
            @AuthenticationPrincipal CatapultOAuth2User principal,
            @RequestParam(required = false) String twitchGameId,
            @RequestParam(required = false) String twitchGameName,
            @RequestParam(required = false) Set<String> ccls) {
        UserAccount user = principal.getUserAccount();
        UserSettings settings = userSettingsRepository.findById(user.getId())
                .orElseGet(() -> { UserSettings s = new UserSettings(); s.setUser(user); return s; });
        settings.setNoGameTwitchGameId(twitchGameId);
        settings.setNoGameTwitchGameName(twitchGameName);
        settings.getNoGameCcls().clear();
        if (ccls != null) settings.getNoGameCcls().addAll(ccls);
        userSettingsRepository.save(settings);
        if (gameStateService.getLastKnownGame(user).isEmpty()) {
            twitchService.resetToDefault(user);
        }
        return "redirect:/app";
    }
}
