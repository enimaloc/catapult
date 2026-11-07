package fr.enimaloc.catapult.controller;

/**
 * Whether /channel/{username} is shown as the compact OBS dock or as the full page. OBS's embedded
 * browser (browser docks and sources alike) appends {@code OBS/<version>} to its User-Agent; the
 * {@code view} query parameter lets a visitor force either version when that guess is wrong
 * ({@code /channel/{username}/dock} always forces the dock).
 */
final class ObsDock {
    /** The {@code view} value forcing the full page, wherever it is opened. */
    static final String FULL = "full";
    /** The {@code view} value forcing the dock, wherever it is opened. */
    static final String DOCK = "dock";

    private ObsDock() {
    }

    /**
     * Whether to serve the dock for a request with this User-Agent (null when absent) and this
     * {@code view} parameter (null when absent).
     */
    static boolean wanted(String userAgent, String view) {
        if (userAgent != null && userAgent.contains(" OBS/")) return !FULL.equalsIgnoreCase(view);
        return DOCK.equalsIgnoreCase(view);
    }
}
