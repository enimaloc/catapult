package fr.enimaloc.catapult.controller;

/** What a test outside this package may do to {@link SiteMetaTags}: empty this instance's list at once. */
public final class SiteMetaTagsTestAccess {

    private SiteMetaTagsTestAccess() {
    }

    /** Marks {@code tags}' list stale here, as {@link SiteMetaTags#CLEAR_TOPIC} does on each instance. */
    public static void clearLocally(SiteMetaTags tags) {
        tags.clearLocally();
    }
}
