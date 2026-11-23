package fr.enimaloc.catapult.service.enrichment.blizzard;

import java.util.Locale;
import java.util.Optional;

/** A Blizzard API region; the host and the namespaces derive from it. */
public enum BlizzardRegion {
    EU(2), US(1), KR(3), TW(3);

    private final int sc2RegionId;

    BlizzardRegion(int sc2RegionId) {
        this.sc2RegionId = sc2RegionId;
    }

    /** Lowercase code, as it appears in hosts, namespaces and routes. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String host() {
        return "https://" + key() + ".api.blizzard.com";
    }

    public String profileNamespace() {
        return "profile-" + key();
    }

    public String dynamicNamespace() {
        return "dynamic-" + key();
    }

    /** The region id of the StarCraft II profile API (1 US, 2 EU, 3 KR / TW). */
    public int sc2RegionId() {
        return sc2RegionId;
    }

    /** One of {@code eu|us|kr|tw} (any case); anything else is empty. */
    public static Optional<BlizzardRegion> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (BlizzardRegion region : values()) {
            if (region.key().equalsIgnoreCase(value)) {
                return Optional.of(region);
            }
        }
        return Optional.empty();
    }
}
