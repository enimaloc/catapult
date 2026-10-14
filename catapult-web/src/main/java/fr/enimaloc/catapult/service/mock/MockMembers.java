package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.AdminMemberDto;
import fr.enimaloc.catapult.common.dto.admin.AdminMembersPage;
import fr.enimaloc.catapult.common.dto.channel.ChannelDto;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * The accounts a mock session's admin members page lists, changed by its actions as catapult-api
 * would: the session's own channel first, its moderated channels, an inactive account and the
 * chat bot's system account.
 */
public class MockMembers {
    private static final Instant CREATED = Instant.parse("2025-03-01T12:00:00Z");

    private final List<AdminMemberDto> members = new ArrayList<>();
    private final Map<UUID, Boolean> live = new LinkedHashMap<>();

    MockMembers(ChannelDto self, List<ChannelDto> moderated) {
        add(self, "76561198000000001");
        moderated.forEach(channel -> add(channel, null));
        members.add(new AdminMemberDto(MockPresets.uuidOf("inactive"), null, null, null, null,
                false, false, "INACTIVE", CREATED.minus(90, ChronoUnit.DAYS)));
        members.add(new AdminMemberDto(MockPresets.uuidOf("catapult_bot"), "99999999", "catapult_bot",
                MockPresets.getDefaultAvatar(), null, true, true, "ACTIVE", CREATED.minus(365, ChronoUnit.DAYS)));
    }

    private void add(ChannelDto channel, String steamId) {
        members.add(new AdminMemberDto(channel.id(), channel.twitchId(), channel.twitchUsername(),
                channel.profileImageUrl(), steamId, false, true, "ACTIVE",
                CREATED.minus(members.size() * 30L, ChronoUnit.DAYS)));
        live.put(channel.id(), channel.live());
    }

    public AdminMembersPage page() {
        return new AdminMembersPage(List.copyOf(members), Map.copyOf(live), true);
    }

    public void toggleBot(UUID id) {
        update(id, m -> with(m, m.steamId(), m.systemAccount(), !m.botEnabled()));
    }

    public void unlinkSteam(UUID id) {
        update(id, m -> with(m, null, m.systemAccount(), m.botEnabled()));
    }

    public void unlinkTwitch(UUID id) {
        update(id, m -> new AdminMemberDto(m.id(), null, null, null, m.steamId(), m.systemAccount(),
                false, "INACTIVE", m.createdAt()));
        live.remove(id);
    }

    /** The previous system account stays, as a regular member: they all have a Twitch identity here. */
    public void promoteToSystem(UUID id) {
        members.replaceAll(m -> m.systemAccount() ? with(m, m.steamId(), false, m.botEnabled()) : m);
        update(id, m -> with(m, m.steamId(), true, m.botEnabled()));
    }

    public void delete(UUID id) {
        members.removeIf(m -> m.id().equals(id));
        live.remove(id);
    }

    private void update(UUID id, UnaryOperator<AdminMemberDto> change) {
        members.replaceAll(m -> m.id().equals(id) ? change.apply(m) : m);
    }

    private static AdminMemberDto with(AdminMemberDto m, String steamId, boolean systemAccount, boolean botEnabled) {
        return new AdminMemberDto(m.id(), m.twitchId(), m.twitchUsername(), m.profileImageUrl(), steamId,
                systemAccount, botEnabled, m.status(), m.createdAt());
    }
}
