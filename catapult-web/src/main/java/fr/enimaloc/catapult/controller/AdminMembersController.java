package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.MigrateRequest;
import fr.enimaloc.catapult.common.dto.admin.DebugEnableRequest;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.debug.DebugMembers;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
    private final DebugMembers debugMembers;

    @PostMapping("/bot/toggle")
    public ResponseEntity<Void> toggleBot(@PathVariable UUID id) {
        requireAdmin();
        return ApiStatus.answer(apiService.adminToggleMemberBot(id));
    }

    @PostMapping("/steam/unlink")
    public ResponseEntity<Void> unlinkSteam(@PathVariable UUID id) {
        requireAdmin();
        return ApiStatus.answer(apiService.adminUnlinkMemberSteam(id));
    }

    @PostMapping("/twitch/unlink")
    public ResponseEntity<Void> unlinkTwitch(@PathVariable UUID id) {
        requireAdmin();
        return ApiStatus.answer(apiService.adminUnlinkMemberTwitch(id));
    }

    @PostMapping("/promote-to-system")
    public ResponseEntity<Void> promoteToSystem(@PathVariable UUID id) {
        requireAdmin();
        return ApiStatus.answer(apiService.adminPromoteMemberToSystem(id));
    }

    @PostMapping("/delete")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        requireAdmin();
        return ApiStatus.answer(apiService.adminDeleteMember(id));
    }

    @PostMapping("/migrate")
    public ResponseEntity<Void> migrate(@PathVariable UUID id, @RequestBody MigrateRequest body) {
        requireAdmin();
        if (body.targetId() == null || body.targetId().equals(id)
                || !(body.migrateSettings() || body.migrateGetters() || body.migrateBindings())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return ApiStatus.answer(apiService.adminMigrateMember(id, body.targetId(), body.migrateSettings(), body.migrateGetters(), body.migrateBindings()));
    }

    /** The member's debug mode; {@code revealSecrets} (default false) unmasks the secrets in what is recorded. */
    @PostMapping("/debug/enable")
    public ResponseEntity<Void> enableDebug(@PathVariable UUID id, @RequestBody DebugEnableRequest body) {
        return debugAction(id, () -> apiService.enableMemberDebug(id, body.revealSecrets()));
    }

    @PostMapping("/debug/disable")
    public ResponseEntity<Void> disableDebug(@PathVariable UUID id) {
        return debugAction(id, () -> apiService.disableMemberDebug(id));
    }

    @PostMapping("/debug/clear")
    public ResponseEntity<Void> clearDebug(@PathVariable UUID id) {
        return debugAction(id, () -> apiService.clearMemberDebug(id));
    }

    /** Forgets what this instance knows of the member's mode, whatever catapult-api answers: it may have changed. */
    private ResponseEntity<Void> debugAction(UUID id, IntSupplier action) {
        requireAdmin();
        try {
            return ApiStatus.answer(action.getAsInt());
        } finally {
            debugMembers.evict(id);
        }
    }

    public record FlagBody(String key, String value) {}
    public record FlagKeyBody(String key) {}
    public record GroupBody(String groupKey) {}

    /** Like every action here, they answer catapult-api's outcome (see {@link ApiStatus}): 204; 404; 502 when unreachable. */
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
        return invalid ? ResponseEntity.badRequest().build() : ApiStatus.answer(action.getAsInt());
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private void requireAdmin() {
        AdminAccess.require(filler);
    }
}
