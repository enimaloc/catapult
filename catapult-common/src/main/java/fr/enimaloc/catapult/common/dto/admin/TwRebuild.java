package fr.enimaloc.catapult.common.dto.admin;

/**
 * The three ways an admin can rebuild the trigger warning mappings: the route under catapult-api's
 * {@code /api/admin/tw/}, and the {@code non-overridden} / {@code untouched} / {@code force-pinned} name catapult-web uses.
 */
public enum TwRebuild {
    NON_OVERRIDDEN("rebuild", "non-overridden"),
    UNTOUCHED("rebuild/force", "untouched"),
    FORCE_PINNED("rebuild/force-pinned", "force-pinned");

    private final String apiPath;
    private final String pathName;

    TwRebuild(String apiPath, String pathName) {
        this.apiPath = apiPath;
        this.pathName = pathName;
    }

    public String apiPath() {
        return apiPath;
    }

    public String pathName() {
        return pathName;
    }

    /** The mode named {@code non-overridden}, {@code untouched} or {@code force-pinned}; null for anything else. */
    public static TwRebuild of(String pathName) {
        for (TwRebuild mode : values()) {
            if (mode.pathName.equals(pathName)) return mode;
        }
        return null;
    }
}
