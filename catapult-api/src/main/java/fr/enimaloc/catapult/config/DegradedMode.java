package fr.enimaloc.catapult.config;

public final class DegradedMode {
    public static final String PROFILE = "degraded";

    private DegradedMode() {
    }

    /** True for the property source Spring Boot builds from application-degraded.properties/yml. */
    static boolean isDegradedSource(String propertySourceName) {
        return propertySourceName.contains("application-" + PROFILE + ".");
    }
}
