package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.common.dto.admin.AdminInvitePageData;
import fr.enimaloc.catapult.common.dto.admin.AdminInviteQuotaRequest;
import fr.enimaloc.catapult.common.dto.admin.InviteSettingsRequest;
import fr.enimaloc.catapult.service.access.AdminInviteService;
import fr.enimaloc.catapult.service.access.InviteService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import fr.enimaloc.catapult.api.ApiPaths;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.ADMIN + "/invites")
@RequiredArgsConstructor
public class ApiAdminInviteController {

    private final InviteService inviteService;
    private final AdminInviteService adminInvites;

    @GetMapping
    public AdminInvitePageData page() {
        return adminInvites.invitePage();
    }

    @PutMapping("/settings")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateSettings(@RequestBody InviteSettingsRequest body) {
        inviteService.setGlobalMaxMembers(body.globalMaxMembers());
        inviteService.setDefaultMaxUses(body.defaultMaxUses());
        inviteService.setDefaultCanReinvite(body.defaultCanReinvite());
    }

    @PutMapping("/{inviteId}/quota")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateQuota(@PathVariable UUID inviteId, @RequestBody AdminInviteQuotaRequest body) {
        inviteService.updateInviteQuota(inviteId, body.maxUses(), body.canReinvite());
    }

    @PostMapping("/grants/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void grantInvite(@PathVariable UUID memberId) {
        adminInvites.grant(memberId);
    }
}
