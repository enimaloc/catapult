package fr.enimaloc.catapult.service.enrichment.riot;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * A League of Legends platform ({@code euw1}…, the host of the platform routes) and the routing cluster it belongs to
 * ({@code europe}…, the host of the regional routes). The Riot account routes (account-v1) are served by
 * {@code americas}, {@code asia} and {@code europe} only: the {@code sea} platforms ask {@code asia} for them.
 */
public enum RiotPlatform {
    EUW1(Cluster.EUROPE), EUN1(Cluster.EUROPE), TR1(Cluster.EUROPE), RU(Cluster.EUROPE), ME1(Cluster.EUROPE),
    NA1(Cluster.AMERICAS), BR1(Cluster.AMERICAS), LA1(Cluster.AMERICAS), LA2(Cluster.AMERICAS),
    KR(Cluster.ASIA), JP1(Cluster.ASIA),
    OC1(Cluster.SEA), PH2(Cluster.SEA), SG2(Cluster.SEA), TH2(Cluster.SEA), TW2(Cluster.SEA), VN2(Cluster.SEA);

    /** A Riot routing cluster. */
    public enum Cluster {
        AMERICAS, ASIA, EUROPE, SEA;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public String host() {
            return "https://" + key() + ".api.riotgames.com";
        }

        /** The cluster that serves the account routes for this one. */
        public Cluster accountCluster() {
            return this == SEA ? ASIA : this;
        }

        public static Optional<Cluster> parse(String raw) {
            if (raw == null) {
                return Optional.empty();
            }
            String key = raw.strip().toLowerCase(Locale.ROOT);
            return Arrays.stream(values()).filter(c -> c.key().equals(key)).findFirst();
        }
    }

    private final Cluster cluster;

    RiotPlatform(Cluster cluster) {
        this.cluster = cluster;
    }

    public Cluster cluster() {
        return cluster;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String host() {
        return "https://" + key() + ".api.riotgames.com";
    }

    public static Optional<RiotPlatform> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String key = raw.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(p -> p.key().equals(key)).findFirst();
    }
}
