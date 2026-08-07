package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.domain.UserGroup;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.repository.UserGroupRepository;
import fr.enimaloc.catapult.service.notification.AdminEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/groups")
@PreAuthorize("hasRole('ADMIN') or hasIpAddress('127.0.0.1') or hasIpAddress('::1')")
@RequiredArgsConstructor
public class ApiAdminGroupsController {

    private final UserGroupRepository groupRepository;
    private final UserAccountRepository userAccountRepository;

    @Autowired(required = false)
    private AdminEventPublisher events;

    private static GroupSummary summaryOf(UserGroup g) {
        return new GroupSummary(g.getId(), g.getKey(), g.getName(), g.getDescription(), g.getMembers().size());
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<GroupSummary> list() {
        return groupRepository.findAll().stream()
            .map(g -> new GroupSummary(g.getId(), g.getKey(), g.getName(), g.getDescription(), g.getMembers().size()))
            .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public void create(@RequestBody CreateGroupRequest body) {
        if (body.key() == null || body.key().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing key");
        }
        String key = body.key().trim();
        if (groupRepository.existsByKey(key)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Group key already exists");
        }
        UserGroup group = new UserGroup();
        group.setKey(key);
        group.setName(body.name() == null || body.name().isBlank() ? key : body.name());
        group.setDescription(body.description());
        UserGroup saved = groupRepository.save(group);
        if (events != null) {
            events.groupCreated(new GroupSummary(saved.getId(), saved.getKey(), saved.getName(), saved.getDescription(), 0));
        }
    }

    @PostMapping("/{id}/rename")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rename(@PathVariable UUID id, @RequestBody RenameRequest body) {
        UserGroup group = findOrThrow(id);
        if (body.name() != null && !body.name().isBlank()) group.setName(body.name());
        group.setDescription(body.description());
        groupRepository.save(group);
        if (events != null) events.groupUpdated(summaryOf(group));
    }

    @PostMapping("/{id}/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        groupRepository.delete(findOrThrow(id));
        if (events != null) events.groupDeleted(id.toString());
    }

    @PostMapping("/{id}/members")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addMember(@PathVariable UUID id, @RequestBody AddMemberRequest body) {
        UserGroup group = findOrThrow(id);
        UserAccount user = userAccountRepository.findByTwitchUsername(body.twitchUsername().trim())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        group.getMembers().add(user);
        groupRepository.save(group);
        if (events != null) events.groupUpdated(summaryOf(group));
    }

    @PostMapping("/{id}/members/{userId}/delete")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable UUID id, @PathVariable UUID userId) {
        UserGroup group = findOrThrow(id);
        group.getMembers().removeIf(m -> m.getId().equals(userId));
        groupRepository.save(group);
        if (events != null) events.groupUpdated(summaryOf(group));
    }

    private UserGroup findOrThrow(UUID id) {
        return groupRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public record GroupSummary(UUID id, String key, String name, String description, int memberCount) {}
    public record CreateGroupRequest(String key, String name, String description) {}
    public record RenameRequest(String name, String description) {}
    public record AddMemberRequest(String twitchUsername) {}
}
