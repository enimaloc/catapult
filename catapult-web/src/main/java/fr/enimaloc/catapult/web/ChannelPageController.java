package fr.enimaloc.catapult.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import fr.enimaloc.catapult.client.ApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Set;

@Controller
@RequestMapping("/channels/{username}")
@RequiredArgsConstructor
public class ChannelPageController {

    private final ApiClient apiClient;

    @GetMapping
    public String channelPage(
            @PathVariable String username,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String source,
            Model model) {

        StringBuilder url = new StringBuilder("/api/channels/{username}?page={page}");
        if (status != null && !status.isBlank()) url.append("&status={status}");
        if (source != null && !source.isBlank()) url.append("&source={source}");
        Object[] vars = buildVars(username, page, status, source);
        ChannelPageData data = apiClient.get(url.toString(), ChannelPageData.class, vars);
        if (data == null) {
            return "redirect:/channels";
        }
        populateModel(model, data);
        return "app";
    }

    @GetMapping("/fragments/status")
    public String statusFragment(@PathVariable String username, Model model) {
        StatusData data = apiClient.get("/api/channels/{username}/status", StatusData.class, username);
        if (data != null) {
            model.addAttribute("channelUsername", data.channelUsername());
            model.addAttribute("isOwner", data.isOwner());
            model.addAttribute("botEnabled", data.botEnabled());
            model.addAttribute("isLive", data.isLive());
            model.addAttribute("currentGame", data.currentGame());
        }
        return "fragments/status :: status";
    }

    @GetMapping(value = "/api/games/search", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Object gamesSearch(@PathVariable String username, @RequestParam(defaultValue = "") String q) {
        return apiClient.get("/api/channels/{username}/games/search?q={q}", Object.class, username, q);
    }

    // ── SSE proxies ────────────────────────────────────────────────────────────

    @GetMapping(value = "/logs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter logsProxy(@PathVariable String username) {
        SseEmitter emitter = new SseEmitter(0L);
        apiClient.streamSse("/api/channels/{username}/logs", emitter, username);
        return emitter;
    }

    @GetMapping(value = "/connections", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connectionsProxy(@PathVariable String username) {
        SseEmitter emitter = new SseEmitter(0L);
        apiClient.streamSse("/api/channels/{username}/connections", emitter, username);
        return emitter;
    }

    // ── Lazy-loaded HTMX fragments ─────────────────────────────────────────────

    @GetMapping("/fragments/connections")
    public String connectionsFragment(@PathVariable String username, Model model) {
        ChannelPageData data = apiClient.get("/api/channels/{username}", ChannelPageData.class, username);
        if (data != null) {
            model.addAttribute("channelUsername", data.channelUsername());
            model.addAttribute("isOwner", data.isOwner());
            model.addAttribute("hasSteamProvider", data.hasSteamProvider());
            model.addAttribute("hasSteam", data.hasSteam());
            model.addAttribute("hasSteamPersonalToken", data.hasSteamPersonalToken());
            model.addAttribute("steamTokenShared", data.steamTokenShared());
            model.addAttribute("steamProfilePrivate", data.steamProfilePrivate());
            model.addAttribute("steamRateLimited", data.steamRateLimited());
        }
        return "fragments/connections :: connections";
    }

    @GetMapping("/fragments/bindings")
    public String bindingsFragment(
            @PathVariable String username,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String source,
            Model model) {
        StringBuilder url = new StringBuilder("/api/channels/{username}?page={page}");
        if (status != null && !status.isBlank()) url.append("&status={status}");
        if (source != null && !source.isBlank()) url.append("&source={source}");
        Object[] vars = buildVars(username, page, status, source);
        ChannelPageData data = apiClient.get(url.toString(), ChannelPageData.class, vars);
        if (data != null) {
            model.addAttribute("channelUsername", data.channelUsername());
            model.addAttribute("bindings", data.bindings());
            model.addAttribute("availableCcls", data.availableCcls());
            model.addAttribute("blockedCcls", data.blockedCcls());
            model.addAttribute("filterStatus", data.filterStatus());
            model.addAttribute("filterSource", data.filterSource());
        }
        return "fragments/bindings :: bindings";
    }

    private static Object[] buildVars(String username, int page, String status, String source) {
        java.util.List<Object> vars = new java.util.ArrayList<>();
        vars.add(username);
        vars.add(page);
        if (status != null && !status.isBlank()) vars.add(status);
        if (source != null && !source.isBlank()) vars.add(source);
        return vars.toArray();
    }

    @GetMapping("/fragments/ccl-settings")
    public String cclSettings(@PathVariable String username, Model model) {
        model.addAttribute("channelUsername", username);
        UserSettingsDto settings = apiClient.get("/api/channels/{username}/settings", UserSettingsDto.class, username);
        if (settings != null) {
            model.addAttribute("cclSettings", settings);
            model.addAttribute("availableCcls", settings.availableCcls());
        }
        return "fragments/ccl-settings :: ccl-settings";
    }

    @GetMapping("/fragments/no-game-settings")
    public String noGameSettings(@PathVariable String username, Model model) {
        model.addAttribute("channelUsername", username);
        UserSettingsDto settings = apiClient.get("/api/channels/{username}/settings", UserSettingsDto.class, username);
        if (settings != null) {
            model.addAttribute("noGameSettings", settings);
            model.addAttribute("availableCcls", settings.availableCcls());
        }
        return "fragments/no-game-settings :: no-game-settings";
    }

    @GetMapping("/fragments/incomplete-fallback-settings")
    public String incompleteFallbackSettings(@PathVariable String username, Model model) {
        model.addAttribute("channelUsername", username);
        UserSettingsDto settings = apiClient.get("/api/channels/{username}/settings", UserSettingsDto.class, username);
        if (settings != null) {
            model.addAttribute("incompleteFallbackSettings", settings);
            model.addAttribute("availableCcls", settings.availableCcls());
        }
        return "fragments/incomplete-fallback-settings :: incomplete-fallback-settings";
    }

    private void populateModel(Model model, ChannelPageData data) {
        model.addAttribute("channelUser", data.channelUser());
        model.addAttribute("channelUsername", data.channelUsername());
        model.addAttribute("isOwner", data.isOwner());
        model.addAttribute("isLive", data.isLive());
        model.addAttribute("botEnabled", data.botEnabled());
        model.addAttribute("currentGame", data.currentGame());
        model.addAttribute("bindings", data.bindings());
        model.addAttribute("availableCcls", data.availableCcls());
        model.addAttribute("blockedCcls", data.blockedCcls());
        model.addAttribute("filterStatus", data.filterStatus());
        model.addAttribute("filterSource", data.filterSource());
        model.addAttribute("hasSteamProvider", data.hasSteamProvider());
        model.addAttribute("hasSteam", data.hasSteam());
        model.addAttribute("hasSteamPersonalToken", data.hasSteamPersonalToken());
        model.addAttribute("steamTokenShared", data.steamTokenShared());
        model.addAttribute("steamProfilePrivate", data.steamProfilePrivate());
        model.addAttribute("steamRateLimited", data.steamRateLimited());
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CclDto(String id, String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChannelPageData(
            ChannelUserDto channelUser,
            String channelUsername,
            boolean isOwner,
            boolean isLive,
            boolean botEnabled,
            GameDto currentGame,
            PagedBindings bindings,
            List<CclDto> availableCcls,
            Set<String> blockedCcls,
            String filterStatus,
            String filterSource,
            boolean hasSteamProvider,
            boolean hasSteam,
            boolean hasSteamPersonalToken,
            boolean steamTokenShared,
            boolean steamProfilePrivate,
            boolean steamRateLimited
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChannelUserDto(String id, String twitchId, String twitchUsername, String profileImageUrl) {}

    public record GameDto(String sourceName, String sourceType) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PagedBindings(int number, int totalPages, long totalElements, List<BindingDto> content) {
        public boolean first() { return number == 0; }
        public boolean last() { return number >= totalPages - 1; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BindingDto(
            String id,
            BindingStatus status,
            String sourceType,
            String sourceName,
            String twitchGameId,
            String twitchGameName,
            boolean ignored,
            boolean cclEnabled,
            Set<String> ccls
    ) {}

    public enum BindingStatus {
        AUTO, MANUAL, INCOMPLETE;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UserSettingsDto(
            boolean cclFeatureEnabled,
            Set<String> blockedCcls,
            String noGameTwitchGameId,
            String noGameTwitchGameName,
            Set<String> noGameCcls,
            boolean applyDefaultOnStreamStart,
            boolean applyDefaultOnNoGame,
            boolean applyDefaultOnStreamEnd,
            String incompleteFallbackTwitchGameId,
            String incompleteFallbackTwitchGameName,
            Set<String> incompleteFallbackCcls,
            List<CclDto> availableCcls
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StatusData(
            String channelUsername,
            boolean isOwner,
            boolean botEnabled,
            boolean isLive,
            GameDto currentGame
    ) {}
}
