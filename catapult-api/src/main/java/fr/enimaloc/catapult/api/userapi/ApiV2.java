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

    // Stable, version-neutral mount point for the v2 controller and all its "more" links.
    static final String PATH = "/api/user";

    // Baked-in Swagger example for "uuid" path parameters — every uuid endpoint's spec carries
    // this literal value so SwaggerUiJwtTransformer's client-side script has something fixed to
    // find and replace with the caller's own widget token (see its class javadoc for why). Public:
    // also the final fallback in ApiChannelDataController#resolveExampleUuid, for a user with no
    // encodable binding on record — the same generic demo everyone unpersonalized would see.
    public static final String GENERIC_UUID_EXAMPLE = "00000000-0000-0000-0001-000000000190";

    private ApiV2() {}
}
