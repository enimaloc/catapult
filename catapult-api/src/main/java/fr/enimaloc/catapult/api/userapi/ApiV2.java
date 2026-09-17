package fr.enimaloc.catapult.api.userapi;

/** Shared constants for the v2 user-facing API — split out so response DTOs in their own files can build "more" links without depending on UserApiV2Controller itself. */
final class ApiV2 {

    // Path-prefixed versioning: schema changes ship as a new controller/path (see
    // UserApiV1Controller for the frozen v1 schema) rather than a query-param-selected variant.
    static final int VERSION = 2;

    private ApiV2() {}
}
