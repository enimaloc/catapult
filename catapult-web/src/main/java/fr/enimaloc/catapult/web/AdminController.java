package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.client.ApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final ApiClient apiClient;

    // ── CCL ──────────────────────────────────────────────────────────────────

    @GetMapping("/ccl")
    public String cclPage(Model model) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = apiClient.get("/api/admin/ccl", Map.class);
        if (data != null) {
            model.addAttribute("ccls", data.get("ccls"));
            model.addAttribute("igdbDescriptors", data.get("igdbDescriptors"));
        }
        return "admin/ccl";
    }

    @PostMapping("/ccl/refresh")
    public String refreshCcl() {
        apiClient.post("/api/admin/ccl/refresh", null);
        return "redirect:/admin/ccl";
    }

    @PostMapping("/ccl/{cclId}/mappings")
    public String saveMappings(@PathVariable String cclId,
                               @RequestParam(required = false) Set<Long> igdbCategoryIds) {
        apiClient.post("/api/admin/ccl/{cclId}/mappings",
                Map.of("igdbCategoryIds", igdbCategoryIds != null ? igdbCategoryIds : Set.of()),
                cclId);
        return "redirect:/admin/ccl";
    }

    // ── Members ──────────────────────────────────────────────────────────────

    @GetMapping("/members")
    public String membersPage(Model model) {
        AdminMembersPageDto data = apiClient.get("/api/admin/members", AdminMembersPageDto.class);
        if (data != null) {
            // Convert string UUID keys to UUID so the template lookup works
            Map<UUID, Boolean> liveStatus = new LinkedHashMap<>();
            if (data.liveStatus() != null) {
                data.liveStatus().forEach((k, v) -> {
                    try { liveStatus.put(UUID.fromString(k), v); } catch (IllegalArgumentException ignored) {}
                });
            }
            model.addAttribute("members", data.members());
            model.addAttribute("liveStatus", liveStatus);
            model.addAttribute("isMockProfile", data.isMockProfile());
        }
        return "admin/members";
    }

    @GetMapping("/members/{id}/settings")
    public String memberSettings(@PathVariable UUID id) {
        MemberSummaryDto member = apiClient.get("/api/admin/members/{id}", MemberSummaryDto.class, id);
        if (member == null || member.twitchUsername() == null) {
            return "redirect:/admin/members";
        }
        return "redirect:/channels/" + member.twitchUsername();
    }

    @PostMapping("/members/{id}/bot/toggle")
    public String toggleMemberBot(@PathVariable UUID id) {
        apiClient.post("/api/admin/members/{id}/bot/toggle", null, id);
        return "redirect:/admin/members";
    }

    /**
     * Marque un compte régulier comme compte système (le bot pour les commandes
     * chat). Le compte cible doit déjà être logué via Twitch ; l'admin déclenche
     * juste le flip du flag. Un éventuel compte système placeholder vide est
     * démarqué côté API.
     */
    @PostMapping("/members/{id}/promote-to-system")
    public String promoteToSystem(@PathVariable UUID id) {
        apiClient.post("/api/admin/members/{id}/promote-to-system", null, id);
        return "redirect:/admin/members";
    }

    @PostMapping("/members/{id}/delete")
    public String deleteMember(@PathVariable UUID id) {
        apiClient.post("/api/admin/members/{id}/delete", null, id);
        return "redirect:/admin/members";
    }

    @PostMapping("/members/{id}/steam/unlink")
    public String unlinkMemberSteam(@PathVariable UUID id) {
        apiClient.post("/api/admin/members/{id}/steam/unlink", null, id);
        return "redirect:/admin/members";
    }

    @PostMapping("/members/{id}/twitch/unlink")
    public String unlinkMemberTwitch(@PathVariable UUID id) {
        apiClient.post("/api/admin/members/{id}/twitch/unlink", null, id);
        return "redirect:/admin/members";
    }

    @PostMapping("/members/{id}/migrate")
    public String migrateData(@PathVariable UUID id,
                              @RequestParam UUID targetId,
                              @RequestParam(defaultValue = "false") boolean migrateSettings,
                              @RequestParam(defaultValue = "false") boolean migrateGetters,
                              @RequestParam(defaultValue = "false") boolean migrateBindings) {
        apiClient.post("/api/admin/members/{id}/migrate",
                new MigrateRequest(targetId, migrateSettings, migrateGetters, migrateBindings), id);
        return "redirect:/admin/members";
    }

    // ── Whitelist ────────────────────────────────────────────────────────────

    @GetMapping("/whitelist")
    public String whitelistPage(Model model) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = apiClient.get("/api/admin/whitelist", Map.class);
        if (data != null) {
            model.addAttribute("entries", data.get("entries"));
            model.addAttribute("resolvedUsernames", data.get("resolvedUsernames"));
            model.addAttribute("whitelistEnabled", Boolean.TRUE.equals(data.get("whitelistEnabled")));
            model.addAttribute("inviteEnabled", Boolean.TRUE.equals(data.get("inviteEnabled")));
            model.addAttribute("globalMaxMembers", data.get("globalMaxMembers"));
            model.addAttribute("defaultMaxUses", data.get("defaultMaxUses"));
            model.addAttribute("defaultCanReinvite", Boolean.TRUE.equals(data.get("defaultCanReinvite")));
            model.addAttribute("globalCapReached", Boolean.TRUE.equals(data.get("globalCapReached")));
            model.addAttribute("invites", data.get("invites"));
        }
        return "admin/whitelist";
    }

    @PostMapping("/whitelist/toggle")
    public String toggleWhitelist() {
        apiClient.post("/api/admin/whitelist/toggle", null);
        return "redirect:/admin/whitelist";
    }

    @PostMapping("/whitelist/add")
    public String addWhitelist(@RequestParam String twitchId) {
        apiClient.post("/api/admin/whitelist/add", Map.of("twitchId", twitchId));
        return "redirect:/admin/whitelist";
    }

    @PostMapping("/whitelist/{id}/delete")
    public String deleteWhitelist(@PathVariable String id) {
        apiClient.post("/api/admin/whitelist/{id}/delete", null, id);
        return "redirect:/admin/whitelist";
    }

    @PostMapping("/whitelist/invite/toggle")
    public String toggleInvites() {
        apiClient.post("/api/admin/whitelist/invite/toggle", null);
        return "redirect:/admin/whitelist";
    }

    @PostMapping("/whitelist/invite/settings")
    public String updateInviteSettings(
            @RequestParam(required = false) Integer globalMaxMembers,
            @RequestParam(required = false) Integer defaultMaxUses,
            @RequestParam(defaultValue = "false") boolean defaultCanReinvite) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("globalMaxMembers", globalMaxMembers);
        body.put("defaultMaxUses", defaultMaxUses);
        body.put("defaultCanReinvite", defaultCanReinvite);
        apiClient.post("/api/admin/whitelist/invite/settings", body);
        return "redirect:/admin/whitelist";
    }

    @PostMapping("/whitelist/invite/{inviteId}/quota")
    public String updateWhitelistInviteQuota(@PathVariable UUID inviteId,
                                             @RequestParam(required = false) Integer maxUses,
                                             @RequestParam(required = false) Boolean canReinvite) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("maxUses", maxUses);
        body.put("canReinvite", canReinvite);
        apiClient.post("/api/admin/whitelist/invite/{inviteId}/quota", body, inviteId);
        return "redirect:/admin/whitelist";
    }

    // ── Steam Keys ───────────────────────────────────────────────────────────

    @GetMapping("/steam-keys")
    public String steamKeysPage(Model model) {
        SteamKeysPageDto data = apiClient.get("/api/admin/steam-keys", SteamKeysPageDto.class);
        if (data != null) {
            model.addAttribute("keyStatuses", data.keyStatuses());
            model.addAttribute("steamEnabled", data.steamEnabled());
        }
        return "admin/steam-keys";
    }

    @PostMapping("/steam-keys/add")
    public String addSteamKey(@RequestParam String apiKey) {
        apiClient.post("/api/admin/steam-keys/add", Map.of("apiKey", apiKey));
        return "redirect:/admin/steam-keys";
    }

    @PostMapping("/steam-keys/delete")
    public String deleteSteamKey(@RequestParam String apiKey) {
        apiClient.post("/api/admin/steam-keys/delete", Map.of("apiKey", apiKey));
        return "redirect:/admin/steam-keys";
    }

    @PostMapping("/steam-keys/refresh")
    public String refreshSteamKeys() {
        apiClient.post("/api/admin/steam-keys/refresh", null);
        return "redirect:/admin/steam-keys";
    }

    // ── Experiments ──────────────────────────────────────────────────────────

    @GetMapping("/experiments")
    public String experimentsPage(Model model) {
        List<?> experiments = apiClient.get("/api/admin/experiments",
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        model.addAttribute("experiments", experiments);
        return "admin/experiments";
    }

    @GetMapping("/experiments/{id}")
    public String experimentDetail(@PathVariable UUID id, Model model) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = apiClient.get("/api/admin/experiments/{id}", Map.class, id);
        if (data != null) {
            model.addAttribute("experiment", data.get("experiment"));
            model.addAttribute("eventKeys", data.get("eventKeys"));
            model.addAttribute("conversionStats", data.get("conversionStats"));
            model.addAttribute("npsStats", data.get("npsStats"));
            model.addAttribute("feedbackPage", data.get("feedbackPage"));
            model.addAttribute("hasManualRule", Boolean.TRUE.equals(data.get("hasManualRule")));
            model.addAttribute("participantPage", data.get("participantPage"));
            model.addAttribute("overrides", data.get("overrides"));
        }
        return "admin/experiment-detail";
    }

    @PostMapping("/experiments/{id}/activate")
    public String activateExperiment(@PathVariable UUID id) {
        apiClient.post("/api/admin/experiments/{id}/activate", null, id);
        return "redirect:/admin/experiments/" + id;
    }

    @PostMapping("/experiments/{id}/pause")
    public String pauseExperiment(@PathVariable UUID id) {
        apiClient.post("/api/admin/experiments/{id}/pause", null, id);
        return "redirect:/admin/experiments/" + id;
    }

    @PostMapping("/experiments/{id}/end")
    public String endExperiment(@PathVariable UUID id) {
        apiClient.post("/api/admin/experiments/{id}/end", null, id);
        return "redirect:/admin/experiments/" + id;
    }

    @PostMapping("/experiments/{id}/assign")
    public String assignUser(@PathVariable UUID id, @RequestParam String twitchUsername) {
        apiClient.post("/api/admin/experiments/{id}/assign",
                Map.of("twitchUsername", twitchUsername), id);
        return "redirect:/admin/experiments/" + id;
    }

    @PostMapping("/experiments/{id}/variants/weights")
    public String updateWeights(@PathVariable UUID id, @RequestParam Map<String, String> params) {
        // params contains "weight_<variantId>" keys — convert to UUID→Integer map
        Map<String, Integer> weights = new java.util.LinkedHashMap<>();
        params.forEach((k, v) -> {
            if (k.startsWith("weight_")) {
                try {
                    weights.put(k.substring(7), Integer.parseInt(v.trim()));
                } catch (NumberFormatException ignored) {}
            }
        });
        apiClient.post("/api/admin/experiments/{id}/variants/weights", weights, id);
        return "redirect:/admin/experiments/" + id;
    }

    @PostMapping("/experiments/{id}/rules")
    public String addRule(@PathVariable UUID id,
                          @RequestParam String ruleType,
                          @RequestParam(defaultValue = "0") int priority,
                          @RequestParam(required = false) Integer percentage,
                          @RequestParam(required = false) String attributeKey,
                          @RequestParam(required = false) String attributeOperator,
                          @RequestParam(required = false) String attributeValue) {
        apiClient.post("/api/admin/experiments/{id}/rules",
                new AddRuleRequest(ruleType, priority, percentage, attributeKey, attributeOperator, attributeValue), id);
        return "redirect:/admin/experiments/" + id;
    }

    @PostMapping("/experiments/{id}/rules/{ruleId}/delete")
    public String deleteRule(@PathVariable UUID id, @PathVariable UUID ruleId) {
        apiClient.post("/api/admin/experiments/{id}/rules/{ruleId}/delete", null, id, ruleId);
        return "redirect:/admin/experiments/" + id;
    }

    @PostMapping("/experiments/{id}/overrides")
    public String addOverride(@PathVariable UUID id,
                              @RequestParam String overrideType,
                              @RequestParam String action,
                              @RequestParam(defaultValue = "0") int priority,
                              @RequestParam(required = false) String twitchUsername,
                              @RequestParam(required = false) String attributeKey,
                              @RequestParam(required = false) String attributeOp,
                              @RequestParam(required = false) String attributeVal,
                              @RequestParam(required = false) UUID targetVariantId) {
        apiClient.post("/api/admin/experiments/{id}/overrides",
                new AddOverrideRequest(overrideType, action, priority, twitchUsername,
                        attributeKey, attributeOp, attributeVal, targetVariantId), id);
        return "redirect:/admin/experiments/" + id;
    }

    @PostMapping("/experiments/{id}/overrides/{overrideId}/delete")
    public String deleteOverride(@PathVariable UUID id, @PathVariable UUID overrideId) {
        apiClient.post("/api/admin/experiments/{id}/overrides/{overrideId}/delete", null, id, overrideId);
        return "redirect:/admin/experiments/" + id;
    }

    @PostMapping("/experiments/{id}/reassign")
    public String reassign(@PathVariable UUID id) {
        apiClient.post("/api/admin/experiments/{id}/reassign", null, id);
        return "redirect:/admin/experiments/" + id;
    }

    // ── Request bodies ────────────────────────────────────────────────────────

    record MigrateRequest(UUID targetId, boolean migrateSettings, boolean migrateGetters, boolean migrateBindings) {}

    record AddRuleRequest(String ruleType, int priority, Integer percentage,
                          String attributeKey, String attributeOperator, String attributeValue) {}

    record AddOverrideRequest(String overrideType, String action, int priority,
                              String twitchUsername, String attributeKey,
                              String attributeOp, String attributeVal, UUID targetVariantId) {}

    // ── Typed response DTOs ───────────────────────────────────────────────────

    enum MemberStatus { ACTIVE, INACTIVE, PENDING_DELETION }

    record MemberDto(UUID id, String twitchId, String twitchUsername, String steamId,
                     MemberStatus status, boolean systemAccount, boolean botEnabled, Instant createdAt) {}

    record AdminMembersPageDto(List<MemberDto> members, Map<String, Boolean> liveStatus, boolean isMockProfile) {}

    record KeyStatusDto(String masked, String owner, boolean blocked, long blockedForSeconds) {}

    record SteamKeysPageDto(Map<String, KeyStatusDto> keyStatuses, boolean steamEnabled) {}

    record MemberSummaryDto(UUID id, String twitchUsername) {}
}
