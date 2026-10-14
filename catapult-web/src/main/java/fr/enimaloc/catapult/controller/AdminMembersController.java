package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.MigrateRequest;
import fr.enimaloc.catapult.service.ApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * The admin members tab's actions, forwarded to catapult-api with the session's JWT (see
 * {@link ChannelActionsController} for why the browser can't call it directly). Refused here
 * too to anyone but an admin, as the page itself is.
 */
@RestController
@RequestMapping("/admin/members/{id}")
@RequiredArgsConstructor
public class AdminMembersController {
    private final ApiService apiService;
    private final ModelFiller filler;

    @PostMapping("/bot/toggle")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void toggleBot(@PathVariable UUID id) {
        requireAdmin();
        apiService.adminToggleMemberBot(id);
    }

    @PostMapping("/steam/unlink")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlinkSteam(@PathVariable UUID id) {
        requireAdmin();
        apiService.adminUnlinkMemberSteam(id);
    }

    @PostMapping("/twitch/unlink")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlinkTwitch(@PathVariable UUID id) {
        requireAdmin();
        apiService.adminUnlinkMemberTwitch(id);
    }

    @PostMapping("/promote-to-system")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void promoteToSystem(@PathVariable UUID id) {
        requireAdmin();
        apiService.adminPromoteMemberToSystem(id);
    }

    @PostMapping("/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        requireAdmin();
        apiService.adminDeleteMember(id);
    }

    @PostMapping("/migrate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void migrate(@PathVariable UUID id, @RequestBody MigrateRequest body) {
        requireAdmin();
        if (body.targetId() == null || body.targetId().equals(id)
                || !(body.migrateSettings() || body.migrateGetters() || body.migrateBindings())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        apiService.adminMigrateMember(id, body.targetId(), body.migrateSettings(), body.migrateGetters(), body.migrateBindings());
    }

    private void requireAdmin() {
        int refused = AdminAccess.refusal(filler);
        if (refused != 0) {
            throw new ResponseStatusException(HttpStatus.valueOf(refused));
        }
    }
}
