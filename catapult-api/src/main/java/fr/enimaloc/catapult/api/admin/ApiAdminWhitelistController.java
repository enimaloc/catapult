package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.common.dto.admin.AddRequest;
import fr.enimaloc.catapult.common.dto.admin.AdminInviteQuotaRequest;
import fr.enimaloc.catapult.common.dto.admin.InviteSettingsRequest;
import fr.enimaloc.catapult.service.access.AdminInviteService;
import fr.enimaloc.catapult.service.access.AdminInviteService.WhitelistPageData;
import fr.enimaloc.catapult.service.access.InviteService;
import fr.enimaloc.catapult.service.access.WhitelistService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import fr.enimaloc.catapult.api.ApiPaths;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.ADMIN + "/whitelist")
@RequiredArgsConstructor
public class ApiAdminWhitelistController {

    private final WhitelistService whitelistService;
    private final InviteService inviteService;
    private final AdminInviteService adminInvites;

    @GetMapping
    public WhitelistPageData page() {
        return adminInvites.whitelistPage();
    }

    @PostMapping("/toggle")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void toggle() {
        whitelistService.toggle();
    }

    @PostMapping("/entries")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void add(@RequestBody AddRequest body) {
        whitelistService.add(body.twitchId().trim());
    }

    @DeleteMapping("/entries/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        whitelistService.remove(id);
    }

    @PostMapping("/invite/toggle")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void toggleInvites() {
        inviteService.setInviteEnabled(!inviteService.isInviteEnabled());
    }

    @PutMapping("/invite/settings")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateInviteSettings(@RequestBody InviteSettingsRequest body) {
        inviteService.setGlobalMaxMembers(body.globalMaxMembers());
        inviteService.setDefaultMaxUses(body.defaultMaxUses());
        inviteService.setDefaultCanReinvite(body.defaultCanReinvite());
    }

    @PutMapping("/invite/{inviteId}/quota")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateInviteQuota(@PathVariable UUID inviteId, @RequestBody AdminInviteQuotaRequest body) {
        inviteService.updateInviteQuota(inviteId, body.maxUses(), body.canReinvite());
    }
}
