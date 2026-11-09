package fr.enimaloc.catapult.api;

/**
 * The prefixes of the internal routes, the ones only catapult-web calls (the external routes keep their own paths).
 * What belongs to one account sits under {@link #USER}, its {@code userId} the account's id; what only an admin
 * reaches sits under {@link #ADMIN}; global routes (no owner) sit directly under {@link #INTERNAL}.
 */
public final class ApiPaths {

    /** Every internal route starts with it. */
    public static final String INTERNAL = "/api/internal";
    /** One account's resources: a channel's (owner or moderator) or a member's own. */
    public static final String USER = INTERNAL + "/users/{userId}";
    /** The admin routes, guarded by the URL rule ({@code Authz.admin()}). */
    public static final String ADMIN = INTERNAL + "/admin";

    private ApiPaths() {}
}
