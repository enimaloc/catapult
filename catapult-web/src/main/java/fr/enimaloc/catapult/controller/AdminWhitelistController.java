package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.service.ApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The admin Whitelist tab's actions (the two switches, add and delete an entry, the invitation
 * settings and an invite's quota), forwarded to catapult-api with the session's JWT and answered
 * with its status (204; 404 for an unknown invite; 502 when unreachable). A Twitch id to add is made of
 * digits; deleting takes any listed id, in the body.
 */
@RestController
@RequestMapping("/admin/whitelist")
@RequiredArgsConstructor
public class AdminWhitelistController {
    private final ApiService apiService;
    private final ModelFiller filler;

    public record AddBody(String twitchId) {}
    public record SettingsBody(Integer globalMaxMembers, Integer defaultMaxUses, Boolean defaultCanReinvite) {}
    public record QuotaBody(Integer maxUses, Boolean canReinvite) {}

    @PostMapping("/toggle")
    public ResponseEntity<Void> toggle() {
        return AdminAccess.relay(filler, apiService::adminToggleWhitelist);
    }

    @PostMapping("/add")
    public ResponseEntity<Void> add(@RequestBody AddBody body) {
        String id = body.twitchId() == null ? "" : body.twitchId().trim();
        if (!isTwitchId(id)) return refuse();
        return AdminAccess.relay(filler, () -> apiService.adminAddWhitelist(id));
    }

    /** Any listed id, legacy ones included (catapult-api never validated them): in the body, safe from the firewall. */
    @PostMapping("/delete")
    public ResponseEntity<Void> delete(@RequestBody AddBody body) {
        if (body.twitchId() == null || body.twitchId().isBlank()) return refuse();
        return AdminAccess.relay(filler, () -> apiService.adminDeleteWhitelist(body.twitchId()));
    }

    @PostMapping("/invite/toggle")
    public ResponseEntity<Void> toggleInvites() {
        return AdminAccess.relay(filler, apiService::adminToggleInvites);
    }

    @PostMapping("/invite/settings")
    public ResponseEntity<Void> saveSettings(@RequestBody SettingsBody body) {
        if (negative(body.defaultMaxUses()) || (body.globalMaxMembers() != null && body.globalMaxMembers() < 1)) return refuse();
        return AdminAccess.relay(filler, () -> apiService.adminSaveInviteSettings(body.globalMaxMembers(), body.defaultMaxUses(),
                Boolean.TRUE.equals(body.defaultCanReinvite())));
    }

    @PostMapping("/invite/{id}/quota")
    public ResponseEntity<Void> saveQuota(@PathVariable UUID id, @RequestBody QuotaBody body) {
        if (negative(body.maxUses())) return refuse();
        return AdminAccess.relay(filler, () -> apiService.adminSaveInviteQuota(id, body.maxUses(), body.canReinvite()));
    }

    private static boolean isTwitchId(String id) {
        return id != null && id.matches("\\d{1,20}");
    }

    private static boolean negative(Integer value) {
        return value != null && value < 0;
    }

    /** A malformed value: 400, but only for an admin, and without calling catapult-api. */
    private ResponseEntity<Void> refuse() {
        AdminAccess.require(filler);
        return ResponseEntity.badRequest().build();
    }
}
