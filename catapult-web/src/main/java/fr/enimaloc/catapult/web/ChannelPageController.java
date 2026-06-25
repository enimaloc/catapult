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
import java.util.UUID;

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
        populateModel(model, data, username);
        return "app";
    }

    @GetMapping(value = "/api/games/search", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Object gamesSearch(@PathVariable String username, @RequestParam(defaultValue = "") String q) {
        return apiClient.get("/api/channels/{username}/games/search?q={q}", Object.class, username, q);
    }

    // ── Event-driven on-demand fragments ──────────────────────────────────────
    // These are NOT lazy-loaded on page render (the page renders complete in
    // channelPage()). They exist so channel-page.js can refetch a single section
    // in response to a WS event without reloading the whole page. Each handler
    // does the same data fetch + populateModel + return-fragment dance as the
    // main controller, scoped to one panel.

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
        model.addAttribute("channelUsername", username);
        if (data != null) {
            model.addAttribute("bindings", data.bindings());
            model.addAttribute("availableCcls", data.availableCcls());
            model.addAttribute("blockedCcls", data.blockedCcls());
            model.addAttribute("availableTws", data.availableTws());
            model.addAttribute("blockedTws", data.blockedTws());
            model.addAttribute("filterStatus", data.filterStatus());
            model.addAttribute("filterSource", data.filterSource());
        }
        return "fragments/bindings :: bindings";
    }

    @GetMapping("/fragments/connections")
    public String connectionsFragment(@PathVariable String username, Model model) {
        ChannelPageData data = apiClient.get("/api/channels/{username}", ChannelPageData.class, username);
        model.addAttribute("channelUsername", username);
        if (data != null) {
            model.addAttribute("isOwner", data.isOwner());
            model.addAttribute("hasSteamProvider", data.hasSteamProvider());
            model.addAttribute("hasSteam", data.hasSteam());
            model.addAttribute("hasSteamPersonalToken", data.hasSteamPersonalToken());
            model.addAttribute("steamTokenShared", data.steamTokenShared());
            model.addAttribute("steamProfilePrivate", data.steamProfilePrivate());
            model.addAttribute("steamRateLimited", data.steamRateLimited());
            model.addAttribute("steamOfflineMode", data.steamOfflineMode());
            model.addAttribute("steamProfileCacheTtlMinutes", data.steamProfileCacheTtlMinutes());
        }
        return "fragments/connections :: connections";
    }

    @GetMapping("/fragments/settings")
    public String settingsFragment(@PathVariable String username, Model model) {
        UserSettingsDto settings = apiClient.get(
                "/api/channels/{username}/settings", UserSettingsDto.class, username);
        ChannelPageData data = apiClient.get("/api/channels/{username}", ChannelPageData.class, username);
        model.addAttribute("channelUsername", username);
        model.addAttribute("cclSettings", settings);
        model.addAttribute("twSettings", settings);
        model.addAttribute("noGameSettings", settings);
        model.addAttribute("incompleteFallbackSettings", settings);
        if (data != null) {
            model.addAttribute("availableCcls", data.availableCcls());
            model.addAttribute("availableTws", data.availableTws());
            model.addAttribute("blockedCcls", data.blockedCcls());
            model.addAttribute("blockedTws", data.blockedTws());
        }
        return "fragments/settings-bundle :: settings-bundle";
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

    private static Object[] buildVars(String username, int page, String status, String source) {
        java.util.List<Object> vars = new java.util.ArrayList<>();
        vars.add(username);
        vars.add(page);
        if (status != null && !status.isBlank()) vars.add(status);
        if (source != null && !source.isBlank()) vars.add(source);
        return vars.toArray();
    }

    /**
     * Populates every model attribute the channel page (and all inlined fragments)
     * needs. Replaces the former per-fragment lazy controllers — the page now
     * renders in a single round-trip with the complete state, and subsequent
     * updates arrive as WS events handled client-side.
     */
    private void populateModel(Model model, ChannelPageData data, String username) {
        // Core channel data
        model.addAttribute("channelUser", data.channelUser());
        model.addAttribute("channelUsername", data.channelUsername());
        model.addAttribute("isOwner", data.isOwner());
        model.addAttribute("isLive", data.isLive());
        model.addAttribute("botEnabled", data.botEnabled());
        model.addAttribute("currentGame", data.currentGame());
        model.addAttribute("bindings", data.bindings());
        model.addAttribute("availableCcls", data.availableCcls());
        model.addAttribute("blockedCcls", data.blockedCcls());
        model.addAttribute("availableTws", data.availableTws());
        model.addAttribute("blockedTws", data.blockedTws());
        model.addAttribute("filterStatus", data.filterStatus());
        model.addAttribute("filterSource", data.filterSource());
        model.addAttribute("hasSteamProvider", data.hasSteamProvider());
        model.addAttribute("hasSteam", data.hasSteam());
        model.addAttribute("hasSteamPersonalToken", data.hasSteamPersonalToken());
        model.addAttribute("steamTokenShared", data.steamTokenShared());
        model.addAttribute("steamProfilePrivate", data.steamProfilePrivate());
        model.addAttribute("steamRateLimited", data.steamRateLimited());
        model.addAttribute("steamOfflineMode", data.steamOfflineMode());
        model.addAttribute("steamProfileCacheTtlMinutes", data.steamProfileCacheTtlMinutes());

        // Settings — single API call, exposed under four template attribute names
        // because each of the *Settings panels references different fields of the
        // same UserSettingsDto. Null tolerated: templates already check via th:if.
        UserSettingsDto settings = apiClient.get(
                "/api/channels/{username}/settings", UserSettingsDto.class, username);
        model.addAttribute("cclSettings", settings);
        model.addAttribute("twSettings", settings);
        model.addAttribute("noGameSettings", settings);
        model.addAttribute("incompleteFallbackSettings", settings);

        // DTDD mapping panel
        DtddMappingStatusDto dtdd = apiClient.get(
                "/api/channels/{username}/dtdd-mapping", DtddMappingStatusDto.class, username);
        model.addAttribute("dtddCurrent", dtdd == null ? null : dtdd.current());
        model.addAttribute("dtddPending", dtdd == null ? null : dtdd.myPendingProposal());
        model.addAttribute("dtddCanValidate", dtdd != null && dtdd.canValidateDirectly());
        model.addAttribute("dtddIgdbId", dtdd == null ? null : dtdd.igdbId());
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
            List<TwDto> availableTws,
            Set<String> blockedTws,
            String filterStatus,
            String filterSource,
            boolean hasSteamProvider,
            boolean hasSteam,
            boolean hasSteamPersonalToken,
            boolean steamTokenShared,
            boolean steamProfilePrivate,
            boolean steamRateLimited,
            boolean steamOfflineMode,
            long steamProfileCacheTtlMinutes
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
            Set<String> ccls,
            boolean twEnabled,
            boolean twOverride,
            Set<String> tws
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
            List<CclDto> availableCcls,
            boolean twFeatureEnabled,
            Set<String> blockedTws,
            List<TwDto> availableTws
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TwDto(String id, String label) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DtddMappingStatusDto(DtddMappingCurrentDto current, DtddMappingProposalDto myPendingProposal, boolean canValidateDirectly, String igdbId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DtddMappingCurrentDto(Long dtddId, String name, double confidence, boolean verified) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DtddMappingProposalDto(UUID id, Long proposedDtddId, String reason) {}
}
