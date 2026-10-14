package fr.enimaloc.catapult.common.dto.admin;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** GET /api/admin/members: every member, and whether each one is live. */
public record AdminMembersPage(List<AdminMemberDto> members, Map<UUID, Boolean> liveStatus, boolean isMockProfile) {

    public boolean isLive(UUID memberId) {
        return Boolean.TRUE.equals(liveStatus.get(memberId));
    }
}
