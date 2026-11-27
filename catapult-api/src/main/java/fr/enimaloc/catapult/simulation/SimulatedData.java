package fr.enimaloc.catapult.simulation;

import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.service.twitch.TwitchExtension;
import fr.enimaloc.catapult.service.twitch.TwitchExtensionPlacement;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the simulated platforms answer for one account. Immutable: change it through
 * {@link SimulationRegistry#update}. The Twitch extension types are stored as they are, their Jackson annotations
 * (Twitch's wire format) round-trip through the state file.
 *
 * @param live                 whether the channel is live
 * @param presence             the game each simulated platform reports the account playing, absent when none
 * @param installed            the extensions the broadcaster has installed
 * @param active               the extensions active on the channel
 * @param moderatedChannelIds  the channels the account moderates
 * @param steamProfilePublic   whether the Steam profile's game details are public
 * @param currentCategoryId    the Twitch category currently set on the channel
 * @param currentCategoryName  that category's name
 * @param ccls                 the content classification labels currently set on the channel
 * @param guestStarConsent     whether the account gave its Guest Star consent (a fictitious one has no token to
 *                             carry the scope)
 * @param guestStars           the Guest Star guests on air, by slot
 */
public record SimulatedData(
        boolean live,
        Map<GetterConfig.Provider, SimulatedPresence> presence,
        List<TwitchExtension> installed,
        TwitchExtensionPlacement active,
        List<String> moderatedChannelIds,
        boolean steamProfilePublic,
        String currentCategoryId,
        String currentCategoryName,
        Set<String> ccls,
        boolean guestStarConsent,
        List<SimulatedGuest> guestStars
) {
    public SimulatedData {
        presence = presence == null ? Map.of() : Map.copyOf(presence);
        installed = installed == null ? List.of() : List.copyOf(installed);
        active = active == null ? TwitchExtensionPlacement.empty() : active;
        moderatedChannelIds = moderatedChannelIds == null ? List.of() : List.copyOf(moderatedChannelIds);
        ccls = ccls == null ? Set.of() : Set.copyOf(ccls);
        guestStars = guestStars == null ? List.of() : List.copyOf(guestStars);
    }

    /** The data as it was before Guest Star: no consent, no guest. */
    public SimulatedData(boolean live, Map<GetterConfig.Provider, SimulatedPresence> presence,
                         List<TwitchExtension> installed, TwitchExtensionPlacement active,
                         List<String> moderatedChannelIds, boolean steamProfilePublic, String currentCategoryId,
                         String currentCategoryName, Set<String> ccls) {
        this(live, presence, installed, active, moderatedChannelIds, steamProfilePublic, currentCategoryId,
                currentCategoryName, ccls, false, List.of());
    }

    /** A Guest Star guest on air: their slot, Twitch id and display name. */
    public record SimulatedGuest(String slotId, String userId, String displayName) {}

    /** What a game on a platform is called and identified by there ({@code sourceId}: Steam app id, Xbox title id…). */
    public record SimulatedPresence(String sourceId, String name) {}

    /** Offline, in Just Chatting, nothing played, a few installed extensions none of which is active. */
    public static SimulatedData defaults() {
        return new SimulatedData(
                false,
                Map.of(),
                List.of(
                        new TwitchExtension(true, "sim-ext-panel", "Simulated panel",
                                List.of(TwitchExtension.Type.PANEL), "1.0.0"),
                        new TwitchExtension(true, "sim-ext-overlay", "Simulated overlay",
                                List.of(TwitchExtension.Type.OVERLAY), "1.0.0"),
                        new TwitchExtension(true, "sim-ext-component", "Simulated component",
                                List.of(TwitchExtension.Type.COMPONENT), "1.0.0")),
                TwitchExtensionPlacement.empty(),
                List.of(),
                true,
                "509658",
                "Just Chatting",
                Set.of());
    }

    public SimulatedData withLive(boolean live) {
        return new SimulatedData(live, presence, installed, active, moderatedChannelIds, steamProfilePublic,
                currentCategoryId, currentCategoryName, ccls, guestStarConsent, guestStars);
    }

    public SimulatedData withPresence(Map<GetterConfig.Provider, SimulatedPresence> presence) {
        return new SimulatedData(live, presence, installed, active, moderatedChannelIds, steamProfilePublic,
                currentCategoryId, currentCategoryName, ccls, guestStarConsent, guestStars);
    }

    public SimulatedData withInstalled(List<TwitchExtension> installed) {
        return new SimulatedData(live, presence, installed, active, moderatedChannelIds, steamProfilePublic,
                currentCategoryId, currentCategoryName, ccls, guestStarConsent, guestStars);
    }

    public SimulatedData withActive(TwitchExtensionPlacement active) {
        return new SimulatedData(live, presence, installed, active, moderatedChannelIds, steamProfilePublic,
                currentCategoryId, currentCategoryName, ccls, guestStarConsent, guestStars);
    }

    public SimulatedData withModeratedChannelIds(List<String> moderatedChannelIds) {
        return new SimulatedData(live, presence, installed, active, moderatedChannelIds, steamProfilePublic,
                currentCategoryId, currentCategoryName, ccls, guestStarConsent, guestStars);
    }

    public SimulatedData withSteamProfilePublic(boolean steamProfilePublic) {
        return new SimulatedData(live, presence, installed, active, moderatedChannelIds, steamProfilePublic,
                currentCategoryId, currentCategoryName, ccls, guestStarConsent, guestStars);
    }

    public SimulatedData withCategory(String id, String name) {
        return new SimulatedData(live, presence, installed, active, moderatedChannelIds, steamProfilePublic,
                id, name, ccls, guestStarConsent, guestStars);
    }

    public SimulatedData withCcls(Set<String> ccls) {
        return new SimulatedData(live, presence, installed, active, moderatedChannelIds, steamProfilePublic,
                currentCategoryId, currentCategoryName, ccls, guestStarConsent, guestStars);
    }

    public SimulatedData withGuestStarConsent(boolean guestStarConsent) {
        return new SimulatedData(live, presence, installed, active, moderatedChannelIds, steamProfilePublic,
                currentCategoryId, currentCategoryName, ccls, guestStarConsent, guestStars);
    }

    public SimulatedData withGuestStars(List<SimulatedGuest> guestStars) {
        return new SimulatedData(live, presence, installed, active, moderatedChannelIds, steamProfilePublic,
                currentCategoryId, currentCategoryName, ccls, guestStarConsent, guestStars);
    }
}
