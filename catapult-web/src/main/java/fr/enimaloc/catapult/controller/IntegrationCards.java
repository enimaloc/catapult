package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.channel.ChannelPageData;
import fr.enimaloc.catapult.common.dto.channel.MinecraftData;
import fr.enimaloc.catapult.common.dto.channel.ObsData;
import fr.enimaloc.catapult.common.dto.channel.SteamData;
import fr.enimaloc.catapult.common.dto.channel.TwitchatData;
import fr.enimaloc.catapult.common.dto.channel.XboxData;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatEventActionDto;
import fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms;
import fr.enimaloc.catapult.dto.integration.Attrs;
import fr.enimaloc.catapult.dto.integration.IntegrationDto;
import fr.enimaloc.catapult.dto.integration.IntegrationNode;
import fr.enimaloc.catapult.dto.integration.IntegrationNode.Button;
import fr.enimaloc.catapult.dto.integration.IntegrationNode.Chip;
import fr.enimaloc.catapult.dto.integration.IntegrationNode.Container;
import fr.enimaloc.catapult.dto.integration.IntegrationNode.Input;
import fr.enimaloc.catapult.dto.integration.IntegrationNode.Select;
import fr.enimaloc.catapult.dto.integration.IntegrationNode.Switch;
import fr.enimaloc.catapult.dto.integration.IntegrationNode.Text;
import fr.enimaloc.catapult.dto.integration.Message;
import fr.enimaloc.catapult.dto.integration.SpaState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static fr.enimaloc.catapult.dto.integration.Message.key;
import static fr.enimaloc.catapult.dto.integration.Message.literal;

/**
 * The channel owner's connection cards, built from the channel page: one card per integration
 * the API reported (Twitch always). Every element is pre-rendered and only toggled through
 * {@code hidden}, either by visibility.js from SSE events (spa:state) or by
 * channel-connections.js, which looks the ids and classes used here up.
 */
public final class IntegrationCards {

    private static final String PREFIX = "channel.connections.";
    private static final Message CONNECTED = key(PREFIX + "connected");
    private static final Message NOT_CONNECTED = key(PREFIX + "not_connected");

    private IntegrationCards() {
    }

    public static List<IntegrationDto> of(ChannelPageData ch, Map<String, List<TwitchatEventActionDto>> twitchatEvents,
                                          TwitchatEventForms forms) {
        List<IntegrationDto> cards = new ArrayList<>();
        cards.add(card("twitch", new Chip(Attrs.NONE, CONNECTED)));
        if (ch.steam() != null) {
            cards.add(steam(ch.steam()));
        }
        if (ch.xbox() != null) {
            cards.add(xbox(ch.xbox()));
        }
        if (ch.minecraft() != null) {
            cards.add(minecraft(ch.minecraft()));
        }
        if (ch.obs() != null) {
            cards.add(obs(ch.obs()));
        }
        if (ch.twitchat() != null) {
            cards.add(twitchat(ch.twitchat(), forms.eventsSection(twitchatEvents)));
        }
        return cards;
    }

    private static IntegrationDto card(String id, IntegrationNode... nodes) {
        return new IntegrationDto(id, key(PREFIX + id), List.of(nodes));
    }

    // Steam

    private static final String STEAM_STATE = "SteamConnectionStateEvent";

    private static IntegrationDto steam(SteamData steam) {
        SpaState connected = SpaState.flag("connected", steam.connected(), STEAM_STATE + ".connected");
        SpaState notConnected = SpaState.flag("notConnected", !steam.connected(), STEAM_STATE + ".!connected");
        SpaState hasToken = SpaState.flag("hasToken", steam.hasPersonalToken(),
                "SteamTokenSavedEvent", "!SteamTokenDeletedEvent");
        SpaState noToken = SpaState.flag("noToken", !steam.hasPersonalToken(),
                "SteamTokenDeletedEvent", "!SteamTokenSavedEvent");

        return card("steam",
                new Chip(Attrs.cls("steam-connected-chip").withState(connected), CONNECTED),
                new Chip(Attrs.cls("steam-not-connected-chip").withState(notConnected), NOT_CONNECTED),

                // Linking runs in a popup (SteamConnectController), unlinking is a post: both are
                // channel-connections.js's, the card following from the connection's event.
                new Container(Attrs.id("steam-link-actions").withClass("card-actions"), Container.Kind.DIV, List.of(
                        new Button(Attrs.id("steam-connect-btn").withState(notConnected), Button.Kind.FILLED,
                                key(PREFIX + "steam.connect")),
                        new Button(Attrs.id("steam-disconnect-btn").withState(connected), Button.Kind.TEXT,
                                key(PREFIX + "steam.disconnect")))),

                steamDiagnostic("rate-limited", connected.and("rateLimited", steam.rateLimited(), STEAM_STATE + ".rateLimited"),
                        key(PREFIX + "steam.rate_limited")),
                steamDiagnostic("offline", connected.and("offline", steam.offlineMode(), STEAM_STATE + ".offline"),
                        key(PREFIX + "steam.offline_mode", steam.profileCacheTtlMinutes())),
                steamDiagnostic("private", connected.and("privateProfile", steam.profilePrivate(), STEAM_STATE + ".privateProfile"),
                        key(PREFIX + "steam.private")),

                new Container(Attrs.id("steam-token-body").withState(connected), Container.Kind.DIV, List.of(
                        new Container(Attrs.cls("steam-token-has").withState(hasToken), Container.Kind.DIV, List.of(
                                new Switch(Attrs.id("steam-token-shared").withState(SpaState.sync("checked",
                                        "SteamTokenSavedEvent.shared", "SteamTokenSharedStateEvent.shared")),
                                        steam.tokenShared(), key(PREFIX + "steam.token.shared")))),
                        new Container(Attrs.cls("steam-token-none").withState(noToken), Container.Kind.DIV, List.of(
                                new Input(Attrs.id("steam-token-input"), "password",
                                        key(PREFIX + "steam.token.placeholder"), null, null, null, null),
                                new Switch(Attrs.id("steam-token-share-new"), false, key(PREFIX + "steam.token.shared")))))),

                new Container(Attrs.id("steam-token-actions").withClass("card-actions").withState(connected),
                        Container.Kind.SEGMENTED_GROUP, List.of(
                        segmented(Attrs.id("steam-token-delete-btn").withState(hasToken), key(PREFIX + "steam.token.delete")),
                        segmented(Attrs.id("steam-token-save-btn").withState(noToken), key(PREFIX + "steam.token.save")),
                        segmented(Attrs.id("steam-refresh-cache-btn"), key(PREFIX + "steam.refresh_cache")))));
    }

    private static Text steamDiagnostic(String name, SpaState state, Message text) {
        return new Text(Attrs.cls("steam-diag-" + name).withState(state), true, text);
    }

    // Xbox: no state event, channel-events.js re-renders the page on its connection's change, so
    // only the current state's chip and button are shown. Linking goes through Microsoft's login
    // (catapult-api's /oauth2/authorization/xbox), unlinking is a post: both channel-connections.js's.

    private static IntegrationDto xbox(XboxData xbox) {
        return card("xbox",
                new Chip(Attrs.NONE, xbox.connected() ? CONNECTED : NOT_CONNECTED),
                new Container(Attrs.id("xbox-link-actions").withClass("card-actions"), Container.Kind.DIV, List.of(
                        new Button(Attrs.id("xbox-connect-btn").hiddenIf(xbox.connected()), Button.Kind.FILLED,
                                key(PREFIX + "xbox.connect")),
                        new Button(Attrs.id("xbox-disconnect-btn").hiddenIf(!xbox.connected()), Button.Kind.TEXT,
                                key(PREFIX + "xbox.disconnect")))));
    }

    // Minecraft: one sub-view and button per status, driven by the status field of
    // MinecraftEnrollEvent/MinecraftSyncEvent. MinecraftDisconnectedEvent carries no field at
    // all, its mere occurrence always means NONE.

    private static IntegrationDto minecraft(MinecraftData minecraft) {
        String status = minecraft.status();
        return card("minecraft",
                new Container(Attrs.id("minecraft-status-body"), Container.Kind.DIV, List.of(
                        minecraftCase("caseNone", status, List.of("NONE"),
                                new Input(Attrs.id("minecraft-name-input"), "text",
                                        key(PREFIX + "minecraft.name_placeholder"), null, null, null, null)),
                        minecraftCase("casePending", status, List.of("PENDING"),
                                new Text(Attrs.NONE, false, key(PREFIX + "minecraft.pending"))),
                        minecraftCase("caseRejected", status, List.of("INVITE_REJECTED"),
                                new Text(Attrs.NONE, false, key(PREFIX + "minecraft.rejected"))),
                        minecraftCase("caseAccepted", status, List.of("ACCEPTED"),
                                new Chip(Attrs.state(SpaState.sync("textContent",
                                        "MinecraftEnrollEvent.minecraftName", "MinecraftSyncEvent.minecraftName")),
                                        Message.literal(minecraft.minecraftName()))),
                        minecraftCase("caseRemoved", status, List.of("REMOVED"),
                                new Text(Attrs.NONE, false, key(PREFIX + "minecraft.removed"))),
                        // No link, and no service account with room for one: nothing to do but wait.
                        minecraftCase("caseFull", status, List.of("FULL"),
                                new Text(Attrs.NONE, false, key(PREFIX + "minecraft.full"))))),

                // Each button needs its own flag name even though they're all page-level:
                // Visibility.apply looks elements up by data-if name alone, so buttons sharing
                // one name would all move in lockstep.
                new Container(Attrs.id("minecraft-status-actions").withClass("card-actions"),
                        Container.Kind.SEGMENTED_GROUP, List.of(
                        segmented(Attrs.id("minecraft-enroll-btn")
                                        .withState(minecraftState("showEnroll", status, List.of("NONE"))),
                                key(PREFIX + "minecraft.enroll")),
                        segmented(Attrs.id("minecraft-check-btn")
                                        .withState(minecraftState("showCheck", status, List.of("PENDING", "REMOVED"))),
                                key(PREFIX + "minecraft.check")),
                        segmented(Attrs.id("minecraft-disconnect-btn")
                                        .withState(minecraftState("showDisconnect", status, List.of("ACCEPTED"))),
                                key(PREFIX + "minecraft.disconnect")))));
    }

    private static Container minecraftCase(String flag, String status, List<String> statuses, IntegrationNode content) {
        return new Container(Attrs.state(minecraftState(flag, status, statuses)), Container.Kind.DIV, List.of(content));
    }

    private static SpaState minecraftState(String flag, String status, List<String> statuses) {
        String values = String.join("/", statuses);
        boolean showsNone = statuses.contains("NONE");
        return SpaState.flag(flag, statuses.contains(status),
                "MinecraftEnrollEvent.status=" + values,
                "MinecraftSyncEvent.status=" + values,
                (showsNone ? "" : "!") + "MinecraftDisconnectedEvent");
    }

    // OBS: no SSE event either, channel-connections.js refreshes the page after saving.

    private static IntegrationDto obs(ObsData obs) {
        return card("obs",
                new Chip(Attrs.cls("obs-connected-chip").hiddenIf(!obs.connected()), CONNECTED),
                new Chip(Attrs.cls("obs-not-connected-chip").hiddenIf(obs.connected()), NOT_CONNECTED),
                new Container(Attrs.id("obs-status-body"), Container.Kind.DIV, List.of(
                        new Container(Attrs.cls("obs-fields"), Container.Kind.DIV, List.of(
                                new Input(Attrs.id("obs-host"), "text", key(PREFIX + "obs.host"), null,
                                        obs.host(), null, null),
                                new Input(Attrs.id("obs-port"), "number", key(PREFIX + "obs.port"), null,
                                        obs.port(), 1, 65535),
                                // The stored password is never sent back: only hint that one exists.
                                new Input(Attrs.id("obs-password"), "password",
                                        obs.hasPassword() ? Message.literal("••••••••") : key(PREFIX + "obs.password"),
                                        key(PREFIX + "obs.password"), null, null, null))))),
                new Container(Attrs.id("obs-actions").withClass("card-actions"), Container.Kind.DIV, List.of(
                        new Button(Attrs.id("obs-connect-btn").hiddenIf(obs.connected()), Button.Kind.FILLED,
                                key(PREFIX + "obs.connect")),
                        new Button(Attrs.id("obs-disconnect-btn").hiddenIf(!obs.connected()), Button.Kind.FILLED,
                                key(PREFIX + "obs.disconnect")))));
    }

    // Twitchat: only the browser knows whether a Twitchat answers through OBS, so
    // channel-connections.js toggles the chips.

    private static IntegrationDto twitchat(TwitchatData twitchat, IntegrationNode events) {
        List<Select.Option> branches = Stream.concat(
                Stream.of(new Select.Option(TwitchatData.AUTO, key(PREFIX + "twitchat.auto"))),
                twitchat.supportedBranches().stream().map(branch -> new Select.Option(branch, Message.literal(branch))))
                .toList();

        return card("twitchat",
                new Chip(Attrs.cls("twitchat-connected-chip").hiddenIf(true), CONNECTED),
                new Chip(Attrs.cls("twitchat-disconnected-chip"), key(PREFIX + "twitchat.disconnected")),
                new Container(Attrs.id("twitchat-status-body"), Container.Kind.DIV, List.of(
                        new Container(Attrs.cls("twitchat-fields"), Container.Kind.DIV, List.of(
                                new Select(Attrs.id("twitchat-branch"), key(PREFIX + "twitchat.branch"),
                                        twitchat.branch(), branches, null))))),
                new Text(Attrs.cls("twitchat-events-title"), true, key(PREFIX + "twitchat.events")),
                events);
    }

    private static Button segmented(Attrs attrs, Message text) {
        return new Button(attrs, Button.Kind.SEGMENTED, text);
    }
}
