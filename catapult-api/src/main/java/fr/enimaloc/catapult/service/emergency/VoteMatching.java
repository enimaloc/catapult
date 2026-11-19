package fr.enimaloc.catapult.service.emergency;

import fr.enimaloc.catapult.domain.emergency.EmergencyReport;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Counts independent votes: each vote is a distinct author paired with a distinct channel, so the
 * count is the size of a maximum bipartite matching between authors and the channels they reported.
 * One author reporting many channels, or many authors on one channel, never adds up.
 */
final class VoteMatching {

    private VoteMatching() {
    }

    static int maxMatching(List<EmergencyReport> reports) {
        Map<String, Set<UUID>> channelsByAuthor = new HashMap<>();
        for (EmergencyReport r : reports) {
            channelsByAuthor.computeIfAbsent(r.getReportedByTwitchId(), k -> new HashSet<>())
                    .add(r.getChannel().getId());
        }
        Map<UUID, String> authorOfChannel = new HashMap<>();
        int matched = 0;
        for (String author : channelsByAuthor.keySet()) {
            if (augment(author, channelsByAuthor, authorOfChannel, new HashSet<>())) {
                matched++;
            }
        }
        return matched;
    }

    /** Kuhn's augmenting path: tries to give the author a channel, re-routing earlier authors if needed. */
    private static boolean augment(String author, Map<String, Set<UUID>> channelsByAuthor,
                                   Map<UUID, String> authorOfChannel, Set<UUID> visited) {
        for (UUID channel : channelsByAuthor.get(author)) {
            if (!visited.add(channel)) {
                continue;
            }
            String holder = authorOfChannel.get(channel);
            if (holder == null || augment(holder, channelsByAuthor, authorOfChannel, visited)) {
                authorOfChannel.put(channel, author);
                return true;
            }
        }
        return false;
    }
}
