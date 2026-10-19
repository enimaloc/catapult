package fr.enimaloc.catapult.common.dto.admin;

import java.util.List;
import java.util.Map;

/**
 * The admin whitelist page as catapult-api's {@code GET /api/admin/whitelist} answers it: the
 * entries (keyed by Twitch id) with the login each resolves to, the two switches, the invitation
 * settings ({@code null} caps mean unlimited) and every invite.
 */
public record WhitelistPageDto(List<Entry> entries, Map<String, String> resolvedUsernames, boolean whitelistEnabled,
                               boolean inviteEnabled, Integer globalMaxMembers, Integer defaultMaxUses,
                               boolean defaultCanReinvite, boolean globalCapReached, List<WhitelistInviteRow> invites) {
    public record Entry(String twitchId) {}
}
