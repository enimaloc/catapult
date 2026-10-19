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
import java.util.function.IntSupplier;

/**
 * The admin Groups tab's actions (create, rename, delete, add a member), forwarded to catapult-api
 * with the session's JWT and answered with its status (201 created, 204; 400, 404, 409; 502 when unreachable).
 */
@RestController
@RequestMapping("/admin/groups")
@RequiredArgsConstructor
public class AdminGroupsController {
    private final ApiService apiService;
    private final ModelFiller filler;

    public record CreateBody(String key, String name, String description) {}
    public record RenameBody(String name, String description) {}
    public record MemberBody(String twitchUsername) {}

    @PostMapping
    public ResponseEntity<Void> create(@RequestBody CreateBody body) {
        return relay(() -> apiService.adminCreateGroup(body.key(), body.name(), body.description()));
    }

    @PostMapping("/{id}/rename")
    public ResponseEntity<Void> rename(@PathVariable UUID id, @RequestBody RenameBody body) {
        return relay(() -> apiService.adminRenameGroup(id, body.name(), body.description()));
    }

    @PostMapping("/{id}/delete")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        return relay(() -> apiService.adminDeleteGroup(id));
    }

    @PostMapping("/{id}/members")
    public ResponseEntity<Void> addMember(@PathVariable UUID id, @RequestBody MemberBody body) {
        // catapult-api trims the login unguarded: a missing one is refused here rather than answered with its 500
        if (body.twitchUsername() == null || body.twitchUsername().isBlank()) {
            AdminAccess.require(filler);
            return ResponseEntity.badRequest().build();
        }
        return relay(() -> apiService.adminAddGroupMember(id, body.twitchUsername()));
    }

    private ResponseEntity<Void> relay(IntSupplier action) {
        AdminAccess.require(filler);
        return ResponseEntity.status(action.getAsInt()).build();
    }
}
