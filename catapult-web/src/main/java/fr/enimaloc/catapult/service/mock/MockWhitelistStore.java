package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.AdminWhitelistRedemptionDto;
import fr.enimaloc.catapult.common.dto.admin.WhitelistInviteRow;
import fr.enimaloc.catapult.common.dto.admin.WhitelistPageDto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The mock's whitelist, its invitation settings and invites: one store for the whole application.
 * Answers like catapult-api: 204 for every action (adding a listed id or deleting an absent one
 * changes nothing); a quota for an unknown invite is a 404.
 */
class MockWhitelistStore {
    private final Map<String, String> entries = new LinkedHashMap<>();
    private final List<WhitelistInviteRow> invites = new ArrayList<>();
    private boolean whitelistEnabled = true;
    private boolean inviteEnabled = true;
    private Integer globalMaxMembers = 50;
    private Integer defaultMaxUses = 5;
    private boolean defaultCanReinvite = false;

    MockWhitelistStore() {
        entries.put("141981764", "twitchdev");
        entries.put("12826", "twitch");
        entries.put("987654321", "—");
        invites.add(invite("twitchdev", "ABC234", 2, null, null));
        invites.add(invite("twitch", "ZXC789", 5, 0, false));
        invites.add(invite("catapult_fan", "QWE456", 0, 10, true));
    }

    private static WhitelistInviteRow invite(String owner, String code, int uses, Integer max, Boolean canReinvite) {
        return new WhitelistInviteRow(UUID.randomUUID(), UUID.randomUUID(), owner, code, max, uses, canReinvite,
                Instant.parse("2026-09-01T10:00:00Z"), List.of(new AdminWhitelistRedemptionDto("555", Instant.parse("2026-09-02T10:00:00Z"))));
    }

    synchronized WhitelistPageDto page() {
        return new WhitelistPageDto(entries.keySet().stream().map(WhitelistPageDto.Entry::new).toList(),
                Map.copyOf(entries), whitelistEnabled, inviteEnabled, globalMaxMembers, defaultMaxUses,
                defaultCanReinvite, globalMaxMembers != null && entries.size() >= globalMaxMembers, List.copyOf(invites));
    }

    synchronized int toggle() {
        whitelistEnabled = !whitelistEnabled;
        return 204;
    }

    synchronized int add(String twitchId) {
        entries.putIfAbsent(twitchId, "—");
        return 204;
    }

    synchronized int delete(String twitchId) {
        entries.remove(twitchId);
        return 204;
    }

    synchronized int toggleInvites() {
        inviteEnabled = !inviteEnabled;
        return 204;
    }

    synchronized int saveSettings(Integer globalMaxMembers, Integer defaultMaxUses, boolean defaultCanReinvite) {
        this.globalMaxMembers = globalMaxMembers;
        this.defaultMaxUses = defaultMaxUses;
        this.defaultCanReinvite = defaultCanReinvite;
        return 204;
    }

    synchronized int saveQuota(UUID inviteId, Integer maxUses, Boolean canReinvite) {
        for (int i = 0; i < invites.size(); i++) {
            WhitelistInviteRow row = invites.get(i);
            if (row.id().equals(inviteId)) {
                invites.set(i, new WhitelistInviteRow(row.id(), row.ownerId(), row.ownerUsername(), row.code(), maxUses,
                        row.useCount(), canReinvite, row.createdAt(), row.redemptions()));
                return 204;
            }
        }
        return 404;
    }
}
