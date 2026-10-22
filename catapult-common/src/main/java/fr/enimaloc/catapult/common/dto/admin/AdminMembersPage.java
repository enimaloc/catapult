package fr.enimaloc.catapult.common.dto.admin;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GET /api/admin/members: one page of members, and whether each one is live (a member missing
 * there isn't), with where that page sits among the matches.
 */
public record AdminMembersPage(List<AdminMemberDto> members, Map<UUID, Boolean> liveStatus, boolean isMockProfile,
                               int number, int totalPages, long totalElements) {

    public AdminMembersPage {
        members = members == null ? List.of() : members;
        liveStatus = liveStatus == null ? Map.of() : liveStatus;
    }

    public boolean isLive(UUID memberId) {
        return memberId != null && Boolean.TRUE.equals(liveStatus.get(memberId));
    }

    /** The first page (also when there is none). */
    public boolean first() {
        return number <= 0;
    }

    /** The last page (also when there is none). */
    public boolean last() {
        return number >= totalPages - 1;
    }
}
