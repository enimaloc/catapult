/**
 * Handlers for the Steam/Minecraft/Battle.net/Ankama/Epic/Speedrun.com/RetroAchievements/Blizzard stats/Hypixel/Riot/FACEIT/Xbox/OBS connection cards. The Steam/Minecraft cards' elements are all
 * pre-rendered and only toggled via the `hidden` class (spa:if/spa:switch, see
 * channel.html) instead of being created/destroyed per SSE event, so handlers only
 * need attaching once, at render — no refresh() either: the SSE echo updates the cards.
 * The Xbox card's are too, flipped by showXboxConnected() on its ChannelConnectionChangedEvent
 * (channel-events.js): that event names any provider, which a spa:* rule can't tell apart.
 */
window.CatapultConnections = (function () {
    // Twitchat's chips: whether a Twitchat answers is only known here, by twitchat.js through OBS.
    function showTwitchatPresence() {
        const connected = window.CatapultTwitchat?.isTwitchatConnected() ?? false;
        document.querySelector(".twitchat-connected-chip")?.classList.toggle("hidden", !connected);
        document.querySelector(".twitchat-disconnected-chip")?.classList.toggle("hidden", connected);
    }

    document.addEventListener("catapult:twitchat:presence", showTwitchatPresence);

    // The OBS card's chips and buttons, as IntegrationCards renders them for these settings.
    function showObsEnabled(enabled) {
        const toggle = (selector, hidden) => document.querySelector(selector)?.classList.toggle("hidden", hidden);
        toggle(".obs-connected-chip", !enabled);
        toggle(".obs-not-connected-chip", enabled);
        toggle("#obs-connect-btn", enabled);
        toggle("#obs-disconnect-btn", !enabled);
    }

    // The Xbox card's chips and buttons, as IntegrationCards renders them for that state (none without the card).
    function showXboxConnected(connected) {
        const toggle = (selector, hidden) => document.querySelector(selector)?.classList.toggle("hidden", hidden);
        toggle(".xbox-connected-chip", !connected);
        toggle(".xbox-not-connected-chip", connected);
        toggle("#xbox-connect-btn", connected);
        toggle("#xbox-disconnect-btn", !connected);
    }

    // Same pattern as the API's (AnkamaFriendService.NAME_TAG): Pseudo#tag, a tag of one to eight digits.
    const ANKAMA_NAME_TAG = /^\s*([^#\s][^#]*?)\s*#\s*(\d{1,8})\s*$/;

    // What the Ankama and Epic cards tell, in the page's language (index.html's body carries it).
    function pageLabel(name, fallback) {
        return document.body.dataset[name] ?? fallback;
    }

    // Links the member to the Ankama account typed in `input`. The card then follows from AnkamaLinkEvent. Unlike
    // CatapultChannel.postJson, a refusal says why: a bad format, an account of another channel, or no bot with room.
    async function enrollAnkama(input) {
        const { showError, actionFailed, unreachable } = CatapultChannel;
        const format = () => pageLabel("labelAnkamaFormat", "Enter your Ankama name as Pseudo#1234");
        const nameTag = input.value.trim();
        if (!ANKAMA_NAME_TAG.test(nameTag)) {
            showError(format());
            return;
        }
        let response;
        try {
            response = await CatapultCsrf.postJson(`${CatapultChannel.baseUrl()}/ankama/enroll`, { nameTag });
        } catch {
            showError(unreachable());
            return;
        }
        if (response.ok) return;
        if (response.status === 400) {
            showError(format());
        } else if (response.status === 409) {
            const reason = (await response.json().catch(() => null))?.reason;
            showError(reason === "ALREADY_LINKED"
                ? pageLabel("labelAnkamaAlreadyLinked",
                    "This Ankama account is already linked to another Catapult channel")
                : pageLabel("labelAnkamaFull",
                    "Every Catapult Ankama account is full for now: linking will be possible again soon."));
        } else {
            showError(actionFailed(response.status));
        }
    }

    // Saves a speedrun.com name or key through `url`. Unlike CatapultChannel.postJson, a refusal says why: speedrun.com
    // does not know the name or refuses the key (400), or cannot be asked (503). The card then follows from
    // SpeedrunAccountChangedEvent. The key's field is emptied once it is saved: it is never shown again.
    // RetroAchievements' name and key are saved the same way, with their own unavailable label.
    async function saveSpeedrun(url, body, input, refusedLabel, unavailableLabel) {
        const { showError, actionFailed, unreachable } = CatapultChannel;
        if (!input.value.trim()) return;
        let response;
        try {
            response = await CatapultCsrf.postJson(url, body);
        } catch {
            showError(unreachable());
            return;
        }
        if (response.ok) return;
        if (response.status === 400) {
            showError(pageLabel(...refusedLabel));
        } else if (response.status === 503) {
            showError(pageLabel(...(unavailableLabel || ["labelSpeedrunUnavailable",
                "speedrun.com cannot be reached right now: try again later."])));
        } else {
            showError(actionFailed(response.status));
        }
    }

    // Why catapult-api refused an Epic login, which it sends back to this tab as ?error=<code>.
    const EPIC_LINK_ERRORS = {
        epic_full: ["labelEpicFull", "Every Catapult Epic Games account is full for now: linking will be possible again soon."],
        epic_already_linked: ["labelEpicAlreadyLinked", "This Epic Games account is already linked to another Catapult channel"],
        epic_unavailable: ["labelEpicUnavailable", "Epic Games linking is not available for now: try again later."],
        // The World of Warcraft import (Blizzard stats card) comes back the same way.
        battlenet_stats_unavailable: ["labelBlizzardStatsUnavailable",
            "The Blizzard import is not available for now: try again later."],
        battlenet_stats_mismatch: ["labelBlizzardStatsMismatch",
            "This is not the Battle.net account linked to Catapult: sign in with the same account."],
        battlenet_stats_failed: ["labelBlizzardStatsFailed", "Importing your characters failed: try again later."],
        battlenet_stats_cancelled: ["labelBlizzardStatsCancelled", "Import cancelled."],
        // The Bungie.net link (Bungie card) too.
        bungie_unavailable: ["labelBungieUnavailable", "Linking Bungie.net is not available for now: try again later."],
        bungie_mismatch: ["labelBungieMismatch",
            "Bungie answered for another account than the one you signed in with: try again."],
        bungie_failed: ["labelBungieFailed", "Linking your Bungie.net account failed: try again later."],
        bungie_cancelled: ["labelBungieCancelled", "Bungie.net linking cancelled."],
        // The osu! link (osu! card) too.
        osu_unavailable: ["labelOsuUnavailable", "Linking osu! is not available for now: try again later."],
        osu_mismatch: ["labelOsuMismatch",
            "osu! answered for another account than the one you signed in with: try again."],
        osu_failed: ["labelOsuFailed", "Linking your osu! account failed: try again later."],
        osu_cancelled: ["labelOsuCancelled", "osu! linking cancelled."],
    };
    // What is not a failure: shown as a plain message, not an error.
    const NEUTRAL_LINK_CODES = new Set(["battlenet_stats_cancelled", "bungie_cancelled", "osu_cancelled"]);

    // Tells a refused Epic login or World of Warcraft import once, or a finished import (?imported=wow): the code
    // leaves the address, so neither a reload nor binding again repeats it.
    function showEpicLinkError() {
        const url = new URL(location.href);
        const code = url.searchParams.get("error");
        if (Object.hasOwn(EPIC_LINK_ERRORS, code)) {
            url.searchParams.delete("error");
            history.replaceState(history.state, "", url);
            const show = NEUTRAL_LINK_CODES.has(code) ? CatapultChannel.showMessage : CatapultChannel.showError;
            show(pageLabel(...EPIC_LINK_ERRORS[code]));
        } else if (url.searchParams.get("imported") === "wow") {
            url.searchParams.delete("imported");
            history.replaceState(history.state, "", url);
            CatapultChannel.showMessage(pageLabel("labelBlizzardStatsDone", "World of Warcraft characters imported."));
        } else if (url.searchParams.get("linked") === "bungie") {
            url.searchParams.delete("linked");
            history.replaceState(history.state, "", url);
            CatapultChannel.showMessage(pageLabel("labelBungieDone", "Bungie.net account linked."));
        } else if (url.searchParams.get("linked") === "osu") {
            url.searchParams.delete("linked");
            history.replaceState(history.state, "", url);
            CatapultChannel.showMessage(pageLabel("labelOsuDone", "osu! account linked."));
        } else if (url.searchParams.get("imported") === "wow-empty") {
            url.searchParams.delete("imported");
            history.replaceState(history.state, "", url);
            CatapultChannel.showMessage(pageLabel("labelBlizzardStatsEmpty",
                "No World of Warcraft character was found on your Battle.net account."));
        }
    }

    // The Integrations tab is built only once picked (lazy.js): its cards are bound then, as with the page otherwise.
    document.addEventListener("catapult:render", bind);
    document.addEventListener("catapult:lazy", event => {
        if (event.detail.root.contains(document.getElementById("channel-connections"))) bind();
    });

    // The import day comes as yyyy-MM-dd (UTC), from the page and from the event: shown in the viewer's format.
    function localizeBlizzardImportDate() {
        const chip = document.querySelector(".blizzard-stats-imported-on");
        if (!chip) return;
        const localize = () => {
            const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(chip.textContent.trim());
            if (!match) return;
            const day = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])));
            chip.textContent = new Intl.DateTimeFormat(document.documentElement.lang || undefined,
                { dateStyle: "short", timeZone: "UTC" }).format(day);
        };
        localize();
        if (!chip.dataset.localized) {
            chip.dataset.localized = "1";
            new MutationObserver(localize).observe(chip, { childList: true, characterData: true, subtree: true });
        }
    }

    function bind() {
        if (!document.getElementById("channel-connections")) return;
        showTwitchatPresence();
        showEpicLinkError();

        const { on, postJson, postSwitch } = CatapultChannel;
        const baseUrl = CatapultChannel.baseUrl();
        const value = id => document.getElementById(id).value;
        const checked = id => document.getElementById(id).checked;

        // Steam. Linking goes through Steam's login in a popup, which closes itself once done
        // (the tab itself when the browser blocks popups); the card follows from the event.
        on("steam-connect-btn", "click", () => {
            const popup = window.open("/connect/steam", "catapult-steam", "popup,width=800,height=720");
            if (!popup) location.assign("/connect/steam");
        });
        on("steam-disconnect-btn", "click", () => postJson("/connect/steam/disconnect", {}));
        on("steam-token-shared", "change", event =>
            postSwitch(event.currentTarget, `${baseUrl}/settings/steam-personal-token/sharing`,
                { shared: event.currentTarget.checked }));
        on("steam-token-delete-btn", "click", () =>
            postJson(`${baseUrl}/settings/steam-personal-token/delete`, {}));
        on("steam-token-save-btn", "click", () =>
            postJson(`${baseUrl}/settings/steam-personal-token`,
                { token: value("steam-token-input"), shared: checked("steam-token-share-new") }));
        // Only drops the cached profile: nothing on the page shows it until the next check.
        on("steam-refresh-cache-btn", "click", () =>
            postJson(`${baseUrl}/steam/refresh-profile-cache`, {}));

        // Xbox. Linking leaves for Microsoft's login, which sends the member back to this tab.
        on("xbox-connect-btn", "click", () => location.assign("/oauth2/authorization/xbox"));
        on("xbox-disconnect-btn", "click", () => postJson(`${baseUrl}/xbox/disconnect`, {}));

        // Minecraft
        on("minecraft-enroll-btn", "click", () =>
            postJson(`${baseUrl}/minecraft/enroll`, { name: value("minecraft-name-input") }));
        on("minecraft-check-btn", "click", () => postJson(`${baseUrl}/minecraft/sync`, {}));
        on("minecraft-disconnect-btn", "click", () => postJson(`${baseUrl}/minecraft/disconnect`, {}));

        // Battle.net. Linking leaves for Blizzard's login, which sends the member back to this tab.
        on("battlenet-connect-btn", "click", () => location.assign("/oauth2/authorization/battlenet"));
        on("battlenet-disconnect-btn", "click", () => postJson(`${baseUrl}/battlenet/disconnect`, {}));

        // Ankama: the streamer types their Pseudo#tag, whose invitation the bot sends.
        on("ankama-connect-btn", "click", () => enrollAnkama(document.getElementById("ankama-name-input")));
        on("ankama-resend-btn", "click", () => postJson(`${baseUrl}/ankama/resend`, {}));
        on("ankama-disconnect-btn", "click", () => postJson(`${baseUrl}/ankama/disconnect`, {}));

        // Epic. Linking leaves for Epic's login, which sends the member back to this tab; the bot's request can be
        // sent again while the link waits.
        on("epic-connect-btn", "click", () => location.assign("/oauth2/authorization/epic"));
        on("epic-resend-btn", "click", () => postJson(`${baseUrl}/epic/resend`, {}));
        on("epic-disconnect-btn", "click", () => postJson(`${baseUrl}/epic/disconnect`, {}));

        // Speedrun.com: a declared name and an optional personal key, each checked with speedrun.com on saving.
        on("speedrun-username-save-btn", "click", () => {
            const input = document.getElementById("speedrun-username-input");
            return saveSpeedrun(`${baseUrl}/settings/speedrun/username`, { username: input.value.trim() }, input,
                ["labelSpeedrunRefusedUsername", "speedrun.com does not know this username"]);
        });
        on("speedrun-username-delete-btn", "click", () =>
            postJson(`${baseUrl}/settings/speedrun/username/delete`, {}));
        on("speedrun-key-save-btn", "click", async () => {
            const input = document.getElementById("speedrun-key-input");
            await saveSpeedrun(`${baseUrl}/settings/speedrun/api-key`, { apiKey: input.value.trim() }, input,
                ["labelSpeedrunRefusedKey", "speedrun.com refused this API key"]);
            input.value = "";
        });
        on("speedrun-key-delete-btn", "click", () => postJson(`${baseUrl}/settings/speedrun/api-key/delete`, {}));

        // RetroAchievements: a declared name and an optional personal web API key, each checked on saving.
        const raUnavailable = ["labelRetroachievementsUnavailable",
            "RetroAchievements cannot be reached right now: try again later."];
        on("retroachievements-username-save-btn", "click", () => {
            const input = document.getElementById("retroachievements-username-input");
            return saveSpeedrun(`${baseUrl}/settings/retroachievements/username`, { username: input.value.trim() },
                input, ["labelRetroachievementsRefusedUsername", "RetroAchievements does not know this username"],
                raUnavailable);
        });
        on("retroachievements-username-delete-btn", "click", () =>
            postJson(`${baseUrl}/settings/retroachievements/username/delete`, {}));
        on("retroachievements-key-save-btn", "click", async () => {
            const input = document.getElementById("retroachievements-key-input");
            await saveSpeedrun(`${baseUrl}/settings/retroachievements/api-key`, { apiKey: input.value.trim() }, input,
                ["labelRetroachievementsRefusedKey", "RetroAchievements refused this API key"], raUnavailable);
            input.value = "";
        });
        on("retroachievements-key-delete-btn", "click", () =>
            postJson(`${baseUrl}/settings/retroachievements/api-key/delete`, {}));

        // Hypixel: a Minecraft name or UUID, checked by Hypixel first; the card follows from
        // HypixelAccountChangedEvent.
        on("hypixel-save-btn", "click", async () => {
            const input = document.getElementById("hypixel-player-input");
            await saveSpeedrun(`${baseUrl}/settings/hypixel/account`, { player: input.value.trim() }, input,
                ["labelHypixelRefused", "This name does not exist, or this account never joined Hypixel"],
                ["labelHypixelUnavailable", "Hypixel cannot be reached right now: try again later."]);
        });
        on("hypixel-delete-btn", "click", () => postJson(`${baseUrl}/settings/hypixel/account/delete`, {}));

        // Riot: the Riot ID and the server are saved together, Riot checking the Riot ID first; the card follows
        // from RiotAccountChangedEvent.
        on("riot-save-btn", "click", () => {
            const input = document.getElementById("riot-id-input");
            const platform = document.getElementById("riot-platform-select")?.value || "euw1";
            return saveSpeedrun(`${baseUrl}/settings/riot/account`, { riotId: input.value.trim(), platform }, input,
                ["labelRiotRefused", "Riot does not know this Riot ID on that server"],
                ["labelRiotUnavailable", "Riot cannot be reached right now: try again later."]);
        });
        on("riot-delete-btn", "click", () => postJson(`${baseUrl}/settings/riot/account/delete`, {}));

        // FACEIT: the nickname is saved once FACEIT knows it; the card follows from FaceitAccountChangedEvent.
        on("faceit-save-btn", "click", () => {
            const input = document.getElementById("faceit-nickname-input");
            return saveSpeedrun(`${baseUrl}/settings/faceit/nickname`, { nickname: input.value.trim() }, input,
                ["labelFaceitRefused", "FACEIT does not know this nickname"],
                ["labelFaceitUnavailable", "FACEIT cannot be reached right now: try again later."]);
        });
        on("faceit-delete-btn", "click", () => postJson(`${baseUrl}/settings/faceit/nickname/delete`, {}));
        // Chess: a Chess.com and a Lichess name, each checked with its site on saving.
        const chessUnavailable = ["labelChessUnavailable",
            "Chess.com or Lichess cannot be reached right now: try again later."];
        const chessRefused = {
            chesscom: ["labelChessRefusedChesscom", "Chess.com does not know this username"],
            lichess: ["labelChessRefusedLichess", "Lichess does not know this username, or the account is closed"],
        };
        for (const site of ["chesscom", "lichess"]) {
            on(`chess-${site}-save-btn`, "click", () => {
                const input = document.getElementById(`chess-${site}-username-input`);
                return saveSpeedrun(`${baseUrl}/settings/chess/${site}`, { username: input.value.trim() }, input,
                    chessRefused[site], chessUnavailable);
            });
            on(`chess-${site}-delete-btn`, "click", () => postJson(`${baseUrl}/settings/chess/${site}/delete`, {}));
        }

        // Blizzard stats: the region and the main are saved as picked; importing leaves for Blizzard's login (which
        // sends the member back to this tab with ?imported=wow, or the reason it failed). The card follows from the
        // event.
        on("blizzard-region-select", "change", event => {
            const region = event.currentTarget.value;
            return region && postJson(`${baseUrl}/settings/blizzard/region`, { region });
        });
        on("blizzard-main-select", "change", event => {
            const [region, characterId] = String(event.currentTarget.value).split(":");
            return region && characterId &&
                postJson(`${baseUrl}/settings/blizzard/wow-main`, { region, characterId: Number(characterId) });
        });
        on("blizzard-import-btn", "click", () => location.assign("/oauth2/authorization/battlenet-stats"));
        on("blizzard-reimport-btn", "click", () => location.assign("/oauth2/authorization/battlenet-stats"));
        on("blizzard-forget-btn", "click", () => postJson(`${baseUrl}/settings/blizzard/wow-characters/delete`, {}));

        // Bungie. Linking leaves for Bungie's login, which sends the member back to this tab.
        on("bungie-connect-btn", "click", () => location.assign("/oauth2/authorization/bungie"));
        on("bungie-disconnect-btn", "click", () => postJson(`${baseUrl}/settings/bungie/delete`, {}));

        // osu!. Linking leaves for osu!'s login, which sends the member back to this tab.
        on("osu-connect-btn", "click", () => location.assign("/oauth2/authorization/osu"));
        on("osu-disconnect-btn", "click", () => postJson(`${baseUrl}/settings/osu/delete`, {}));
        localizeBlizzardImportDate();

        // OBS: connecting and disconnecting both save the whole form, only `enabled` differs.
        // An empty password field means "keep the stored one" (it's never sent back to the page),
        // and blank host/port fall back to catapult-api's defaults. No SSE event covers OBS, so the
        // chips and buttons flip here, and obs-session.js follows the new settings.
        const saveObs = async enabled => {
            const password = value("obs-password");
            const response = await postJson(`${baseUrl}/settings/obs`, {
                enabled,
                host: value("obs-host"),
                port: value("obs-port") === "" ? null : Number(value("obs-port")),
                password: password === "" ? null : password,
            });
            if (!response.ok) return;
            showObsEnabled(enabled);
            if (password !== "") {
                // Stored now: shown like any stored password, as a placeholder.
                const field = document.getElementById("obs-password");
                field.value = "";
                field.placeholder = "••••••••";
            }
            await window.CatapultObsSession?.refresh();
        };
        on("obs-connect-btn", "click", () => saveObs(true));
        on("obs-disconnect-btn", "click", () => saveObs(false));

        // Twitchat: the branch rides along /me/obs, which obs-session.js hands to twitchat.js.
        on("twitchat-branch", "change", async event => {
            await postJson(`${baseUrl}/settings/twitchat/branch`, { branch: event.currentTarget.value });
            await window.CatapultObsSession?.refresh();
        });
    }

    return { showXboxConnected };
})();
