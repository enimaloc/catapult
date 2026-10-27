package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.GroupSummaryDto;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The mock's member groups: one store for the whole application. Answers like catapult-api: 201
 * for a created group (400 blank key, 409 key already used), 204 for the other actions (404 for an
 * unknown group). Adding a member twice changes nothing, like the set it is in catapult-api.
 */
class MockGroupsStore {
    private static final class Group {
        final UUID id = UUID.randomUUID();
        final String key;
        String name;
        String description;
        final Set<UUID> members = new LinkedHashSet<>();

        Group(String key, String name, String description) {
            this.key = key;
            this.name = name;
            this.description = description;
        }

        GroupSummaryDto summary() {
            return new GroupSummaryDto(id, key, name, description, members.size());
        }
    }

    private final List<Group> groups = new ArrayList<>();

    MockGroupsStore() {
        groups.add(new Group("beta-testers", "Beta testers", "Members trying the features before everyone"));
        groups.add(new Group("staff", "Staff", null));
    }

    synchronized List<GroupSummaryDto> list() {
        return groups.stream().map(Group::summary).toList();
    }

    synchronized Optional<GroupSummaryDto> find(String key) {
        return groups.stream().filter(g -> g.key.equals(key)).map(Group::summary).findFirst();
    }

    synchronized int create(String key, String name, String description) {
        if (key == null || key.isBlank()) return 400;
        String trimmed = key.trim();
        if (groups.stream().anyMatch(g -> g.key.equals(trimmed))) return 409;
        groups.add(new Group(trimmed, name == null || name.isBlank() ? trimmed : name, description));
        return 201;
    }

    /** A blank name keeps the current one; the description is replaced as given. */
    synchronized int rename(UUID id, String name, String description) {
        Group group = byId(id);
        if (group == null) return 404;
        if (name != null && !name.isBlank()) group.name = name;
        group.description = description;
        return 204;
    }

    synchronized int delete(UUID id) {
        return groups.removeIf(g -> g.id.equals(id)) ? 204 : 404;
    }

    synchronized int addMember(UUID groupId, UUID memberId) {
        Group group = byId(groupId);
        if (group == null) return 404;
        group.members.add(memberId);
        return 204;
    }

    synchronized int removeMember(UUID groupId, UUID memberId) {
        Group group = byId(groupId);
        if (group == null) return 404;
        group.members.remove(memberId);
        return 204;
    }

    /** The keys of the groups the member belongs to, in creation order. */
    synchronized List<String> keysOf(UUID memberId) {
        return groups.stream().filter(g -> g.members.contains(memberId)).map(g -> g.key).toList();
    }

    private Group byId(UUID id) {
        return groups.stream().filter(g -> g.id.equals(id)).findFirst().orElse(null);
    }
}
