package fr.enimaloc.catapult.service.account;

import fr.enimaloc.catapult.common.dto.admin.GroupSummaryDto;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserGroup;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.account.UserGroupRepository;
import fr.enimaloc.catapult.service.notification.AdminEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/** The member groups the admin manages, each change told to the admin dashboards. */
@Service
@RequiredArgsConstructor
public class UserGroupService {

    private final UserGroupRepository groupRepository;
    private final UserAccountRepository userAccountRepository;
    private final AdminEventPublisher events;

    private static GroupSummaryDto summaryOf(UserGroup g) {
        return new GroupSummaryDto(g.getId(), g.getKey(), g.getName(), g.getDescription(), g.getMembers().size());
    }

    @Transactional(readOnly = true)
    public List<GroupSummaryDto> list() {
        return groupRepository.findAll().stream()
            .map(g -> new GroupSummaryDto(g.getId(), g.getKey(), g.getName(), g.getDescription(), g.getMembers().size()))
            .toList();
    }

    public void create(String rawKey, String name, String description) {
        if (rawKey == null || rawKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing key");
        }
        String key = rawKey.trim();
        if (groupRepository.existsByKey(key)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Group key already exists");
        }
        UserGroup group = new UserGroup();
        group.setKey(key);
        group.setName(name == null || name.isBlank() ? key : name);
        group.setDescription(description);
        UserGroup saved = groupRepository.save(group);
        events.groupCreated(new GroupSummaryDto(saved.getId(), saved.getKey(), saved.getName(), saved.getDescription(), 0));
    }

    /** Publishes inside the transaction, as the route always did. */
    @Transactional
    public void rename(UUID id, String name, String description) {
        UserGroup group = findOrThrow(id);
        if (name != null && !name.isBlank()) group.setName(name);
        group.setDescription(description);
        groupRepository.save(group);
        events.groupUpdated(summaryOf(group));
    }

    public void delete(UUID id) {
        groupRepository.delete(findOrThrow(id));
        events.groupDeleted(id.toString());
    }

    @Transactional
    public void addMember(UUID id, String twitchUsername) {
        UserGroup group = findOrThrow(id);
        UserAccount user = userAccountRepository.findByTwitchUsername(twitchUsername.trim())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        group.getMembers().add(user);
        groupRepository.save(group);
        events.groupUpdated(summaryOf(group));
    }

    @Transactional
    public void removeMember(UUID id, UUID memberId) {
        UserGroup group = findOrThrow(id);
        group.getMembers().removeIf(m -> m.getId().equals(memberId));
        groupRepository.save(group);
        events.groupUpdated(summaryOf(group));
    }

    private UserGroup findOrThrow(UUID id) {
        return groupRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
