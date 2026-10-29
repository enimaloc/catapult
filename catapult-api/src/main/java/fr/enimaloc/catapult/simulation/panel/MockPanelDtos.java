package fr.enimaloc.catapult.simulation.panel;

import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.service.twitch.TwitchExtension;
import fr.enimaloc.catapult.service.twitch.TwitchExtensionPlacement;
import fr.enimaloc.catapult.simulation.CallKey;
import fr.enimaloc.catapult.simulation.CallSetting;
import fr.enimaloc.catapult.simulation.SimulatedData;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** What the control panel's JSON API reads and writes ({@link MockPanelApiController}). */
public final class MockPanelDtos {

    private MockPanelDtos() {}

    /**
     * One account of the list.
     *
     * @param moderates the usernames of the channels the simulated Twitch says it moderates
     */
    public record AccountView(UUID id, @Nullable String username, String twitchId, boolean fictitious,
                              boolean admin, boolean botEnabled, String status, List<String> moderates) {}

    /** A new fictitious account; {@code moderates} are account ids. */
    public record CreateAccount(@Nullable String username, boolean admin, @Nullable List<UUID> moderates,
                                @Nullable Boolean botEnabled) {}

    /**
     * The channel as Catapult sees it ({@code live}, {@code detectedGame}, {@code botEnabled}) and as the
     * simulated platforms answer ({@code data}).
     *
     * @param categoryId   the category the simulated Twitch has on the channel
     * @param detectedGame the last game the detection found, {@code null} for none
     */
    public record StateView(UUID accountId, boolean live, boolean botEnabled, @Nullable DetectedGameView detectedGame,
                            @Nullable String categoryId, @Nullable String categoryName, SimulatedData data) {}

    public record DetectedGameView(String sourceId, String sourceType, String name) {}

    public record LiveRequest(boolean live) {}

    public record BotRequest(boolean enabled) {}

    public record PlayRequest(GetterConfig.@Nullable Provider provider, @Nullable String sourceId,
                              @Nullable String name) {}

    public record ManualCategoryRequest(@Nullable String categoryId, @Nullable String categoryName) {}

    public record RaidRequest(@Nullable String toLogin) {}

    /** One API of the catalogue and its calls, in {@code SimulatedCalls} order. */
    public record ApiCalls(String api, List<CallView> calls) {}

    public record CallView(String key, String call, CallKey.Scope scope) {}

    /**
     * One call's effective setting.
     *
     * @param realAllowed whether {@code REAL} may be chosen (the token or key it needs exists)
     * @param forced      {@code REAL} only because a call that uses the refreshed token is: not editable meanwhile
     */
    public record SettingView(String key, String api, String call, CallKey.Scope scope, CallSetting.Mode mode,
                              int httpStatus, int latencyMs, boolean realAllowed, boolean forced) {}

    /** {@code httpStatus} is only read for {@code ERROR}. */
    public record SettingRequest(CallSetting.@Nullable Mode mode, @Nullable Integer httpStatus,
                                 @Nullable Integer latencyMs) {}

    /** The simulated data to change: a {@code null} field is left as it is. */
    public record DataUpdate(@Nullable List<TwitchExtension> installed, @Nullable TwitchExtensionPlacement active,
                             @Nullable List<String> moderatedChannelIds, @Nullable Boolean steamProfilePublic,
                             @Nullable Set<String> ccls) {}
}
