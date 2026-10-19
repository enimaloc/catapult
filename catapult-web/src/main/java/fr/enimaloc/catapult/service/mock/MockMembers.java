package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.AdminMemberDto;
import fr.enimaloc.catapult.common.dto.admin.AdminMembersPage;
import fr.enimaloc.catapult.common.dto.channel.ChannelDto;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * The accounts the admin members page lists, the same for every mock session as catapult-api's
 * are: each account that logged in (and the channels it moderates), an inactive account and the
 * chat bot's system account, changed by the page's actions.
 */
public class MockMembers {
    private static final Instant CREATED = Instant.parse("2025-03-01T12:00:00Z");
    /** The inactive account and the bot, listed after the accounts that logged in. */
    private static final Set<UUID> SEEDED = Set.of(MockPresets.uuidOf("inactive"), MockPresets.uuidOf("catapult_bot"));

    private final List<AdminMemberDto> members = new ArrayList<>();
    private final Map<UUID, Boolean> live = new LinkedHashMap<>();
    /** Every account ever registered: one the admin deleted is not brought back by its session's next request. */
    private final Set<UUID> registered = new HashSet<>();

    MockMembers() {
        members.add(new AdminMemberDto(MockPresets.uuidOf("inactive"), null, null, null, null,
                false, false, "INACTIVE", CREATED.minus(90, ChronoUnit.DAYS)));
        members.add(new AdminMemberDto(MockPresets.uuidOf("catapult_bot"), "99999999", "catapult_bot",
                MockPresets.getDefaultAvatar(), null, true, true, "ACTIVE", CREATED.minus(365, ChronoUnit.DAYS)));
    }

    /** Lists {@code session}'s account and the channels it moderates, unless they already were. */
    synchronized void register(MockData session) {
        ChannelDto self = session.getChannelDto();
        add(self, session.isHasSteam() ? String.valueOf(76561198000000000L + Math.floorMod(self.id().hashCode(), 1_000_000)) : null);
        session.getModeratedChannels().forEach(channel -> add(channel, null));
    }

    private void add(ChannelDto channel, String steamId) {
        if (!registered.add(channel.id())) return;
        int beforeSeeded = (int) members.stream().filter(m -> !SEEDED.contains(m.id())).count();
        members.add(beforeSeeded, new AdminMemberDto(channel.id(), channel.twitchId(), channel.twitchUsername(),
                channel.profileImageUrl(), steamId, false, true, "ACTIVE",
                CREATED.minus(beforeSeeded * 30L, ChronoUnit.DAYS)));
        live.put(channel.id(), channel.live());
    }

    /** The members, {@code self}'s account first. */
    public synchronized AdminMembersPage page(UUID self) {
        List<AdminMemberDto> sorted = new ArrayList<>(members);
        sorted.sort(Comparator.comparing(m -> !m.id().equals(self)));
        return new AdminMembersPage(List.copyOf(sorted), Map.copyOf(live), true);
    }

    public synchronized void toggleBot(UUID id) {
        update(id, m -> with(m, m.steamId(), m.systemAccount(), !m.botEnabled()));
    }

    public synchronized void unlinkSteam(UUID id) {
        update(id, m -> with(m, null, m.systemAccount(), m.botEnabled()));
    }

    public synchronized void unlinkTwitch(UUID id) {
        update(id, m -> new AdminMemberDto(m.id(), null, null, null, m.steamId(), m.systemAccount(),
                false, "INACTIVE", m.createdAt()));
        live.remove(id);
    }

    /** The previous system account stays, as a regular member: they all have a Twitch identity here. */
    public synchronized void promoteToSystem(UUID id) {
        members.replaceAll(m -> m.systemAccount() ? with(m, m.steamId(), false, m.botEnabled()) : m);
        update(id, m -> with(m, m.steamId(), true, m.botEnabled()));
    }

    public synchronized void delete(UUID id) {
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
