# Changelog

Toutes les versions publiées de Catapult, la plus récente en premier.

Ce fichier est régénéré par le bot à chaque release à partir des notes des [GitHub Releases](https://github.com/enimaloc/catapult/releases) : pour corriger une entrée, modifie les notes de la release (ou le changelog de la PR de release avant sa publication), pas ce fichier.

## [v1.0.0-BETA.97](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.97) - 2026-10-28

### Features
- **web:** remove old file (fa4c0a2)
- **web:** implement new web module base structure (d80c20a)
- **web:** add SPA-compatible error page with correct HTTP status codes (73cb914)
- **web:** harden SPA navigation with titles, active link and loading state (41e7f5f)
- **web:** add ApiClient JSON body support and CSRF fetch helper (570c1b9)
- **web:** render real channel status card with bot toggle and recheck action (d2a7a00)
- **web:** render bindings list with filters and pagination (fb1b8da)
- **web:** add binding ccl-enabled and ignored toggles (ac2cfe8)
- **web:** add binding delete action (4403e38)
- **web:** add binding inline edit with game search and ccl selection (b3ea7de)
- **web:** add binding trigger-warning inline edit (02c4277)
- **web:** render connections card (read-only display) (5a831c7)
- **web:** add steam personal token management (4f890a6)
- **web:** add minecraft connection state machine (f563cc2)
- **web:** add ccl settings card (c4083c8)
- **web:** add tw settings card (b8f0332)
- **web:** add no-game settings card (07535d4)
- **web:** add incomplete-fallback settings card (cd78003)
- **web:** add dtdd mapping card, completing the channel dashboard page (3c5c39b)
- **web:** make mock login fully customizable (e7bd55c)
- **web:** make mock-mode UI actions persist across navigation (ab194a5)
- **web:** add mock-only admin page for state unreachable from the app's UI (58cfaf0)
- **web:** add the channels picker page, responsive across widths (d85517f)
- **web:** push live updates to /channel/{username} over WebSocket (ed9a6a9)
- **web:** broadcast granular channel update events over SSE (a57c361)
- **web:** isolate mock connections with per-session random state (01c2e7d)
- **web:** add sp:visible-when Thymeleaf attribute for SSE-synced visibility (15e0927)
- **web:** add generic Visibility.apply client module (8b91698)
- **web:** push Steam connection diagnostics live over SSE (04b4845)
- **web:** add spa:on attribute declaring SSE-event-to-flag mappings (00acef8)
- **web:** add spa:value attribute declaring SSE-event-to-property syncs (b9c295c)
- **web:** support |default fallback in spa:value specs (9d4f2e6)
- **web:** add spa:in attribute for checkbox-group membership sync (eb2c4cb)
- **web:** handle data-in in Visibility.dispatch (57c7331)
- **web:** add equality matching to spec grammar (for spa:switch) (d7d517a)
- **web:** add spa:switch attribute for enum-status visibility flags (c29628b)
- **web:** handle data-switch in Visibility.dispatch (f344bba)
- **web:** add Visibility.discoverEventNames for auto-subscription (fb2987c)
- **web:** split the channel page into overview/settings/integrations tabs (1685a5e)
- **web:** offer a bindings filter for every supported platform (790f03e)
- **web:** show the current game's platform next to its name (057e176)
- add an OBS connection card backed by the Twitchat relay settings (48b4094)

### Fixes
- **web:** hide loading indicator after SPA navigation completes (dbda862)
- **web:** navigate to error pages through the SPA router (7a96b54)
- **web:** resolve HttpClient.postVoid overload ambiguity and add comprehensive test coverage (045b996)
- **web:** gate channelSettings fetch behind isOwner check (428676c)
- **web:** add missing i18n key to default messages bundle for incomplete-fallback card (22b0b5e)
- **web:** fix SPA script re-runs, CSRF cookie creation and post-mutation errors on the channel page (8406b95)
- **web:** defer initial catapult:render dispatch until DOMContentLoaded (df7e2bc)
- **web:** use the plain CSRF token request handler for the cookie-based SPA setup (4c90b6b)
- **web:** preserve the query string on SPA navigation (9876306)
- **web:** restore readable text and full pill corners on channel cards (e2e40c6)
- **web:** restrict the channel WebSocket handshake to same-origin (4fb11e5)
- **web:** wrap error and privacy pages in mdui-layout-main (5283868)
- **web:** harden sp:visible-when truthiness, error messages and partial-state handling (f02f8a3)
- **web:** actually apply the mdui dark theme (f1de32b)
- **web:** save per-binding ccl switches and keep them on game change (acb78ec)
- **web:** make .hidden and [hidden] win over component display rules (bd07453)
- **web:** apply the status and source filters in the mock channel page (df94c28)
- **api:** only include the Minecraft link in the channel page for its owner (54991d0)
- **api:** only register the Swagger UI transformer when springdoc's UI is active (b26702e)
- **steam:** restore CCL and TW signal extraction lost in the SteamStorePage move (ec729b1)
- **steam:** keep linux requirements apart and tolerate missing highlighted achievements (1b26cf6)
- **web:** actually resolve the catapult-api base URL (63eaea8)
- **web:** don't crash mock sessions that drew no binding (bbf59ad)
- **web:** expand i18n references before parsing message arguments (60ba6b3)
- **api:** let EventSub chat sockets opened by connect() reconnect after a drop (3c5ee4b)
- **api:** stop a pending EventSub reconnect from reviving a disconnected user (881d4b1)
- **web:** point thymesVar type hints at the per-feature DTO packages (c85de54)
- **web:** translate the channels page eyebrow (d3cc235)
- **api:** drop the ineffective @Transactional on the system bot token refresh (597eb92)
- **web:** correct the French channels page title and prompt (07849b8)
- **api:** use French colon spacing in the Twitchat quick-config help (08ef977)
- **api:** migrate IGDB websites and age ratings off the deprecated fields (c16e5b8)
- **web:** give the channel page text inputs an accessible label (9ba3f43)

### Refactoring
- **api:** extract shared API DTOs into catapult-common module (c021be7)
- **web:** publish ChannelUpdatedEvent instead of calling the WS handler directly (946355d)
- **web:** push channel updates over an SSE stream instead of WebSocket (4c32760)
- **web:** use a wildcard import for common DTOs in ApiService (07ef51f)
- **web:** drive Steam/binding visibility through sp:visible-when (04a7acb)
- **web:** auto-save binding edit/ccl/tw panels on change (d2bf2c5)
- **web:** rename sp:visible-when attribute to spa:if (259b175)
- **web:** drive channel-state-live/offline chips through spa:if (ea274a0)
- **web:** restructure #channel-current-game as a spa:if pair (e21a895)
- **web:** drive Visibility.apply from spa:on declarations generically (fd88964)
- **web:** scope Visibility.dispatch per-element instead of per-event (4f1d230)
- **web:** sync bot/binding checkboxes and current-game text via spa:value (ce3b429)
- **web:** merge setSteamTokenSaved/setSteamTokenShared via spa:value (f626ea4)
- **web:** sync .binding-game-name text via spa:value (c147051)
- **web:** sync tw/ccl checkbox groups via spa:in (e9ddc24)
- **web:** drive Minecraft status/actions via spa:if/spa:switch (e09f8be)
- **web:** pre-render #channel-bindings-empty instead of stashing its i18n label (8f80dd3)
- **web:** auto-subscribe EventSource via Visibility.discoverEventNames (4963b79)
- **web:** deduplicate channel scripts and stylesheet (212131b)
- **web:** expose features, platforms and spa routes as one app model attribute (e4be411)
- **web:** split the channel page into one fragment per tab (c682b86)
- group per-provider connection state into dedicated records (6986731)
- **web:** expose the SPA router as window.CatapultSpa (b85ee0b)
- **common:** build broadcast data maps through one helper (cacd3fa)
- **web:** slim ApiClient down to the calls ApiService makes (6b4a18a)
- **web:** gather the mock-only controllers under controller.mock (0f406a9)
- **web:** split the site catalog and SPA fragments out of IndexController (ba48ac7)
- **web:** tidy ModelFiller and cover every branch (2394dbf)
- **web:** move the channel events out of the ws package (7d993ca)
- **web:** split MockData into presets, login config and session state (79bca58)
- **web:** use explicit imports in the mock API and admin controller (6be866d)
- **web:** build the settings cards from shared control fragments (18cf267)
- **web:** loop over the binding status filters (b5b21df)
- **api:** resolve the caller and channel through ApiUserResolver (32b2f65)
- **api:** assemble the channel dashboard outside its controller (5bf506b)
- **api:** compute Steam profile diagnostics in one place (49da57f)
- **api:** factor the repeated steps out of the channel actions (acaa292)
- **api:** give UserApiV2Controller's aggregation named steps (29ba413)
- **api:** split the experiment detail stats into named steps (ac48c3c)
- **api:** match the AI disclosure heading against every pattern once (b6e53a9)
- **api:** run every IGDB call through one locked, logged helper (c57e414)
- **api:** read invite settings through typed helpers (8309c57)
- **api:** share key selection, 429 handling and parsing across Steam calls (4828646)
- **api:** share IgdbService's external-uid lookup chain and cache bookkeeping (4ab8108)
- **api:** move the chat transports' shared Helix calls into TwitchHelixChannelClient (36da346)
- **api:** share the EventSub WebSocket lifecycle between the chat and stream services (d2bf796)
- **api:** resolve admin member lookups through one helper (09ee09d)
- **api:** name the Steam popup redirect and account lookup (c36e1ae)
- **api:** name AdminTwService's change event and keyword persistence (d432514)
- **api:** let InviteCodeRelayFilter read and clear the invite code it relays (b09ccf6)
- **api:** share the system bot's linked-token lookup (4aa2e9e)
- **api:** share lookups, DTO mapping and field checks in the Minecraft pool admin API (d606684)
- **api:** drop unused Steam key controller constructors and name its helpers (0e96541)
- **api:** map whitelist invite rows in a named method (c8eacfd)
- **api:** build OAuth2 registrations from one authorization-code template (2eb69dc)
- **api:** send activity log entries through one helper (718b5cb)
- **api:** import the cache inspector's types and share index entry mapping (b0d9685)
- **api:** normalize feedback descriptions in one place (42743dc)
- **api:** build the admin data search clause once (64d3721)
- **api:** route binding edits and TW suggestions through shared helpers (cc41b33)
- **api:** await getter prefetches through one helper (08212b6)
- **api:** group entities and repositories into per-domain packages (ece962e)
- **api:** group services into per-domain packages (574f071)
- **api:** split controllers into admin, provider, channel, chat-command, connect, experiment (4eae1f5)
- **api:** split Steam and DTDD getters and the event listeners into their own packages (766fca3)
- **common:** group the shared DTOs into per-feature packages (9133dfa)
- **web:** group the channel update events by feature (826ca0b)
- **api:** drop null checks that can never trigger (e54fac8)
- use getFirst()/getLast() on sequenced collections (3e8f7e0)
- **api:** use method references instead of trivial lambdas (b656ad0)
- **api:** drop the unused rate-limit window field and DSL controller dependency (16cd9eb)
- drop redundant casts, type arguments and modifiers (fe760de)
- **api:** inject IgdbClient's observations through its constructor (0b48222)

### Documentation
- fix French spelling and agreement in the README feature list (e228618)
- **api:** fix broken Javadoc references (fa4fdfd)
- **api:** merge a doubled Javadoc and mark Javadoc paragraphs (f78f5d8)

### Other
- **git:** update .gitignore to remove old web module (b531761)
- **old-web:** add to compile to old version (b1509f7)
- **web:** new module (6dac582)
- **dep:** add mdui for new UI (52bf196)
- **web:** adapt the channel dashboard page to the site's design system (26609b8)
- **web:** group each connection card's action buttons at the bottom (d316475)
- **web:** use mdui-segmented-button for association filters and card actions (784284d)
- **web:** style open mdui-collapse-item headers via utils.js/css (a95b1c3)
- **web:** pin Minecraft PENDING status rendering (abf40a6)
- **web:** lay out channel connections as a vertical list (98fe5f3)
- **api:** compare SteamLanguage lookups against enum constants (9bcfb11)
- enforce a JaCoCo line-coverage floor on common, api and web (70562fb)
- **web:** cover the browser scripts with Vitest and jsdom (2ca8d45)
- **common:** pin every DTO's JSON contract and the DTOs' own behaviour (abd5cd7)
- **web:** cover the full-page and fragment error views (311122f)
- **web:** cover the SPA fragments and the application context (e6566a7)
- raise catapult-web's coverage floor to 95% (dc94b43)
- **api:** share one slice setup across the channel actions tests (9cca972)
- raise catapult-api's coverage floor to 73% (0ea20f3)
- **api:** cover the Xbox Display Catalog product mapping (619252c)
- **api:** drive RealSteamApiClient through a mocked Steam Web API (45cd462)
- **api:** cover IgdbService's lookup chains, prewarming and CCL suggestions (6dfad92)
- **api:** cover the CCL sync, default mapping seeding and admin edits (8da07d8)
- **api:** drive the IRC chat transport against a local IRC server (aba06f7)
- **api:** cover the admin member actions and their guards (8a75351)
- **api:** cover the Steam OpenID link round trip (8581869)
- **api:** cover TW definition edits and their signal mappings (c19f589)
- **api:** cover Twitch logins, the whitelist and invite relaying (10eac7f)
- **api:** cover the system bot token refresh and mod-status cache (583dd44)
- **api:** cover the Minecraft service-account admin flows (065b976)
- **api:** cover the Steam key pool and whitelist administration (2a40f61)
- **api:** cover the Minecraft services client and the dev backdoor UUIDs (b4b0a4f)
- **api:** cover which OAuth2 providers get registered (462bf7c)
- **api:** cover the activity feed and the outbound HTTP trace log (2ce087c)
- **api:** cover the cache inspector and feedback submissions (516370a)
- **api:** pin which binding edits save and push to Twitch (1edd03c)
- **api:** cover detection cycles, getter prefetching and Steam library warm-up (3a18e99)
- raise catapult-api's coverage floor to 87% (4aadce3)
- **web:** bump vitest to 4.1.11 to drop vulnerable mocker and tinypool (fafc488)
- **api:** bump jsoup and spring-cloud-context past known CVEs (9ae12b9)
- **grafana:** restore French accents in the dashboard panel notes (b04827c)
- remove unused imports (a6e7150)
- replace deprecated JSON and AssertJ assertion calls (5248091)
- **api:** remove the unused IGDB game-page preload (1dc13fd)
- **web:** type the response() helper body as any value (c103823)
- upgrade the Gradle wrapper to 9.8.1 (fd04950)
- drop redundant @SuppressWarnings (4eebf00)
- narrow test-only fields to locals and make shared containers final (c6a5999)
- **web:** close the privacy resource streams in ModelFillerTest (5fb3de8)

… et 844 autres commits

## [v1.0.0-BETA.96](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.96) - 2026-09-30

### Fixes
- **steam:** decode HTML-entity-encoded text from Steam APIs (c5f98ce)

### Other
- **release:** bump version to 0.3.7-BETA (0fa41d2)

Anciennement `v0.3.7-BETA`.

## [v1.0.0-BETA.95](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.95) - 2026-09-29

### Fixes
- **xbox:** exclude Online pseudo-title from presence detection (eb8cb5e)

### Other
- **release:** bump version to 0.3.6-BETA (d7dd618)

Anciennement `v0.3.6-BETA`.

## [v1.0.0-BETA.94](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.94) - 2026-09-23

### Fixes
- **test:** compare SteamLanguage.fromLocale by its Steam string form (fa7a266)
- **steam:** restore case-insensitive CCL matching and empty-result filtering (f2148ee)
- **api:** skip Swagger UI transformer bean outside a servlet context (58aca37)
- **igdb:** recover from concurrent insert race on igdb_game_details (f2bc200)
- **api:** fix IA disclosure incorrect pattern (50ecf68)

### Other
- **#2:** fix CI tests suite (d36ee43)
- **#1:** fix concurrent insert on igdb_game_details (95042ef)
- **release:** bump version to 0.3.5-BETA (67a99c8)

Anciennement `v0.3.5-BETA`.

## [v1.0.0-BETA.93](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.93) - 2026-09-21

### Fixes
- **security:** permit swagger-config sub-path in prod api-docs matcher (806455f)

### Other
- **release:** bump version to 0.3.4-BETA (5a66be8)

Anciennement `v0.3.4-BETA`.

## [v1.0.0-BETA.92](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.92) - 2026-09-20

### Fixes
- **steam:** restore build by implementing SteamStoreService#fetchIADisclosure (67a019e)

### Refactoring
- **chat:** rename game#release_date's IGDB field to steamReleaseDate (6b453a5)

### Other
- **release:** bump version to 0.3.3-BETA (0e0d7c3)

Anciennement `v0.3.3-BETA`.

## [v1.0.0-BETA.91](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.91) - 2026-09-19

### Features
- personalize the widget uuid example for logged-in dashboard users (bed1017)
- **steam:** detect and extract AI-generated-content disclosures (ac4a59c)
- **api:** surface Steam AI-generated-content disclosure in user API (1d7b54f)

### Fixes
- **api:** bake a static example into uuid path parameters (a1a155d)

### Refactoring
- **api:** personalize the Swagger uuid example client-side instead of via JWT (810a436)

### Other
- **release:** bump version to 0.3.2-BETA (4c3fbd6)

Anciennement `v0.3.2-BETA`.

## [v1.0.0-BETA.90](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.90) - 2026-09-17

### Features
- **steam:** expose full store-page data via SteamStorePage (9882ebe)
- **xbox:** add XboxStoreService backed by the Microsoft Store catalog (e9ab645)
- **igdb:** cache genres, media, and ref ids from the game-details fetch (c09a741)
- **api:** add v2 game-info endpoint with per-platform detail routes (0f97317)
- **api:** add springdoc-openapi, pin docs server URL to app.base-url (5c7f12e)
- **api:** document and mature the v2 user-api (dfdbad3)
- **web:** link to the API docs from the twitchat widget settings (30a1fb1)
- **api:** personalize the Swagger "uuid" example for logged-in callers (6cb7408)
- **api:** allow prefilling the Swagger JWT via a ?jwt= link (6ce9901)
- **web:** store widget token (85b1f64)
- **api:** remove transformer and authentication (dbf6573)

### Fixes
- **api:** parse Steam release dates as Instant across all responses (34f841e)
- **nginx:** route /api/user and Swagger UI paths to the backend (cce8a96)
- **security:** allow public access to /api/user/** in production (2868e7a)
- **security:** move the Swagger JWT link to a URL fragment (b2b423c)

### Refactoring
- **api:** simplify user-api versioning, drop dead ApiGameInfoController (d10f493)

### Documentation
- **api:** document v1 endpoint, support Accept-Language fallback (f49014f)

### Other
- **release:** bump version to 0.3.1-BETA (371fff5)

Anciennement `v0.3.1-BETA`.

## [v1.0.0-BETA.89](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.89) - 2026-09-14

### Features
- **api:** localize tw labels in game-info response (d091162)
- **security:** make session expiry configurable, default to infinite (1cb4a6d)
- **security:** retry Twitch login once before showing an error (b07ea61)

### Other
- **release:** bump version to 0.2.9-BETA (0408a71)

Anciennement `v0.2.9-BETA`.

## [v1.0.0-BETA.88](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.88) - 2026-09-11

### Features
- **api:** prefer localized store description in game-info endpoint (35e481d)

### Other
- **release:** bump version to 0.2.8-BETA (d208ebc)

Anciennement `v0.2.8-BETA`.

## [v1.0.0-BETA.87](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.87) - 2026-09-11

### Fixes
- **api:** allow cross-origin access to /api/game/{uuid} (e304bbc)

### Other
- **release:** bump version to 0.2.7-BETA (eece095)

Anciennement `v0.2.7-BETA`.

## [v1.0.0-BETA.86](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.86) - 2026-09-11

### Fixes
- **nginx:** route /api/game/ to catapult-api (e980a99)

### Other
- **release:** bump version to 0.2.6-BETA (68c287b)

Anciennement `v0.2.6-BETA`.

## [v1.0.0-BETA.85](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.85) - 2026-09-11

### Features
- **api:** add normalized game-info widget endpoint (17b5b55)

### Refactoring
- **api:** generalize twitchat widget token to UserAccount (e20b10c)

### Other
- **git:** update .gitignore (464c970)
- **release:** bump version to 0.2.5-BETA (f930334)

Anciennement `v0.2.5-BETA`.

## [v1.0.0-BETA.84](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.84) - 2026-09-11

### Features
- **ci:** add commit-lint check and automated GitHub Release creation (5daba71)

### Other
- **ci:** migrate CI/CD from GitLab to GitHub Actions (b35aef7)
- **release:** bump version to 0.2.4-BETA (70ab9a5)

Anciennement `v0.2.4-BETA`.

## [v1.0.0-BETA.83](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.83) - 2026-09-11

### Features
- **tw:** seed Steam TW keywords and add a force-rebuild-including-pinned action (f47e143)
- **tw:** seed Steam TW keywords and add a force-rebuild-including-pinned action (b052b1d)

### Fixes
- **ci:** run the test job on a privileged runner (7b91210)
- **ci:** run release-* jobs on a privileged runner (1679cc5)

### Other
- Add k3s-cluster agent config (5cb24d1)
- **ci:** remove the sonar analysis job (336b0ca)
- **release:** bump version to 0.2.3-BETA (a73255a)

Anciennement `v0.2.3-BETA`.

## [v1.0.0-BETA.82](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.82) - 2026-08-24

### Features
- **tw:** add admin page to manage Steam TW keywords per definition (a040d24)

### Other
- **release:** bump version to 0.2.2-BETA (29046ac)
- Revert "chore(release): bump version to 0.2.2-BETA" (0cbb681)
- **release:** bump version to 0.2.2-BETA (821c75b)

Anciennement `v0.2.2-BETA`.

## [v1.0.0-BETA.81](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.81) - 2026-08-21

### Features
- **web:** pick Twitchat quick config delivery once, filter actions via checkboxes (0cf804c)
- **web:** animate tab panel content on channel tab switch (a4b9ef0)
- **web:** add hover and entrance micro-animations across UI (a1c9e90)
- **steam:** add steam_app_parent cache table (7b30f98)
- **steam:** resolve playtest parent app id via store redirect (4101edf)
- **steam:** resolve demo/beta/playtest apps to their base game (5524483)
- **steam:** resolve steam#getGame to the base game (50e1e7a)
- **steam:** resolve presence detection to the base game (7815f89)

### Fixes
- **web:** restore missing Twitchat OBS relay include on the channel page (e49ea76)
- **twitch:** stop EventSub websockets from silently dying without reconnecting (f532a68)
- **steam:** don't cache parent name on failed lookup, add fresh negative-cache test (e617b2c)

### Other
- Merge branch 'worktree-steam-parent-app-resolution' (d6722b0)
- **release:** bump version to 0.2.1-BETA (2777b54)

Anciennement `v0.2.1-BETA`.

## [v1.0.0-BETA.80](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.80) - 2026-08-14

### Features
- **web:** finalize channel-page-tabbed-layout as the only layout (423e6b7)
- merge channel-page-tabbed-layout finalization into master (558803f)
- add twitchat notifications domain model and migration (0545355)
- add twitchat widget settings service (b48164b)
- add twitchat action token generation and consumption (9042790)
- add dedicated redis channel for twitchat notifications (ad745bf)
- add category-only twitch update and binding game-only update (d70c575)
- track self-set twitch category to distinguish manual changes (23f6007)
- compose twitchat notifications for category, bot and stream events (5b650a2)
- execute twitchat notification button actions (425819f)
- trigger twitchat notifications from bot, stream and category hooks (d9591ed)
- expose twitchat widget and settings HTTP endpoints (39aadb9)
- resolve twitchat widget token to its notification channel (057038a)
- add twitchat notification settings to the channel page (8c2db26)
- serve the public twitchat relay and action pages (314dc47)
- relay twitchat notifications to obs-websocket from the widget page (85ed092)
- add author and per-event icon to twitchat notifications (6d7fea6)
- sync widget OBS connection with live streamer settings changes (4367f04)
- add Twitchat payload preset schema and entities (18b00f8)
- add Twitchat default payload registry and preset DTOs (4848efe)
- add Twitchat payload preset CRUD and activation service (1118d9b)
- render Twitchat notifications through active payload presets (112d481)
- expose Twitchat payload preset REST API (3045561)
- wire Twitchat payload preset CRUD into catapult-web (32a4e7a)
- add Twitchat payload preset settings UI (c3f806b)
- render twitchat preset actions as a raw array with token placeholders (cbc0c87)
- reshape twitchat defaults endpoint to the raw actions array format (459223f)
- support a message field on twitchat preset actions for chat-type buttons (f51d4da)
- add a test button for twitchat presets (3a7616a)
- add predefined parameterized quick configurations for twitchat presets (4c3bf25)
- **web:** move twitchat quick config picker into a modal (0279cb4)
- **web:** surface a hidden activity log and Twitchat trigger discovery in the widget (39b9c5f)
- **api:** cover every twitchat event/action combination with a quick config (5f30081)
- **web:** detect and offer to create the Twitchat relay source from the settings page (f241c9f)
- **web:** relay Twitchat notifications from the settings page as a fallback (d8e8e9c)
- twitchat notifications (a2d6db3)

### Fixes
- keep twitchat action token consumption durable across dispatch failures (ec775ae)
- prevent duplicate twitchat notification relay from double subscription (361050e)
- address final review findings for twitchat notifications (0b78517)
- break circular Spring bean dependency in TwitchatNotifier (f69e7b9)
- strip trailing slash from public web URL when building twitchat links (d9d1bba)
- fall back to placeholder OBS-websocket host/port when left blank (ea26ff7)
- reconnect the widget to OBS-websocket automatically after a drop (8b48496)
- dispatch twitchat settings save over the WS channel like sibling forms (b144a58)
- stop dropping the e2e twitchat test's Redis event via a race (9e61fe3)
- **web:** add PRG fallback and error feedback to twitchat presets (e41b57e)
- **web:** detect real backend validation failures on twitchat preset save (07a2e1e)
- **twitchat:** widen action-placeholder detection to catch malformed refs (f07726e)
- **web:** surface twitchat preset test failures instead of swallowing them (d1211fe)
- **twitchat:** repair quick-config picker after preset swap and guard test notification (40282bc)
- **web:** stop crashing the whole channel page on the twitchat presets tab (fc74628)
- **web:** show every twitchat quick config regardless of event type (aded8ef)
- **web:** i18n twitchat preset feedback and pretty-print quick-config JSON (405cac0)
- **api:** resolve twitchat quick-config labels per locale (6a82222)
- **api:** use Twitchat's real "message" actionType in the chat-button quick config (7732dc9)
- **web:** allow twitchat.fr to call the action endpoint cross-origin (1d6edf3)
- **api:** drop the "(chat)" suffix from the chat-command quick config's button label (93a1897)
- **web:** load obs-websocket-client.js on the settings page (62a370d)
- **web:** move OBS data attributes onto the element the script actually reads (d96e028)
- **web:** mark an OBS-websocket connection attempt in flight before connecting (2c72dbd)
- **web:** dedupe Twitchat relay when both the widget and settings page are open (948527b)
- **api:** correct blacklisted id from Xbox (9a196ab)
- **front:** added missing file (b97878b)

### Documentation
- **web:** explain twitchat preset placeholder syntax in the settings UI (b2879c1)

### Other
- add e2e coverage for the twitchat widget relay (4131635)
- fail fast with a clear message if the seeded twitchat widget lacks OBS config (b62ad1c)
- verify twitchat preset deletion order in AccountServiceTest (14b0000)
- restore pre-existing widget controller tests dropped in preset API commit (a9801a5)
- cover multi-placeholder and dedup cases in twitchat preset action rendering (59af98b)
- **web:** match twitchat presets UI to the app's design system (91fd606)
- **web:** match twitchat action page to the app's design system (0443797)
- fix conflict (2dfb26c)
- **web:** shrink saved-preset row height in the twitchat presets list (fe621ce)
- **web:** put event title, active preset picker and "+ Nouveau" on one line (8bca2bf)
- **web:** shrink saved-preset rows further (875f495)
- **release:** bump version to 0.2.0-BETA (c68e950)

Anciennement `v0.2.0-BETA`.

## [v1.0.0-BETA.79](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.79) - 2026-08-13

### Fixes
- update XboxGameGetterTest for MinecraftPresenceGetter constructor param (f07d02a)
- update XboxGameGetterTest for MinecraftPresenceGetter constructor param (3870250)
- send full command payload when toggling chat-command enabled state (ea0dfe4)
- stub findByExternalAppId instead of findBySteamAppId in BindingServiceTest (5371113)

### Other
- **release:** bump version to 0.1.12-BETA (860b3bb)

Anciennement `v0.1.12-BETA`.

## [v1.0.0-BETA.78](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.78) - 2026-08-11

### Other
- **release:** bump version to 0.1.11-BETA (51576ec)

Anciennement `v0.1.11-BETA`.

## [v1.0.0-BETA.77](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.77) - 2026-08-11

### Features
- **igdb:** unify Steam/Xbox external app id resolution (afcbb53)
- **xbox:** resolve Microsoft Store product id via Titlehub (72e9fd5)

### Other
- **release:** bump version to 0.1.10-BETA (5a825df)

Anciennement `v0.1.10-BETA`.

## [v1.0.0-BETA.76](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.76) - 2026-08-08

### Features
- **admin:** make Xbox presence level query param editable (9800056)
- **admin:** make Minecraft presence activityId editable (8139d43)
- **admin:** make Twitch users login query param editable (dd01d8e)
- **admin:** make Steam appdetails cc/l query params editable (c5b05b7)

### Other
- **release:** bump version to 0.1.9-BETA (2ad6d20)

Anciennement `v0.1.9-BETA`.

## [v1.0.0-BETA.75](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.75) - 2026-08-08

### Fixes
- **admin:** enforce ADMIN role or loopback IP on remaining admin controllers (0a22a50)

### Other
- **release:** bump version to 0.1.8-BETA (4eccd63)

Anciennement `v0.1.8-BETA`.

## [v1.0.0-BETA.74](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.74) - 2026-08-08

### Features
- **admin:** add shared raw provider response support (30381bf)
- **admin:** add Steam raw provider response route (0121171)
- **admin:** add GitLab raw provider response route (59f86f8)
- **admin:** add IGDB raw provider response route (25c3cae)
- **admin:** add Twitch raw provider response routes (0fa4d24)
- **admin:** add Minecraft raw provider response routes (346be16)
- **admin:** add Xbox raw provider response route (8a4f435)
- add admin routes for raw provider responses (d34faa7)

### Fixes
- **admin:** gate Minecraft/Xbox provider routes on their feature flags (d05457b)
- **admin:** catch Twitch token-fetch failures inside raw response support (faad52b)

### Other
- **release:** bump version to 0.1.7-BETA (43be9c9)

Anciennement `v0.1.7-BETA`.

## [v1.0.0-BETA.73](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.73) - 2026-08-07

### Features
- **scheduler:** add manual game recheck for bot-disabled accounts (369252f)

### Fixes
- **twitch:** gate channel-mutating actions on account status (7859657)

### Other
- **release:** bump version to 0.1.6-BETA (1cde86c)

Anciennement `v0.1.6-BETA`.

## [v1.0.0-BETA.72](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.72) - 2026-08-07

### Features
- **minecraft:** detect invite rejected by player privacy at enrollment (745d828)
- **minecraft:** retry addFriend once a rejected invite is added manually (7a2250b)
- **minecraft:** add i18n messages for invite-rejected guidance (a6d53f9)
- **minecraft:** surface invite-rejected guidance in connections UI (999898e)

### Other
- **minecraft:** cover INVITE_REJECTED link resolved via direct friends match (4361f36)
- **minecraft:** lock in INVITE_REJECTED status pass-through in connect controller (bf88c24)
- **release:** bump version to 0.1.5-BETA (a4711aa)

Anciennement `v0.1.5-BETA`.

## [v1.0.0-BETA.71](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.71) - 2026-08-07

### Features
- add PostgreSQL schema via Flyway and JPA entities (15d94ec)
- add JPA repositories and full application.properties configuration (fb1f87f)
- OAuth2 authentication with Twitch/Steam/Discord, token encryption, account lifecycle (78c054d)
- add persistence with PostgreSQL and Flyway migrations (1e0c777)
- add OAuth2 authentication (Twitch/Steam/Discord) (87b981c)
- GameGetter interface, SteamGameGetter, DiscordGameGetter, GameGetterChain (610012b)
- add game detection via Steam/Discord getters (e7d6d29)
- SchedulerService, GameStateService, GameDetectedEvent, NoGameDetectedEvent (5116ec5)
- add scheduler and game state events (f7b134d)
- IgdbService, BindingService, TwitchService, GameEventListener (54b0d77)
- add Twitch category binding and IGDB integration (8d2795a)
- chat commands system (ChatCommandEvent, CommandRegistry, !game, !setgame) (441f4de)
- add Twitch chat command system (!game, !setgame) (a9eef92)
- Thymeleaf UI — /login, /dashboard, /settings, /bindings controllers and templates (ce12f70)
- add Thymeleaf web UI (login, dashboard, settings, bindings) (fcd2f52)
- add Dockerfile and docker-compose.yml (4443531)
- multi-stage Dockerfile + build directive for local run (353a9bc)
- add Docker containerization with multi-stage local build (c9dd06b)
- optional Steam/Discord providers + IGDB fallback to TWITCH_CLIENT_ID (99030ca)
- make Steam/Discord providers optional with IGDB fallback (b188f5c)
- add IgdbClient — thin IGDBWrapper wrapper with thread-safe API calls (cd797af)
- rewrite IgdbService with app-level token and two-level cache (226d9bd)
- add platform external IDs and age ratings tables to IGDB cache (08a6c07)
- persist external IDs and age ratings during IGDB preload (de570d3)
- resolve Twitch game ID from IGDB external games table (108cda6)
- preload IGDB cache from users' Steam libraries instead of top-500 (2a8a82b)
- batch fetch Steam app IDs from IGDB in a single API call (ca2f738)
- persist CCL suggestions to DB (igdb_game_ccl_cache) (ada725b)
- enrich CCLs with Steam store age rating data (cea4c5f)
- add per-binding CCL toggle and inline table editing (c8ced26)
- rewrite IGDB integration with app-level token, DB cache and CCL pipeline (0e46c4a)
- add steam_id column and replace Discord provider with Xbox/BattleNet (cc83691)
- replace Discord game getter with Xbox/BattleNet stubs, use Steam API key (d33877a)
- add Xbox/BattleNet OAuth2 registration and update settings page (e1c6aaa)
- add Steam integration and Xbox/BattleNet OAuth2 stubs (36e095c)
- add per-user activity log with SSE streaming to dashboard (1002cdd)
- add per-user activity log with SSE streaming (c3bfee4)
- add ROLE_ADMIN authority based on app.owner-id and V2 migration (9572cc3)
- add TwitchCclDefinition and IgdbAgeRatingCategory entities and repos (d71553b)
- add AdminCclService with Twitch/IGDB startup sync (6bdfeff)
- replace TwitchCcl enum with DB-driven String CCL IDs (c39ff4c)
- add AdminCclController, admin/ccl.html and admin nav link (37ee96a)
- replace IGDB age rating categories with content descriptors for CCL mapping (bf0220a)
- de-duplicate descriptor select and auto-expand mapping to all org variants (febd5d4)
- add admin CCL management with IGDB content descriptor sync (8fb8eb5)
- add Twitch token refresh (716905a)
- add Twitch token refresh (cd7efda)
- add StreamStateService to track live status and pending bindings (8518c48)
- add StreamOnlineEvent and StreamOfflineEvent (64ae9eb)
- add TwitchService.resetToDefault() to reset channel category on stream end (f200a03)
- add TwitchEventSubService with WebSocket EventSub connection management (7451709)
- gate Twitch updates behind live check, apply pending on stream.online (6f38547)
- add Twitch EventSub with live stream detection and pending bindings (d6bd725)
- add twitch-event-spy CLI for real-time event logging (899abb4)
- add twitch-event-spy CLI for real-time event logging (e96d019)
- add blanc and old-steam CSS theme variable blocks (ecfd76e)
- add floating theme switcher CSS (8e65f49)
- add FOUC-prevention theme script to head fragment (283fa2c)
- add setTheme and initThemeUI JS functions (0c70b5d)
- add floating theme switcher widget to nav fragment (3d52b8f)
- **old-steam:** override border-radius for sharp Windows XP era edges (12232a0)
- add Nord, Dracula, Catppuccin Mocha and Tokyo Night themes (8aa5e62)
- add CSS theme system with Nord, Dracula, Catppuccin and Tokyo Night themes (18027f9)
- implement UserDetails on CatapultOAuth2User for SwitchUserFilter support (8ace9eb)
- add admin/members.html template (c11d48e)
- add impersonation banner and members nav link (4378df5)
- add pill-toggle CSS component (6873438)
- refactor bot column to pill-toggle in members table (6a11362)
- refactor statut column to pill-toggle in members table (ab4306b)
- add admin account impersonation via SwitchUserFilter and members management (d1f2501)
- change ChatCommand.execute() return type to Object for chat responses (fdadc46)
- wire TwitchChatService into CommandRegistry for chat responses (a33503f)
- add Twitch chat service with IRC and EventSub implementations (bf9b18c)
- add V17 migration for twitch_category_cache table (088af9f)
- add Twitch category cache with DB-backed search and prewarm (1a8c5ad)
- add no_game_ccls to UserSettings and Flyway migration V19 (4bb0829)
- apply no-game CCLs in TwitchService.resetToDefault (9638bfb)
- add no-game category and CCL settings (2089672)
- add searchGames() to IgdbService (783da9c)
- support data-search-url override in gameSearch() (2fb7ac0)
- replace mock steam text inputs with IGDB autocomplete (86573ef)
- add IGDB game search endpoint with autocomplete integration (8f19845)
- i18n login and nav fragments (f394c34)
- i18n admin members page (1442901)
- add ICU4J i18n with EN/FR translations across all templates (63824df)
- refresh game if imcomplete (96d34b6)
- update notification event handling to include content classification labels (13889fe)
- add findBySourceId method to retrieve all external IDs for a given source (f3938f3)
- add mock-web profile for local development without OAuth2 (0e55746)
- add A/B testing migration (V20) (944ee97)
- annotation-based experiment definition with Thymeleaf dialect (869d1f2)
- add experiment_overrides migration (V21) (4786e12)
- seed ExperimentService knownKeys from DB at startup (ab07415)
- add HideForVariant, IfRolledOut, VariantAttribute processors and ensureExists integration (4a3f1d5)
- add #experiments expression utility object to Thymeleaf dialect (2fa133b)
- add annotation-driven experiment overrides with deferred user resolution in ExperimentSynchronizer (7f591dd)
- make ShowForVariantProcessor status-aware and add show-definition mode (97d5986)
- add third bot-right variant and per-user overrides to SidebarLayoutExperiment (710c0e1)
- add A/B testing engine with Thymeleaf dialect, overrides and rollout (1b242b0)
- add CSS classes for home landing page (d67cc67)
- add home landing page template (7164321)
- add footer with version info, GDPR privacy page and public landing page (c95895f)
- add profileImageUrl field to UserAccount (1c577be)
- persist Twitch profile_image_url on OAuth2 login (41aad1b)
- add channel card grid CSS classes (79e3195)
- add mock A/B experiment fixtures for dark-mode and notification-banner (cf6e6ae)
- add multi-channel support with owner/mod access control (5a2af76)
- add incomplete fallback fields to UserSettings (17cf59a)
- apply incomplete fallback game when binding is INCOMPLETE (1c9ce33)
- apply incomplete binding fallback game when binding is INCOMPLETE (f5cbcd7)
- add Micrometer Prometheus metrics for bindings and platform connections (a03817b)
- add ExperimentProviderBootstrap for startup sync on provider change (78a7fe1)

### Fixes
- use VARCHAR instead of PostgreSQL custom enums for Hibernate compatibility, switch ddl-auto to none (e80987f)
- add test application.properties with complete OAuth2 provider configuration (99803a2)
- rename OAuth2UserService to CatapultOAuth2UserService to avoid naming conflict, clean AccountService imports (533da7c)
- add app.css + explicit Flyway location + disable open-in-view (b76ccf3)
- explicit Flyway bean + DependsOn to guarantee migrations before scheduler (a993d5d)
- add app.css, fix Flyway location and explicit bean startup order (13ab00c)
- handle Twitch user info endpoint (Client-ID header + data[] unwrap) (d3adb01)
- add OAuth2 error logging and expose postgres port (a3524b1)
- add OAuth2 failure handler with explicit ERROR logging (e0a2a92)
- use CLIENT_SECRET_POST for Twitch — Twitch ignores Basic auth credentials (313c738)
- fix Twitch user info endpoint and OAuth2 login debugging (c791605)
- disable Spring Boot Flyway auto-config, always call migrate() explicitly (bbe3b1e)
- add @DependsOn("flyway") on IgdbService to guarantee migration order (1b4df81)
- persist preloaded IGDB games to DB cache on preload (8b20611)
- resolve Twitch category ID from Twitch API when IGDB external ID unavailable (a553e62)
- use age ratings as primary CCL signal, fix theme keyword matching (d84bee8)
- cache CCL suggestions in memory to avoid repeated IGDB API calls (d2daf02)
- filter read-only MatureGame CCL and preload CCLs at startup (fc5288b)
- always send full CCL payload to Twitch to clear removed labels (a8c2412)
- use data-id attributes in bindings.html to comply with Thymeleaf 3.1 security restrictions (876d459)
- mutate existing CCL collection instead of replacing it (e1bcfb0)
- push Twitch update immediately on binding edit or toggle (37a5783)
- remove LanguageBarrier CCL — not a valid Twitch label (913e643)
- add steam_id to UserAccount and remove DiscordGameGetter (prerequisite for Steam integration) (5f65886)
- remove DiscordGameGetter from GameGetterChain (only STEAM active until Xbox/BattleNet branch) (75f491d)
- rename V2__admin_ccl.sql to V11 to avoid Flyway version conflict (031209d)
- switch IGDB descriptor sync to /age_ratings to get organization context (5f8eb71)
- drop FK on ccl_igdb_mapping.igdb_rating_descriptor_id to allow seed migration (315588f)
- replace V13 seed migration with keyword-based default mapping at startup (88c126d)
- handle rate limit in doResetToDefault consistently with doUpdateChannel (b798642)
- use shared HttpClient and add PreDestroy shutdown in TwitchEventSubService (cca1715)
- keep websocat connection open after stdin EOF (1aa0c88)
- show subscription errors instead of silently crashing (8c9d59f)
- require user access token for WebSocket EventSub, add auth hint (4261076)
- improve theme-btn accessibility and button reset (9461e9e)
- use const and arrow functions in theme JS for consistency (ef96673)
- add serialVersionUID and missing isEnabled test to CatapultOAuth2User (a6ebbbb)
- correct page title in admin/members.html (d934c78)
- log warning when command result JSON serialization falls back to toString (e67b2a2)
- strip query before length check in searchGames() (3c7010a)
- use search-wrapper class for consistent dropdown positioning (9dc734d)
- require game selection before submit, remove redundant import (fa3b318)
- fall back to app-token search when user token absent for Twitch category lookup (5c257c3)
- add H2 database dependency (0c1f1b4)
- quote all H2 identifiers to avoid KEY reserved word conflict (1120cd8)
- use NON_KEYWORDS=KEY in H2 URL instead of globally_quoted_identifiers (4240724)
- variable reference (5bbbe9e)
- add ShowForVariantProcessor tests, warn on malformed exp: attributes, document precedence (773b36c)
- assert no service interactions for anonymous users in ExpressionObject tests (70f94fe)
- enable Thymeleaf expression object caching so assignment cache is effective per render (2b00618)
- missing parameter for ShowForVariantProcessor (1cdd9aa)
- display hero tagline on landing page (d009167)
- missing column internal_id in experiment_variants table (a6dfcac)

### Performance
- batch-fetch CCLs from IGDB during cache prewarm (2f84a9b)
- add indexes to A/B testing tables (31e586c)

### Refactoring
- remove user token from IGDB calls in BindingService (9143da6)
- use IGDB content_descriptions for CCL detection (e28efb2)
- use IGDB v4 age rating API and cache rating labels in DB (0bfec32)
- remove TwitchCcl enum and dead age-rating classes (ad7f82b)
- remove Xbox and BattleNet game getters (eb25d55)
- remove Xbox and BattleNet game getters (39a4722)
- extend template layout with main_class param and update login (ebb15ed)
- migrate admin/members to template layout (624a85d)
- add shared template layout and migrate all pages (764470b)
- extract createDescriptor() helper in AdminCclService.syncIgdbDescriptors() (b2b4494)
- fix ThemeContrastTest classpath resource and add pair count guard (c5e31eb)

### Documentation
- add IGDB redo design spec and implementation plan (ed8f345)

### Other
- add PostgreSQL service to test workflow for integration tests (8f098b7)
- remove temporary debug logging levels (fc7a4bc)
- add unit tests for CCL pipeline and bindings controller (b6ab1ff)
- add playwright-mcp and application-dev.properties to gitignore (150127b)
- add admin CCL and OAuth2User unit tests (2116155)
- add missing UserSettingsRepository mock and onNoGameDetected coverage (b8c9d23)
- **mock-web:** add application-mock-web.properties with H2 config (a61d546)
- add jsoup for HTML template tests (e6ecf6d)
- add WCAG AA contrast test for all CSS themes (b8a1cf5)
- add LoginTemplateTest for HTML structure (0df5508)
- add MockLoginTemplateTest for HTML structure (d6ed7d8)
- add AdminMembersTemplateTest for HTML structure (ab2b34c)
- add AdminExperimentsTemplateTest for HTML structure (ecb6e4e)
- add AdminCclTemplateTest for HTML structure (fd6c7db)
- inject git branch, commit and github url via bootBuildInfo (37cac8d)
- strengthen sources section assertion in HomeTemplateTest (e312987)
- upgrade Java toolchain from 17 to 21 (22926dd)
- ignore catapult_obs_local.py local script (b66dad3)
- add header comment to V24 migration (c657868)
- cover Optional.empty settings case in incomplete fallback (0b92c03)
- add Sonarqube analysis (98e2adb)

… et 1120 autres commits.

Historique non linéaire : comparé au commit initial.

Anciennement `v0.1.4-BETA`.

## [v1.0.0-BETA.70](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.70) - 2026-08-06

### Fixes
- **api:** pin spring-cloud-context to stable 5.0.2 instead of floating snapshot (ba09f51)

### Other
- **release:** bump version to 0.1.3-BETA (5e95f1f)

Anciennement `v0.1.3-BETA`.

## [v1.0.0-BETA.69](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.69) - 2026-08-06

### Features
- **metrics:** enable http percentile histograms, hibernate stats and instrumented GrowthBook client (14ad816)
- **metrics:** add ExternalApiObservations wrapper for external API calls (d772ddf)
- **metrics:** observe IGDB, DtDD and Steam external calls (8546724)
- **metrics:** observe Twitch, EventSub and GitLab external calls (6d89c76)
- **metrics:** count Steam and DtDD rate-limit hits (559b351)
- **metrics:** stream duration histogram and chat message counters (cb92099)
- **metrics:** gauges for chat connections and registered commands (ca690bd)
- **metrics:** time game detection chain with resolved_by tag (ce5ba10)
- **grafana:** provisioning structure and datasource variable (62d17a1)
- **grafana:** revamp catapult dashboard with hourly heatmap and new rows (826c3b2)
- enrich metrics and revamp Grafana dashboards (6d4e883)
- **metrics:** expose service token state and external API status codes (38cfd92)
- **minecraft:** clients Xbox et Minecraft (auth, friends, presence) (be34266)
- **minecraft:** entités comptes de service et liens d'amitié (db9d1d1)
- **minecraft:** lookup de profil pseudo vers UUID (3cd92d4)
- **minecraft:** client OAuth MSA (refresh et device-code) (9c0e93a)
- **minecraft:** service de token avec cache et rotation du refresh MSA (3d04d15)
- **minecraft:** enrôlement d'amis avec remplissage séquentiel des comptes (9d3c880)
- **minecraft:** sync planifiée des liens d'amitié (6f05f54)
- **minecraft:** getter de présence branché dans la chaîne de détection (66f9c84)
- **minecraft:** API de liaison de compte pour les utilisateurs (65ba1bb)
- **minecraft:** API admin des comptes de service avec enrôlement device-code (1e59d0b)
- **minecraft:** configuration du getter de présence (f3b4a57)
- **web:** méthodes ApiClient pour la liaison et l'admin Minecraft (d4691c0)
- **web:** bloc de liaison Minecraft dans les connections (25dc584)
- **web:** page admin des comptes de service Minecraft (045d7e8)
- **web:** enrôlement device-code des comptes Minecraft en admin (34437a4)
- **minecraft:** endpoint de vérification immédiate du lien (dc7cf2f)
- **web:** bouton Vérifier déclenche la sync et correction des apostrophes i18n (004606d)
- **minecraft:** experiment minecraft.integration et gate centralisé (364d150)
- **minecraft:** API de liaison gatée par l'experiment (805aafd)
- **minecraft:** détection gatée par l'experiment (lecture seule) (485910f)
- **minecraft:** section d'aide conditionnée par l'experiment (09a877e)
- **chat:** commande \!debug réservée à l'owner de l'application (a076265)
- **chat:** commande !debug réservée à l'owner de l'application (e28611e)
- merge master into feat/minecraft-integration (53bc094)
- **minecraft:** getter de présence via l'API Friends/Presence (c4a9c7f)
- **xbox:** provider XBOX et relying party paramétrable sur XboxService (6793107)
- **xbox:** XboxUserTokenService pour le token XSTS par utilisateur (fb74d69)
- **xbox:** détection de jeu via l'API de présence Xbox Live (e373310)
- **xbox:** liaison de compte via OAuth2 et persistance du refresh token (8c3ffe8)
- **xbox:** carte de connexion Xbox dans les paramètres de chaîne (e341a6d)
- **web:** add .well-known (e1d8f0a)
- intégration Xbox (détection de présence via OAuth2) (76edc83)
- **xbox:** blacklist configurable d'IDs de titre à ignorer (6b46af1)
- **api:** ajoute le provider Xbox au panneau d'aide des connexions (1ae815f)
- **web,api:** ajoute le layout à onglets de la page chaîne (expérience 50/50) (663a4b9)
- **web:** ajoute l'onglet Invitations et fixe le placement du lien d'invitation (ae00250)
- **api,web:** réintègre le placement Invitations dans l'expérience A/B au lieu de le fixer (554856c)
- layout à onglets de la page chaîne (expérience 50/50) (8d5b180)
- **monitoring:** expose Minecraft service accounts on the Grafana dashboard (3e9c797)
- **minecraft:** allow re-authenticating an existing service account (714e580)
- **minecraft:** hide the connect option when no service account has capacity (0439866)
- **dtdd:** import DTDD API keys from config into the DB pool on startup (316b016)
- **channel:** add the missing invitations-tab fragment for the tab variant (a925060)
- **web:** serve 3rd-party domain verification files publicly (7a62fc9)
- **chat-commands:** add AST node model for block/DSL editor (d756f17)
- **chat-commands:** add If/ForEach node types behind an extensible registry (a7c0fd2)
- **chat-commands:** add extensible registry for whitelisted service functions (1ea30f0)
- **chat-commands:** wire igdb/twitch/steam service functions through a gateway (840b0eb)
- **chat-commands:** add DSL parser for literals, placeholders and service calls (b1a9f8b)
- **chat-commands:** add DSL generator with parser round-trip coverage (5c7269e)
- **chat-commands:** parse and generate {if}/{for} control-flow DSL syntax (38806ed)
- **chat-commands:** add JSON codec for the command AST (ce817a1)
- **chat-commands:** compile the command AST to JavaScript (37bc1e1)
- **chat-commands:** execute compiled command JS in a GraalJS sandbox (27db8ce)
- **chat-commands:** bind whitelisted service functions into the sandbox ctx.call (d07440a)
- **chat-commands:** migrate existing templates into the new AST column (02099d3)
- **chat-commands:** execute dynamic commands through the AST/JS sandbox pipeline (80d9cdc)
- **chat-commands:** add chat-commands.test WS action for the editor's Tester button (dee83da)
- **chat-commands:** add step-by-step execution trace and a Tester endpoint (03464e0)
- **chat-commands:** replace inline row editing with a tabbed editor modal (0277f3d)
- **chat-commands:** integrate Blockly with custom blocks mapped to the AST (948ff32)
- **chat-command-ast:** replace CommandNode with Statement/Expression core types (917877b)
- **chat-command-ast:** replace CommandNode with Statement/Expression core types (18cb12e)
- **chat-command-ast:** add Expression node types (literal/var-ref/context-get/service-call/binary) (b84d333)
- **chat-command-ast:** add Statement node types (var-decl/assign/concat/print/if/for-each) (1052fca)
- **chat-command-ast:** rewrite NodeJsonCodec for the statement/expression AST (1f58f80)
- **chat-command-registry:** rework StructuralNodeType(Registry) for IfStatement/ForEachStatement (a138a1b)
- **chat-command-dsl:** rewrite CommandDslParser for the statement/expression grammar (c91e62d)
- **chat-command-dsl:** rewrite CommandDslGenerator for the statement/expression grammar (006e597)
- **chat-command-js:** rewrite JsCompiler for statement codegen with native JS block scoping (b025a14)
- **chat-command-js:** bind __trace.var/__trace.branch hooks for statement-level execution trace (772c9a9)
- **chat-command-api:** use JsCompiler#compileWithTrace for the Tester endpoint (b81cb85)
- **chat-command-editor:** rework Blockly blocks for the statement/expression AST (8545755)
- **chat-command-editor:** organize the Blockly toolbox into categories (e7c2711)
- **chat-command-editor:** make the Blockly editor follow the site theme (637048e)
- **chat-command-editor:** serve the Blockly catalog from the server (2c930ce)
- **chat-command-editor:** populate the read-only JS généré tab (ba044cc)
- **chat-command-editor:** let the Tester button use manual placeholder overrides (ec549c7)

### Fixes
- **grafana:** restore typographic title on hourly heatmap panel (a5f2377)
- **metrics:** publish percentile histogram for external api timer (c596845)
- **grafana:** replace dead application label filters in catapult dashboard (7f1819d)
- **grafana:** merge WS dashboard into main and repair No Data panels (0807226)
- **metrics:** register lazy meters eagerly and enable WS duration histograms (1e97a5e)
- **grafana:** dashboard unifié, No Data et nouvelles métriques tokens/status (4f83df4)
- **eventsub:** survie aux session_reconnect Twitch (perte totale après ~2h) (d0f51e1)
- **minecraft:** encodage du pseudo dans l'URL de lookup (7eb86d3)
- **minecraft:** détection stricte de authorization_pending dans le device-code (9f8bd85)
- **minecraft:** persistance du flag limite d'amis hors transaction d'enrôlement (9886603)
- **minecraft:** messages d'erreur fixes et couverture des branches de l'API de liaison (73c39b9)
- **minecraft:** 502 garanti sur échec de chaîne et comptage des liens sans N+1 (266aad5)
- **minecraft:** active les clients Xbox et Minecraft si l'un des deux flags est actif (764e522)
- **minecraft:** réutilisation du lien existant au ré-enrôlement (contrainte user_id unique) (28f4067)
- **minecraft:** pas de transaction tenue pendant les appels HTTP de la sync (704b715)
- **minecraft:** validation des types du PATCH admin (74458fd)
- **web:** device-code start typé et test du Bearer sur le chemin exchange (dec46db)
- **web:** message PENDING sans th:utext et validation serveur du pseudo (432d0ad)
- **web:** device-code robuste au double-clic et validation du lien Microsoft (e5f8cff)
- **web:** normalisation camelCase de la réponse device-code pour le front (25550b9)
- **web:** rendu DOM sans innerHTML dans l'enrôlement device-code (c9bdd5f)
- **minecraft:** verrou de refresh par compte de service (7e362c1)
- **web:** bloc Minecraft masqué quand l'intégration est désactivée (6c5980b)
- **minecraft:** erreur MSA du device-code exposée en 502 au lieu d'un 500 opaque (4796d4c)
- **web:** message d'erreur MSA affiché dans l'enrôlement device-code (f907beb)
- **minecraft:** validation d'enrôlement sans effet de bord (NPE sur compte transient) (c0bcd8a)
- **minecraft:** chargement du compte de service avec le lien (OSIV désactivé) (5178a6b)
- **minecraft:** matching des profileId insensible au format d'UUID (826d8fc)
- **minecraft:** activation du getter dans la chaîne de détection à l'enrôlement (0950642)
- **minecraft:** présence périmée ignorée (jeu fermé sans statut OFFLINE) (262a88a)
- **minecraft:** aide robuste aux JWT non-utilisateur (2027207)
- **minecraft:** le gate prime sur la validation du pseudo et client-id trimé (c8274cf)
- **chat:** séparateur '#' dans les placeholders pour éviter la détection de lien Twitch (740c39f)
- **metrics:** retire les secrets et IDs bruts des labels http.client.requests (becc87d)
- **db:** renomme la migration Minecraft V59 en V60 (fd57ccb)
- **xbox:** redirect_uri stable au lieu du template dynamique Spring (8be82cb)
- **xbox:** configure jwkSetUri pour la vérification du id_token (53ff2b2)
- **xbox:** retire l'appel Microsoft Graph, inutile et incompatible (e94cbce)
- **xbox:** redirige vers catapult-web après la liaison, pas /dashboard (8f15b7b)
- **xbox:** retire le scope openid, la vraie cause du bypass (59ef728)
- **web:** Dont display error message when api is starting (b50768b)
- **api:** When in dev mode, basic route can't be accessed (2819aa3)
- **web:** Add Minecraft provider (16f9773)
- **web:** app.name not reflected everywhere (3838117)
- **api:** migration not commited (8182971)
- **web:** .well-known endpoint not accessible in public (2199e22)
- **web:** .well-known endpoint for microsoft need to finish with .json (a98eb24)
- **web:** add .well-known properties to env var (960cb98)
- **api:** add xbox.enabled to env var (78aabee)
- **xbox:** sessions distinctes pour catapult-api et catapult-web (3b6dcb3)
- **xbox:** active le GetterConfig XBOX à la liaison de compte (4e11159)
- **xbox:** corrige le relying party XSTS (http au lieu de https) (f73985e)
- **xbox:** corrige le chemin de l'API de présence Xbox Live (7a2a4f4)
- **xbox,web:** redirects relatifs pour contourner les en-têtes X-Forwarded incorrects (cb1ff1c)
- merge les correctifs Xbox restants dans master (9dc3af2)
- **web:** propage la locale de l'utilisateur vers l'API pour les panneaux d'aide (d178ba7)
- **web:** corrige les panneaux d'onglets jamais masqués et déplace la navigation (374063c)
- **web:** charge channel-tabs.js globalement et centre les onglets dans la navbar (ce48dac)
- **web:** corrige l'URL des onglets cassée par channelUsername indéfini (33eafc6)
- **web:** corrige le retour arrière navigateur sur les onglets de la page chaîne (74a44fd)
- **db:** ajoute la variante "tab" à l'expérience invite-button-placement en base (b86bead)
- **web:** réexécute les <script> inline lors du chargement paresseux d'un onglet (e82e606)
- **web:** construit un vrai CatapultWebUser pour les requêtes mvc bridgées en WS (3615f49)
- **web:** affiche la page de connexion au lieu de reboucler sur Twitch après un échec (103a4fc)
- **web:** prevent path traversal and WebSocket route shadowing in verification endpoint (f0894d5)
- **api:** replace '3' with 'thi' (d54a791)
- **chat-command:** observe Steam price gateway calls and harden twitch lookup (74e1bf6)
- **chat-commands:** error on unclosed brace and fix arg splitting on quoted commas (1d19025)
- **chat-dsl:** raise CommandDslParseException on malformed if/for headers (cae864d)
- **chat-command:** escape placeholder path and service call names in generated JS (70abc74)
- **js-compiler:** escape ForEachNode listSource in ctx.list() call (41d56ac)
- **chat-command:** validate loop binding names before splicing into JS (2986ae5)
- **chat-commands:** harden GraalJS sandbox timeout cancellation path (eef9e08)
- **chat:** isolate per-row errors in chat command AST backfill (79e3967)
- **chat-commands:** persist client-supplied ast on update, not just template (1234b59)
- **chat-commands:** require ownership and the experiment gate on the test endpoint (e604065)
- **chat-commands:** move Blockly script tags inside the editor modal fragment (124ad2b)
- **chat-commands:** harden Blocks<->AST conversion and surface editor errors (4dde7c0)
- **chat-commands:** wait for Blockly to actually load before wiring the editor (8dc2466)
- **chat-command:** drop redundant parens in compiled if-conditions and align round-trip test with canonical print output (11c7455)
- **chat-command:** re-backfill AST rows still in the old expression-tree shape (ef4c9cf)
- **chat-command:** ignore braces inside string literals when scanning tag boundaries (ee94d1b)
- **web:** preserve execution order for dynamically re-inserted script tags (891164a)
- **chat-command-api:** return 400 instead of a leaked 500 for malformed DSL text/AST (b25a465)
- **chat-command-ws:** detect Spring's 4xx error body, not just null, from the DSL endpoints (c98fb93)
- **chat-command-dsl:** tolerate legacy dot-separated context paths (game.name) (d2e1beb)
- **chat-command-editor:** stop wiping fallback config on every Blocks/Text save (7131dc3)
- **chat-command-editor:** walk the full statement chain when converting Blocks to AST (6c7674e)
- **chat-command-registry:** actually register ServiceFunction beans at startup (812ac97)
- **chat-command-test:** resolve missing test overrides to empty string, not null (ecfbb13)

### Refactoring
- **chat-command-editor:** one class per Blockly block, behind a shared contract (0fef534)

### Documentation
- **minecraft:** compatibilité de la détection dans l'aide des connexions (90cfbb8)

### Other
- adapt ActiveProviderHolderTest to RestClient.Builder injection (cb77ff3)
- adapt IrcTwitchChatServiceTest to MeterRegistry injection (27fa838)
- **release:** bump version to 0.10.1-ALPHA (1ec8287)
- **release:** bump version to 0.10.2-ALPHA (598ea79)
- **release:** bump version to 0.10.3-ALPHA (80f1da7)
- **minecraft:** vérifie le succès de la chaîne après éviction du cache (ec09593)
- **minecraft:** couvre l'échec de getFriends dans la sync des liens (adc24b8)
- **minecraft:** nettoyage de la dette de revue (tokenState, code mort, @PreUpdate) (5c58c17)
- **release:** bump version to 0.10.4-ALPHA (bd69dcb)
- **release:** bump version to 0.10.5-ALPHA (70e6d12)
- **release:** bump version to 0.10.6-ALPHA (229505c)
- **release:** bump version to 0.10.7-ALPHA (66a40f9)
- **build:** include org.springframework.boot in the spring.io snapshot filter (258e79b)
- **release:** bump version to 0.10.8-ALPHA (978758b)
- **release:** bump version to 0.10.9-ALPHA (e691818)
- **chat-commands:** add ServiceCallNode coverage to round-trip test (74dca0b)
- Revert "feat(chat-command-ast): replace CommandNode with Statement/Expression core types" (783fc4f)
- **chat-command:** update LegacyTemplateConverter/backfill tests for the new AST JSON shape (c41e89e)

… et 145 autres commits.

Anciennement `v0.1.2-BETA`.

## [v1.0.0-BETA.68](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.68) - 2026-07-05

### Features
- **api:** add user groups and user flags entities (5bbb754)
- **api:** add typed attribute values and operator evaluator (8155265)
- **api:** add account and active-experiment attribute resolvers (9238db7)
- **api:** add group, flag and assigned-experiment resolvers (615e466)
- **api:** add attribute evaluator registry (5517240)
- **api:** target groups and experiment assignment in A/B rules (81d8248)
- **api:** add admin groups CRUD endpoints (2c687fc)
- **api:** manage per-member flags and group membership (28676a4)
- **web:** admin page to manage user groups (a25265a)
- **web:** admin UI for member flags and group membership (3f5dc2c)
- **web:** target groups and experiments in the A/B rule builder (21a755c)
- user groups and flags for A/B test targeting (c9e6aaf)
- **admin:** publish key add/delete/refresh events on events.admin channel (e33eb3d)
- **admin:** live-update steam/dtdd key pages over WebSocket events (ef85fa6)
- **admin:** publish ccl refresh/mappings and tw definition events (9c33356)
- **admin:** live-update ccl and tw admin pages over WebSocket events (a859d3c)
- **admin:** publish dtdd mapping proposal resolution events (bf60312)
- **admin:** live-remove resolved dtdd mapping proposals over WebSocket (60fb5c7)
- **admin:** publish user-group created/updated/deleted events (9dd78ea)
- **admin:** live-update user groups admin page over WebSocket events (0172895)
- **admin:** re-render whitelist section over WebSocket instead of page reload (c3df2fc)
- **admin:** re-render members section over WebSocket instead of page reload (75a60b1)
- **admin:** re-render member-targeting and experiment-detail sections over WebSocket (fd441f0)
- **admin:** WS event-driven steam/dtdd key pages + search.game handler (c1770fe)

### Fixes
- **api:** align DTDD tests with reviewed parsing and null-safe media filter (790ab39)
- repair DTDD parsing tests to unblock master CI (6b83a46)
- **api:** silence H2 DDL warnings and align user_flag column length (71640bf)
- **api:** guard lazy group members read and broaden groups controller tests (c762cad)
- **api:** use writable transaction for group rename (ce8e673)
- **api:** make deleted-group rules fail closed and fix group key trim check (9f917e8)
- **ws:** search.game handler for admin members IGDB search over WebSocket (29836d5)
- **ws:** gate events.admin on ROLE_ADMIN so admins actually receive admin events (c320aab)
- **admin:** bind cloned key/ccl rows so their ws:* forms fire over WebSocket (116ef41)
- **admin:** use non-underscore placeholder for cloned group row ids (Thymeleaf eats __x__) (6a8fc9d)
- **ws:** keep event-driven admin/channel scripts working across hx-boost navigation (4f1e154)
- **ws:** disable hx-boost on logout/impersonate/login so session changes navigate the page (07f84bc)
- **ws:** make hx-boost navigation follow server redirects via HX-Redirect (f59473b)

### Other
- **api:** add curl and jq to container (e71ef61)
- **api:** add curl and jq to container (414f139)
- local change with remote (6f5721b)
- **release:** bump version to 0.10.0 (25f3ba1)

Anciennement `v0.10.0-ALPHA`.

## [v1.0.0-BETA.67](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.67) - 2026-08-02

### Fixes
- **api:** block Gambling CCL by default for new streamers (52eb472)

### Other
- **release:** bump version to 0.1.1-BETA (b44d980)

Anciennement `v0.1.1-BETA`.

## [v1.0.0-BETA.66](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.66) - 2026-08-02

### Features
- **api,web:** add an admin page for in-memory cache inspection (5be6790)
- **api:** paginate and search admin cache entries (f402964)
- **api:** resolve the full DB entity behind each admin cache entry (88da55b)
- **web:** wire admin cache pagination, search and detail routes (ce8d5e7)
- **web:** add search and pagination to the admin cache entries page (b4adfa2)
- **web:** render the full DB entity behind an admin cache entry (14a0414)
- **api:** add generic scalar value coercion for the admin data viewer (4de05a1)
- **api:** add id codec for simple and composite primary keys (b2b6e6e)
- **api:** auto-discover JPA repositories for the admin data viewer (f026f67)
- **api:** classify entity attributes for the admin data viewer (ceefc56)
- **api:** add generic paginated and searchable entity list for the admin data viewer (3087bf3)
- **api:** add generic create, update, and detail endpoints for the admin data viewer (cdaf84e)
- **api:** add generic delete endpoint with FK-violation handling for the admin data viewer (e2d6024)
- **web:** add the generic admin data viewer UI (3a8471a)
- merge admin data viewer into master (c78d35b)

### Fixes
- **api:** update stale test for the all-TW catalog query (66b0aab)
- **api:** resolve allTws in the command Tester too (c3f61e4)
- **api:** initialize collection relations and reject unresolved relation targets in the admin data viewer (96d5560)
- **web:** prevent silent create failures and open redirect in the admin data viewer (eb5dfb3)
- **api:** make admin-data list transactional and relation values round-trippable (4b23091)
- **web:** surface admin-data save/delete failures instead of redirecting as success (1c2cb9f)
- **api:** normalize roman numerals in twitch title matching (c8e3465)
- **web:** add missing admin nav links for caches and data viewer (39ea1e8)

### Other
- **release:** bump version to 0.11.3-ALPHA (f4b40a6)
- **api:** log when a repository bean is skipped from the admin data registry (f849f1f)
- **release:** bump version to 0.1.0-BETA (479136a)

Anciennement `v0.1.0-BETA`.

## [v1.0.0-BETA.65](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.65) - 2026-07-31

### Features
- **api:** rate-limit Twitch chat command sends (c9c59c6)
- **api:** list disabled TWs too in the DSL command catalog (e826b89)
- **api:** instrument the chat command DSL execution pipeline (6ce2c93)
- **grafana:** add DSL pipeline panels to the Chat row (5d10001)
- **api,web:** add an "allTws" DSL list for the full TW catalog (23e448e)

### Fixes
- **web:** externalize TW picker strings and add V54 label catalog (04eec73)

### Other
- **release:** bump version to 0.11.1-ALPHA (8fd91f0)
- **release:** bump version to 0.11.2-ALPHA (4b7f788)

Anciennement `v0.11.2-ALPHA`.

## [v1.0.0-BETA.64](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.64) - 2026-07-30

### Fixes
- **api:** don't crash on boot when a custom command has no presetKey (57afc87)
- **api:** recognize the ContextGetMigration-rewritten form of old preset defaults (0ee001c)

### Other
- **release:** bump version to 0.11.0-ALPHA (2e83945)

Anciennement `v0.11.0-ALPHA`.

## [v1.0.0-BETA.63](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.63) - 2026-07-30

### Features
- **chat-commands:** add AST node model for block/DSL editor (d756f17)
- **chat-commands:** add If/ForEach node types behind an extensible registry (a7c0fd2)
- **chat-commands:** add extensible registry for whitelisted service functions (1ea30f0)
- **chat-commands:** wire igdb/twitch/steam service functions through a gateway (840b0eb)
- **chat-commands:** add DSL parser for literals, placeholders and service calls (b1a9f8b)
- **chat-commands:** add DSL generator with parser round-trip coverage (5c7269e)
- **chat-commands:** parse and generate {if}/{for} control-flow DSL syntax (38806ed)
- **chat-commands:** add JSON codec for the command AST (ce817a1)
- **chat-commands:** compile the command AST to JavaScript (37bc1e1)
- **chat-commands:** execute compiled command JS in a GraalJS sandbox (27db8ce)
- **chat-commands:** bind whitelisted service functions into the sandbox ctx.call (d07440a)
- **chat-commands:** migrate existing templates into the new AST column (02099d3)
- **chat-commands:** execute dynamic commands through the AST/JS sandbox pipeline (80d9cdc)
- **chat-commands:** add chat-commands.test WS action for the editor's Tester button (dee83da)
- **chat-commands:** add step-by-step execution trace and a Tester endpoint (03464e0)
- **chat-commands:** replace inline row editing with a tabbed editor modal (0277f3d)
- **chat-commands:** integrate Blockly with custom blocks mapped to the AST (948ff32)
- **chat-command-ast:** replace CommandNode with Statement/Expression core types (917877b)
- **chat-command-ast:** replace CommandNode with Statement/Expression core types (18cb12e)
- **chat-command-ast:** add Expression node types (literal/var-ref/context-get/service-call/binary) (b84d333)
- **chat-command-ast:** add Statement node types (var-decl/assign/concat/print/if/for-each) (1052fca)
- **chat-command-ast:** rewrite NodeJsonCodec for the statement/expression AST (1f58f80)
- **chat-command-registry:** rework StructuralNodeType(Registry) for IfStatement/ForEachStatement (a138a1b)
- **chat-command-dsl:** rewrite CommandDslParser for the statement/expression grammar (c91e62d)
- **chat-command-dsl:** rewrite CommandDslGenerator for the statement/expression grammar (006e597)
- **chat-command-js:** rewrite JsCompiler for statement codegen with native JS block scoping (b025a14)
- **chat-command-js:** bind __trace.var/__trace.branch hooks for statement-level execution trace (772c9a9)
- **chat-command-api:** use JsCompiler#compileWithTrace for the Tester endpoint (b81cb85)
- **chat-command-editor:** rework Blockly blocks for the statement/expression AST (8545755)
- **chat-command-editor:** organize the Blockly toolbox into categories (e7c2711)
- **chat-command-editor:** make the Blockly editor follow the site theme (637048e)
- **chat-command-editor:** serve the Blockly catalog from the server (2c930ce)
- **chat-command-editor:** populate the read-only JS généré tab (ba044cc)
- **chat-command-editor:** let the Tester button use manual placeholder overrides (ec549c7)
- **chat-command-editor:** compact test-values grid + import a game from IGDB (46cd79e)
- **chat-command:** add reversible "eject to JS" (Phase 2) (8dd41c0)
- **chat-command-editor:** wire up eject/revert to JS in the editor UI (c9a0f53)
- **chat-command:** add object literals grouping several values into one variable (95a7738)
- **chat-command-editor:** add Blockly blocks for object literals (7bbf45b)
- **chat-command-registry:** thread invoking UserAccount into ServiceFunction#invoke (20ed117)
- **chat-command-js:** thread invoking UserAccount through SandboxExecutor's ctx.call (b63073e)
- **chat-command-param:** add ChatCommandParam entity, repository, migration (9320a45)
- **chat-command:** add ParamGetExpr, parsed from ctx.params.<key> (35f8cf9)
- **chat-command-js:** compile ctx.a.b to a real dot-chain at runtime (263f654)
- **chat-command:** regenerate stored templates to the current canonical spelling on boot (a59628f)
- **chat-command-js:** add ctx.param(key) sandbox primitive and compile-time setup lines (1666d7b)
- **chat-command:** resolve ctx.params from the real per-streamer param store (18f8cda)
- **chat-command-params:** add REST endpoints for managing per-streamer params (35a216f)
- **chat-command-params:** add WS handlers for list/set/delete (8f14a98)
- **chat-command-editor:** add a Paramètres UI section to the commands page (3957a4e)
- **chat-commands:** add a Blocks-tab block for streamer settings (28341c0)
- **chat-commands:** add ArgGetExpr AST node for chat command arguments (b1b4157)
- **chat-commands:** parse arg(N) as an ArgGetExpr (0e750d1)
- **chat-commands:** generate arg(N) text from ArgGetExpr (d03bfcd)
- **chat-commands:** compile arg(N) to a ctx.list("args") lookup (79d99c7)
- **chat-commands:** resolve arg(N) and {for a in args} at real dispatch time (285ed78)
- **chat-commands:** let the Tester endpoint supply test args (9fb2f86)
- **chat-commands:** add a Blocks-tab block for arg(N) (4fa0e7d)
- **chat-commands:** persist granted Twitch OAuth scopes on (re)auth (afd7320)
- **twitch:** add stream info, user profile, follow date and shoutout to TwitchChatService (fcb2364)
- **chat-commands:** expose Twitch stream/user/moderation functions to the DSL (b171766)
- **chat-commands:** collect service calls referenced in a command's AST (cfa18b5)
- **chat-commands:** report missing Twitch scopes when saving a command (c64db5a)
- **chat-command-editor:** show a reconnect banner on Twitch scope gaps (283c515)
- **steam:** expose player display name, online status and playtime (77b3a7a)
- **chat-commands:** expose steam#getProfile and steam#playtime to the DSL (205018d)
- **chat-commands:** resolve {for c in ownCommands} to the streamer's own enabled commands (28eba4d)
- **chat-commands:** expose Catapult account state and setParam to the DSL (f2b9b17)
- **igdb:** cache rating, aggregated rating, platforms, DLCs and similar games (263dac0)
- **chat-commands:** thread rating/platforms/dlcs/similar games through GameContext (01bdff3)
- **chat-commands:** expose game#rating, game#critic_rating and game#platforms (4e429df)
- **chat-commands:** resolve {for d in gameDlcs} and {for g in similarGames} (a4b7167)
- **chat-command-editor:** give each service its own Blocks toolbox category (470b0b3)
- **chat-commands:** declare service function return keys in the catalog (e8e82e3)
- **chat-command-editor:** pre-fill property-get field with known object keys (01aad8a)
- **chat-commands:** support arg(N, "default") with a literal string default (5766666)
- **chat-commands:** support optional trailing service function parameters (b335cc3)
- **chat-command-editor:** expose default values and optional params in the Blocks tab (0999251)
- **chat-commands:** finish steam#getGame(appId, locale) (b30bd1d)
- **chat-command-editor:** add a suggestion dropdown next to the property field (ae8d361)
- **chat-commands:** declare action-only service functions in the catalog (bce14e6)
- **chat-command-editor:** render action-only functions as stackable statement blocks (9028107)
- **chat-command-editor:** add a fixed entry-point block to the Blocks tab (85a9e42)
- **chat-commands:** add steam#getCurrentGame() resolving the streamer's active game (517b5ae)
- **chat-commands:** add igdb#getCurrentGame() exposing enriched game details (da699f1)
- **chat-commands:** add tw#active() and {for t in activeTws} (bb7b5f5)
- **chat-command-editor:** label the tw namespace category TW in the toolbox (ad2563b)
- **chat-command-editor:** enable zoom controls on the Blocks workspace (dd915f6)
- **chat-command-editor:** add fullscreen toggle and larger default modal size (38f5c60)
- **chat-command-editor:** add tw#has, a list-source picker, and service-call mocking for Tester (b0f9dd2)
- **chat-commands:** migrate legacy game#/tw#active placeholders to service calls (d4fe500)
- **chat-commands:** compile service calls to namespace.function() instead of ctx.call (ab54711)
- **chat-commands:** expand permission tiers to VIEWERS/FOLLOWERS/SUBS/VIP/MODERATOR/BROADCASTER (6d003a7)
- **chat-command-editor:** let a streamer change any command's permission, including builtins (ec0be80)
- **chat-commands:** add str#/arr# basic string and array operations (6b843fd)
- **chat-commands:** add list(name) to read command argument lists as a value (af0e779)
- **chat-commands:** migrate !setgame to a data-driven preset (9bc666c)
- **chat-commands:** modernize presets off legacy game#/tw# placeholders (e587521)
- **chat-commands:** let !debug run ad-hoc templates and unsandboxed JS (d5b24e2)
- chat command block/DSL editor (d389227)

### Fixes
- **chat-command:** observe Steam price gateway calls and harden twitch lookup (74e1bf6)
- **chat-commands:** error on unclosed brace and fix arg splitting on quoted commas (1d19025)
- **chat-dsl:** raise CommandDslParseException on malformed if/for headers (cae864d)
- **chat-command:** escape placeholder path and service call names in generated JS (70abc74)
- **js-compiler:** escape ForEachNode listSource in ctx.list() call (41d56ac)
- **chat-command:** validate loop binding names before splicing into JS (2986ae5)
- **chat-commands:** harden GraalJS sandbox timeout cancellation path (eef9e08)
- **chat:** isolate per-row errors in chat command AST backfill (79e3967)
- **chat-commands:** persist client-supplied ast on update, not just template (1234b59)
- **chat-commands:** require ownership and the experiment gate on the test endpoint (e604065)
- **chat-commands:** move Blockly script tags inside the editor modal fragment (124ad2b)
- **chat-commands:** harden Blocks<->AST conversion and surface editor errors (4dde7c0)
- **chat-commands:** wait for Blockly to actually load before wiring the editor (8dc2466)
- **chat-command:** drop redundant parens in compiled if-conditions and align round-trip test with canonical print output (11c7455)
- **chat-command:** re-backfill AST rows still in the old expression-tree shape (ef4c9cf)
- **chat-command:** ignore braces inside string literals when scanning tag boundaries (ee94d1b)
- **web:** preserve execution order for dynamically re-inserted script tags (891164a)
- **chat-command-api:** return 400 instead of a leaked 500 for malformed DSL text/AST (b25a465)
- **chat-command-ws:** detect Spring's 4xx error body, not just null, from the DSL endpoints (c98fb93)
- **chat-command-dsl:** tolerate legacy dot-separated context paths (game.name) (d2e1beb)
- **chat-command-editor:** stop wiping fallback config on every Blocks/Text save (7131dc3)
- **chat-command-editor:** walk the full statement chain when converting Blocks to AST (6c7674e)
- **chat-command-registry:** actually register ServiceFunction beans at startup (812ac97)
- **chat-command-test:** resolve missing test overrides to empty string, not null (ecfbb13)
- **chat-command-editor:** scroll the modal instead of overflowing, persist test values across commands (ff683ec)
- **chat-command:** update remaining tests for ServiceFunction/SandboxExecutor user param (cdeb709)
- **chat-command:** finish test updates for the ctx./. canonical DSL spelling (ba4c50f)
- **chat-command-params:** reject reserved prototype-pollution key names at write time (b2e3cd1)
- **chat-command-test:** resolve ctx.params from the real saved value in the Tester (64e95a2)
- **chat-commands:** bounds-check ctx.list index reads in the sandbox (be90537)
- **chat-commands:** forward the real update response instead of echoing the request (4a68a4f)
- **chat-commands:** remove catapult#experimentVariant/isRolledOut DSL functions (762fef0)
- **chat-commands:** allow map access so object-returning service calls work (ef73a0b)
- **chat-commands:** parse .property access chained after a service call (297f271)
- **chat-command-editor:** revert get [obj][prop] to a free-text property field (9c90bce)
- **chat-commands:** finish catapult#getGame() as a typed result DTO (e6b3de9)
- **chat-commands:** stop flagging bare {else} as an unknown placeholder (162a9bb)
- **channel-tabs:** don't let one tab fragment's script failure block the rest (39742f9)
- **chat-command-editor:** fix broken zoom controls and non-working fullscreen toggle (f55aea5)
- **chat-command-editor:** scale the Blocks pane with the modal instead of a fixed height (29505fd)
- **chat-command-editor:** give the Blocks pane a min-height floor (65dd90a)
- **chat-commands:** validate service-call namespace/function before splicing into compiled JS (bb9be5a)
- **chat-commands:** fix igdb#getGame() and add IGDB sub-type functions (b635544)
- **chat-command-editor:** fix Blocks-tab crash from block render order (1eb25ee)
- **chat-commands:** recognize {var}-declared names during placeholder validation (b043555)
- merge master into feat/chat-command-block-dsl, resolve IGDB fields conflict (dd19404)

### Performance
- **chat-commands:** skip __output += (...) for printed action calls (193f6ab)

### Refactoring
- **chat-command-editor:** one class per Blockly block, behind a shared contract (0fef534)
- **chat-commands:** rename params store to settings (d3d88da)
- **chat-commands:** extract a shared duration formatter (8b1645e)
- **chat-commands:** organize ServiceFunction implementations by service package (62306ce)
- **chat-commands:** back map-returning functions with typed result DTOs (41b2f4a)
- **chat-command-editor:** drop legacy mock fields, mock service calls per return field, add a TW picker block (427134a)
- **chat-command-editor:** wire the field-by-field mocks, drop legacy overrides UI, and the TW picker into the catalog/modal (113c00f)

### Other
- **chat-commands:** add ServiceCallNode coverage to round-trip test (74dca0b)
- Revert "feat(chat-command-ast): replace CommandNode with Statement/Expression core types" (783fc4f)
- **chat-command:** update LegacyTemplateConverter/backfill tests for the new AST JSON shape (c41e89e)
- **chat-command:** prove invoking user reaches ServiceFunction end-to-end (2fb77b5)
- **release:** bump version to 0.10.12-ALPHA (6f73859)

Anciennement `v0.10.12-ALPHA`.

## [v1.0.0-BETA.62](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.62) - 2026-07-28

### Fixes
- **api:** request expanded external_game_source name in IGDB game details (97ae002)
- **api:** avoid duplicate incomplete-binding refresh at startup (ba2652f)

### Other
- **release:** bump version to 0.10.11-ALPHA (02b57e7)

Anciennement `v0.10.11-ALPHA`.

## [v1.0.0-BETA.61](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.61) - 2026-07-28

### Fixes
- **api:** resolve Twitch game id via IGDB external_games before name matching (ec8117e)

### Other
- **release:** bump version to 0.10.10-ALPHA (e34320b)

Anciennement `v0.10.10-ALPHA`.

## [v1.0.0-BETA.60](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.60) - 2026-07-18

### Fixes
- **api:** replace '3' with 'thi' (d54a791)

### Other
- **release:** bump version to 0.10.9-ALPHA (e691818)

Anciennement `v0.10.9-ALPHA`.

## [v1.0.0-BETA.59](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.59) - 2026-07-18

### Features
- **api:** ajoute le provider Xbox au panneau d'aide des connexions (1ae815f)
- **web,api:** ajoute le layout à onglets de la page chaîne (expérience 50/50) (663a4b9)
- **web:** ajoute l'onglet Invitations et fixe le placement du lien d'invitation (ae00250)
- **api,web:** réintègre le placement Invitations dans l'expérience A/B au lieu de le fixer (554856c)
- layout à onglets de la page chaîne (expérience 50/50) (8d5b180)
- **monitoring:** expose Minecraft service accounts on the Grafana dashboard (3e9c797)
- **minecraft:** allow re-authenticating an existing service account (714e580)
- **minecraft:** hide the connect option when no service account has capacity (0439866)
- **dtdd:** import DTDD API keys from config into the DB pool on startup (316b016)
- **channel:** add the missing invitations-tab fragment for the tab variant (a925060)
- **web:** serve 3rd-party domain verification files publicly (7a62fc9)

### Fixes
- **web:** propage la locale de l'utilisateur vers l'API pour les panneaux d'aide (d178ba7)
- **web:** corrige les panneaux d'onglets jamais masqués et déplace la navigation (374063c)
- **web:** charge channel-tabs.js globalement et centre les onglets dans la navbar (ce48dac)
- **web:** corrige l'URL des onglets cassée par channelUsername indéfini (33eafc6)
- **web:** corrige le retour arrière navigateur sur les onglets de la page chaîne (74a44fd)
- **db:** ajoute la variante "tab" à l'expérience invite-button-placement en base (b86bead)
- **web:** réexécute les <script> inline lors du chargement paresseux d'un onglet (e82e606)
- **web:** construit un vrai CatapultWebUser pour les requêtes mvc bridgées en WS (3615f49)
- **web:** affiche la page de connexion au lieu de reboucler sur Twitch après un échec (103a4fc)
- **web:** prevent path traversal and WebSocket route shadowing in verification endpoint (f0894d5)

### Other
- **build:** include org.springframework.boot in the spring.io snapshot filter (258e79b)
- **release:** bump version to 0.10.8-ALPHA (978758b)

Anciennement `v0.10.8-ALPHA`.

## [v1.0.0-BETA.58](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.58) - 2026-07-13

### Features
- **xbox:** blacklist configurable d'IDs de titre à ignorer (6b46af1)

### Fixes
- **xbox:** sessions distinctes pour catapult-api et catapult-web (3b6dcb3)
- **xbox:** active le GetterConfig XBOX à la liaison de compte (4e11159)
- **xbox:** corrige le relying party XSTS (http au lieu de https) (f73985e)
- **xbox:** corrige le chemin de l'API de présence Xbox Live (7a2a4f4)
- **xbox,web:** redirects relatifs pour contourner les en-têtes X-Forwarded incorrects (cb1ff1c)
- merge les correctifs Xbox restants dans master (9dc3af2)

### Other
- **release:** bump version to 0.10.7-ALPHA (66a40f9)

Anciennement `v0.10.7-ALPHA`.

## [v1.0.0-BETA.57](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.57) - 2026-07-13

### Features
- **minecraft:** clients Xbox et Minecraft (auth, friends, presence) (be34266)
- **minecraft:** entités comptes de service et liens d'amitié (db9d1d1)
- **minecraft:** lookup de profil pseudo vers UUID (3cd92d4)
- **minecraft:** client OAuth MSA (refresh et device-code) (9c0e93a)
- **minecraft:** service de token avec cache et rotation du refresh MSA (3d04d15)
- **minecraft:** enrôlement d'amis avec remplissage séquentiel des comptes (9d3c880)
- **minecraft:** sync planifiée des liens d'amitié (6f05f54)
- **minecraft:** getter de présence branché dans la chaîne de détection (66f9c84)
- **minecraft:** API de liaison de compte pour les utilisateurs (65ba1bb)
- **minecraft:** API admin des comptes de service avec enrôlement device-code (1e59d0b)
- **minecraft:** configuration du getter de présence (f3b4a57)
- **web:** méthodes ApiClient pour la liaison et l'admin Minecraft (d4691c0)
- **web:** bloc de liaison Minecraft dans les connections (25dc584)
- **web:** page admin des comptes de service Minecraft (045d7e8)
- **web:** enrôlement device-code des comptes Minecraft en admin (34437a4)
- **minecraft:** endpoint de vérification immédiate du lien (dc7cf2f)
- **web:** bouton Vérifier déclenche la sync et correction des apostrophes i18n (004606d)
- **minecraft:** experiment minecraft.integration et gate centralisé (364d150)
- **minecraft:** API de liaison gatée par l'experiment (805aafd)
- **minecraft:** détection gatée par l'experiment (lecture seule) (485910f)
- **minecraft:** section d'aide conditionnée par l'experiment (09a877e)
- merge master into feat/minecraft-integration (53bc094)
- **minecraft:** getter de présence via l'API Friends/Presence (c4a9c7f)
- **xbox:** provider XBOX et relying party paramétrable sur XboxService (6793107)
- **xbox:** XboxUserTokenService pour le token XSTS par utilisateur (fb74d69)
- **xbox:** détection de jeu via l'API de présence Xbox Live (e373310)
- **xbox:** liaison de compte via OAuth2 et persistance du refresh token (8c3ffe8)
- **xbox:** carte de connexion Xbox dans les paramètres de chaîne (e341a6d)
- **web:** add .well-known (e1d8f0a)
- intégration Xbox (détection de présence via OAuth2) (76edc83)

### Fixes
- **minecraft:** encodage du pseudo dans l'URL de lookup (7eb86d3)
- **minecraft:** détection stricte de authorization_pending dans le device-code (9f8bd85)
- **minecraft:** persistance du flag limite d'amis hors transaction d'enrôlement (9886603)
- **minecraft:** messages d'erreur fixes et couverture des branches de l'API de liaison (73c39b9)
- **minecraft:** 502 garanti sur échec de chaîne et comptage des liens sans N+1 (266aad5)
- **minecraft:** active les clients Xbox et Minecraft si l'un des deux flags est actif (764e522)
- **minecraft:** réutilisation du lien existant au ré-enrôlement (contrainte user_id unique) (28f4067)
- **minecraft:** pas de transaction tenue pendant les appels HTTP de la sync (704b715)
- **minecraft:** validation des types du PATCH admin (74458fd)
- **web:** device-code start typé et test du Bearer sur le chemin exchange (dec46db)
- **web:** message PENDING sans th:utext et validation serveur du pseudo (432d0ad)
- **web:** device-code robuste au double-clic et validation du lien Microsoft (e5f8cff)
- **web:** normalisation camelCase de la réponse device-code pour le front (25550b9)
- **web:** rendu DOM sans innerHTML dans l'enrôlement device-code (c9bdd5f)
- **minecraft:** verrou de refresh par compte de service (7e362c1)
- **web:** bloc Minecraft masqué quand l'intégration est désactivée (6c5980b)
- **minecraft:** erreur MSA du device-code exposée en 502 au lieu d'un 500 opaque (4796d4c)
- **web:** message d'erreur MSA affiché dans l'enrôlement device-code (f907beb)
- **minecraft:** validation d'enrôlement sans effet de bord (NPE sur compte transient) (c0bcd8a)
- **minecraft:** chargement du compte de service avec le lien (OSIV désactivé) (5178a6b)
- **minecraft:** matching des profileId insensible au format d'UUID (826d8fc)
- **minecraft:** activation du getter dans la chaîne de détection à l'enrôlement (0950642)
- **minecraft:** présence périmée ignorée (jeu fermé sans statut OFFLINE) (262a88a)
- **minecraft:** aide robuste aux JWT non-utilisateur (2027207)
- **minecraft:** le gate prime sur la validation du pseudo et client-id trimé (c8274cf)
- **metrics:** retire les secrets et IDs bruts des labels http.client.requests (becc87d)
- **db:** renomme la migration Minecraft V59 en V60 (fd57ccb)
- **xbox:** redirect_uri stable au lieu du template dynamique Spring (8be82cb)
- **xbox:** configure jwkSetUri pour la vérification du id_token (53ff2b2)
- **xbox:** retire l'appel Microsoft Graph, inutile et incompatible (e94cbce)
- **xbox:** redirige vers catapult-web après la liaison, pas /dashboard (8f15b7b)
- **xbox:** retire le scope openid, la vraie cause du bypass (59ef728)
- **web:** Dont display error message when api is starting (b50768b)
- **api:** When in dev mode, basic route can't be accessed (2819aa3)
- **web:** Add Minecraft provider (16f9773)
- **web:** app.name not reflected everywhere (3838117)
- **api:** migration not commited (8182971)
- **web:** .well-known endpoint not accessible in public (2199e22)
- **web:** .well-known endpoint for microsoft need to finish with .json (a98eb24)
- **web:** add .well-known properties to env var (960cb98)
- **api:** add xbox.enabled to env var (78aabee)

### Documentation
- **minecraft:** compatibilité de la détection dans l'aide des connexions (90cfbb8)

### Other
- **minecraft:** vérifie le succès de la chaîne après éviction du cache (ec09593)
- **minecraft:** couvre l'échec de getFriends dans la sync des liens (adc24b8)
- **minecraft:** nettoyage de la dette de revue (tokenState, code mort, @PreUpdate) (5c58c17)
- **release:** bump version to 0.10.6-ALPHA (229505c)

Anciennement `v0.10.6-ALPHA`.

## [v1.0.0-BETA.56](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.56) - 2026-07-11

### Fixes
- **chat:** séparateur '#' dans les placeholders pour éviter la détection de lien Twitch (740c39f)

### Other
- **release:** bump version to 0.10.5-ALPHA (70e6d12)

Anciennement `v0.10.5-ALPHA`.

## [v1.0.0-BETA.55](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.55) - 2026-07-11

### Features
- **chat:** commande \!debug réservée à l'owner de l'application (a076265)
- **chat:** commande !debug réservée à l'owner de l'application (e28611e)

### Other
- **release:** bump version to 0.10.4-ALPHA (bd69dcb)

Anciennement `v0.10.4-ALPHA`.

## [v1.0.0-BETA.54](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.54) - 2026-07-07

### Fixes
- **eventsub:** survie aux session_reconnect Twitch (perte totale après ~2h) (d0f51e1)

### Other
- **release:** bump version to 0.10.3-ALPHA (80f1da7)

Anciennement `v0.10.3-ALPHA`.

## [v1.0.0-BETA.53](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.53) - 2026-07-06

### Features
- **metrics:** expose service token state and external API status codes (38cfd92)

### Fixes
- **grafana:** merge WS dashboard into main and repair No Data panels (0807226)
- **metrics:** register lazy meters eagerly and enable WS duration histograms (1e97a5e)
- **grafana:** dashboard unifié, No Data et nouvelles métriques tokens/status (4f83df4)

### Other
- **release:** bump version to 0.10.2-ALPHA (598ea79)

Anciennement `v0.10.2-ALPHA`.

## [v1.0.0-BETA.52](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.52) - 2026-07-05

### Features
- **api:** add user groups and user flags entities (5bbb754)
- **api:** add typed attribute values and operator evaluator (8155265)
- **api:** add account and active-experiment attribute resolvers (9238db7)
- **api:** add group, flag and assigned-experiment resolvers (615e466)
- **api:** add attribute evaluator registry (5517240)
- **api:** target groups and experiment assignment in A/B rules (81d8248)
- **api:** add admin groups CRUD endpoints (2c687fc)
- **api:** manage per-member flags and group membership (28676a4)
- **web:** admin page to manage user groups (a25265a)
- **web:** admin UI for member flags and group membership (3f5dc2c)
- **web:** target groups and experiments in the A/B rule builder (21a755c)
- user groups and flags for A/B test targeting (c9e6aaf)
- **admin:** publish key add/delete/refresh events on events.admin channel (e33eb3d)
- **admin:** live-update steam/dtdd key pages over WebSocket events (ef85fa6)
- **admin:** publish ccl refresh/mappings and tw definition events (9c33356)
- **admin:** live-update ccl and tw admin pages over WebSocket events (a859d3c)
- **admin:** publish dtdd mapping proposal resolution events (bf60312)
- **admin:** live-remove resolved dtdd mapping proposals over WebSocket (60fb5c7)
- **admin:** publish user-group created/updated/deleted events (9dd78ea)
- **admin:** live-update user groups admin page over WebSocket events (0172895)
- **admin:** re-render whitelist section over WebSocket instead of page reload (c3df2fc)
- **admin:** re-render members section over WebSocket instead of page reload (75a60b1)
- **admin:** re-render member-targeting and experiment-detail sections over WebSocket (fd441f0)
- **admin:** WS event-driven steam/dtdd key pages + search.game handler (c1770fe)
- **metrics:** enable http percentile histograms, hibernate stats and instrumented GrowthBook client (14ad816)
- **metrics:** add ExternalApiObservations wrapper for external API calls (d772ddf)
- **metrics:** observe IGDB, DtDD and Steam external calls (8546724)
- **metrics:** observe Twitch, EventSub and GitLab external calls (6d89c76)
- **metrics:** count Steam and DtDD rate-limit hits (559b351)
- **metrics:** stream duration histogram and chat message counters (cb92099)
- **metrics:** gauges for chat connections and registered commands (ca690bd)
- **metrics:** time game detection chain with resolved_by tag (ce5ba10)
- **grafana:** provisioning structure and datasource variable (62d17a1)
- **grafana:** revamp catapult dashboard with hourly heatmap and new rows (826c3b2)
- enrich metrics and revamp Grafana dashboards (6d4e883)

### Fixes
- **api:** align DTDD tests with reviewed parsing and null-safe media filter (790ab39)
- repair DTDD parsing tests to unblock master CI (6b83a46)
- **api:** silence H2 DDL warnings and align user_flag column length (71640bf)
- **api:** guard lazy group members read and broaden groups controller tests (c762cad)
- **api:** use writable transaction for group rename (ce8e673)
- **api:** make deleted-group rules fail closed and fix group key trim check (9f917e8)
- **ws:** search.game handler for admin members IGDB search over WebSocket (29836d5)
- **ws:** gate events.admin on ROLE_ADMIN so admins actually receive admin events (c320aab)
- **admin:** bind cloned key/ccl rows so their ws:* forms fire over WebSocket (116ef41)
- **admin:** use non-underscore placeholder for cloned group row ids (Thymeleaf eats __x__) (6a8fc9d)
- **ws:** keep event-driven admin/channel scripts working across hx-boost navigation (4f1e154)
- **ws:** disable hx-boost on logout/impersonate/login so session changes navigate the page (07f84bc)
- **ws:** make hx-boost navigation follow server redirects via HX-Redirect (f59473b)
- **grafana:** restore typographic title on hourly heatmap panel (a5f2377)
- **metrics:** publish percentile histogram for external api timer (c596845)
- **grafana:** replace dead application label filters in catapult dashboard (7f1819d)

### Other
- **api:** add curl and jq to container (e71ef61)
- **api:** add curl and jq to container (414f139)
- local change with remote (6f5721b)
- **release:** bump version to 0.10.0 (25f3ba1)
- adapt ActiveProviderHolderTest to RestClient.Builder injection (cb77ff3)
- adapt IrcTwitchChatServiceTest to MeterRegistry injection (27fa838)
- **release:** bump version to 0.10.1-ALPHA (1ec8287)

Anciennement `v0.10.1-ALPHA`.

## [v1.0.0-BETA.51](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.51) - 2026-06-28

### Features
- **api:** add missing local access (d912275)

### Other
- **release:** bump version to 0.9.6-ALPHA (cd8b9ef)

Anciennement `v0.9.6-ALPHA`.

## [v1.0.0-BETA.50](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.50) - 2026-06-28

### Features
- **api:** add rebuild for all games (c48dc66)
- **api:** add dev-specific API security configuration and local access (948fa20)

### Other
- **release:** bump version to 0.9.5-ALPHA (68c4302)

Anciennement `v0.9.5-ALPHA`.

## [v1.0.0-BETA.49](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.49) - 2026-06-28

### Fixes
- **api:** typo in field name (fe37662)

### Other
- **release:** bump version to 0.9.4-ALPHA (f4036db)

Anciennement `v0.9.4-ALPHA`.

## [v1.0.0-BETA.48](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.48) - 2026-06-28

### Fixes
- **api:** typo in field name (57c0e11)

### Other
- **release:** bump version to 0.9.3-ALPHA (7beaa41)

Anciennement `v0.9.3-ALPHA`.

## [v1.0.0-BETA.47](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.47) - 2026-06-28

### Fixes
- **api:** Incorrect DTDD API endpoint (b820124)
- **api:** Review DTDD parsing (e845420)

### Other
- **release:** bump version to 0.9.2-ALPHA (d33ede4)

Anciennement `v0.9.2-ALPHA`.

## [v1.0.0-BETA.46](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.46) - 2026-06-27

### Features
- **chat:** split overlength responses into [i/N]-prefixed parts (e64bdef)
- **admin:** rebuild non-overridden TW mappings on demand (41cf986)
- **auth:** redirect unauthenticated traffic to home instead of /login (b9fba6a)
- **ws:** chat-commands.{list,create,update,delete,preset.instantiate} handlers, drop HTTP proxy (5d212fe)

### Fixes
- **ws:** capture csrfToken before firing pending load triggers (c7a6224)
- **channels:** publish binding.upserted on scheduler-detected game (f877286)
- **igdb:** bind websites_json as jsonb instead of varchar (75f06fc)
- **auth:** opt every Twitch OAuth link out of hx-boost (c7986fd)
- **admin:** invalidate stored SecurityContext on impersonate swap (a19fd11)
- **admin:** refresh SecurityContext synchronously on impersonate swap (68f92c4)
- **chat:** resolve nested placeholders in inline fallbacks (614a680)
- **chat-commands:** bypass hx-boost on custom-form + add toast feedback (ed2acbc)
- **admin:** wire currentUserTwitchId + surface impersonate failures (919f17a)

### Other
- **chat:** log when GameContext is empty at chat-command time (fa02e7b)
- **chat:** demote GameContext empty-game log to debug (d9a0c07)
- **tw:** log resolved TW mapping when binding is (re)resolved (0551601)
- **channels:** seed binding id + ChannelEventPublisher mock (6890774)
- **chat:** promote EventSub lifecycle logs to INFO + log incoming commands (3ee3eb7)
- **ws:** TRACE every incoming request/command and its response (7727b6e)
- **http:** TRACE every outbound RestClient call to Steam/DTDD/Twitch/IGDB (f25964b)
- **release:** bump version to 0.9.1-ALPHA (ba9630d)

Anciennement `v0.9.1-ALPHA`.

## [v1.0.0-BETA.45](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.45) - 2026-06-26

### Features
- **channels:** render channel page fully server-side in one round-trip (5eb8629)
- **ws:** channel.viewed.<ownerId> subscription channel (13a0e06)
- **channels:** publish bot.toggled event end-to-end (event-driven P3) (2e36951)
- **channels:** publish per-domain events for remaining state changes (81ad7bf)
- **channels:** render channel page fully server-side (event-driven P1) (250282d)
- **ws:** channel.viewed.<ownerId> subscription channel (event-driven P2) (2231df1)
- **channels:** publish bot.toggled event end-to-end (event-driven P3) (1262a0b)
- **channels:** publish per-domain events for remaining state changes (event-driven P4) (05c027e)
- **channels:** replace reload-on-event with in-place DOM updates (b9ab09e)
- **channels:** in-place DOM updates on WS events (no reload) (b669511)
- **ws:** ws: Thymeleaf dialect + ws-actions.js, migrate all forms (644e5bd)
- **ws:** ws: Thymeleaf dialect + ws-actions.js, migrate all forms (c998a7b)
- **ws:** rename action to mvc, migrate help-panel buttons, add diagnostics (629fd67)
- **ws:** rename action to mvc, migrate help-panel buttons, add broadcast diagnostics (755eb19)
- **notif:** snapshot endpoint + publish read/markAll/deleted events (9647973)
- **ws:** SubscriptionInitializer SPI + notification snapshot push (e5a928d)
- **ws:** notification.list/markAll handlers, drop /notifications/api/* (c352a15)
- **ws:** admin.notification.* handlers, drop /admin/notifications/api/* (45b575a)
- **ws:** admin.broadcast.send handler, drop /admin/broadcast/api/send (6b65a60)
- **ws:** admin.config.* handlers, drop /admin/config/api/* (1736793)
- **ws:** search.game handler, drop /api/games/search (4d68b24)
- **ws:** catapultWs.mvc() helper + channel-page refetch via mvc (5015b79)
- **channels:** publish full BindingDto in binding.upserted event (6f7747c)
- **channels:** publish UserSettingsDto in settings.updated event (b944c31)
- **channels:** publish ProviderConnections snapshot in steam/connection events (498058e)
- **ws:** migrate changelog/feedback widgets to ws:* dialect (0728fe2)
- **ws:** hx-boost navigation to preserve WS across page loads (6cd8271)
- **metrics:** WS Micrometer instrumentation + Grafana dashboard (995c9cd)
- **ws:** full action migration — HTTP→WS + suppression refetch fragments (01d5ecd)

### Fixes
- **nginx:** preserve X-Forwarded-* headers on /ws location (492cebc)
- **nginx:** merge X-Forwarded-* preservation on /ws location (f58f455)
- **channels:** bot toggle via WS + hidden attribute beats display:flex (bb51e5a)
- **channels:** bot toggle via WS + hidden attribute beats display:flex (4e478ed)
- **ws:** align hasSteam definition + extract ResponseStatusException helper (f3624e6)
- **channels:** publish connection.changed on Steam link/unlink (0c6e71d)
- **notif:** include unreadCount in notification.deleted payload (a872b6a)
- **ws:** flatten admin.broadcast.send data into API body (907e7bd)
- **notif:** @Transactional on findRecent + handle bodiless 202 from broadcast (305b75e)

### Other
- consolidate render + subscribe phases for in-place DOM updates (4b90b38)
- **channels:** drop /channels/{u}/fragments/* endpoints + refetchAndSwap (ddceeff)
- **ws:** remove htmx-ws-extension.js + hx-ext='ws-rpc' + scope htmx CDN to dev/igdb (e69483f)
- **e2e:** add Playwright specs for WS handlers, channel events, hx-boost nav (a439b9d)
- **release:** bump version to 0.9.0-ALPHA (e2ae861)

Anciennement `v0.9.0-ALPHA`.

## [v1.0.0-BETA.44](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.44) - 2026-06-23

### Fixes
- **ws:** forward htmx envelope to dispatcher (917d352)
- **ui:** null-guard feedback widget in updatePosition (2e9aef8)
- **ws:** perform auth-ticket handshake on connection open (c6fa08c)
- **ws:** forward htmx envelope to dispatcher (c78688a)
- **ui:** null-guard feedback widget in updatePosition (2dc2b16)
- **ws:** perform auth-ticket handshake on connection open (a3a06bf)

### Other
- **release:** bump version to 0.8.2-ALPHA (678e9c6)

Anciennement `v0.8.2-ALPHA`.

## [v1.0.0-BETA.43](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.43) - 2026-06-23

### Fixes
- **ws:** hide maintenance overlay with display:none to release pointer events (fbda84e)

### Other
- **release:** bump version to 0.8.1-ALPHA (7aae1ab)

Anciennement `v0.8.1-ALPHA`.

## [v1.0.0-BETA.42](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.42) - 2026-06-23

### Features
- **ws:** add JSON discriminated message DTOs and codec (7e6c473)
- **ws:** add session registry with channel subscriptions (dcbeb5b)
- **ws:** add channel resolver with auth/role checks and redis mapping (6675a2b)
- **ws:** add WsHub handler and /ws endpoint with subscribe/unsubscribe (1b088be)
- **ws:** add 15s server heartbeat to all sessions (030014e)
- **notifications:** add redis event publisher (fc91aa9)
- **ws:** subscribe to redis events and fanout to ws sessions (3e2083b)
- **ws:** add ws-client.js and migrate notifications.js from SSE (a57da1e)
- **ws:** add ticket store for short-lived ws auth tickets (bc4f570)
- **ws:** add /ws/auth-ticket endpoint backed by spring security session (d1edb59)
- **ws:** handle auth frame and authenticate ws session (427ec84)
- **ws:** add request dispatcher with auth/admin checks (49c1848)
- **ws:** add search.twitch.categories request handler (8f9f6a4)
- **ws:** add search.dtdd request handler (6774c03)
- **ws:** add notification.read command handler (462459c)
- **ws:** add per-session rate limiter for ws requests (8ff31ef)
- **ws:** add origin check handshake interceptor (e6c6956)
- **ws:** add HtmxWsDispatcher skeleton with mock-request forwarding (b7b728a)
- **ws:** add HX-* header propagation and out-of-band swap extraction (4e3a770)
- **ws:** add htmx-ws-extension to route hx-* requests over websocket (2f902b3)
- **broadcast:** add broadcast request dtos and validator (18662fc)
- **broadcast:** add admin broadcast endpoint with rate limit and audit log (cf9b01d)
- **ws:** broadcast maintenance.imminent on graceful shutdown (c13c90c)
- **admin:** add broadcast page and web proxy endpoint (c00f192)
- **ws:** add maintenance overlay, banner and toast UX (0467dd8)
- **ws:** inert fallback to body children when no <main> on page (e5746a4)
- **ws:** unified server event stream over websocket (9448a21)

### Fixes
- **ws:** make /ws allowed origins configurable, default to dev-permissive (6008c93)
- **infra:** make /ws location work with explicit connection-upgrade map (d737e3f)
- **ws:** annotate primary @Autowired constructors on dispatcher and handlers (720621a)
- **ws:** propagate authenticated jwt from ws session to api client calls (61ea7b1)
- **ws:** server-issued csrf token per ws session validated by htmx dispatcher (d833ff5)
- **ws:** broadcast maintenance.imminent only once on shutdown (f759ca8)
- **chat:** resolve game context lazily from GameStateService (ad76623)

### Refactoring
- **notifications:** replace SSE pusher with redis event pusher (1c20371)
- **notifications:** remove legacy SSE registry and stream endpoints (e21c924)

### Documentation
- **e2e:** add chaos engineering checklist for manual pre-release runs (e71ed99)

### Dependencies
- add spring-data-redis to api and web modules (cac06b9)

### Other
- **spike:** add spring-websocket dependency for HTMX-over-WS PoC (224a61a)
- **spike:** add target controller and thymeleaf fragment for PoC (7223823)
- **spike:** add websocket handler that forwards to dispatcher servlet (fb23aa2)
- **spike:** remove PoC code after successful validation (12a83a8)
- **infra:** add redis 7 service to docker-compose (8c8457c)
- **infra:** configure spring-data-redis connection in both modules (9b5769d)
- **infra:** verify spring-data-redis wiring with testcontainers (825c476)
- **infra:** add /ws location with websocket upgrade to nginx-router (3e4db38)
- **ws:** cover auth flow, search, rate limit and origin check end-to-end (d3d43b4)
- **ws:** verify jwt propagation from ws session into api client (1ee304d)
- **e2e:** scaffold playwright project under e2e/ (d3e06a0)
- **e2e:** add playwright specs for overlay, banner, shutdown, search, notification (01f93ff)
- **e2e:** add post-deploy smoke script (038d24e)
- **gitignore:** ignore playwright outputs and node_modules under e2e/ (a6926aa)
- **infra:** fall back to maintenance page on backend 502/503/504 (e9522a3)
- **e2e:** add manual-smoke spec covering overlay, banner, reconnect and shutdown (fa5cc82)
- **release:** bump version to 0.8.0-ALPHA (d88ba8f)

Anciennement `v0.8.0-ALPHA`.

## [v1.0.0-BETA.41](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.41) - 2026-06-22

### Features
- **tw:** add TwDefinition entity + V47 migration (620b706)
- **tw:** add four mapping entities (DTDD/IGDB/Steam id/Steam keyword) + V48 (72f6b29)
- **tw:** add tws/tw_enabled/tw_override to GameBinding + descriptor_ids cache column (648ee69)
- **tw:** add tw_feature_enabled + blocked_tws to UserSettings (V50) (9359d7b)
- **tw:** seed 20 TW definitions + Steam keyword/id mappings (V51) (3a433b7)
- **tw:** add V52 placeholder migration + V53 app_state + AppState entity (b98efbb)
- **tw:** add DtddSignalService (internal yes+mostly extractor) (f7df7de)
- **tw:** add SteamStoreService.fetchTwSignals (content_descriptors + notes) (1d1861c)
- **tw:** persist IGDB descriptor_ids and expose fetch/resolve helpers (aa2f19d)
- **tw:** add TwLabelService, TwPlaceholderRegistry, TwDefinitionsChangedEvent (75d3d97)
- **tw:** add TwResolverService (multi-source signal aggregation) (02642af)
- **tw:** add AdminTwService (definitions + 4 mappings + change event) (ed32bc1)
- **tw:** add TwBackfillService (ApplicationReadyEvent, throttled, idempotent) (4e0a803)
- **tw:** wire TwResolverService into BindingService, add reset/toggle/override paths (a61d540)
- **tw:** replace dtdd.* placeholders with tw.<slug>/tw.active in resolver+context (c3932d5)
- **tw:** add ApiAdminTwController + rewrite !triggers preset + tw labels i18n (88f1679)
- **tw:** add channel TW endpoints (save/reset/suggest/tw-enabled/settings) (b11d7f8)
- **tw:** add /admin/tw page (list) (b97bcf0)
- **tw:** add streamer settings card (toggle + blocked TW list) (b44f87a)
- **tw:** add TW block on binding card (save/reset/toggle) (b8d6fae)
- **tw:** add tw.* config + admin config prefixes + help i18n (efedc0e)
- **tw:** rework trigger warnings as CCL-like categories (4f43dfd)
- **i18n:** locale-aware date/time formatting in templates (2c0e4ef)

### Fixes
- **test:** align ApiAdminDtddKeysControllerTest with ddd_ key pattern (70b3a82)

### Other
- **i18n:** reorganize, dedupe and fix message bundles (4de9deb)
- **release:** bump version to 0.8.0-ALPHA + add TW metrics (bcc6255)
- **i18n:** dedupe bundles and fix help.tw cardId mismatch (6cd624f)
- **release:** bump version to 0.7.4-ALPHA (373eb22)

Anciennement `v0.7.4-ALPHA`.

## [v1.0.0-BETA.40](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.40) - 2026-06-21

### Fixes
- **api:** Dtdd API key pattern update (eb4f522)

### Other
- **release:** bump version to 0.7.3-ALPHA (462db6b)

Anciennement `v0.7.3-ALPHA`.

## [v1.0.0-BETA.39](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.39) - 2026-06-21

### Fixes
- **api:** missing @Autowired for RealDtddApiClient dependency (bd725a4)

### Other
- **release:** bump version to 0.7.2-ALPHA (77c8a8f)

Anciennement `v0.7.2-ALPHA`.

## [v1.0.0-BETA.38](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.38) - 2026-06-21

### Features
- **dtdd:** add dtdd_api_key migration (646fc05)
- **dtdd:** add DtddApiKeyEntry + repository (0cf10a4)
- **dtdd:** add dtdd_game_cache migration (24c5f6f)
- **dtdd:** add DtddSearchCache entity + repository (f72bd77)
- **dtdd:** add DtddTopicsCache entity + repository (5b22a6c)
- **dtdd:** add DtddGameMapping entity + repository (b5588ed)
- **dtdd:** add DtddMappingProposal entity + repository (61534fd)
- **dtdd:** add DtddApiKeyRotator with round-robin + per-key backoff (57ad0e7)
- **dtdd:** add DtddApiClient interface + DTOs (785d0ad)
- **dtdd:** add MockDtddApiClient with fixtures (a5913d8)
- **dtdd:** add RealDtddApiClient (search + topics, retry on 429) (3f1782b)
- **dtdd:** add DtddMappingService (ranking + search cache reuse) (f68d001)
- **dtdd:** add DtddService with stale-while-revalidate topics cache (2d87afe)
- **dtdd:** add /api/admin/dtdd-keys controller (9767a92)
- **dtdd:** add admin DTDD keys UI (39cb3be)
- **dtdd:** add dtdd.* and game.agerating placeholders (00bc42e)
- **dtdd:** add !triggers chat command preset (3e070b8)
- **dtdd:** add /api/channel/dtdd-mapping (status/validate/propose/search) (d77a99f)
- **dtdd:** add streamer dashboard mapping card (eeb23cf)
- **dtdd:** add /api/admin/dtdd-mapping (approve invalidates topics cache) (195cdac)
- **dtdd:** add admin DTDD mapping review UI (7c59308)
- **dtdd:** add dtdd.* properties + admin config prefixes (d94ee7e)
- **dtdd:** validate dtdd.* config override values (5b23fb4)
- **dtdd:** DoesTheDogDie.com integration with multi-key pool (9513b42)

### Fixes
- **dtdd:** gate DtddService/DtddMappingService on dtdd.enabled to avoid breaking test context (9bfe71e)
- **dtdd:** gate ApiChannelDtddMappingController on dtdd.enabled to avoid test context failure (31fa5a4)
- **security:** stop returning raw Steam API keys in admin response; use opaque ids (ee576d1)
- **security:** stop returning raw DTDD API keys in admin response; use opaque ids (8fbdab9)

### Documentation
- **dtdd:** document DoesTheDogDie integration in README (ce7a1a6)

### Other
- **admin:** add ApiKeyHasher utility for opaque key identifiers in admin payloads (efe5c78)
- **release:** bump version to 0.7.1-ALPHA (bc775c4)

Anciennement `v0.7.1-ALPHA`.

## [v1.0.0-BETA.37](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.37) - 2026-06-20

### Features
- **chat-commands:** add Flyway V39 schema for chat command tables (fe3bb89)
- **chat-commands:** add IgdbGameDetails entity and repository (3dddcbf)
- **chat-commands:** add ChatCommandDefinition entity and repository (beeb38a)
- **chat-commands:** allow nullable user on OAuthToken for SYSTEM provider (a24e925)
- **chat-commands:** add IgdbClient.fetchGameDetails for enriched fields (398fcb3)
- **chat-commands:** add IgdbGameDetailsService with stale-while-revalidate cache (74f470e)
- **chat-commands:** add GameContext record for placeholder resolution (e4e4dcb)
- **chat-commands:** add GameContextService listening to GameDetectedEvent (1ef973d)
- **chat-commands:** add PlaceholderResolver with inline/db fallback precedence (3e83d4b)
- **chat-commands:** add i18n keys for chat command presets (3398b54)
- **chat-commands:** add ChatCommandPresetCatalog with i18n-driven instantiation (bb15a50)
- **chat-commands:** add ChatCommandDefinitionChangedEvent for cache invalidation (c4fdfc9)
- **chat-commands:** add DynamicChatCommand adapter (73be232)
- **chat-commands:** add DynamicCommandResolver with per-user cache (a5faaa5)
- **chat-commands:** wire CommandRegistry to DynamicCommandResolver with experiment gate (e9a99cc)
- **chat-commands:** add SystemTwitchAccountService with token refresh and mod cache (28a13e1)
- **chat-commands:** route sendMessage through system bot with streamer fallback (8c5eef8)
- **chat-commands:** add admin REST endpoints for chat command CRUD (db2846d)
- **chat-commands:** seed chat.commands experiment (100% control by default) (b373923)
- **chat-commands:** add admin page with preset list, command CRUD and bot status banner (2a3fd3f)
- **chat-commands:** force Twitch consent screen on bot-link OAuth flow (08a4072)
- **chat-commands:** admin action to promote a regular account to system (0e60156)
- **chat-commands:** nav link visible only when experiment rolled out (b55333f)
- **chat-commands:** inline edit, custom create form, and built-in listing (835f591)
- **chat-commands:** bootstrap presets disabled for new users on first visit (8ea60dd)
- **chat-commands:** customizable response template for built-in commands (91d4480)
- **chat-commands:** table layout + !setgame disabled by default (aed06f5)
- **chat-commands:** theme-aware inputs and switch-style enabled toggle (cef7e22)
- **chat-commands:** allow renaming built-ins; i18n labels (4f570f1)
- **chat-commands:** move UI to a card on the channel page (9cfc004)
- **chat-commands:** data-driven chat commands with system bot fallback (8382a58)

### Fixes
- **chat-commands:** send OAuth refresh secrets in form body, not URL (688720e)
- **chat-commands:** explicit constructor for IgdbGameDetailsService to propagate @Qualifier (6280537)
- **chat-commands:** wire bot-link button to OAuth start endpoint (404) (572e4e3)
- **chat-commands:** admin check before state mutation in bot-link callback (f10a43c)
- **chat-commands:** TTL on bot-link-pending to mitigate CSRF window (fe47d93)
- **chat-commands:** emit relative Location to avoid wrong port behind nginx (c94e177)
- **chat-commands:** proper experiment seed (100/0 weights, DRAFT) + override honoured (203661b)
- **chat-commands:** admin API uses evaluateGate so overrides take effect (e9849f6)
- **chat-commands:** move page out of /admin (per-streamer, not admin-only) (e79fda8)
- **chat-commands:** proxy API calls through catapult-web (4b7e7d4)
- **chat-commands:** surface upstream errors in proxy + UI (3383cf2)
- **chat-commands:** surface POST presets error to user via alert (c234374)
- **chat-commands:** send CSRF token on POST/PUT/DELETE proxy calls (ffb8406)
- **api-client:** handle null body in typed post() (241345c)
- **chat-commands:** bot sends regardless of mod, fall back to streamer only if no bot (d954152)
- **chat-commands:** always ensure built-ins, even for users with existing presets (9629899)
- **chat-commands:** render commands; don't drop delete-btn before wiring handlers (20f6aa3)
- **web:** chat-commands card layout displayed when experiment not rolled out for user (dc511e1)
- **web:** remove warning banner (3dcf131)
- **web:** temporary disabling name edit for builtin command (68757aa)
- **web:** html chat-commands template placeholders escaping (be65927)

### Refactoring
- **chat-commands:** use system UserAccount + TWITCH OAuth token for bot (77c588d)

### Documentation
- **chat-commands:** document system bot env vars and feature in README (92e70d4)

### Other
- **chat-commands:** remove GameCommand (migrated to preset), reactivate SetGameCommand (2d03147)
- **chat-commands:** add end-to-end integration test for preset dispatch (c5a5fa7)
- **chat-commands:** remove OAuth bot-link flow (817088a)
- **chat-commands:** drop placeholder system account + initializer (5c1f224)
- **chat-commands:** drop seeder reconciliation, defer to ExperimentService (797e077)
- **api:** rename ApiAdminChatCommandsController to ApiChatCommandsController (019b9bc)
- **release:** bump version to 0.7.0-ALPHA (5c04f3f)

Anciennement `v0.7.0-ALPHA`.

## [v1.0.0-BETA.36](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.36) - 2026-06-18

### Fixes
- **twitch:** reconnect EventSub WebSockets on keepalive timeout (f79a19a)
- **i18n:** align Steam translations across locales and fix footer.git.local typo (0605b8a)

### Other
- **release:** bump version to 0.6.1-ALPHA (d7ca182)

Anciennement `v0.6.1-ALPHA`.

## [v1.0.0-BETA.35](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.35) - 2026-06-18

### Features
- **config:** add config_override and config_audit schema (ceba8e2)
- **config:** add ConfigOverride and ConfigAudit JPA entities (c099134)
- **config:** inject DatabaseOverridePropertySource at top priority (7fd7329)
- **config:** ConfigCatalogService exposes filtered .properties keys (773ed85)
- **config:** ConfigOverrideService applies/clears with audit and refresh (0ce8941)
- **config:** load overrides from DB at boot and refresh (5e05bc5)
- **config:** /api/admin/config endpoints (catalog, apply, clear, audit) (6e60a23)
- **config:** admin UI for /admin/config (fbfc36e)
- **notifications:** add notification and notification_recipient schema (cef3a49)
- **notifications:** add Notification JPA entities (420a5f0)
- **notifications:** NotificationRenderer with markdown + jsoup sanitize (2209e5b)
- **notifications:** SseEmitterRegistry with per-user emitters + heartbeat (cbdb325)
- **notifications:** NotificationService create/list/markRead (b976e19)
- **notifications:** SSE push after-commit hook (48bd930)
- **notifications:** scheduled purge job for expired notifications (1b97be9)
- **notifications:** /api/notifications and /api/admin/notifications endpoints (c69740f)
- **notifications:** admin UI for /admin/notifications (53f8b69)
- **notifications:** NotificationBell component + SSE client (6c86034)
- **admin:** add sidebar links for config and notifications pages (26c4cef)
- **config:** add module column for shared catapult-web/api override storage (54b7b25)
- **config:** catapult-web config catalog and overrides via api persistence (a45ec4b)
- **config:** admin UI tabs Back (catapult-api) and Front (catapult-web) (378f53e)
- **admin:** runtime config override + notifications platform (8dc2c32)

### Fixes
- **stream:** prioritize default category over pending binding on stream start (d7c28f1)
- **stream:** clear pending binding when game closes off-stream (8f2346a)
- **stream:** default category honored at stream start (#28) (eae7ddb)
- **config:** align secret-patterns with kebab-to-dot normalization (aa12958)
- **config:** expose DatabaseOverridePropertySource as Spring bean (dee4bda)
- **web:** proxy admin and notifications calls server-side via ApiClient (92da58a)
- **notifications:** admin page styling + cancel handler + surface error body (97f1b1d)

### Dependencies
- add spring-cloud-context snapshot, commonmark; promote jsoup to runtime (d6fd00a)

### Other
- integration tests for config refresh and notification flow (1f53ae3)
- **release:** bump version to 0.6.0-ALPHA (26dd350)

Anciennement `v0.6.0-ALPHA`.

## [v1.0.0-BETA.34](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.34) - 2026-06-17

### Fixes
- **invite:** missed variable (f66ebe1)

### Other
- **release:** bump version to 0.5.14-ALPHA (00b6e72)

Anciennement `v0.5.14-ALPHA`.

## [v1.0.0-BETA.33](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.33) - 2026-06-17

### Fixes
- **invite:** moved card panel to /app only (29bf0ec)

### Other
- **release:** bump version to 0.5.13-ALPHA (8eeb691)

Anciennement `v0.5.13-ALPHA`.

## [v1.0.0-BETA.32](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.32) - 2026-06-17

### Fixes
- **admin:** format ISO instant string directly since assignedAt is a JSON String (no Temporals.format 3-arg overload) (2768b2d)
- **api:** add @Transactional to user variant endpoint to prevent LazyInitializationException on ExperimentVariant (e7e783b)

### Other
- **release:** bump version to 0.5.12-ALPHA (1ab8758)

Anciennement `v0.5.12-ALPHA`.

## [v1.0.0-BETA.31](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.31) - 2026-06-17

### Fixes
- **admin:** feedbackPage and participantPage are Lists (not Page), use #lists.isEmpty() (b6ed263)

### Other
- **release:** bump version to 0.5.11-ALPHA (c462ff0)

Anciennement `v0.5.11-ALPHA`.

## [v1.0.0-BETA.30](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.30) - 2026-06-17

### Fixes
- **admin:** compare enum values as strings since experiments are deserialized to Map<String, Object> (038f098)

### Other
- **release:** bump version to 0.5.10-ALPHA (7bec143)

Anciennement `v0.5.10-ALPHA`.

## [v1.0.0-BETA.29](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.29) - 2026-06-17

### Fixes
- **api:** break JSON serialization cycle by ignoring experiment back-references on variant and rule (a1e01de)

### Other
- **release:** bump version to 0.5.9-ALPHA (fef0a55)

Anciennement `v0.5.9-ALPHA`.

## [v1.0.0-BETA.28](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.28) - 2026-06-17

### Fixes
- **api:** force-load lazy collections in experiments list to prevent LazyInitializationException during JSON serialization (cb1d0c3)

### Other
- **release:** bump version to 0.5.8-ALPHA (b38be05)

Anciennement `v0.5.8-ALPHA`.

## [v1.0.0-BETA.27](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.27) - 2026-06-17

### Fixes
- **experiment:** expose user variant via API endpoint and use th:if instead of exp dialect in catapult-web (92216db)

### Other
- **release:** bump version to 0.5.7-ALPHA (1b31345)

Anciennement `v0.5.7-ALPHA`.

## [v1.0.0-BETA.26](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.26) - 2026-06-17

### Features
- **experiment:** A/B/C test on invite button placement (nav-default, nav-end, floating) (407185d)
- **experiment:** replace floating button with sidebar card in right column (7bff231)
- **experiment:** track invite_page_view, invite_link_regenerated and invite_redeemed events for placement experiment (6db60ab)
- A/B/C test on invite button placement (e042217)

### Fixes
- **invite:** deserialize redemptions to typed record and format with explicit UTC zone (de9829b)

### Other
- complete EN/FR translations for invite system and experiment variant (b119c9f)
- **oauth2:** pass mocked InviteService to CatapultOAuth2UserService constructor (e2863ef)
- **release:** bump version to 0.5.6-ALPHA (cad93ad)

Anciennement `v0.5.6-ALPHA`.

## [v1.0.0-BETA.25](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.25) - 2026-06-17

### Features
- **api:** add alpha invite domain, repositories and migration (fa58f1f)
- **api:** add invite service, API controllers and OAuth2 integration (8409877)
- **web:** add invitation UI, join page and admin invite panel (db5f857)
- **invite:** available for all whitelisted users, admin toggle, settings on whitelist page, fix join OAuth URL in dev (00ffa6a)
- **router:** templatize upstreams via envsubst, disable HTTPS enforcement, run as non-root (4ccded7)
- alpha invitation system (72aeb4d)

### Fixes
- **api:** repair Flyway schema history before migration to handle checksum drift (0c9e835)
- **invite:** no invite by default — admin must grant explicitly, V35 repair migration (4e596df)
- **security:** validate invite code format and URL-encode in join page (da93318)
- **whitelist:** owner always bypasses whitelist check (091a8d8)
- **api:** add @Transactional to whitelist page to prevent LazyInitializationException on invite owner, fix dev web-url port to 8081 (69c37c8)
- **web:** default oauth public url to api url so invite join page works without nginx, remove admin invite nav link (7bab886)
- **router:** use nginx-unprivileged image and wait for upstream services in DNS (e9a5ce9)
- **invite:** run relay filter at HIGHEST_PRECEDENCE and add cookie fallback to survive Spring Security filter ordering (4d1f75c)
- not passing Instant to redemption model attribute (e2adbdb)

### Other
- **invite:** add INFO logging to trace invite code session flow (282bb44)
- **docker:** set CATAPULT_API_PUBLIC_URL empty in catapult-web for nginx OAuth routing (f9b2df5)
- **release:** bump version to 0.5.5-ALPHA (ba9d4ce)

Anciennement `v0.5.5-ALPHA`.

## [v1.0.0-BETA.24](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.24) - 2026-06-16

### Features
- **steam:** expose offline mode warning when Steam account is set to offline (f2621c8)
- **web:** display warning when Steam account is in offline mode (0dd16a5)
- **steam:** warn user of cache TTL in offline mode and allow manual refresh (cbb08a1)
- **binding:** add findAllByStatus query to GameBindingRepository (8aa519f)
- **binding:** add refreshIncompleteBindings to retry unresolved game bindings (e4525fc)
- **scheduler:** refresh incomplete bindings at startup and every 6 hours (eb35d97)
- **steam:** warn user of cache TTL in offline mode and allow manual refresh (bc404dd)

### Fixes
- **steam:** use personal token in priority for owned games fetch and rotate keys on 429 (e791160)
- **steam:** switch to per-key rate limiting to prevent cross-key contention (3d14ada)
- **steam:** rate limiter par clé, token personnel prioritaire et avertissement offline (0000384)
- **steam:** resolve beta app IDs to their parent game for IGDB lookup (936b97e)
- **steam:** use fullgame.id instead of parentid for beta app resolution (f60e0a4)
- incorrect field name (3eed031)
- **binding:** clear CCLs before retry and skip ignored bindings in refresh (c744e39)
- **scheduler:** add retry interval property and guard startup refresh against exceptions (57a51d8)
- **scheduler:** guard scheduled retry against exceptions and remove dead repo method (584015b)
- **steam:** resolve beta app IDs to their parent game for IGDB lookup (ac62ca5)

### Other
- **release:** bump version to 0.5.4-ALPHA (46b8175)

Anciennement `v0.5.4-ALPHA`.

## [v1.0.0-BETA.23](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.23) - 2026-06-12

### Other
- **security:** add security.txt and vulnerability disclosure policy (d981c9c)
- **release:** bump version to 0.5.3-ALPHA (688151b)

Anciennement `v0.5.3-ALPHA`.

## [v1.0.0-BETA.22](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.22) - 2026-06-10

### Features
- **monitoring:** add experiment activations panel to Grafana dashboard (c94a5d1)

### Fixes
- **api:** add @Transactional to deleteSteamPersonalToken (8d61fad)

### Other
- **release:** bump version to 0.5.2-ALPHA (780945e)

Anciennement `v0.5.2-ALPHA`.

## [v1.0.0-BETA.21](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.21) - 2026-06-09

### Features
- **maintenance:** auto-reload when server comes back online (814a5b1)

### Fixes
- **changelog:** deserialize API response into typed DTOs instead of raw Map (05fd1de)

### Other
- **deploy:** add healthchecks, external networks and fix Dockerfile dependencies (9ac743f)
- **release:** bump version to 0.5.1-ALPHA (653cbdc)

Anciennement `v0.5.1-ALPHA`.

## [v1.0.0-BETA.20](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.20) - 2026-06-09

### Features
- **web:** add catapult-web module — Thymeleaf frontend with JWT session auth (25edcdc)
- **docker:** add multi-service compose — maintenance page and nginx router (23aea1e)
- **catapult-api:** add REST endpoints for admin, feedback, steam connect, help, and dev (12a5d2d)
- **catapult-web:** add proxy controllers for admin, feedback, steam connect, help, and dev (56569a8)
- **admin:** implement impersonation via JWT swap — POST /admin/impersonate and /exit (e5c2418)
- **web:** show maintenance banner on homepage when catapult-api is unreachable (a2c73b8)
- **catapult-api:** expose channel REST and SSE endpoints (42c7b3e)
- **catapult-web:** proxy channel routes with SSE forwarding and member settings redirect (9166c05)
- split frontend/backend into catapult-api and catapult-web (3a78ac9)

### Fixes
- **web:** expose deletionRequestedAt in auth validate so danger-zone renders correctly (767ce46)
- **admin:** use typed DTOs for members and steam-keys pages to fix SpEL property access (eab8467)
- **routing:** route all browser traffic through nginx, never expose catapult-api directly (14f1c66)
- **security:** replace JWT in query param with one-time exchange code (03eb81c)
- **test:** restore application-mock-web.properties and guard ApiSecurityConfig on servlet web context (3377dbe)

### Other
- **build:** restructure as Gradle multi-module, extract catapult-api (271cbea)
- split release into 4 independent jobs per service (4a8745b)
- **env:** document new JWT and inter-service URL variables (9f1b70d)
- **catapult-api:** remove dead monolith web layer (91e5af9)
- delete root src/ — original monolith superseded by catapult-api and catapult-web (b24f1a0)
- add set_version.sh and update deploy.sh for multi-service architecture (1bb30ad)
- **deploy:** make REGISTRY optional, default to empty for local builds (7446bfb)
- set_version.sh now commits, tags and pushes to GitLab (3c76018)
- **release:** bump version to 0.5.0-ALPHA (5cdab9d)

Anciennement `v0.5.0-ALPHA`.

## [v1.0.0-BETA.19](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.19) - 2026-06-07

### Features
- **twitch-event-spy:** support multiple streams as positional arguments (c3b9040)
- **twitch-event-spy:** spawn one WebSocket connection per batch of channels to bypass 10-subscription limit (688cac3)

### Fixes
- **twitch-event-spy:** guard unknown broadcaster in format_event, fix FIFO EOF race on reconnect (83a5ca1)
- **twitch-event-spy:** use O_RDWR on FIFO sentinel to avoid blocking on open (4d66f9a)
- **twitch-event-spy:** clean stale WS subs at startup, cap connections to WS_CONN_LIMIT=3 (0c6c862)
- **security:** downgrade whitelist access denied from error to warning (852bd55)
- **security:** downgrade whitelist access denied from error to warning (8625132)

### Documentation
- add multi-stream design spec for twitch-event-spy (2298ff2)
- add implementation plan for twitch-event-spy multi-stream (80de3bc)

### Other
- **release:** bump version to 0.4.7-ALPHA (4d11a2b)

Anciennement `v0.4.7-ALPHA`.

## [v1.0.0-BETA.18](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.18) - 2026-06-07

### Features
- **admin:** add Edit settings link for system account in members page (068364b)

### Fixes
- **security:** prevent system account impersonation to modify default configs (f3e95be)
- **security:** allow admin direct access to system account, block impersonation (c4e257b)

### Other
- **release:** bump version to 0.4.6-ALPHA (d7034f4)

Anciennement `v0.4.6-ALPHA`.

## [v1.0.0-BETA.17](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.17) - 2026-06-07

### Fixes
- **steam:** replace @PostConstruct with ApplicationReadyEvent to fix startup deadlock (1735fb0)

### Other
- **release:** bump version to 0.4.5-ALPHA (5866e81)

Anciennement `v0.4.5-ALPHA`.

## [v1.0.0-BETA.16](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.16) - 2026-06-07

### Features
- **system-profile:** add is_system column and domain field (5f02fbe)
- **system-profile:** add SystemAccountInitializer and startup bootstrapping (1c98b53)
- **system-profile:** guard system account in OAuth2 service, handle bot-link flow (184026f)
- **system-profile:** protect system account in admin controller, add bot-link endpoint (4361eab)
- **system-profile:** display system account in members UI with SYSTEM badge and bot-link action (43491f3)
- system profile — default config template and Twitch bot account (497b58c)

### Other
- **release:** bump version to 0.4.4-ALPHA (bbf572a)

Anciennement `v0.4.4-ALPHA`.

## [v1.0.0-BETA.15](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.15) - 2026-06-06

### Features
- **steam:** add acquireBlocking() for background tasks (473276a)
- **steam:** add virtual-thread executor bean for async API calls (7ed3a05)
- **steam:** migrate SteamApiClient interface to CompletableFuture (cf769fc)
- **steam:** update MockSteamApiClient to return CompletableFuture (cac1dc4)
- **steam:** wrap RealSteamApiClient HTTP calls in supplyAsync on virtual-thread executor (6c59504)
- **steam:** prefetchBatch returns CompletableFuture, getCurrentGame joins async result (3d2c650)
- **steam:** await prefetchBatch future in scheduler with 5s timeout (ada34bf)
- **steam:** parallel library preload with CompletableFuture.allOf() (996ead7)
- **steam:** join isProfilePublic CompletableFuture with 2s timeout in controller (85db7df)
- **steam:** add steam_api_key table, entity and repository (0d460bb)
- **steam:** add SteamApiKeyRotator with per-key 429 penalty and round-robin selection (30bd87b)
- **steam:** seed Steam API key pool from STEAM_API_KEYS and legacy STEAM_API_KEY env vars (d29384c)
- **steam:** delegate API key selection to SteamApiKeyRotator in RealSteamApiClient (2a45cc3)
- **steam:** add admin UI to manage Steam API key pool (fd208fc)
- **mock:** add toggleable Steam 429 rate-limit state to mock profile (cca2b12)
- **steam:** allow sharing personal token to the shared API key pool (b74351b)
- **admin:** expose runtime Steam API key status and force-refresh (d8cc3d1)
- **steam:** always persist personal tokens in steam_api_key table (149caf3)
- Steam API key pool — rate-limit UX, admin management, user token sharing (624cf2d)

### Fixes
- **steam:** wait through penalty window during library preload (9c19d42)
- **steam:** wait through penalty window during library preload (b89cebc)
- **steam:** use explicit constructor for qualifier injection, cleanup constants (eebd8dc)
- **steam:** volatile cycleCache, cancel future on timeout, graceful error in preload (ab1359e)
- **steam:** use Math.floorMod to avoid IndexOutOfBoundsException on counter overflow (e3130f9)
- **steam:** add missing SteamApiKeyEntry import in SteamApiKeyRotator (9f65bfb)
- **steam:** deduplicate api-keys list and remove redundant @Autowired in SteamApiKeyMigrationService (122db45)
- **steam:** validate key format, add i18n, wire nav for Steam API keys admin page (98889b3)
- **steam:** check key pool availability in HomeController and ChannelController (b8b89a7)
- **steam:** avoid logging API key in delete URL (af944dc)
- **steam:** show rate-limit warning instead of false private-profile (f9c81c5)

### Other
- **steam:** fix interrupt test to assert in main thread (aad990e)
- **steam:** update ChannelControllerTest stubs for CompletableFuture interface (0dc2a59)
- **steam:** document steam.api-keys property in application.properties (1292472)
- **release:** bump version to 0.4.3-ALPHA (da9f0c4)

Anciennement `v0.4.3-ALPHA`.

## [v1.0.0-BETA.14](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.14) - 2026-06-06

### Fixes
- **steam:** route library preload through rate limiter (3599f14)
- **steam:** route library preload through rate limiter (4f1d250)
- **event:** apply no-game fallback when ignored game is detected (c76e760)
- **event:** apply no-game fallback when ignored game is detected (268be53)

### Other
- **release:** bump version to 0.4.2-ALPHA (41f559e)

Anciennement `v0.4.2-ALPHA`.

## [v1.0.0-BETA.13](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.13) - 2026-06-06

### Performance
- **steam:** cache profile public/private check in RealSteamApiClient (67845cd)
- **steam:** batch GetPlayerSummaries to reduce API calls at scale (601837f)
- **steam:** cache profil public/privé + batch GetPlayerSummaries (ce9e08f)

### Other
- **scheduler:** update SchedulerServiceTest for SteamGameGetter injection (9379a95)
- **release:** bump version to 0.4.1-ALPHA (1c9da18)

Anciennement `v0.4.1-ALPHA`.

## [v1.0.0-BETA.12](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.12) - 2026-06-05

### Features
- **ccl:** add blockedCcls field to UserSettings with DB migration (00ba47d)
- **ccl:** mask blocked CCLs at Twitch API level in buildCclPayload (4d80b22)
- **ccl:** add CCL filter settings card with enable toggle and blocklist UI (3a89d2e)
- **ccl:** filtre CCL — blocklist et toggle global (fbe5c3c)

### Other
- **release:** bump version to 0.4.0-ALPHA (560f5d9)

Anciennement `v0.4.0-ALPHA`.

## [v1.0.0-BETA.11](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.11) - 2026-06-05

### Features
- **admin:** add deleteAccountImmediately to AccountService (b148a6e)
- **admin:** add POST /{id}/delete endpoint with self-deletion guard (debccbc)
- **admin:** group member actions in dropdown menu with delete option (ce92df6)
- **admin:** add findByUserAndProvider to GetterConfigRepository (021e448)
- **admin:** add AdminMigrationService with copy semantics (d74a62b)
- **admin:** add Steam unlink endpoint (4315de5)
- **admin:** add data migration endpoint and i18n keys (62f9f60)
- **admin:** add Steam unlink and data migration UI (2a26be7)
- **account:** add INACTIVE status and nullable twitch fields (8cf2934)
- **account:** add unlinkTwitch method marking account INACTIVE (939a769)
- **account:** add nightly Twitch account validation job (c38d56b)
- **account:** re-activate INACTIVE accounts on Twitch login (92125db)
- **admin:** add POST /admin/members/{id}/twitch/unlink endpoint (effc288)
- **admin:** display INACTIVE accounts and add Unlink Twitch action in UI (fc2619c)
- **admin:** account management — delete, unlink, migrate, and inactive account support (859cb04)

### Fixes
- **admin:** null-safe twitchId comparison and complete 404 test verification (2964ef7)
- **admin:** add AccountService MockitoBean in AdminMembersTemplateTest (dc0d8c0)
- **admin:** add fallback i18n keys and delete confirmation guard (ffc8422)
- **admin:** audit log for account deletion and i18n confirmation message (626cab0)
- **admin:** copy sourceName in binding update and add missing test assertions (a4f5c39)
- **admin:** correct modal structure and JS for migration UI (cec3baf)
- **admin:** replace th:onclick with data attribute for Thymeleaf 3.1+ compatibility (2a30e43)
- **account:** delete experiment overrides before removing user account (29baaa1)
- **account:** delete all FK-referencing records before removing user account (727ce23)
- **migration:** rename V26 to V29 to avoid version conflict (653fc5a)
- **security:** null-safe twitchUsername/twitchId in OAuth2 user handling (a0bd677)
- **account:** disable bot and clear profile on Twitch unlink (97b1a78)
- **account:** guard null Twitch API response in validation batch (2f5f794)
- **account:** separate INACTIVE and PENDING_DELETION re-activation paths (226cc79)
- **admin:** guard ACTIVE status before Twitch unlink in controller (6e4e02d)
- **admin:** correct INACTIVE display and strengthen template test (5595cc8)
- **account:** add @Transactional to nightly Twitch validation job (bfeb2dd)

### Other
- **account:** initialize profileImageUrl in test setup to guard unlink assertions (d40bd3f)
- **release:** bump version to 0.3.0-ALPHA (82127fa)

Anciennement `v0.3.0-ALPHA`.

## [v1.0.0-BETA.10](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.10) - 2026-06-04

### Features
- **steam:** add rate limiter with 429 handling in RealSteamApiClient (bb4197c)
- **twitch:** initialize stream live state on session welcome (d501a13)
- **twitch:** initialize stream live state on session welcome (070b514)
- **steam:** add rate limiter with 429 handling in RealSteamApiClient (ef183ab)

### Other
- **chat:** disable GameCommand and SetGameCommand with ConditionalOnExpression (b4f7be4)
- **chat:** disable GameCommand and SetGameCommand with ConditionalOnExpression (bb32997)
- update version to 0.2.7-ALPHA (e1bd361)

Anciennement `v0.2.7-ALPHA`.

## [v1.0.0-BETA.9](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.9) - 2026-06-04

### Features
- **monitoring:** listen to Twitch channel.update CCL and category events, add session gauge and login counter (f2a9dec)
- **monitoring:** listen to Twitch channel.update CCL and category events, add session gauge and login counter (4a686a3)
- **twitch:** add TwitchTokenService for shared token refresh logic (cd2ee58)

### Fixes
- **steam:** pass personal token to getPlayerSummary for private profile access (605510f)
- **steam:** pass personal token to getPlayerSummary for private profile access (4fe7513)
- **ui:** ajoute le menu hamburger pour la navigation mobile (f9fbe84)
- **ui:** ajoute les media queries responsive mobile (d9a8488)
- **ui:** améliore la disposition sur mobile (9478287)
- **ui:** corrige le débordement horizontal des cards sur mobile (26a9e9a)
- **ui:** aligne le z-index du feedback-widget sur celui du theme-switcher (b251607)
- **ui:** corrige la hiérarchie z-index des panneaux glissants (d3e9975)
- **ui:** bloque le scroll de la page quand un panneau glissant est ouvert (318e792)
- **ui:** corrections UI — mobile, z-index et scroll lock (5f64511)
- **twitch:** guard against null access_token in refresh response (b825d64)
- **twitch:** add token refresh and retry on 401 in TwitchServiceImpl (3e8019e)
- **twitch:** improve retry error logging and Content-Type consistency in TwitchServiceImpl (e3349c1)
- **twitch:** use resolveAccessToken consistently and fix token propagation in subscribe (40f9ea3)
- **twitch:** deduplicate initChannelState warning per user (fba2ea9)
- **twitch:** add token refresh with retry and deduplicate warnings (2b0c2d1)

### Refactoring
- **twitch:** delegate token refresh to TwitchTokenService in TwitchEventSubService (21f2c5d)

### Other
- update version to 0.2.5-ALPHA (ec9bcf7)
- **twitch:** remove redundant same-package import in TwitchEventSubServiceTest (395da09)
- update version to 0.2.6-ALPHA (81c4bab)

Anciennement `v0.2.6-ALPHA`.

## [v1.0.0-BETA.8](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.8) - 2026-06-03

### Other
- update version to 0.2.5-ALPHA (2deb289)

Anciennement `v0.2.5-ALPHA`.

## [v1.0.0-BETA.7](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.7) - 2026-06-03

### Fixes
- **security:** restore TwitchLoginSuccessHandler wiring and guard mock-web profile (8666b86)
- **ui:** move steamPrivacyModal out of htmx fragment into page template (c6a26fc)

Anciennement `v0.2.4-ALPHA`.

## [v1.0.0-BETA.6](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.6) - 2026-06-03

### Features
- **steam:** add personal API token to resolve private profile (82b7306)
- **steam:** add personal API token to resolve private profile (f750fa6)

### Other
- **channel:** add TokenEncryptionService mock to WebMvc test contexts (0d761eb)
- **channel:** update isProfilePublic stubs to two-arg signature (ff88191)
- update version to 0.2.3-ALPHA (e288e6f)

Anciennement `v0.2.3-ALPHA`.

## [v1.0.0-BETA.5](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.5) - 2026-06-03

### Fixes
- **steam:** check game list visibility in addition to profile visibility (d12881c)
- **steam:** check game list visibility in addition to profile visibility (b78bf3e)

### Other
- update version to 0.2.2-ALPHA (dcba849)

Anciennement `v0.2.2-ALPHA`.

## [v1.0.0-BETA.4](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.4) - 2026-06-03

### Fixes
- **flyway:** enable outOfOrder to apply missed V15 migration (3b62241)
- **flyway:** enable outOfOrder to apply missed V15 migration (cf03c6b)
- **connections:** add steamProfilePrivate to main channel page model (a3ff51a)
- **eventsub:** refresh expired Twitch user token before subscribing (500af5f)
- **eventsub:** suppress repeated token refresh warnings per user (b982364)
- **connections:** add steamProfilePrivate to main channel page model (0d2e31c)
- **eventsub:** refresh expired Twitch user token before subscribing (3f40c13)

### Other
- update version to 0.2.1-ALPHA (189686c)

Anciennement `v0.2.1-ALPHA`.

## [v1.0.0-BETA.3](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.3) - 2026-06-03

### Features
- **mock:** add mock user with private Steam profile and admin toggle (346df2c)
- **whitelist:** add whitelist_entry table, entity and repository (6d49fa4)
- **whitelist:** add WhitelistService with enable/add/remove/findAll (8421e84)
- **whitelist:** auto-import app.whitelist IDs to DB on startup (d202123)
- **whitelist:** show error on login page when user is not whitelisted (b6198c1)
- **whitelist:** add admin whitelist page with add/remove/toggle (8e53b1b)

### Fixes
- detect private Steam profile and warn the user in connections panel (7fb8417)
- **whitelist:** atomic toggle, template test, method reference cleanup (1dbd108)
- detect private Steam profile and warn the user in connections panel (e426895)
- **whitelist:** atomic toggle, template test, method reference cleanup (c8b2336)

### Refactoring
- **whitelist:** add logging and use Boolean.parseBoolean() (6f970c4)
- **whitelist:** replace @Value whitelist with WhitelistService in OAuth2UserService (6ad19f6)

### Other
- update version to 0.2.0-ALPHA (ba306cd)

Anciennement `v0.2.0-ALPHA`.

## [v1.0.0-BETA.2](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.2) - 2026-06-02

### Features
- **metrics:** expose countLive() on StreamStateService (6086765)
- **metrics:** add countByBotEnabledTrueAndStatus to UserAccountRepository (bf8b43d)
- **metrics:** add CatapultGauges MeterBinder for active users and live streams (688ca90)
- **metrics:** add IGDB cache hit/miss counters to IgdbService (1d5fd8e)
- **metrics:** add poll duration timer and users polled counter to SchedulerService (dcb7b0f)
- **metrics:** add Grafana panels for IGDB cache, scheduler and live gauges (adbea16)
- **metrics:** add MetricsEventListener for game/stream/account/experiment events (e4373dc)
- more monitoring (dbc1dcb)
- **domain:** add apply_default_on_* flags to UserSettings (9d6631c)
- **event:** gate stream-end default reset on applyDefaultOnStreamEnd flag (2942b53)
- **event:** gate no-game fallback on applyDefaultOnNoGame flag (f2e892e)
- **event:** apply default category on stream start when no pending game (515759f)
- **web:** accept and persist trigger flag params in saveNoGameSettings (2e73df9)
- **i18n:** add trigger flag labels for no-game settings (6cdeb58)
- **ui:** add trigger flag checkboxes to no-game settings card (be693bf)
- selection no game set (fbde0bf)

### Fixes
- incorrect routing to repository URL in footer (052fef5)
- bump year to 2026 (adb53eb)
- **test:** update test (13d3866)
- **footer:** fix incorrect repository link and corrected yea (a942723)
- error 500 on privacy page (165abdd)
- feedback-widget not correctly positionned (add5893)
- feedback-widget dissapear when closed (9c740eb)
- error 500 on privacy page (b32f5c7)
- feedback-widget close button not visible (ae3e518)
- feedback widget (d9ac277)
- changelog not loading in Docker container (5df43c9)
- changelog not loading in Docker container (eb436ee)
- **metrics:** ensure poll duration timer always records via finally block (45057f9)
- **metrics:** ensure poll duration timer always records via finally block (15b23bf)

### Other
- **db:** add default game trigger flag columns to user_settings (5ef63b3)
- update version to 0.1.0-ALPHA (b4129c5)

Anciennement `v0.1.0-ALPHA`.

## [v1.0.0-BETA.1](https://github.com/enimaloc/catapult/releases/tag/v1.0.0-BETA.1) - 2026-06-02

### Features
- add PostgreSQL schema via Flyway and JPA entities (15d94ec)
- add JPA repositories and full application.properties configuration (fb1f87f)
- OAuth2 authentication with Twitch/Steam/Discord, token encryption, account lifecycle (78c054d)
- add persistence with PostgreSQL and Flyway migrations (1e0c777)
- add OAuth2 authentication (Twitch/Steam/Discord) (87b981c)
- GameGetter interface, SteamGameGetter, DiscordGameGetter, GameGetterChain (610012b)
- add game detection via Steam/Discord getters (e7d6d29)
- SchedulerService, GameStateService, GameDetectedEvent, NoGameDetectedEvent (5116ec5)
- add scheduler and game state events (f7b134d)
- IgdbService, BindingService, TwitchService, GameEventListener (54b0d77)
- add Twitch category binding and IGDB integration (8d2795a)
- chat commands system (ChatCommandEvent, CommandRegistry, !game, !setgame) (441f4de)
- add Twitch chat command system (!game, !setgame) (a9eef92)
- Thymeleaf UI — /login, /dashboard, /settings, /bindings controllers and templates (ce12f70)
- add Thymeleaf web UI (login, dashboard, settings, bindings) (fcd2f52)
- add Dockerfile and docker-compose.yml (4443531)
- multi-stage Dockerfile + build directive for local run (353a9bc)
- add Docker containerization with multi-stage local build (c9dd06b)
- optional Steam/Discord providers + IGDB fallback to TWITCH_CLIENT_ID (99030ca)
- make Steam/Discord providers optional with IGDB fallback (b188f5c)
- add IgdbClient — thin IGDBWrapper wrapper with thread-safe API calls (cd797af)
- rewrite IgdbService with app-level token and two-level cache (226d9bd)
- add platform external IDs and age ratings tables to IGDB cache (08a6c07)
- persist external IDs and age ratings during IGDB preload (de570d3)
- resolve Twitch game ID from IGDB external games table (108cda6)
- preload IGDB cache from users' Steam libraries instead of top-500 (2a8a82b)
- batch fetch Steam app IDs from IGDB in a single API call (ca2f738)
- persist CCL suggestions to DB (igdb_game_ccl_cache) (ada725b)
- enrich CCLs with Steam store age rating data (cea4c5f)
- add per-binding CCL toggle and inline table editing (c8ced26)
- rewrite IGDB integration with app-level token, DB cache and CCL pipeline (0e46c4a)
- add steam_id column and replace Discord provider with Xbox/BattleNet (cc83691)
- replace Discord game getter with Xbox/BattleNet stubs, use Steam API key (d33877a)
- add Xbox/BattleNet OAuth2 registration and update settings page (e1c6aaa)
- add Steam integration and Xbox/BattleNet OAuth2 stubs (36e095c)
- add per-user activity log with SSE streaming to dashboard (1002cdd)
- add per-user activity log with SSE streaming (c3bfee4)
- add ROLE_ADMIN authority based on app.owner-id and V2 migration (9572cc3)
- add TwitchCclDefinition and IgdbAgeRatingCategory entities and repos (d71553b)
- add AdminCclService with Twitch/IGDB startup sync (6bdfeff)
- replace TwitchCcl enum with DB-driven String CCL IDs (c39ff4c)
- add AdminCclController, admin/ccl.html and admin nav link (37ee96a)
- replace IGDB age rating categories with content descriptors for CCL mapping (bf0220a)
- de-duplicate descriptor select and auto-expand mapping to all org variants (febd5d4)
- add admin CCL management with IGDB content descriptor sync (8fb8eb5)
- add Twitch token refresh (716905a)
- add Twitch token refresh (cd7efda)
- add StreamStateService to track live status and pending bindings (8518c48)
- add StreamOnlineEvent and StreamOfflineEvent (64ae9eb)
- add TwitchService.resetToDefault() to reset channel category on stream end (f200a03)
- add TwitchEventSubService with WebSocket EventSub connection management (7451709)
- gate Twitch updates behind live check, apply pending on stream.online (6f38547)
- add Twitch EventSub with live stream detection and pending bindings (d6bd725)
- add twitch-event-spy CLI for real-time event logging (899abb4)
- add twitch-event-spy CLI for real-time event logging (e96d019)
- add blanc and old-steam CSS theme variable blocks (ecfd76e)
- add floating theme switcher CSS (8e65f49)
- add FOUC-prevention theme script to head fragment (283fa2c)
- add setTheme and initThemeUI JS functions (0c70b5d)
- add floating theme switcher widget to nav fragment (3d52b8f)
- **old-steam:** override border-radius for sharp Windows XP era edges (12232a0)
- add Nord, Dracula, Catppuccin Mocha and Tokyo Night themes (8aa5e62)
- add CSS theme system with Nord, Dracula, Catppuccin and Tokyo Night themes (18027f9)
- implement UserDetails on CatapultOAuth2User for SwitchUserFilter support (8ace9eb)
- add admin/members.html template (c11d48e)
- add impersonation banner and members nav link (4378df5)
- add pill-toggle CSS component (6873438)
- refactor bot column to pill-toggle in members table (6a11362)
- refactor statut column to pill-toggle in members table (ab4306b)
- add admin account impersonation via SwitchUserFilter and members management (d1f2501)
- change ChatCommand.execute() return type to Object for chat responses (fdadc46)
- wire TwitchChatService into CommandRegistry for chat responses (a33503f)
- add Twitch chat service with IRC and EventSub implementations (bf9b18c)
- add V17 migration for twitch_category_cache table (088af9f)
- add Twitch category cache with DB-backed search and prewarm (1a8c5ad)
- add no_game_ccls to UserSettings and Flyway migration V19 (4bb0829)
- apply no-game CCLs in TwitchService.resetToDefault (9638bfb)
- add no-game category and CCL settings (2089672)
- add searchGames() to IgdbService (783da9c)
- support data-search-url override in gameSearch() (2fb7ac0)
- replace mock steam text inputs with IGDB autocomplete (86573ef)
- add IGDB game search endpoint with autocomplete integration (8f19845)
- i18n login and nav fragments (f394c34)
- i18n admin members page (1442901)
- add ICU4J i18n with EN/FR translations across all templates (63824df)
- refresh game if imcomplete (96d34b6)
- update notification event handling to include content classification labels (13889fe)
- add findBySourceId method to retrieve all external IDs for a given source (f3938f3)
- add mock-web profile for local development without OAuth2 (0e55746)
- add A/B testing migration (V20) (944ee97)
- annotation-based experiment definition with Thymeleaf dialect (869d1f2)
- add experiment_overrides migration (V21) (4786e12)
- seed ExperimentService knownKeys from DB at startup (ab07415)
- add HideForVariant, IfRolledOut, VariantAttribute processors and ensureExists integration (4a3f1d5)
- add #experiments expression utility object to Thymeleaf dialect (2fa133b)
- add annotation-driven experiment overrides with deferred user resolution in ExperimentSynchronizer (7f591dd)
- make ShowForVariantProcessor status-aware and add show-definition mode (97d5986)
- add third bot-right variant and per-user overrides to SidebarLayoutExperiment (710c0e1)
- add A/B testing engine with Thymeleaf dialect, overrides and rollout (1b242b0)
- add CSS classes for home landing page (d67cc67)
- add home landing page template (7164321)
- add footer with version info, GDPR privacy page and public landing page (c95895f)
- add profileImageUrl field to UserAccount (1c577be)
- persist Twitch profile_image_url on OAuth2 login (41aad1b)
- add channel card grid CSS classes (79e3195)
- add mock A/B experiment fixtures for dark-mode and notification-banner (cf6e6ae)
- add multi-channel support with owner/mod access control (5a2af76)
- add incomplete fallback fields to UserSettings (1ff88b4)
- apply incomplete fallback game when binding is INCOMPLETE (527f57c)
- apply incomplete binding fallback game when binding is INCOMPLETE (3a340c4)

### Fixes
- use VARCHAR instead of PostgreSQL custom enums for Hibernate compatibility, switch ddl-auto to none (e80987f)
- add test application.properties with complete OAuth2 provider configuration (99803a2)
- rename OAuth2UserService to CatapultOAuth2UserService to avoid naming conflict, clean AccountService imports (533da7c)
- add app.css + explicit Flyway location + disable open-in-view (b76ccf3)
- explicit Flyway bean + DependsOn to guarantee migrations before scheduler (a993d5d)
- add app.css, fix Flyway location and explicit bean startup order (13ab00c)
- handle Twitch user info endpoint (Client-ID header + data[] unwrap) (d3adb01)
- add OAuth2 error logging and expose postgres port (a3524b1)
- add OAuth2 failure handler with explicit ERROR logging (e0a2a92)
- use CLIENT_SECRET_POST for Twitch — Twitch ignores Basic auth credentials (313c738)
- fix Twitch user info endpoint and OAuth2 login debugging (c791605)
- disable Spring Boot Flyway auto-config, always call migrate() explicitly (bbe3b1e)
- add @DependsOn("flyway") on IgdbService to guarantee migration order (1b4df81)
- persist preloaded IGDB games to DB cache on preload (8b20611)
- resolve Twitch category ID from Twitch API when IGDB external ID unavailable (a553e62)
- use age ratings as primary CCL signal, fix theme keyword matching (d84bee8)
- cache CCL suggestions in memory to avoid repeated IGDB API calls (d2daf02)
- filter read-only MatureGame CCL and preload CCLs at startup (fc5288b)
- always send full CCL payload to Twitch to clear removed labels (a8c2412)
- use data-id attributes in bindings.html to comply with Thymeleaf 3.1 security restrictions (876d459)
- mutate existing CCL collection instead of replacing it (e1bcfb0)
- push Twitch update immediately on binding edit or toggle (37a5783)
- remove LanguageBarrier CCL — not a valid Twitch label (913e643)
- add steam_id to UserAccount and remove DiscordGameGetter (prerequisite for Steam integration) (5f65886)
- remove DiscordGameGetter from GameGetterChain (only STEAM active until Xbox/BattleNet branch) (75f491d)
- rename V2__admin_ccl.sql to V11 to avoid Flyway version conflict (031209d)
- switch IGDB descriptor sync to /age_ratings to get organization context (5f8eb71)
- drop FK on ccl_igdb_mapping.igdb_rating_descriptor_id to allow seed migration (315588f)
- replace V13 seed migration with keyword-based default mapping at startup (88c126d)
- handle rate limit in doResetToDefault consistently with doUpdateChannel (b798642)
- use shared HttpClient and add PreDestroy shutdown in TwitchEventSubService (cca1715)
- keep websocat connection open after stdin EOF (1aa0c88)
- show subscription errors instead of silently crashing (8c9d59f)
- require user access token for WebSocket EventSub, add auth hint (4261076)
- improve theme-btn accessibility and button reset (9461e9e)
- use const and arrow functions in theme JS for consistency (ef96673)
- add serialVersionUID and missing isEnabled test to CatapultOAuth2User (a6ebbbb)
- correct page title in admin/members.html (d934c78)
- log warning when command result JSON serialization falls back to toString (e67b2a2)
- strip query before length check in searchGames() (3c7010a)
- use search-wrapper class for consistent dropdown positioning (9dc734d)
- require game selection before submit, remove redundant import (fa3b318)
- fall back to app-token search when user token absent for Twitch category lookup (5c257c3)
- add H2 database dependency (0c1f1b4)
- quote all H2 identifiers to avoid KEY reserved word conflict (1120cd8)
- use NON_KEYWORDS=KEY in H2 URL instead of globally_quoted_identifiers (4240724)
- variable reference (5bbbe9e)
- add ShowForVariantProcessor tests, warn on malformed exp: attributes, document precedence (773b36c)
- assert no service interactions for anonymous users in ExpressionObject tests (70f94fe)
- enable Thymeleaf expression object caching so assignment cache is effective per render (2b00618)
- missing parameter for ShowForVariantProcessor (1cdd9aa)
- display hero tagline on landing page (d009167)
- missing column internal_id in experiment_variants table (a6dfcac)

### Performance
- batch-fetch CCLs from IGDB during cache prewarm (2f84a9b)
- add indexes to A/B testing tables (31e586c)

### Refactoring
- remove user token from IGDB calls in BindingService (9143da6)
- use IGDB content_descriptions for CCL detection (e28efb2)
- use IGDB v4 age rating API and cache rating labels in DB (0bfec32)
- remove TwitchCcl enum and dead age-rating classes (ad7f82b)
- remove Xbox and BattleNet game getters (eb25d55)
- remove Xbox and BattleNet game getters (39a4722)
- extend template layout with main_class param and update login (ebb15ed)
- migrate admin/members to template layout (624a85d)
- add shared template layout and migrate all pages (764470b)
- extract createDescriptor() helper in AdminCclService.syncIgdbDescriptors() (b2b4494)
- fix ThemeContrastTest classpath resource and add pair count guard (c5e31eb)

### Documentation
- add IGDB redo design spec and implementation plan (ed8f345)
- add spec for incomplete binding fallback game feature (c6b0e54)
- add implementation plan for incomplete fallback game feature (ef1f427)

### Other
- add PostgreSQL service to test workflow for integration tests (8f098b7)
- remove temporary debug logging levels (fc7a4bc)
- add unit tests for CCL pipeline and bindings controller (b6ab1ff)
- add playwright-mcp and application-dev.properties to gitignore (150127b)
- add admin CCL and OAuth2User unit tests (2116155)
- add missing UserSettingsRepository mock and onNoGameDetected coverage (b8c9d23)
- **mock-web:** add application-mock-web.properties with H2 config (a61d546)
- add jsoup for HTML template tests (e6ecf6d)
- add WCAG AA contrast test for all CSS themes (b8a1cf5)
- add LoginTemplateTest for HTML structure (0df5508)
- add MockLoginTemplateTest for HTML structure (d6ed7d8)
- add AdminMembersTemplateTest for HTML structure (ab2b34c)
- add AdminExperimentsTemplateTest for HTML structure (ecb6e4e)
- add AdminCclTemplateTest for HTML structure (fd6c7db)
- inject git branch, commit and github url via bootBuildInfo (37cac8d)
- strengthen sources section assertion in HomeTemplateTest (e312987)
- upgrade Java toolchain from 17 to 21 (22926dd)
- ignore catapult_obs_local.py local script (b66dad3)
- add header comment to V24 migration (6c46243)
- cover Optional.empty settings case in incomplete fallback (f4fd685)
- add Sonarqube analysis (8774db9)

… et 143 autres commits.

Anciennement `v0.0.1-ALPHA`.

## [v0.0.0](https://github.com/enimaloc/catapult/releases/tag/v0.0.0) - 2026-03-21

Commit initial du projet, point de départ de la numérotation des versions.
