package fr.enimaloc.catapult.simulation;

import fr.enimaloc.catapult.domain.account.UserAccount;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The check a real client makes before an outbound call that no simulated decorator wraps (startup connections,
 * scheduled jobs, prewarms): under simulation it only goes out when the call's mode is {@code REAL}. Real clients
 * take it as an {@link ObjectProvider}: without the simulation the bean is absent and nothing changes.
 */
@Component
@ConditionalOnBooleanProperty("catapult.simulation.enabled")
public class SimulationGate {

    private final SimulationRegistry registry;

    public SimulationGate(SimulationRegistry registry) {
        this.registry = registry;
    }

    /** Whether {@code key} runs for real for the account ({@code null} for a global key). */
    public boolean real(@Nullable UUID accountId, CallKey key) {
        return registry.setting(accountId, key).mode() == CallSetting.Mode.REAL;
    }

    /** What the simulated platforms answer for the account, for the real clients that seed a state from it. */
    public SimulatedData data(UUID accountId) {
        return registry.data(accountId);
    }

    /** Whether the simulation is on and keeps {@code key} from going out for the account. */
    public static boolean blocks(ObjectProvider<SimulationGate> gate, @Nullable UUID accountId, CallKey key) {
        SimulationGate active = gate.getIfAvailable();
        return active != null && !active.real(accountId, key);
    }

    /**
     * Whether the simulation is on and keeps a call shared by several accounts (one batch for all of them) from
     * going out: none of them has {@code key} {@code REAL}. The accounts are only listed when the simulation is on.
     */
    public static boolean blocksAll(ObjectProvider<SimulationGate> gate,
                                    Supplier<? extends Collection<UUID>> accountIds, CallKey key) {
        SimulationGate active = gate.getIfAvailable();
        return active != null && accountIds.get().stream().noneMatch(id -> active.real(id, key));
    }

    /** Whether the simulation is on and {@code account} is a fictitious one. */
    public static boolean isFictitious(ObjectProvider<SimulationGate> gate, UserAccount account) {
        return gate.getIfAvailable() != null && isFictitious(account);
    }

    /**
     * Whether {@code account} is a fictitious one, made from the mock control panel: its Twitch id is
     * {@value SimulationRegistry#FICTITIOUS_PREFIX} and digits, which no real (numeric) Twitch id is, or one made
     * before the prefix was shortened ({@value #LEGACY_FICTITIOUS_PREFIX} and a suffix). Any other id starting with
     * an {@code m} is not: test and perf fixtures give real accounts such ids.
     */
    public static boolean isFictitious(UserAccount account) {
        return isFictitiousTwitchId(account.getTwitchId());
    }

    /** Whether {@code twitchId} is a fictitious account's, see {@link #isFictitious(UserAccount)}. */
    public static boolean isFictitiousTwitchId(@Nullable String twitchId) {
        if (twitchId == null) {
            return false;
        }
        if (twitchId.startsWith(LEGACY_FICTITIOUS_PREFIX)) {
            return true;
        }
        String digits = twitchId.startsWith(SimulationRegistry.FICTITIOUS_PREFIX)
                ? twitchId.substring(SimulationRegistry.FICTITIOUS_PREFIX.length())
                : "";
        return !digits.isEmpty() && digits.chars().allMatch(Character::isDigit);
    }

    private static final String LEGACY_FICTITIOUS_PREFIX = "mock-";

    /**
     * Whether the simulation is off and {@code account} is a fictitious one: the scheduled jobs and connections leave
     * it alone, so a run without the simulation never works on an account no platform knows.
     */
    public static boolean ignoresFictitious(ObjectProvider<SimulationGate> gate, UserAccount account) {
        return gate.getIfAvailable() == null && isFictitious(account);
    }
}
