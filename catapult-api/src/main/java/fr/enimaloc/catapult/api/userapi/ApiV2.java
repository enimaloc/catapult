package fr.enimaloc.catapult.api.userapi;

/** Shared constants for the v2 user-facing API — split out so response DTOs in their own files can build "more" links without depending on UserApiV2Controller itself. */
final class ApiV2 {

    // Real API versioning (Spring Framework 7's native support, configured in
    // ApiVersioningConfig): the URL itself carries no version marker — a schema change ships as a
    // new "version" value, resolved from the X-API-Version request header, not a new URL prefix.
    // UserApiV1Controller predates this and keeps its own frozen "/api/game" + "/api/v1" paths
    // untouched (existing pasted-URL consumers can't attach headers), so it isn't part of this
    // versioning scheme at all — it's a separate, unversioned controller by design.
    static final String VERSION = "2";

    // Stable, version-neutral mount point for the v2 controller and all its "more" links.
    static final String PATH = "/api/user";

    private ApiV2() {}
}
