package fr.enimaloc.catapult.service.gueststar;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.event.GuestStarsChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The Guest Star guests on air in each channel, in memory, kept by its EventSub events and read again from Helix when
 * its connection opens. The broadcaster's own slot ({@code "0"}) and the screen share are not guests. A change of the
 * names (what a title shows) is published as a {@link GuestStarsChangedEvent}.
 */
@Component
@RequiredArgsConstructor
public class GuestStarRoster {

    /** A guest on air: their slot, Twitch id, login (null when unknown) and display name. */
    public record Guest(String slotId, String userId, String login, String displayName) {

        /** Without their login: mentioned by their display name. */
        public Guest(String slotId, String userId, String displayName) {
            this(slotId, userId, null, displayName);
        }

        /**
         * How a title mentions them: {@code @} and their display name when it is their login (Twitch resolves a
         * mention by login, whatever its case), their login otherwise (a display name in another script).
         */
        public String mention() {
            return "@" + (login == null || login.isBlank() || displayName.equalsIgnoreCase(login) ? displayName : login);
        }
    }

    static final String HOST_SLOT = "0";
    static final String SCREENSHARE_SLOT = "SCREENSHARE";
    private static final String LIVE = "live";
    /** Numeric slots in their order, anything else after them. */
    private static final Comparator<String> BY_SLOT = Comparator
            .comparing((String slot) -> !slot.chars().allMatch(Character::isDigit) || slot.isEmpty())
            .thenComparing(slot -> slot.length())
            .thenComparing(Comparator.naturalOrder());

    private final ApplicationEventPublisher eventPublisher;
    private final Map<UUID, TreeMap<String, Guest>> rosters = new ConcurrentHashMap<>();

    /**
     * A {@code channel.guest_star_guest.update}: {@code state} {@code live} puts the guest in their slot; any other
     * state, or an empty slot (no guest), takes the guest (or the slot) out.
     */
    public void onGuestUpdate(UserAccount user, String slotId, String guestUserId, String guestDisplayName,
                              String state) {
        onGuestUpdate(user, slotId, guestUserId, null, guestDisplayName, state);
    }

    /** {@link #onGuestUpdate(UserAccount, String, String, String, String)} with the guest's login, for mentions. */
    public void onGuestUpdate(UserAccount user, String slotId, String guestUserId, String guestLogin,
                              String guestDisplayName, String state) {
        change(user, guests -> {
            if (guestUserId != null) {
                guests.values().removeIf(guest -> guestUserId.equals(guest.userId()));
            } else if (slotId != null) {
                guests.remove(slotId);
            }
            if (LIVE.equals(state) && guestUserId != null && counts(slotId)) {
                guests.put(slotId, new Guest(slotId, guestUserId, guestLogin,
                        guestDisplayName == null ? guestUserId : guestDisplayName));
            }
        });
    }

    /** No guest any more: a session began or ended, or the channel can't be followed. */
    public void clear(UserAccount user) {
        change(user, TreeMap::clear);
    }

    /** The guests read again from Helix. */
    public void replace(UserAccount user, List<Guest> live) {
        change(user, guests -> {
            guests.clear();
            live.stream().filter(guest -> counts(guest.slotId())).forEach(guest -> guests.put(guest.slotId(), guest));
        });
    }

    /** The names of the guests on air, by slot, joined by {@code ", "}; empty without any. */
    public Optional<String> names(UUID userId) {
        TreeMap<String, Guest> guests = rosters.get(userId);
        if (guests == null) return Optional.empty();
        synchronized (guests) {
            return names(guests);
        }
    }

    /** How a title mentions the guests on air ({@link Guest#mention}), by slot, joined by {@code ", "}. */
    public Optional<String> mentions(UUID userId) {
        TreeMap<String, Guest> guests = rosters.get(userId);
        if (guests == null) return Optional.empty();
        synchronized (guests) {
            return joined(guests, Guest::mention);
        }
    }

    private void change(UserAccount user, java.util.function.Consumer<TreeMap<String, Guest>> update) {
        TreeMap<String, Guest> guests = rosters.computeIfAbsent(user.getId(), id -> new TreeMap<>(BY_SLOT));
        boolean changed;
        synchronized (guests) {
            List<Optional<String>> before = List.of(names(guests), joined(guests, Guest::mention));
            update.accept(guests);
            changed = !Objects.equals(before, List.of(names(guests), joined(guests, Guest::mention)));
        }
        if (changed) eventPublisher.publishEvent(new GuestStarsChangedEvent(this, user));
    }

    private static Optional<String> names(TreeMap<String, Guest> guests) {
        return joined(guests, Guest::displayName);
    }

    private static Optional<String> joined(TreeMap<String, Guest> guests,
                                           java.util.function.Function<Guest, String> part) {
        if (guests.isEmpty()) return Optional.empty();
        return Optional.of(guests.values().stream().map(part).collect(Collectors.joining(", ")));
    }

    private static boolean counts(String slotId) {
        return slotId != null && !HOST_SLOT.equals(slotId) && !SCREENSHARE_SLOT.equals(slotId);
    }
}
