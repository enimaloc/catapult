package fr.enimaloc.catapult.controller;

/** What a test outside this package may do to {@link SiteSocialLinks}: empty this instance's list at once. */
public final class SiteSocialLinksTestAccess {

    private SiteSocialLinksTestAccess() {
    }

    /** Marks {@code links}' list stale here, as {@link SiteSocialLinks#CLEAR_TOPIC} does on each instance. */
    public static void clearLocally(SiteSocialLinks links) {
        links.clearLocally();
    }
}
