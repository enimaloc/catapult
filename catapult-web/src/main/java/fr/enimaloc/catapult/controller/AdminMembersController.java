package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.MigrateRequest;
import fr.enimaloc.catapult.service.ApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.util.function.IntSupplier;

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

    public record FlagBody(String key, String value) {}
    public record FlagKeyBody(String key) {}
    public record GroupBody(String groupKey) {}

    /** Targeting actions answer catapult-api's status (204; 404; 502 when unreachable), not a void. */
    @PostMapping("/flags")
    public ResponseEntity<Void> setFlag(@PathVariable UUID id, @RequestBody FlagBody body) {
        return relayUnless(isBlank(body.key()), () -> apiService.adminSetMemberFlag(id, body.key(), body.value()));
    }

    /** The key in the body: a flag key can hold a "/" or a "%", which the container refuses in a path. */
    @PostMapping("/flags/delete")
    public ResponseEntity<Void> deleteFlag(@PathVariable UUID id, @RequestBody FlagKeyBody body) {
        return relayUnless(isBlank(body.key()), () -> apiService.adminDeleteMemberFlag(id, body.key()));
    }

    @PostMapping("/groups")
    public ResponseEntity<Void> addToGroup(@PathVariable UUID id, @RequestBody GroupBody body) {
        return relayUnless(isBlank(body.groupKey()), () -> apiService.adminAddMemberToGroup(id, body.groupKey()));
    }

    @PostMapping("/groups/{groupId}/delete")
    public ResponseEntity<Void> removeFromGroup(@PathVariable UUID id, @PathVariable UUID groupId) {
        return relayUnless(false, () -> apiService.adminRemoveMemberFromGroup(id, groupId));
    }

    /** Admins only; a missing key is refused here (400) rather than sent on. */
    private ResponseEntity<Void> relayUnless(boolean invalid, IntSupplier action) {
        requireAdmin();
        return invalid ? ResponseEntity.badRequest().build() : ResponseEntity.status(action.getAsInt()).build();
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private void requireAdmin() {
        AdminAccess.require(filler);
    }
}
