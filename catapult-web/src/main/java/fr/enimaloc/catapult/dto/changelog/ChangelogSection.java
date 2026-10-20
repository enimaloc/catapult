package fr.enimaloc.catapult.dto.changelog;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * One version of catapult-api's changelog ({@code GET /api/changelog}, built from its git history):
 * its conventional commits, newest first.
 */
public record ChangelogSection(String version, List<Entry> entries) {

    /** A conventional commit: its type (feat, fix…), optional scope, subject and whether it breaks something. */
    public record Entry(String hash, String type, String scope, String subject, boolean breaking) {}

    /** Entries of one kind, as the page lists them: {@code feat}, {@code fix}, then every {@code other} type. */
    public record Group(String kind, List<Entry> entries) {}

    private static final Map<String, Predicate<Entry>> KINDS = Map.of(
            "feat", entry -> "feat".equals(entry.type()),
            "fix", entry -> "fix".equals(entry.type()),
            "other", entry -> !"feat".equals(entry.type()) && !"fix".equals(entry.type()));

    /** The version's entries by kind, new features first, leaving out the kinds it has none of. */
    public List<Group> groups() {
        List<Entry> all = entries == null ? List.of() : entries;
        return List.of("feat", "fix", "other").stream()
                .map(kind -> new Group(kind, all.stream().filter(KINDS.get(kind)).toList()))
                .filter(group -> !group.entries().isEmpty())
                .toList();
    }
}
