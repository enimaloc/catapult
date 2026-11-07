package fr.enimaloc.catapult.simulation;

import fr.enimaloc.catapult.domain.account.UserAccount;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * What every injected incoming event (an EventSub notification the simulation hands to the real handling) goes
 * through, in the event key's setting for the account: {@code REAL} refuses it (the real event arrives), the latency
 * comes before the delivery, {@code ERROR} and {@code TIMEOUT} drop it. Each injection is journaled.
 */
public final class InjectedEvents {

    /** The journal mode of a delivered injection. */
    public static final String INJECTED = "INJECTED";

    private InjectedEvents() {}

    /**
     * @throws IllegalStateException when {@code key} is {@code REAL} for the account: the real event arrives, the
     *                               simulation must not make one up
     */
    public static void requireSimulated(SimulationRegistry registry, UserAccount user, CallKey key) {
        if (registry.setting(user.getId(), key).mode() == CallSetting.Mode.REAL) {
            throw new IllegalStateException(key.id() + " is Real: the real event arrives");
        }
    }

    /**
     * Waits the setting's latency, then says whether the event is delivered; a dropped one is journaled as such, a
     * delivered one as {@value #INJECTED}.
     *
     * @throws IllegalStateException when {@code key} is {@code REAL} for the account, see {@link #requireSimulated}
     */
    public static boolean admit(SimulationRegistry registry, SimulationJournal journal, UserAccount user,
                                CallKey key, String request) {
        CallSetting setting = registry.setting(user.getId(), key);
        if (setting.mode() == CallSetting.Mode.REAL) {
            throw new IllegalStateException(key.id() + " is Real: the real event arrives");
        }
        sleep(setting.latencyMs());
        return switch (setting.mode()) {
            case ERROR -> {
                record(journal, user, key, request, setting, "ERROR", "dropped (http " + setting.httpStatus() + ")");
                yield false;
            }
            case TIMEOUT -> {
                record(journal, user, key, request, setting, "TIMEOUT", "dropped (timeout)");
                yield false;
            }
            default -> {
                record(journal, user, key, request, setting, INJECTED, "ok");
                yield true;
            }
        };
    }

    /** The account's Twitch login as an event carries it: its username in lower case, else its Twitch id. */
    public static @Nullable String login(UserAccount user) {
        return user.getTwitchUsername() == null ? user.getTwitchId() : user.getTwitchUsername().toLowerCase();
    }

    private static void record(SimulationJournal journal, UserAccount user, CallKey key, String request,
                               CallSetting setting, String mode, String outcome) {
        journal.record(new SimulationJournal.Entry(Instant.now(), user.getId(), key.id(), request, mode, outcome,
                setting.latencyMs()));
    }

    private static void sleep(int ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while simulating latency", e);
        }
    }
}
