package fr.enimaloc.catapult.api.userapi;

/** Shared constants for the v2 user-facing API — split out so response DTOs in their own files can build "more" links without depending on UserApiV2Controller itself. */
public final class ApiV2 {

    // Real API versioning (Spring Framework 7's native support, configured in
    // ApiVersioningConfig): the URL itself carries no version marker — a schema change ships as a
    // new "version" value, resolved from the X-API-Version request header, not a new URL prefix.
    // UserApiV1Controller predates this and keeps its own frozen "/api/game" + "/api/v1" paths
    // untouched (existing pasted-URL consumers can't attach headers), so it isn't part of this
    // versioning scheme at all — it's a separate, unversioned controller by design.
    static final String VERSION = "2";

    // Request header carrying the version (see ApiVersioningConfig). Public: the CORS of the public routes
    // (ApiSecurityConfig) allows it.
    public static final String VERSION_HEADER = "X-API-Version";

    // Query parameter carrying the version when the header is absent (see ApiVersioningConfig).
    static final String VERSION_PARAM = "version";

    // Stable, version-neutral mount point for the v2 controller and all its "more" links.
    static final String PATH = "/api/user";

    // Mount points of the current routes ("games" by platform id, the caller's own bindings); the
    // "more" links of GameInfoAssembler point at them in the CURRENT link style.
    static final String GAMES_PATH = "/api/games";
    static final String ME_BINDINGS_PATH = "/api/me/bindings";

    // The other mount points under /api/me: the caller's own game, their custom bindings.
    static final String ME_GAME_PATH = "/api/me/game";
    static final String ME_CUSTOM_BINDINGS_PATH = "/api/me/custom-bindings";

    // The widget token of the dev backdoor (DevBackdoorResolver) that names no real game, and the
    // example the legacy routes' "uuid" path parameter documents. LegacyRoutesGate does not read it:
    // it recognizes any backdoor uuid by DevBackdoorResolver.isBackdoor.
    static final String GENERIC_UUID_EXAMPLE = "00000000-0000-0000-0001-000000000190";

    private ApiV2() {}
}
