package fr.enimaloc.catapult.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import fr.enimaloc.catapult.client.ApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Controller
@RequestMapping("/channels/{username}")
@RequiredArgsConstructor
public class ChannelPageController {

    private final ApiClient apiClient;
    private final ObjectMapper jackson;

    @Value("${catapult.web.public-url:}")
    private String publicWebUrl;

    @GetMapping({"", "/{tab:dashboard|configuration|commands|invitations}"})
    public String channelPage(
            @PathVariable String username,
            @PathVariable(required = false) String tab,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String source,
            Model model) {

        // Only the channel owner or one of their Twitch moderators ever reaches this
        // point — fetchChannelPageData returns null (403/404 from the API) for anyone
        // else — so the tabbed layout is always the right one here, for owner and
        // moderator alike.
        ChannelPageData data = fetchChannelPageData(username, page, status, source);
        if (data == null) {
            return "redirect:/channels";
        }
        populateModel(model, data, username);

        String resolvedTab = tab == null ? "dashboard" : tab;
        if ("invitations".equals(resolvedTab) && !isInviteTabVariant(model)) {
            return "redirect:/channels/{username}";
        }
        model.addAttribute("activeTab", resolvedTab);
        if ("invitations".equals(resolvedTab)) {
            populateInviteModel(model);
        }
        return "app-tabbed";
    }

    /** Lazy-loaded panel fragment for a tab not rendered at initial page load. */
    @GetMapping("/tabs/{tab:dashboard|configuration|commands|invitations}")
    public String tabFragment(@PathVariable String username, @PathVariable String tab, Model model) {
        ChannelPageData data = fetchChannelPageData(username, 0, null, null);
        if (data == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        if ("invitations".equals(tab) && !isInviteTabVariant(model)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        populateModel(model, data, username);
        if ("invitations".equals(tab)) {
            populateInviteModel(model);
        }
        return "fragments/" + tab + "-tab :: " + tab + "-tab";
    }

    /** invitePlacementVariant is set globally by GlobalModelAdvice before this controller runs. */
    private boolean isInviteTabVariant(Model model) {
        return "tab".equals(model.getAttribute("invitePlacementVariant"));
    }

    /** Same data the standalone /invite page shows — the current user's own invite code. */
    private void populateInviteModel(Model model) {
        Map<?, ?> data = apiClient.get("/api/invite", Map.class);
        if (data == null) return;
        model.addAttribute("canInvite", Boolean.TRUE.equals(data.get("canInvite")));
        model.addAttribute("code", data.get("code"));
        model.addAttribute("inviteUrl", data.get("inviteUrl"));
        Object rawRedemptions = data.get("redemptions");
        List<InviteRedemption> redemptions = rawRedemptions instanceof List<?> list
                ? list.stream()
                        .filter(Map.class::isInstance)
                        .map(Map.class::cast)
                        .map(m -> new InviteRedemption((String) m.get("inviteeTwitchId"),
                                java.time.Instant.parse((String) m.get("redeemedAt"))))
                        .toList()
                : List.of();
        model.addAttribute("redemptions", redemptions);
    }

    public record InviteRedemption(String inviteeTwitchId, java.time.Instant redeemedAt) {}

    private ChannelPageData fetchChannelPageData(String username, int page, String status, String source) {
        StringBuilder url = new StringBuilder("/api/channels/{username}?page={page}");
        if (status != null && !status.isBlank()) url.append("&status={status}");
        if (source != null && !source.isBlank()) url.append("&source={source}");
        Object[] vars = buildVars(username, page, status, source);
        return apiClient.get(url.toString(), ChannelPageData.class, vars);
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

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
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
        model.addAttribute("hasXboxProvider", data.hasXboxProvider());
        model.addAttribute("hasXbox", data.hasXbox());

        // Liaison Minecraft : uniquement pour le propriétaire (l'API est scoped au JWT courant)
        if (data.isOwner()) {
            Map<String, Object> mc = apiClient.minecraftLinkState();
            model.addAttribute("minecraftStatus", mc == null ? "UNAVAILABLE" : String.valueOf(mc.getOrDefault("status", "NONE")));
            model.addAttribute("minecraftName", mc == null ? null : mc.get("minecraftName"));
            model.addAttribute("minecraftBotUsername", mc == null ? null : mc.get("serviceAccountUsername"));
        } else {
            model.addAttribute("minecraftStatus", "NONE");
        }

        // Settings — single API call, exposed under four template attribute names
        // because each of the *Settings panels references different fields of the
        // same UserSettingsDto. Null tolerated: templates already check via th:if.
        UserSettingsDto settings = apiClient.get(
                "/api/channels/{username}/settings", UserSettingsDto.class, username);
        model.addAttribute("cclSettings", settings);
        model.addAttribute("twSettings", settings);
        model.addAttribute("noGameSettings", settings);
        model.addAttribute("incompleteFallbackSettings", settings);

        // Twitchat notification widget settings — separate call, not part of UserSettingsDto.
        // Owner-only on the API side (requireOwner), so only fetch it for the owner.
        if (data.isOwner()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> twitchat = apiClient.get(
                    "/api/channels/{username}/settings/twitchat", Map.class, username);
            model.addAttribute("twitchatSettings", twitchat);
            model.addAttribute("twitchatWidgetUrl", twitchat == null ? null
                    : stripTrailingSlash(publicWebUrl) + "/widget/twitchat/" + twitchat.get("widgetToken"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> twitchatPresets = apiClient.get(
                    "/api/channels/{username}/twitchat/presets",
                    new org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>>() {}, username);
            @SuppressWarnings("unchecked")
            Map<String, String> twitchatActivePresets = apiClient.get(
                    "/api/channels/{username}/twitchat/active-presets", Map.class, username);
            Map<String, List<Map<String, Object>>> twitchatPresetsByEvent = new java.util.LinkedHashMap<>();
            for (String eventType : fr.enimaloc.catapult.web.TwitchatEventTypes.ALL) {
                twitchatPresetsByEvent.put(eventType, new java.util.ArrayList<>());
            }
            if (twitchatPresets != null) {
                for (Map<String, Object> preset : twitchatPresets) {
                    twitchatPresetsByEvent
                            .computeIfAbsent(String.valueOf(preset.get("eventType")), k -> new java.util.ArrayList<>())
                            .add(preset);
                }
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> twitchatDefaults = apiClient.get("/api/twitchat/defaults", Map.class);
            Map<String, String> twitchatDefaultsJson = new java.util.LinkedHashMap<>();
            if (twitchatDefaults != null) {
                twitchatDefaults.forEach((eventType, payload) ->
                        twitchatDefaultsJson.put(eventType, jackson.writeValueAsString(payload)));
            }
            model.addAttribute("twitchatEventTypes", fr.enimaloc.catapult.web.TwitchatEventTypes.ALL);
            model.addAttribute("twitchatPresetsByEvent", twitchatPresetsByEvent);
            model.addAttribute("twitchatActivePresets", twitchatActivePresets == null ? Map.of() : twitchatActivePresets);
            model.addAttribute("twitchatDefaultsJson", twitchatDefaultsJson);

            List<Map<String, Object>> twitchatQuickConfigs = apiClient.get("/api/twitchat/quick-configs",
                    new org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>>() {});
            String twitchatQuickConfigsJson = twitchatQuickConfigs == null
                    ? "[]" : jackson.writeValueAsString(twitchatQuickConfigs);
            model.addAttribute("twitchatQuickConfigsJson", twitchatQuickConfigsJson);
        }

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
            long steamProfileCacheTtlMinutes,
            boolean hasXboxProvider,
            boolean hasXbox
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
