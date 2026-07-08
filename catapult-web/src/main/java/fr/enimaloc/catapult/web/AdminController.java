package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.security.CatapultWebUser;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
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

    // ── TW ───────────────────────────────────────────────────────────────────

    @GetMapping("/tw")
    public String twPage(Model model) {
        List<?> definitions = apiClient.get("/api/admin/tw",
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        model.addAttribute("definitions", definitions != null ? definitions : List.of());
        return "admin/tw";
    }

    @PostMapping("/tw/add")
    public String addTw(@RequestParam String id,
                        @RequestParam String label,
                        @RequestParam(required = false) String description,
                        @RequestParam(defaultValue = "0") int sortOrder) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("label", label);
        body.put("description", description);
        body.put("sortOrder", sortOrder);
        apiClient.post("/api/admin/tw", body);
        return "redirect:/admin/tw";
    }

    @PostMapping("/tw/rebuild")
    public String rebuildTw() {
        apiClient.post("/api/admin/tw/rebuild", null);
        return "redirect:/admin/tw";
    }

    // ── Members ──────────────────────────────────────────────────────────────

    @GetMapping("/members")
    public String membersPage(Model model,
                              @AuthenticationPrincipal CatapultWebUser principal,
                              @RequestParam(required = false) String error) {
        populateMembersModel(model, principal, error);
        return "admin/members";
    }

    private void populateMembersModel(Model model, CatapultWebUser principal, String error) {
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
        // Drives the th:disabled guard on the "Impersonner" button — clicking
        // on yourself is rejected upstream with 403 "Cannot impersonate yourself"
        // and previously produced silent ?error=impersonateFailed redirects.
        model.addAttribute("currentUserTwitchId", principal == null ? null : principal.getTwitchId());
        model.addAttribute("impersonateError", error);
    }

    /**
     * Row actions re-render only the {@code membersBody} fragment over WebSocket
     * (ws:* dialect sends the HX-Request header); JS-less POSTs keep the PRG redirect.
     */
    private String membersResult(String hxRequest, CatapultWebUser principal, Model model) {
        if (hxRequest != null) {
            populateMembersModel(model, principal, null);
            return "admin/members :: membersBody";
        }
        return "redirect:/admin/members";
    }

    @GetMapping("/members/{id}/settings")
    public String memberSettings(@PathVariable UUID id) {
        MemberSummaryDto member = apiClient.get("/api/admin/members/{id}", MemberSummaryDto.class, id);
        if (member == null || member.twitchUsername() == null) {
            return "redirect:/admin/members";
        }
        return "redirect:/channels/" + member.twitchUsername();
    }

    @GetMapping("/members/{id}/targeting")
    public String memberTargeting(@PathVariable UUID id, Model model) {
        if (!populateTargetingModel(id, model)) {
            return "redirect:/admin/members";
        }
        return "admin/member-targeting";
    }

    /** Returns false if the member cannot be resolved (caller should redirect). */
    private boolean populateTargetingModel(UUID id, Model model) {
        MemberSummaryDto member = apiClient.get("/api/admin/members/{id}", MemberSummaryDto.class, id);
        if (member == null) {
            return false;
        }
        MemberTargetingDto targeting = apiClient.get("/api/admin/members/{id}/targeting", MemberTargetingDto.class, id);
        List<GroupOptionDto> allGroups = apiClient.get("/api/admin/groups",
                new ParameterizedTypeReference<List<GroupOptionDto>>() {});
        model.addAttribute("member", member);
        model.addAttribute("memberId", id);
        model.addAttribute("flags", targeting != null && targeting.flags() != null ? targeting.flags() : List.of());
        model.addAttribute("memberGroupKeys", targeting != null && targeting.groupKeys() != null ? targeting.groupKeys() : List.of());
        model.addAttribute("allGroups", allGroups != null ? allGroups : List.of());
        return true;
    }

    /** Re-renders only the {@code targetingBody} fragment for ws:* actions; PRG redirect otherwise. */
    private String targetingResult(UUID id, String hxRequest, Model model) {
        if (hxRequest != null && populateTargetingModel(id, model)) {
            return "admin/member-targeting :: targetingBody";
        }
        return "redirect:/admin/members/" + id + "/targeting";
    }

    @PostMapping("/members/{id}/flags")
    public String setMemberFlag(@PathVariable UUID id,
                                @RequestParam String key,
                                @RequestParam(required = false) String value,
                                @RequestHeader(value = "HX-Request", required = false) String hx, Model model) {
        apiClient.post("/api/admin/members/{id}/flags", new SetFlagBody(key, value), id);
        return targetingResult(id, hx, model);
    }

    @PostMapping("/members/{id}/flags/{key}/delete")
    public String deleteMemberFlag(@PathVariable UUID id, @PathVariable String key,
                                   @RequestHeader(value = "HX-Request", required = false) String hx, Model model) {
        apiClient.post("/api/admin/members/{id}/flags/{key}/delete", null, id, key);
        return targetingResult(id, hx, model);
    }

    @PostMapping("/members/{id}/groups")
    public String addMemberToGroup(@PathVariable UUID id, @RequestParam String groupKey,
                                   @RequestHeader(value = "HX-Request", required = false) String hx, Model model) {
        apiClient.post("/api/admin/members/{id}/groups", new AddToGroupBody(groupKey), id);
        return targetingResult(id, hx, model);
    }

    @PostMapping("/members/{id}/groups/{groupId}/delete")
    public String removeMemberFromGroup(@PathVariable UUID id, @PathVariable UUID groupId,
                                        @RequestHeader(value = "HX-Request", required = false) String hx, Model model) {
        apiClient.post("/api/admin/members/{id}/groups/{groupId}/delete", null, id, groupId);
        return targetingResult(id, hx, model);
    }

    @PostMapping("/members/{id}/bot/toggle")
    public String toggleMemberBot(@PathVariable UUID id,
                                  @RequestHeader(value = "HX-Request", required = false) String hx,
                                  @AuthenticationPrincipal CatapultWebUser principal, Model model) {
        apiClient.post("/api/admin/members/{id}/bot/toggle", null, id);
        return membersResult(hx, principal, model);
    }

    /**
     * Marque un compte régulier comme compte système (le bot pour les commandes
     * chat). Le compte cible doit déjà être logué via Twitch ; l'admin déclenche
     * juste le flip du flag. Un éventuel compte système placeholder vide est
     * démarqué côté API.
     */
    @PostMapping("/members/{id}/promote-to-system")
    public String promoteToSystem(@PathVariable UUID id,
                                  @RequestHeader(value = "HX-Request", required = false) String hx,
                                  @AuthenticationPrincipal CatapultWebUser principal, Model model) {
        apiClient.post("/api/admin/members/{id}/promote-to-system", null, id);
        return membersResult(hx, principal, model);
    }

    @PostMapping("/members/{id}/delete")
    public String deleteMember(@PathVariable UUID id,
                               @RequestHeader(value = "HX-Request", required = false) String hx,
                               @AuthenticationPrincipal CatapultWebUser principal, Model model) {
        apiClient.post("/api/admin/members/{id}/delete", null, id);
        return membersResult(hx, principal, model);
    }

    @PostMapping("/members/{id}/steam/unlink")
    public String unlinkMemberSteam(@PathVariable UUID id,
                                    @RequestHeader(value = "HX-Request", required = false) String hx,
                                    @AuthenticationPrincipal CatapultWebUser principal, Model model) {
        apiClient.post("/api/admin/members/{id}/steam/unlink", null, id);
        return membersResult(hx, principal, model);
    }

    @PostMapping("/members/{id}/twitch/unlink")
    public String unlinkMemberTwitch(@PathVariable UUID id,
                                     @RequestHeader(value = "HX-Request", required = false) String hx,
                                     @AuthenticationPrincipal CatapultWebUser principal, Model model) {
        apiClient.post("/api/admin/members/{id}/twitch/unlink", null, id);
        return membersResult(hx, principal, model);
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
        populateWhitelistModel(model);
        return "admin/whitelist";
    }

    /**
     * Populates the whitelist model. Shared by the GET page and the POST actions
     * that re-render the {@code body} fragment over WebSocket (ws:* dialect).
     */
    private void populateWhitelistModel(Model model) {
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
    }

    /**
     * For ws:* form actions the client sends the {@code HX-Request} header; we
     * then re-render only the {@code body} fragment so ws-actions can swap it in
     * place. Plain (JS-less) POSTs still get the PRG redirect.
     */
    private String whitelistResult(String hxRequest, Model model) {
        if (hxRequest != null) {
            populateWhitelistModel(model);
            return "admin/whitelist :: body";
        }
        return "redirect:/admin/whitelist";
    }

    @PostMapping("/whitelist/toggle")
    public String toggleWhitelist(@RequestHeader(value = "HX-Request", required = false) String hx, Model model) {
        apiClient.post("/api/admin/whitelist/toggle", null);
        return whitelistResult(hx, model);
    }

    @PostMapping("/whitelist/add")
    public String addWhitelist(@RequestParam String twitchId,
                               @RequestHeader(value = "HX-Request", required = false) String hx, Model model) {
        apiClient.post("/api/admin/whitelist/add", Map.of("twitchId", twitchId));
        return whitelistResult(hx, model);
    }

    @PostMapping("/whitelist/{id}/delete")
    public String deleteWhitelist(@PathVariable String id,
                                  @RequestHeader(value = "HX-Request", required = false) String hx, Model model) {
        apiClient.post("/api/admin/whitelist/{id}/delete", null, id);
        return whitelistResult(hx, model);
    }

    @PostMapping("/whitelist/invite/toggle")
    public String toggleInvites(@RequestHeader(value = "HX-Request", required = false) String hx, Model model) {
        apiClient.post("/api/admin/whitelist/invite/toggle", null);
        return whitelistResult(hx, model);
    }

    @PostMapping("/whitelist/invite/settings")
    public String updateInviteSettings(
            @RequestParam(required = false) Integer globalMaxMembers,
            @RequestParam(required = false) Integer defaultMaxUses,
            @RequestParam(defaultValue = "false") boolean defaultCanReinvite,
            @RequestHeader(value = "HX-Request", required = false) String hx, Model model) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("globalMaxMembers", globalMaxMembers);
        body.put("defaultMaxUses", defaultMaxUses);
        body.put("defaultCanReinvite", defaultCanReinvite);
        apiClient.post("/api/admin/whitelist/invite/settings", body);
        return whitelistResult(hx, model);
    }

    @PostMapping("/whitelist/invite/{inviteId}/quota")
    public String updateWhitelistInviteQuota(@PathVariable UUID inviteId,
                                             @RequestParam(required = false) Integer maxUses,
                                             @RequestParam(required = false) Boolean canReinvite,
                                             @RequestHeader(value = "HX-Request", required = false) String hx, Model model) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("maxUses", maxUses);
        body.put("canReinvite", canReinvite);
        apiClient.post("/api/admin/whitelist/invite/{inviteId}/quota", body, inviteId);
        return whitelistResult(hx, model);
    }

    // ── Steam Keys ───────────────────────────────────────────────────────────

    @GetMapping("/steam-keys")
    public String steamKeysPage(Model model) {
        SteamKeysPageDto data = apiClient.get("/api/admin/steam-keys", SteamKeysPageDto.class);
        if (data != null) {
            model.addAttribute("keys", data.keys());
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
    public String deleteSteamKey(@RequestParam String keyId) {
        apiClient.post("/api/admin/steam-keys/delete", Map.of("keyId", keyId));
        return "redirect:/admin/steam-keys";
    }

    @PostMapping("/steam-keys/refresh")
    public String refreshSteamKeys() {
        apiClient.post("/api/admin/steam-keys/refresh", null);
        return "redirect:/admin/steam-keys";
    }

    // ── Minecraft Service Accounts ───────────────────────────────────────────

    @GetMapping("/minecraft-accounts")
    public String minecraftAccountsPage(Model model) {
        List<Map<String, Object>> accounts = apiClient.adminMinecraftAccounts();
        model.addAttribute("accounts", accounts == null ? List.of() : accounts);
        return "admin/minecraft-accounts";
    }

    @PostMapping("/minecraft-accounts/{id}/toggle")
    public String toggleMinecraftAccount(@PathVariable UUID id, @RequestParam boolean enabled) {
        apiClient.adminMinecraftPatch(id, Map.of("enabled", enabled));
        return "redirect:/admin/minecraft-accounts";
    }

    @PostMapping("/minecraft-accounts/{id}/reset-limit")
    public String resetMinecraftAccountLimit(@PathVariable UUID id) {
        apiClient.adminMinecraftPatch(id, Map.of("friendLimitReached", false));
        return "redirect:/admin/minecraft-accounts";
    }

    @PostMapping("/minecraft-accounts/{id}/delete")
    public String deleteMinecraftAccount(@PathVariable UUID id, org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes) {
        if (apiClient.adminMinecraftDelete(id) == 409) {
            redirectAttributes.addFlashAttribute("minecraftAccountsError", "links");
        }
        return "redirect:/admin/minecraft-accounts";
    }

    // ── DTDD Keys ────────────────────────────────────────────────────────────

    @GetMapping("/dtdd-keys")
    public String dtddKeysPage(Model model) {
        DtddKeysPageDto data = apiClient.get("/api/admin/dtdd-keys", DtddKeysPageDto.class);
        if (data != null) {
            model.addAttribute("keys", data.keys());
            model.addAttribute("dtddEnabled", data.dtddEnabled());
        }
        return "admin/dtdd-keys";
    }

    @PostMapping("/dtdd-keys/add")
    public String addDtddKey(@RequestParam String apiKey) {
        apiClient.post("/api/admin/dtdd-keys/add", Map.of("apiKey", apiKey));
        return "redirect:/admin/dtdd-keys";
    }

    @PostMapping("/dtdd-keys/delete")
    public String deleteDtddKey(@RequestParam String keyId) {
        apiClient.post("/api/admin/dtdd-keys/delete", Map.of("keyId", keyId));
        return "redirect:/admin/dtdd-keys";
    }

    @PostMapping("/dtdd-keys/refresh")
    public String refreshDtddKeys() {
        apiClient.post("/api/admin/dtdd-keys/refresh", null);
        return "redirect:/admin/dtdd-keys";
    }

    // ── DTDD Mapping ─────────────────────────────────────────────────────────

    @GetMapping("/dtdd-mapping")
    public String dtddMappingPage(@RequestParam(defaultValue = "PENDING") String status, Model model) {
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> proposals = apiClient.get(
            "/api/admin/dtdd-mapping/proposals?status={s}",
            new org.springframework.core.ParameterizedTypeReference<java.util.List<java.util.Map<String, Object>>>() {},
            status);
        model.addAttribute("proposals", proposals != null ? proposals : java.util.List.of());
        model.addAttribute("currentStatus", status);
        return "admin/dtdd-mapping";
    }

    @PostMapping("/dtdd-mapping/proposals/{id}/approve")
    public String approveDtddProposal(@PathVariable UUID id) {
        apiClient.post("/api/admin/dtdd-mapping/proposals/" + id + "/approve", null);
        return "redirect:/admin/dtdd-mapping";
    }

    @PostMapping("/dtdd-mapping/proposals/{id}/reject")
    public String rejectDtddProposal(@PathVariable UUID id, @RequestParam(required = false) String reason) {
        apiClient.post("/api/admin/dtdd-mapping/proposals/" + id + "/reject",
            reason != null ? Map.of("reason", reason) : Map.of());
        return "redirect:/admin/dtdd-mapping";
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
        populateExperimentDetailModel(id, model);
        return "admin/experiment-detail";
    }

    private void populateExperimentDetailModel(UUID id, Model model) {
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
        List<GroupOptionDto> allGroups = apiClient.get("/api/admin/groups",
                new ParameterizedTypeReference<List<GroupOptionDto>>() {});
        List<Map<String, Object>> allExperiments = apiClient.get("/api/admin/experiments",
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        model.addAttribute("allGroups", allGroups != null ? allGroups : List.of());
        model.addAttribute("allExperiments", allExperiments != null ? allExperiments : List.of());
    }

    /**
     * Detail-page actions re-render only the {@code detailBody} fragment over
     * WebSocket (ws:* dialect); JS-less POSTs keep the PRG redirect. The page's
     * inline scripts live outside the fragment so their global helpers survive.
     */
    private String experimentDetailResult(UUID id, String hxRequest, Model model) {
        if (hxRequest != null) {
            populateExperimentDetailModel(id, model);
            return "admin/experiment-detail :: detailBody";
        }
        return "redirect:/admin/experiments/" + id;
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
    public String assignUser(@PathVariable UUID id, @RequestParam String twitchUsername,
                             @RequestHeader(value = "HX-Request", required = false) String hx, Model model) {
        apiClient.post("/api/admin/experiments/{id}/assign",
                Map.of("twitchUsername", twitchUsername), id);
        return experimentDetailResult(id, hx, model);
    }

    @PostMapping("/experiments/{id}/variants/weights")
    public String updateWeights(@PathVariable UUID id, @RequestParam Map<String, String> params,
                                @RequestHeader(value = "HX-Request", required = false) String hx, Model model) {
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
        return experimentDetailResult(id, hx, model);
    }

    @PostMapping("/experiments/{id}/rules")
    public String addRule(@PathVariable UUID id,
                          @RequestParam String ruleType,
                          @RequestParam(defaultValue = "0") int priority,
                          @RequestParam(required = false) Integer percentage,
                          @RequestParam(required = false) String attributeKey,
                          @RequestParam(required = false) String attributeOperator,
                          @RequestParam(required = false) String attributeValue,
                          @RequestHeader(value = "HX-Request", required = false) String hx, Model model) {
        apiClient.post("/api/admin/experiments/{id}/rules",
                new AddRuleRequest(ruleType, priority, percentage, attributeKey, attributeOperator, attributeValue), id);
        return experimentDetailResult(id, hx, model);
    }

    @PostMapping("/experiments/{id}/rules/{ruleId}/delete")
    public String deleteRule(@PathVariable UUID id, @PathVariable UUID ruleId,
                             @RequestHeader(value = "HX-Request", required = false) String hx, Model model) {
        apiClient.post("/api/admin/experiments/{id}/rules/{ruleId}/delete", null, id, ruleId);
        return experimentDetailResult(id, hx, model);
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
                              @RequestParam(required = false) UUID targetVariantId,
                              @RequestHeader(value = "HX-Request", required = false) String hx, Model model) {
        apiClient.post("/api/admin/experiments/{id}/overrides",
                new AddOverrideRequest(overrideType, action, priority, twitchUsername,
                        attributeKey, attributeOp, attributeVal, targetVariantId), id);
        return experimentDetailResult(id, hx, model);
    }

    @PostMapping("/experiments/{id}/overrides/{overrideId}/delete")
    public String deleteOverride(@PathVariable UUID id, @PathVariable UUID overrideId,
                                 @RequestHeader(value = "HX-Request", required = false) String hx, Model model) {
        apiClient.post("/api/admin/experiments/{id}/overrides/{overrideId}/delete", null, id, overrideId);
        return experimentDetailResult(id, hx, model);
    }

    @PostMapping("/experiments/{id}/reassign")
    public String reassign(@PathVariable UUID id,
                           @RequestHeader(value = "HX-Request", required = false) String hx, Model model) {
        apiClient.post("/api/admin/experiments/{id}/reassign", null, id);
        return experimentDetailResult(id, hx, model);
    }

    // ── Request bodies ────────────────────────────────────────────────────────

    record SetFlagBody(String key, String value) {}

    record AddToGroupBody(String groupKey) {}

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

    record KeyStatusDto(String id, String masked, String owner, boolean blocked, long blockedForSeconds) {}

    record SteamKeysPageDto(java.util.List<KeyStatusDto> keys, boolean steamEnabled) {}

    record DtddKeysPageDto(java.util.List<KeyStatusDto> keys, boolean dtddEnabled) {}

    record MemberSummaryDto(UUID id, String twitchUsername) {}

    record FlagViewDto(String key, String value) {}

    record MemberTargetingDto(List<FlagViewDto> flags, List<String> groupKeys) {}

    record GroupOptionDto(UUID id, String key, String name, String description, int memberCount) {}
}
