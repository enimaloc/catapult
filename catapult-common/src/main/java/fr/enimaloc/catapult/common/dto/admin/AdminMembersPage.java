package fr.enimaloc.catapult.common.dto.admin;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** GET /api/admin/members: every member, and whether each one is live (a member missing there isn't). */
public record AdminMembersPage(List<AdminMemberDto> members, Map<UUID, Boolean> liveStatus, boolean isMockProfile) {

    public AdminMembersPage {
        members = members == null ? List.of() : members;
        liveStatus = liveStatus == null ? Map.of() : liveStatus;
    }

    public boolean isLive(UUID memberId) {
        return memberId != null && Boolean.TRUE.equals(liveStatus.get(memberId));
    }
}
