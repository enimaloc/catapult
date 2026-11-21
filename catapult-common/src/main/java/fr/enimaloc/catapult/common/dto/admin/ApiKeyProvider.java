package fr.enimaloc.catapult.common.dto.admin;

/**
 * The providers whose shared API keys an admin manages: the path segment of catapult-api's
 * {@code /api/internal/admin/{apiPath}} routes, and the {@code steam}, {@code dtdd} or {@code steamgriddb} one catapult-web uses.
 */
public enum ApiKeyProvider {
    STEAM("steam-keys", "steam"),
    DTDD("dtdd-keys", "dtdd"),
    STEAMGRIDDB("steamgriddb-keys", "steamgriddb");

    private final String apiPath;
    private final String pathName;

    ApiKeyProvider(String apiPath, String pathName) {
        this.apiPath = apiPath;
        this.pathName = pathName;
    }

    public String apiPath() {
        return apiPath;
    }

    public String pathName() {
        return pathName;
    }

    /** The provider named {@code steam}, {@code dtdd} or {@code steamgriddb}; null for anything else. */
    public static ApiKeyProvider of(String pathName) {
        for (ApiKeyProvider provider : values()) {
            if (provider.pathName.equals(pathName)) return provider;
        }
        return null;
    }
}
