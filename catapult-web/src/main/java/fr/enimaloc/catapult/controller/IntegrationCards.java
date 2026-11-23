package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.channel.ChannelPageData;
import fr.enimaloc.catapult.common.dto.channel.AnkamaData;
import fr.enimaloc.catapult.common.dto.channel.BattleNetData;
import fr.enimaloc.catapult.common.dto.channel.BlizzardStatsData;
import fr.enimaloc.catapult.common.dto.channel.FaceitData;
import fr.enimaloc.catapult.common.dto.channel.BungieAccountData;
import fr.enimaloc.catapult.common.dto.channel.RiotData;
import fr.enimaloc.catapult.common.dto.channel.EpicData;
import fr.enimaloc.catapult.common.dto.channel.MinecraftData;
import fr.enimaloc.catapult.common.dto.channel.ObsData;
import fr.enimaloc.catapult.common.dto.channel.RetroAchievementsData;
import fr.enimaloc.catapult.common.dto.channel.ChessData;
import fr.enimaloc.catapult.common.dto.channel.SpeedrunData;
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
import fr.enimaloc.catapult.event.blizzard.BlizzardAccountChangedEvent;
import fr.enimaloc.catapult.event.bungie.BungieAccountChangedEvent;

import java.util.ArrayList;
import java.util.Comparator;
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
        cards.add(twitch());
        if (ch.steam() != null) {
            cards.add(steam(ch.steam()));
        }
        if (ch.xbox() != null) {
            cards.add(xbox(ch.xbox()));
        }
        if (ch.minecraft() != null) {
            cards.add(minecraft(ch.minecraft()));
        }
        if (ch.battleNet() != null) {
            cards.add(battleNet(ch.battleNet()));
        }
        if (ch.ankama() != null) {
            cards.add(ankama(ch.ankama()));
        }
        if (ch.epic() != null) {
            cards.add(epic(ch.epic()));
        }
        if (ch.speedrun() != null) {
            cards.add(speedrun(ch.speedrun()));
        }
        if (ch.retroAchievements() != null) {
            cards.add(retroAchievements(ch.retroAchievements()));
        }
        if (ch.blizzardStats() != null) {
            cards.add(blizzardStats(ch.blizzardStats()));
        }
        if (ch.bungie() != null) {
            cards.add(bungie(ch.bungie()));
        }
        if (ch.riot() != null) {
            cards.add(riot(ch.riot()));
        }
        if (ch.faceit() != null) {
            cards.add(faceit(ch.faceit()));
        }
        if (ch.chess() != null) {
            cards.add(chess(ch.chess()));
        }
        if (ch.obs() != null) {
            cards.add(obs(ch.obs()));
        }
        if (ch.twitchat() != null) {
            cards.add(twitchat(ch.twitchat(), forms.eventsSection(twitchatEvents)));
        }
        // The grid's cards first, the wide ones below them: the page reads in the order it shows (stable sort).
        cards.sort(Comparator.comparing(IntegrationDto::wide));
        return cards;
    }

    /** A card of the grid: it never shows more than two buttons at once. */
    private static IntegrationDto card(String id, IntegrationNode... nodes) {
        return new IntegrationDto(id, key(PREFIX + id), List.of(nodes), false);
    }

    /** A card spanning the grid, below the others: more buttons at once, or a form that needs the width. */
    private static IntegrationDto wideCard(String id, IntegrationNode... nodes) {
        return new IntegrationDto(id, key(PREFIX + id), List.of(nodes), true);
    }

    static IntegrationDto twitch() {
        return card("twitch", new Chip(Attrs.NONE, CONNECTED));
    }

    // Steam

    private static final String STEAM_STATE = "SteamConnectionStateEvent";

    // Wide: once connected, its unlink, token and cache buttons all show.
    static IntegrationDto steam(SteamData steam) {
        SpaState connected = SpaState.flag("connected", steam.connected(), STEAM_STATE + ".connected");
        SpaState notConnected = SpaState.flag("notConnected", !steam.connected(), STEAM_STATE + ".!connected");
        SpaState hasToken = SpaState.flag("hasToken", steam.hasPersonalToken(),
                "SteamTokenSavedEvent", "!SteamTokenDeletedEvent");
        SpaState noToken = SpaState.flag("noToken", !steam.hasPersonalToken(),
                "SteamTokenDeletedEvent", "!SteamTokenSavedEvent");

        return wideCard("steam",
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

    // Xbox: both states' chips and buttons, the other one's hidden; channel-connections.js flips them on
    // the connection's ChannelConnectionChangedEvent (no spa:state: that event names any provider).
    // Linking goes through Microsoft's login (catapult-api's /oauth2/authorization/xbox), unlinking is
    // a post: both channel-connections.js's.

    static IntegrationDto xbox(XboxData xbox) {
        return card("xbox",
                new Chip(Attrs.cls("xbox-connected-chip").hiddenIf(!xbox.connected()), CONNECTED),
                new Chip(Attrs.cls("xbox-not-connected-chip").hiddenIf(xbox.connected()), NOT_CONNECTED),
                new Container(Attrs.id("xbox-link-actions").withClass("card-actions"), Container.Kind.DIV, List.of(
                        new Button(Attrs.id("xbox-connect-btn").hiddenIf(xbox.connected()), Button.Kind.FILLED,
                                key(PREFIX + "xbox.connect")),
                        new Button(Attrs.id("xbox-disconnect-btn").hiddenIf(!xbox.connected()), Button.Kind.TEXT,
                                key(PREFIX + "xbox.disconnect")))));
    }

    // Minecraft: one sub-view and button per status, driven by the status field of
    // MinecraftEnrollEvent/MinecraftSyncEvent. MinecraftDisconnectedEvent carries no field at
    // all, its mere occurrence always means NONE.

    static IntegrationDto minecraft(MinecraftData minecraft) {
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
                // one name would all move in lockstep. Like the Battle.net and Ankama cards: a filled
                // link button, text ones for the rest.
                new Container(Attrs.id("minecraft-status-actions").withClass("card-actions"),
                        Container.Kind.DIV, List.of(
                        new Button(Attrs.id("minecraft-enroll-btn")
                                        .withState(minecraftState("showEnroll", status, List.of("NONE"))),
                                Button.Kind.FILLED, key(PREFIX + "minecraft.enroll")),
                        new Button(Attrs.id("minecraft-check-btn")
                                        .withState(minecraftState("showCheck", status, List.of("PENDING", "REMOVED"))),
                                Button.Kind.TEXT, key(PREFIX + "minecraft.check")),
                        new Button(Attrs.id("minecraft-disconnect-btn")
                                        .withState(minecraftState("showDisconnect", status, List.of("ACCEPTED"))),
                                Button.Kind.TEXT, key(PREFIX + "minecraft.disconnect")))));
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

    // Battle.net: one sub-view and button per status, driven by the status field of BattleNetLinkEvent,
    // which carries NONE too. Linking goes through Blizzard's login (catapult-api's
    // /oauth2/authorization/battlenet), so a rejected or removed link offers the same button as none at all.
    // The tags are sentence parts of their own so that the event can replace them in place.

    static IntegrationDto battleNet(BattleNetData battleNet) {
        String status = battleNet.status() == null ? "NONE" : battleNet.status();
        return card("battlenet",
                new Container(Attrs.id("battlenet-status-body"), Container.Kind.DIV, List.of(
                        battleNetCase("caseNone", status, List.of("NONE"),
                                new Text(Attrs.NONE, false, key(PREFIX + "battlenet.none"))),
                        battleNetCase("casePending", status, List.of("PENDING"),
                                new Container(Attrs.cls("battlenet-sentence"), Container.Kind.DIV, List.of(
                                        new Text(Attrs.NONE, false, key(PREFIX + "battlenet.pending.before")),
                                        battleNetTag("battlenet-bot-tag", "serviceAccountBattleTag",
                                                battleNet.serviceAccountBattleTag()),
                                        new Text(Attrs.NONE, false, key(PREFIX + "battlenet.pending.after"))))),
                        battleNetCase("caseRejected", status, List.of("INVITE_REJECTED"),
                                new Text(Attrs.NONE, false, key(PREFIX + "battlenet.rejected"))),
                        battleNetCase("caseAccepted", status, List.of("ACCEPTED"),
                                new Container(Attrs.cls("battlenet-sentence"), Container.Kind.DIV, List.of(
                                        new Text(Attrs.NONE, false, key(PREFIX + "battlenet.accepted")),
                                        battleNetTag("battlenet-tag", "battleTag", battleNet.battleTag())))),
                        battleNetCase("caseRemoved", status, List.of("REMOVED"),
                                new Text(Attrs.NONE, false, key(PREFIX + "battlenet.removed"))),
                        // No service account with room for one: nothing to do but wait.
                        battleNetCase("caseFull", status, List.of("FULL"),
                                new Text(Attrs.NONE, false, key(PREFIX + "battlenet.full"))))),

                // An OAuth login, so it looks like the Xbox card's actions: filled connect, text disconnect.
                // Each button needs its own flag name, as the Minecraft card's do.
                new Container(Attrs.id("battlenet-status-actions").withClass("card-actions"),
                        Container.Kind.DIV, List.of(
                        new Button(Attrs.id("battlenet-connect-btn")
                                        .withState(battleNetState("showConnect", status,
                                                List.of("NONE", "INVITE_REJECTED", "REMOVED"))),
                                Button.Kind.FILLED, key(PREFIX + "battlenet.connect")),
                        new Button(Attrs.id("battlenet-disconnect-btn")
                                        .withState(battleNetState("showDisconnect", status, List.of("ACCEPTED"))),
                                Button.Kind.TEXT, key(PREFIX + "battlenet.disconnect")))));
    }

    private static Text battleNetTag(String className, String field, String tag) {
        return new Text(Attrs.cls(className).withState(SpaState.sync("textContent", "BattleNetLinkEvent." + field)),
                false, Message.literal(tag));
    }

    private static Container battleNetCase(String flag, String status, List<String> statuses, IntegrationNode content) {
        return new Container(Attrs.state(battleNetState(flag, status, statuses)), Container.Kind.DIV, List.of(content));
    }

    private static SpaState battleNetState(String flag, String status, List<String> statuses) {
        return SpaState.flag(flag, statuses.contains(status), "BattleNetLinkEvent.status=" + String.join("/", statuses));
    }

    // Ankama: one sub-view and button per status, driven by the status field of AnkamaLinkEvent, which carries NONE
    // too. Linking is a typed Pseudo#tag the streamer enters (channel-connections.js checks it and posts it), so a
    // rejected or removed link shows the same form as none at all. No "check" button: the bot's chat tells the
    // card by itself. The tags are sentence parts of their own so that the event can replace them in place.

    static IntegrationDto ankama(AnkamaData ankama) {
        String status = ankama.status() == null ? "NONE" : ankama.status();
        List<String> formStatuses = List.of("NONE", "INVITE_REJECTED", "REMOVED");
        return card("ankama",
                new Container(Attrs.id("ankama-status-body"), Container.Kind.DIV, List.of(
                        ankamaCase("caseNone", status, List.of("NONE"),
                                new Text(Attrs.NONE, false, key(PREFIX + "ankama.none"))),
                        ankamaCase("casePending", status, List.of("PENDING"),
                                new Container(Attrs.cls("ankama-sentence"), Container.Kind.DIV, List.of(
                                        new Text(Attrs.NONE, false, key(PREFIX + "ankama.pending.before")),
                                        ankamaTag("ankama-bot-tag", "serviceAccountTag", ankama.serviceAccountTag()),
                                        new Text(Attrs.NONE, false, key(PREFIX + "ankama.pending.after"))))),
                        ankamaCase("caseRejected", status, List.of("INVITE_REJECTED"),
                                new Text(Attrs.NONE, false, key(PREFIX + "ankama.rejected"))),
                        ankamaCase("caseAccepted", status, List.of("ACCEPTED"),
                                new Container(Attrs.cls("ankama-sentence"), Container.Kind.DIV, List.of(
                                        new Text(Attrs.NONE, false, key(PREFIX + "ankama.accepted")),
                                        ankamaTag("ankama-tag", "nameTag", ankama.nameTag())))),
                        ankamaCase("caseRemoved", status, List.of("REMOVED"),
                                new Text(Attrs.NONE, false, key(PREFIX + "ankama.removed"))),
                        // No service account with room for one: nothing to do but wait.
                        ankamaCase("caseFull", status, List.of("FULL"),
                                new Text(Attrs.NONE, false, key(PREFIX + "ankama.full"))),
                        new Input(Attrs.id("ankama-name-input")
                                        .withState(ankamaState("showInput", status, formStatuses)),
                                "text", key(PREFIX + "ankama.name_placeholder"), key(PREFIX + "ankama.name_label"),
                                null, null, null))),

                // Each button needs its own flag name, as the Minecraft card's do.
                new Container(Attrs.id("ankama-status-actions").withClass("card-actions"),
                        Container.Kind.DIV, List.of(
                        new Button(Attrs.id("ankama-connect-btn")
                                        .withState(ankamaState("showConnect", status, formStatuses)),
                                Button.Kind.FILLED, key(PREFIX + "ankama.connect")),
                        new Button(Attrs.id("ankama-resend-btn")
                                        .withState(ankamaState("showResend", status, List.of("PENDING"))),
                                Button.Kind.TEXT, key(PREFIX + "ankama.resend")),
                        new Button(Attrs.id("ankama-disconnect-btn")
                                        .withState(ankamaState("showDisconnect", status, List.of("PENDING", "ACCEPTED"))),
                                Button.Kind.TEXT, key(PREFIX + "ankama.disconnect")))));
    }

    private static Text ankamaTag(String className, String field, String tag) {
        return new Text(Attrs.cls(className).withState(SpaState.sync("textContent", "AnkamaLinkEvent." + field)),
                false, Message.literal(tag));
    }

    private static Container ankamaCase(String flag, String status, List<String> statuses, IntegrationNode content) {
        return new Container(Attrs.state(ankamaState(flag, status, statuses)), Container.Kind.DIV, List.of(content));
    }

    private static SpaState ankamaState(String flag, String status, List<String> statuses) {
        return SpaState.flag(flag, statuses.contains(status), "AnkamaLinkEvent.status=" + String.join("/", statuses));
    }

    // Speedrun.com: not a link but two optional settings, the name the member declares and a personal API key. The
    // account used (the key's owner, else the Twitch-linked profile, else the declared name) shows as a sentence per
    // source, its name a part of its own so that SpeedrunAccountChangedEvent replaces it in place. Wide: two forms.
    // The key is never in the page: only whether one is saved, so that its field gives way to the delete button.

    private static final String SPEEDRUN_EVENT = "SpeedrunAccountChangedEvent";

    static IntegrationDto speedrun(SpeedrunData speedrun) {
        boolean hasKey = speedrun.hasApiKey();
        boolean hasName = speedrun.declaredUsername() != null && !speedrun.declaredUsername().isBlank();
        SpaState hasNameState = SpaState.flag("speedrunHasName", hasName, SPEEDRUN_EVENT + ".declaredUsername");
        SpaState hasKeyState = SpaState.flag("speedrunHasKey", hasKey, SPEEDRUN_EVENT + ".hasApiKey");
        SpaState noKeyState = SpaState.flag("speedrunNoKey", !hasKey, SPEEDRUN_EVENT + ".!hasApiKey");
        String resolvedName = speedrun.resolvedName() == null ? "" : speedrun.resolvedName();

        return wideCard("speedrun",
                new Container(Attrs.id("speedrun-account-body"), Container.Kind.DIV, List.of(
                        speedrunVia("speedrunViaKey", "api_key", "speedrun.via_key", speedrun, resolvedName),
                        speedrunVia("speedrunViaTwitch", "twitch", "speedrun.via_twitch", speedrun, resolvedName),
                        speedrunVia("speedrunViaDeclared", "declared", "speedrun.via_declared", speedrun, resolvedName),
                        new Text(Attrs.cls("speedrun-none").withState(SpaState.flag("speedrunNoAccount",
                                speedrun.resolvedSource() == null, SPEEDRUN_EVENT + ".!resolvedSource")),
                                false, key(PREFIX + "speedrun.none")))),
                new Text(Attrs.cls("speedrun-key-invalid").withState(SpaState.flag("speedrunKeyInvalid",
                        speedrun.keyInvalid(), SPEEDRUN_EVENT + ".keyInvalid")), true,
                        key(PREFIX + "speedrun.key.invalid")),

                new Input(Attrs.id("speedrun-username-input").withState(SpaState.sync("value",
                        SPEEDRUN_EVENT + ".declaredUsername")), "text", key(PREFIX + "speedrun.username.placeholder"),
                        null, speedrun.declaredUsername(), null, null),
                new Container(Attrs.id("speedrun-username-actions").withClass("card-actions"),
                        Container.Kind.SEGMENTED_GROUP, List.of(
                        segmented(Attrs.id("speedrun-username-save-btn"), key(PREFIX + "speedrun.username.save")),
                        segmented(Attrs.id("speedrun-username-delete-btn").withState(hasNameState),
                                key(PREFIX + "speedrun.username.delete")))),

                new Text(Attrs.cls("speedrun-key-saved").withState(hasKeyState), false,
                        key(PREFIX + "speedrun.key.saved")),
                new Input(Attrs.id("speedrun-key-input").withState(noKeyState), "password",
                        key(PREFIX + "speedrun.key.placeholder"), null, null, null, null, "new-password"),
                new Container(Attrs.id("speedrun-key-actions").withClass("card-actions"),
                        Container.Kind.SEGMENTED_GROUP, List.of(
                        segmented(Attrs.id("speedrun-key-save-btn").withState(noKeyState),
                                key(PREFIX + "speedrun.key.save")),
                        segmented(Attrs.id("speedrun-key-delete-btn").withState(hasKeyState),
                                key(PREFIX + "speedrun.key.delete")))),

                new Text(Attrs.cls("speedrun-help"), true, key(PREFIX + "speedrun.help")));
    }

    /** "Account used: NAME (via ...)", shown while the account is {@code source}'s. */
    private static Container speedrunVia(String flag, String source, String via, SpeedrunData speedrun,
                                         String resolvedName) {
        SpaState shown = SpaState.flag(flag, source.equals(speedrun.resolvedSource()),
                SPEEDRUN_EVENT + ".resolvedSource=" + source);
        return new Container(Attrs.cls("speedrun-account").withState(shown), Container.Kind.DIV, List.of(
                new Text(Attrs.NONE, false, key(PREFIX + "speedrun.account_used")),
                new Chip(Attrs.cls("speedrun-account-name").withState(
                        SpaState.sync("textContent", SPEEDRUN_EVENT + ".resolvedName")), Message.literal(resolvedName)),
                new Text(Attrs.NONE, false, key(PREFIX + via))));
    }

    // RetroAchievements: two optional settings like speedrun.com's, the name the member declares and a personal web API
    // key (their own quota; it does not prove the account). The account shows as a chip whose name is a part of its
    // own so that RetroAchievementsAccountChangedEvent replaces it in place. Wide: two forms. The key is never in
    // the page: only whether one is saved, so that its field gives way to the delete button.

    private static final String RA_EVENT = "RetroAchievementsAccountChangedEvent";

    static IntegrationDto retroAchievements(RetroAchievementsData ra) {
        boolean hasKey = ra.hasApiKey();
        boolean hasName = ra.username() != null && !ra.username().isBlank();
        SpaState hasNameState = SpaState.flag("raHasName", hasName, RA_EVENT + ".declaredUsername");
        SpaState hasKeyState = SpaState.flag("raHasKey", hasKey, RA_EVENT + ".hasApiKey");
        SpaState noKeyState = SpaState.flag("raNoKey", !hasKey, RA_EVENT + ".!hasApiKey");
        // The shared key is what answers a member without a key of their own: without it, they are asked for theirs.
        SpaState noPoolState = SpaState.flag("raNoPool", !ra.poolAvailable(), RA_EVENT + ".!poolAvailable")
                .and("raNoKeyOfTheirOwn", !hasKey, RA_EVENT + ".!hasApiKey");

        return wideCard("retroachievements",
                new Container(Attrs.cls("retroachievements-account").withState(hasNameState), Container.Kind.DIV,
                        List.of(new Text(Attrs.NONE, false, key(PREFIX + "retroachievements.account")),
                                new Chip(Attrs.cls("retroachievements-account-name").withState(
                                        SpaState.sync("textContent", RA_EVENT + ".declaredUsername")),
                                        Message.literal(hasName ? ra.username() : "")))),
                new Text(Attrs.cls("retroachievements-none").withState(SpaState.flag("raNoAccount", !hasName,
                        RA_EVENT + ".!declaredUsername")), false, key(PREFIX + "retroachievements.none")),
                new Text(Attrs.cls("retroachievements-key-invalid").withState(SpaState.flag("raKeyInvalid",
                        ra.keyInvalid(), RA_EVENT + ".keyInvalid")), true,
                        key(PREFIX + "retroachievements.key.invalid")),
                new Text(Attrs.cls("retroachievements-no-pool").withState(noPoolState), true,
                        key(PREFIX + "retroachievements.no_pool")),

                new Input(Attrs.id("retroachievements-username-input").withState(SpaState.sync("value",
                        RA_EVENT + ".declaredUsername")), "text",
                        key(PREFIX + "retroachievements.username.placeholder"), null, ra.username(), null, null),
                new Container(Attrs.id("retroachievements-username-actions").withClass("card-actions"),
                        Container.Kind.SEGMENTED_GROUP, List.of(
                        segmented(Attrs.id("retroachievements-username-save-btn"),
                                key(PREFIX + "retroachievements.username.save")),
                        segmented(Attrs.id("retroachievements-username-delete-btn").withState(hasNameState),
                                key(PREFIX + "retroachievements.username.delete")))),

                new Text(Attrs.cls("retroachievements-key-saved").withState(hasKeyState), false,
                        key(PREFIX + "retroachievements.key.saved")),
                new Input(Attrs.id("retroachievements-key-input").withState(noKeyState), "password",
                        key(PREFIX + "retroachievements.key.placeholder"), null, null, null, null, "new-password"),
                new Container(Attrs.id("retroachievements-key-actions").withClass("card-actions"),
                        Container.Kind.SEGMENTED_GROUP, List.of(
                        segmented(Attrs.id("retroachievements-key-save-btn").withState(noKeyState),
                                key(PREFIX + "retroachievements.key.save")),
                        segmented(Attrs.id("retroachievements-key-delete-btn").withState(hasKeyState),
                                key(PREFIX + "retroachievements.key.delete")))),

                new Text(Attrs.cls("retroachievements-help"), true, key(PREFIX + "retroachievements.help")));
    }

    // Chess: the Chess.com and the Lichess names the member declares, each checked with its site on saving. Each
    // account shows as a chip whose name is a part of its own so that ChessAccountChangedEvent replaces it in place.
    // Wide: two forms.

    private static final String CHESS_EVENT = "ChessAccountChangedEvent";

    static IntegrationDto chess(ChessData chess) {
        return wideCard("chess",
                chessSite("chesscom", chess.chesscomUsername(), "chesscomUsername"),
                chessSite("lichess", chess.lichessUsername(), "lichessUsername"),
                new Text(Attrs.cls("chess-help"), true, key(PREFIX + "chess.help")));
    }

    /** One site's account chip (or "none"), name field and its save / remove buttons. */
    private static Container chessSite(String site, String name, String field) {
        boolean has = name != null && !name.isBlank();
        String flag = "chess" + Character.toUpperCase(site.charAt(0)) + site.substring(1);
        SpaState hasState = SpaState.flag(flag + "HasName", has, CHESS_EVENT + "." + field);
        return new Container(Attrs.cls("chess-site").withClass("chess-" + site), Container.Kind.DIV, List.of(
                new Container(Attrs.cls("chess-" + site + "-account").withState(hasState), Container.Kind.DIV,
                        List.of(new Text(Attrs.NONE, false, key(PREFIX + "chess." + site + ".account")),
                                new Chip(Attrs.cls("chess-" + site + "-account-name").withState(
                                        SpaState.sync("textContent", CHESS_EVENT + "." + field)),
                                        Message.literal(has ? name : "")))),
                new Text(Attrs.cls("chess-" + site + "-none").withState(SpaState.flag(flag + "NoName", !has,
                        CHESS_EVENT + ".!" + field)), false, key(PREFIX + "chess." + site + ".none")),
                new Input(Attrs.id("chess-" + site + "-username-input").withState(SpaState.sync("value",
                        CHESS_EVENT + "." + field)), "text", key(PREFIX + "chess." + site + ".placeholder"), null,
                        name, null, null),
                new Container(Attrs.id("chess-" + site + "-actions").withClass("card-actions"),
                        Container.Kind.SEGMENTED_GROUP, List.of(
                        segmented(Attrs.id("chess-" + site + "-save-btn"), key(PREFIX + "chess.save")),
                        segmented(Attrs.id("chess-" + site + "-delete-btn").withState(hasState),
                                key(PREFIX + "chess.delete"))))));
    }

    // Blizzard stats: the settings of the owner's Blizzard stats (the region of their Battle.net account, the imported
    // World of Warcraft characters and the one that is their main). It needs the Battle.net link: without one the card
    // only says so. Importing leaves for Blizzard's login (catapult-api's /oauth2/authorization/battlenet-stats), which
    // sends the member back to this tab; it is not offered when the instance has no Blizzard API set up. The imported
    // list is read once, by the page: BlizzardAccountChangedEvent flips what shows and the values picked, and an import
    // reloads the tab. Wide: two selects and their buttons. Never a token, never an internal id.

    private static final String BLIZZARD_EVENT = "BlizzardAccountChangedEvent";
    private static final String BLIZZARD_PREFIX = PREFIX + "blizzard-stats.";

    static IntegrationDto blizzardStats(BlizzardStatsData blizzard) {
        boolean imported = blizzard.wowImported();
        SpaState linkedState = SpaState.flag("blizzardLinked", blizzard.linked(), BLIZZARD_EVENT + ".linked");
        SpaState importedState = SpaState.flag("blizzardImported", imported, BLIZZARD_EVENT + ".wowImported");
        // The import needs the Blizzard API: without it the login would fail, so its buttons stay out.
        SpaState importState = SpaState.flag("blizzardCanImport", blizzard.available(), BLIZZARD_EVENT + ".available")
                .and("blizzardNotImported", !imported, BLIZZARD_EVENT + ".!wowImported");
        SpaState reimportState = SpaState.flag("blizzardCanReimport", blizzard.available(),
                BLIZZARD_EVENT + ".available").and("blizzardWasImported", imported, BLIZZARD_EVENT + ".wowImported");
        List<Select.Option> regions = Stream.of("eu", "us", "kr", "tw")
                .map(region -> new Select.Option(region, literal(region.toUpperCase(java.util.Locale.ROOT)))).toList();
        List<Select.Option> characters = blizzard.characters().stream()
                .map(character -> new Select.Option(character.key(), literal(character.label()))).toList();

        return wideCard("blizzard-stats",
                new Text(Attrs.cls("blizzard-stats-unlinked").withState(SpaState.flag("blizzardUnlinked",
                        !blizzard.linked(), BLIZZARD_EVENT + ".!linked")), true, key(BLIZZARD_PREFIX + "unlinked")),
                new Container(Attrs.id("blizzard-stats-body").withState(linkedState), Container.Kind.DIV, List.of(
                        new Select(Attrs.id("blizzard-region-select").withState(
                                SpaState.sync("value", BLIZZARD_EVENT + ".region")),
                                key(BLIZZARD_PREFIX + "region"), blizzard.region() == null ? "" : blizzard.region(),
                                regions, null),
                        new Container(Attrs.cls("blizzard-stats-imported").withState(importedState),
                                Container.Kind.DIV, List.of(
                                new Text(Attrs.NONE, false, key(BLIZZARD_PREFIX + "imported")),
                                new Chip(Attrs.cls("blizzard-stats-imported-on").withState(
                                        SpaState.sync("textContent", BLIZZARD_EVENT + ".importedOn")),
                                        literal(BlizzardAccountChangedEvent.importedOn(blizzard.wowImportedAt()))))),
                        new Select(Attrs.id("blizzard-main-select").withState(
                                SpaState.flag("blizzardHasCharacters", !characters.isEmpty(),
                                        BLIZZARD_EVENT + ".hasCharacters")
                                        .andSync("value", BLIZZARD_EVENT + ".wowMainKey")),
                                key(BLIZZARD_PREFIX + "main"),
                                blizzard.wowMainKey() == null ? "" : blizzard.wowMainKey(), characters, null),
                        new Text(Attrs.cls("blizzard-stats-unavailable").withState(SpaState.flag("blizzardUnavailable",
                                !blizzard.available(), BLIZZARD_EVENT + ".!available")), true,
                                key(BLIZZARD_PREFIX + "unavailable")),
                        new Container(Attrs.id("blizzard-stats-actions").withClass("card-actions"),
                                Container.Kind.DIV, List.of(
                                new Button(Attrs.id("blizzard-import-btn").withState(importState),
                                        Button.Kind.FILLED, key(BLIZZARD_PREFIX + "import")),
                                new Button(Attrs.id("blizzard-reimport-btn").withState(reimportState),
                                        Button.Kind.TEXT, key(BLIZZARD_PREFIX + "reimport")),
                                new Button(Attrs.id("blizzard-forget-btn").withState(importedState),
                                        Button.Kind.TEXT, key(BLIZZARD_PREFIX + "forget")))),
                        new Text(Attrs.cls("blizzard-stats-help"), true, key(BLIZZARD_PREFIX + "help")))));
    }

    // Bungie: the owner's Bungie.net link (Destiny 2 stats). Linking leaves for Bungie's login (catapult-api's
    // /oauth2/authorization/bungie), which sends the member back to this tab; it is only offered while the instance
    // has a Bungie application set (otherwise the login does not exist). A link Bungie refused since asks to link
    // again. BungieAccountChangedEvent flips everything. Never a token, never an internal id.

    private static final String BUNGIE_EVENT = "BungieAccountChangedEvent";
    private static final String BUNGIE_PREFIX = PREFIX + "bungie.";

    static IntegrationDto bungie(BungieAccountData bungie) {
        BungieAccountChangedEvent now = BungieAccountChangedEvent.of(null, bungie);
        return card("bungie",
                new Chip(Attrs.cls("bungie-connected-chip").withState(SpaState.flag("bungieUsable", now.usable(),
                        BUNGIE_EVENT + ".usable")), CONNECTED),
                new Chip(Attrs.cls("bungie-not-connected-chip").withState(SpaState.flag("bungieNotUsable",
                        !now.usable(), BUNGIE_EVENT + ".!usable")), NOT_CONNECTED),
                new Chip(Attrs.cls("bungie-account").withState(SpaState.flag("bungieNamed", now.usable(),
                        BUNGIE_EVENT + ".usable").andSync("textContent", BUNGIE_EVENT + ".account")),
                        literal(now.account())),
                new Text(Attrs.cls("bungie-invalid").withState(SpaState.flag("bungieInvalid", now.invalid(),
                        BUNGIE_EVENT + ".invalid")), true, key(BUNGIE_PREFIX + "invalid")),
                new Text(Attrs.cls("bungie-no-profile").withState(SpaState.flag("bungieNoProfile", now.noProfile(),
                        BUNGIE_EVENT + ".noProfile")), true, key(BUNGIE_PREFIX + "no_profile")),
                new Text(Attrs.cls("bungie-unavailable").withState(SpaState.flag("bungieUnavailable",
                        !now.available(), BUNGIE_EVENT + ".!available")), true, key(BUNGIE_PREFIX + "unavailable")),
                new Container(Attrs.id("bungie-link-actions").withClass("card-actions"), Container.Kind.DIV, List.of(
                        new Button(Attrs.id("bungie-connect-btn").withState(SpaState.flag("bungieCanLink",
                                now.canLink(), BUNGIE_EVENT + ".canLink")), Button.Kind.FILLED,
                                key(BUNGIE_PREFIX + "connect")),
                        new Button(Attrs.id("bungie-disconnect-btn").withState(SpaState.flag("bungieLinked",
                                now.linked(), BUNGIE_EVENT + ".linked")), Button.Kind.TEXT,
                                key(BUNGIE_PREFIX + "disconnect")))),
                new Text(Attrs.cls("bungie-help"), true, key(BUNGIE_PREFIX + "help")));
    }

    // Riot: the Riot ID the member declares (gameName#tagLine) and the League of Legends platform it plays on, saved
    // together; Riot checks the Riot ID before anything is kept. The declared one shows as a chip that
    // RiotAccountChangedEvent replaces in place. Without a key in the shared pool the card says the stats are off on
    // this instance (a save would be refused). Wide: a form. No secret is ever held for a member.

    private static final String RIOT_EVENT = "RiotAccountChangedEvent";
    private static final String RIOT_PREFIX = PREFIX + "riot.";
    public static final List<String> RIOT_PLATFORMS = List.of("euw1", "eun1", "tr1", "ru", "me1", "na1", "br1", "la1",
            "la2", "kr", "jp1", "oc1", "ph2", "sg2", "th2", "tw2", "vn2");

    static IntegrationDto riot(RiotData riot) {
        boolean hasId = riot.riotId() != null && !riot.riotId().isBlank();
        SpaState hasIdState = SpaState.flag("riotHasId", hasId, RIOT_EVENT + ".riotId");
        List<Select.Option> platforms = RIOT_PLATFORMS.stream()
                .map(p -> new Select.Option(p, literal(p.toUpperCase(java.util.Locale.ROOT)))).toList();
        return wideCard("riot",
                new Container(Attrs.cls("riot-account").withState(hasIdState), Container.Kind.DIV, List.of(
                        new Text(Attrs.NONE, false, key(RIOT_PREFIX + "account")),
                        new Chip(Attrs.cls("riot-account-name").withState(
                                SpaState.sync("textContent", RIOT_EVENT + ".riotId")),
                                Message.literal(hasId ? riot.riotId() : "")))),
                new Text(Attrs.cls("riot-none").withState(SpaState.flag("riotNoId", !hasId,
                        RIOT_EVENT + ".!riotId")), false, key(RIOT_PREFIX + "none")),
                new Text(Attrs.cls("riot-unavailable").withState(SpaState.flag("riotUnavailable",
                        !riot.available(), RIOT_EVENT + ".!available")), true, key(RIOT_PREFIX + "unavailable")),
                new Input(Attrs.id("riot-id-input").withState(SpaState.sync("value", RIOT_EVENT + ".riotId")),
                        "text", key(RIOT_PREFIX + "id.placeholder"), null, riot.riotId(), null, null, "off"),
                new Select(Attrs.id("riot-platform-select").withState(
                        SpaState.sync("value", RIOT_EVENT + ".platform")),
                        key(RIOT_PREFIX + "platform"), riot.platform() == null ? "euw1" : riot.platform(),
                        platforms, null),
                new Container(Attrs.id("riot-actions").withClass("card-actions"), Container.Kind.SEGMENTED_GROUP,
                        List.of(segmented(Attrs.id("riot-save-btn"), key(RIOT_PREFIX + "save")),
                                segmented(Attrs.id("riot-delete-btn").withState(hasIdState),
                                        key(RIOT_PREFIX + "delete")))),
                new Text(Attrs.cls("riot-help"), true, key(RIOT_PREFIX + "help")));
    }

    // FACEIT: the nickname the member declares; FACEIT checks it before anything is kept. The declared one shows as a
    // chip that FaceitAccountChangedEvent replaces in place. Without a key in the shared pool the card says the stats
    // are off on this instance (a save would be refused). Wide: a form. No secret is ever held for a member.

    private static final String FACEIT_EVENT = "FaceitAccountChangedEvent";
    private static final String FACEIT_PREFIX = PREFIX + "faceit.";

    static IntegrationDto faceit(FaceitData faceit) {
        boolean hasName = faceit.nickname() != null && !faceit.nickname().isBlank();
        SpaState hasNameState = SpaState.flag("faceitHasName", hasName, FACEIT_EVENT + ".nickname");
        return wideCard("faceit",
                new Container(Attrs.cls("faceit-account").withState(hasNameState), Container.Kind.DIV, List.of(
                        new Text(Attrs.NONE, false, key(FACEIT_PREFIX + "account")),
                        new Chip(Attrs.cls("faceit-account-name").withState(
                                SpaState.sync("textContent", FACEIT_EVENT + ".nickname")),
                                Message.literal(hasName ? faceit.nickname() : "")))),
                new Text(Attrs.cls("faceit-none").withState(SpaState.flag("faceitNoName", !hasName,
                        FACEIT_EVENT + ".!nickname")), false, key(FACEIT_PREFIX + "none")),
                new Text(Attrs.cls("faceit-unavailable").withState(SpaState.flag("faceitUnavailable",
                        !faceit.available(), FACEIT_EVENT + ".!available")), true, key(FACEIT_PREFIX + "unavailable")),
                new Input(Attrs.id("faceit-nickname-input").withState(SpaState.sync("value",
                        FACEIT_EVENT + ".nickname")), "text", key(FACEIT_PREFIX + "nickname.placeholder"), null,
                        faceit.nickname(), null, null, "off"),
                new Container(Attrs.id("faceit-actions").withClass("card-actions"), Container.Kind.SEGMENTED_GROUP,
                        List.of(segmented(Attrs.id("faceit-save-btn"), key(FACEIT_PREFIX + "save")),
                                segmented(Attrs.id("faceit-delete-btn").withState(hasNameState),
                                        key(FACEIT_PREFIX + "delete")))),
                new Text(Attrs.cls("faceit-help"), true, key(FACEIT_PREFIX + "help")));
    }

    // Epic: one sub-view and button per status, driven by the status field of EpicLinkEvent, which carries NONE too.
    // Linking goes through Epic's login (catapult-api's /oauth2/authorization/epic), like Battle.net's, so a rejected
    // or removed link offers the same button as none at all; the bot's request can also be sent again while the link
    // waits (a declined one included), and the bot's chat tells the card the rest by itself, like Ankama's. A refused
    // login comes back to this tab with its reason, which channel-connections.js tells. The names are sentence parts
    // of their own so that the event can replace them in place.

    static IntegrationDto epic(EpicData epic) {
        String status = epic.status() == null ? "NONE" : epic.status();
        return card("epic",
                new Container(Attrs.id("epic-status-body"), Container.Kind.DIV, List.of(
                        epicCase("caseNone", status, List.of("NONE"),
                                new Text(Attrs.NONE, false, key(PREFIX + "epic.none"))),
                        epicCase("casePending", status, List.of("PENDING"),
                                new Container(Attrs.cls("epic-sentence"), Container.Kind.DIV, List.of(
                                        new Text(Attrs.NONE, false, key(PREFIX + "epic.pending.before")),
                                        epicName("epic-bot-name", "serviceAccountName", epic.serviceAccountName()),
                                        new Text(Attrs.NONE, false, key(PREFIX + "epic.pending.after"))))),
                        epicCase("caseRejected", status, List.of("INVITE_REJECTED"),
                                new Text(Attrs.NONE, false, key(PREFIX + "epic.rejected"))),
                        epicCase("caseAccepted", status, List.of("ACCEPTED"),
                                new Container(Attrs.cls("epic-sentence"), Container.Kind.DIV, List.of(
                                        new Text(Attrs.NONE, false, key(PREFIX + "epic.accepted")),
                                        epicName("epic-name", "displayName", epic.displayName())))),
                        epicCase("caseRemoved", status, List.of("REMOVED"),
                                new Text(Attrs.NONE, false, key(PREFIX + "epic.removed"))),
                        // No service account with room for one: nothing to do but wait.
                        epicCase("caseFull", status, List.of("FULL"),
                                new Text(Attrs.NONE, false, key(PREFIX + "epic.full"))))),

                // An OAuth login, so it looks like the Battle.net card's actions: filled connect, text for the rest.
                // Each button needs its own flag name, as the Minecraft card's do.
                new Container(Attrs.id("epic-status-actions").withClass("card-actions"),
                        Container.Kind.DIV, List.of(
                        new Button(Attrs.id("epic-connect-btn")
                                        .withState(epicState("showConnect", status,
                                                List.of("NONE", "INVITE_REJECTED", "REMOVED"))),
                                Button.Kind.FILLED, key(PREFIX + "epic.connect")),
                        new Button(Attrs.id("epic-resend-btn")
                                        .withState(epicState("showResend", status,
                                                List.of("PENDING", "INVITE_REJECTED"))),
                                Button.Kind.TEXT, key(PREFIX + "epic.resend")),
                        new Button(Attrs.id("epic-disconnect-btn")
                                        .withState(epicState("showDisconnect", status, List.of("PENDING", "ACCEPTED"))),
                                Button.Kind.TEXT, key(PREFIX + "epic.disconnect")))));
    }

    private static Text epicName(String className, String field, String name) {
        return new Text(Attrs.cls(className).withState(SpaState.sync("textContent", "EpicLinkEvent." + field)),
                false, Message.literal(name));
    }

    private static Container epicCase(String flag, String status, List<String> statuses, IntegrationNode content) {
        return new Container(Attrs.state(epicState(flag, status, statuses)), Container.Kind.DIV, List.of(content));
    }

    private static SpaState epicState(String flag, String status, List<String> statuses) {
        return SpaState.flag(flag, statuses.contains(status), "EpicLinkEvent.status=" + String.join("/", statuses));
    }

    // OBS: no SSE event either, channel-connections.js refreshes the page after saving. Wide: its three fields sit
    // side by side.

    static IntegrationDto obs(ObsData obs) {
        return wideCard("obs",
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
    // channel-connections.js toggles the chips. Wide: its events form needs the width.

    static IntegrationDto twitchat(TwitchatData twitchat, IntegrationNode events) {
        List<Select.Option> branches = Stream.concat(
                Stream.of(new Select.Option(TwitchatData.AUTO, key(PREFIX + "twitchat.auto"))),
                twitchat.supportedBranches().stream().map(branch -> new Select.Option(branch, Message.literal(branch))))
                .toList();

        return wideCard("twitchat",
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
