package fr.enimaloc.catapult.service.enrichment.bungie;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** What the Bungie.net client reads, as plain values. */
public final class BungieData {

    private BungieData() {
    }

    /**
     * A token answer of Bungie's OAuth: the access token (1 h), the refresh token (90 days, a new one each time) and
     * the Bungie.net membership they belong to. {@link #toString()} never shows the tokens.
     */
    public record TokenGrant(String accessToken, Instant expiresAt, String refreshToken, Instant refreshExpiresAt,
                             String membershipId) {
        @Override
        public String toString() {
            return "TokenGrant[membershipId=" + membershipId + ", expiresAt=" + expiresAt + "]";
        }
    }

    /**
     * The Bungie.net account a token belongs to ({@code GetMembershipsForCurrentUser}): its membership id, its Bungie
     * Name ({@code Name#1234}, null when it has none), its Destiny memberships and the one cross save made primary
     * (null without cross save).
     */
    public record CurrentUser(String membershipId, String displayName, List<DestinyMembership> memberships,
                              String primaryMembershipId) {
        public CurrentUser {
            memberships = List.copyOf(memberships);
        }

        /**
         * The membership the Destiny 2 profile is read from: the primary one under cross save, else the first one
         * that cross save does not override, else the first; null when the account has none.
         */
        public DestinyMembership destiny() {
            if (primaryMembershipId != null) {
                for (DestinyMembership m : memberships) {
                    if (m.membershipId().equals(primaryMembershipId)) {
                        return m;
                    }
                }
            }
            for (DestinyMembership m : memberships) {
                if (m.crossSaveOverride() == 0 || m.crossSaveOverride() == m.membershipType()) {
                    return m;
                }
            }
            return memberships.isEmpty() ? null : memberships.getFirst();
        }
    }

    /** A Destiny platform membership: its type (1 Xbox, 2 PlayStation, 3 Steam, 6 Epic…) and id. */
    public record DestinyMembership(int membershipType, String membershipId, int crossSaveOverride) {
    }

    /**
     * The Destiny 2 profile ({@code GetProfile}, components Profiles, Characters, CharacterActivities). A component
     * Bungie withholds for the player's privacy settings is {@code null} with its flag set.
     */
    public record Profile(Instant lastPlayed, Integer guardianRank, Long currentSeasonHash,
                          List<Character> characters, Activity activity, boolean profilePrivate,
                          boolean charactersPrivate) {
        public Profile {
            characters = characters == null ? List.of() : List.copyOf(characters);
        }
    }

    /** A character: its class ({@code 0} Titan, {@code 1} Hunter, {@code 2} Warlock), light, time played. */
    public record Character(String characterId, int classType, int light, long minutesPlayed, Instant lastPlayed,
                            String emblemPath) {

        /** The class as a stable key: {@code titan}, {@code hunter}, {@code warlock}; null for another value. */
        public String className() {
            return switch (classType) {
                case 0 -> "titan";
                case 1 -> "hunter";
                case 2 -> "warlock";
                default -> null;
            };
        }
    }

    /** The activity a character is in now: its definition hashes and when it started. */
    public record Activity(String characterId, long activityHash, long modeHash, Instant startedAt) {
    }

    /** The name and icon of a manifest definition. */
    public record Definition(String name, String icon) {
    }

    /** A season definition. */
    public record Season(int number, String name, Instant startsAt, Instant endsAt) {
    }

    /** Bungie's language codes ({@code lc}): the request's language when Bungie has it, else English. */
    public static String locale(Locale locale) {
        if (locale == null) {
            return "en";
        }
        String language = locale.getLanguage();
        String country = locale.getCountry();
        return switch (language) {
            case "fr", "de", "it", "ja", "ru", "pl", "ko" -> language;
            case "es" -> "MX".equals(country) ? "es-mx" : "es";
            case "pt" -> "pt-br";
            case "zh" -> "TW".equals(country) || "HK".equals(country) || "Hant".equals(locale.getScript())
                    ? "zh-cht" : "zh-chs";
            default -> "en";
        };
    }

    /** A membership type as a stable key ({@code xbox}, {@code psn}, {@code steam}, {@code epic}…); null when unknown. */
    public static String platform(Integer membershipType) {
        if (membershipType == null) {
            return null;
        }
        return switch (membershipType) {
            case 1 -> "xbox";
            case 2 -> "psn";
            case 3 -> "steam";
            case 4 -> "blizzard";
            case 5 -> "stadia";
            case 6 -> "epic";
            default -> null;
        };
    }
}
