package fr.enimaloc.catapult.service;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

import java.util.*;
import java.util.stream.Stream;

public interface SteamStoreService {
    Map<String, Set<String>> fetchCcls(Collection<String> appIds);

    /** Base game the given appId belongs to, when appId is a demo/beta/playtest. */
    record ResolvedParentApp(String appId, String name) {}

    Optional<ResolvedParentApp> resolveEffectiveApp(String appId);

    /** Steam content_descriptors block flattened for TW resolution. */
    record SteamTwSignals(Set<Integer> contentDescriptorIds, String notesLowercase) {
        public static SteamTwSignals empty() {
            return new SteamTwSignals(Set.of(), "");
        }
    }

    Map<String, SteamTwSignals> fetchTwSignals(Collection<String> appIds);

    /**
     * Store-page short description for the given app, in the given locale (mapped to Steam's
     * own language names internally — Steam does not accept ISO codes). Empty when Steam has
     * none in that language or the app doesn't resolve (unknown appId, API failure).
     */
    Optional<String> fetchDescription(String appId, Locale locale);

    Optional<String> fetchIADisclosure(String appId, Locale locale);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SteamStorePage(
            String type, String name, @JsonAlias("steam_appid") int steamAppId,
            @JsonAlias("required_age") int requiredAge, @JsonAlias("is_free") boolean isFree,
            @JsonAlias("controller_support") String controllerSupport, int[] dlc,
            @JsonAlias("detailed_description") String detailedDescription,
            @JsonAlias("about_the_game") String aboutTheGame, @JsonAlias("short_description") String shortDescription,
            FullGame fullgame, @JsonAlias("supported_languages") String supportedLanguages, String reviews,
            @JsonAlias("header_image") String headerImage, @JsonAlias("capsule_image") String capsuleImage,
            @JsonAlias("capsule_imagev5") String capsuleImageV5, String website,
            @JsonIgnore @JsonAlias("pc_requirements") JsonNode pcRequirementsRaw, Requirement pcRequirements,
            @JsonIgnore @JsonAlias("mac_requirements") JsonNode macRequirementsRaw, Requirement macRequirements,
            @JsonIgnore @JsonAlias("linux_requirements") JsonNode linuxRequirementsRaw, Requirement linuxRequirements,
            @JsonAlias("legal_notice") String legalNotice, String[] developers, String[] publishers, Demo[] demos,
            @JsonAlias("price_overview") PriceOverview priceOverview, int[] packages,
            @JsonAlias("package_groups") Packages[] packageGroups, Platforms platforms, Metacritic metacritic,
            Category[] categories, Genre[] genres, Screenshot[] screenshots, Movie[] movies,
            Recommendations recommendations,
            @JsonIgnore @JsonAlias("achievements") JsonNode achievementsRaw, Achievement achievements,
            @JsonAlias("release_date") ReleaseDate releaseDate, @JsonAlias("support_info") SupportInfo supportInfo,
            String background, @JsonAlias("background_raw") String backgroundRaw,
            @JsonAlias("content_descriptors") ContentDescriptors contentDescriptors, Ratings ratings) {

        public SteamStorePage {
            JsonNode node = pcRequirementsRaw;
            if (node != null && node.isObject()) {
                pcRequirements = new Requirement(
                        node.has("minimum") ? node.get("minimum").asString() : null,
                        node.has("recommended") ? node.get("recommended").asString() : null
                );
            }
            node = macRequirementsRaw;
            if (node != null && node.isObject()) {
                macRequirements = new Requirement(
                        node.has("minimum") ? node.get("minimum").asString() : null,
                        node.has("recommended") ? node.get("recommended").asString() : null
                );
            }
            node = linuxRequirementsRaw;
            if (node != null && node.isObject()) {
                pcRequirements = new Requirement(
                        node.has("minimum") ? node.get("minimum").asString() : null,
                        node.has("recommended") ? node.get("recommended").asString() : null
                );
            }

            node = achievementsRaw;
            if (node != null && node.isObject()) {
                List<Achievement.Highlighted> highlighteds = new ArrayList<>();
                Optional<ArrayNode> highlighted = node.get("highlighted").asArrayOpt();
                if (highlighted.isPresent()) {
                    for (JsonNode sub : highlighted.get()) {
                        highlighteds.add(new Achievement.Highlighted(
                                sub.has("icon") ? sub.get("icon").asString() : null,
                                sub.has("localized_name") ? sub.get("localized_name").asString() : null,
                                sub.has("archived") && sub.get("archived").asBoolean(),
                                sub.has("hidden") && sub.get("hidden").asBoolean(),
                                sub.has("name") ? sub.get("name").asString() : null,
                                sub.has("path") ? sub.get("path").asString() : null
                        ));
                    }
                }
                achievements = new Achievement(node.get("total").asInt(), highlighteds.toArray(Achievement.Highlighted[]::new));
            }
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record FullGame(Integer appid, String name) {}
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Requirement(String minimum, String recommended) {
        }
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Demo(int appid, String description) {}
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record PriceOverview(
                String currency, int initial, @JsonAlias("final") int final0,
                @JsonAlias("discount_percent") int discountPercent,
                @JsonAlias("initial_formatted") String initialFormatted,
                @JsonAlias("final_formatted") String finalFormatted) {}
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Packages(
                String name, String title, String description, @JsonAlias("selection_text") String selectionText,
                @JsonAlias("save_text") String saveText, @JsonAlias("display_type") int displayType,
                @JsonAlias("is_recurring_subscription") String isRecurringSubscription, Sub[] subs
        ) {
            @JsonIgnoreProperties(ignoreUnknown = true)
            public record Sub(
                    int packageid, @JsonAlias("percent_savings_text") String percentSavingText,
                    @JsonAlias("percent_savings") int percentSaving, @JsonAlias("option_text") String optionText,
                    @JsonAlias("option_description") String optionDescription,
                    @JsonAlias("can_get_free_license") String canGetFreeLicense,
                    @JsonAlias("is_free_license") boolean isFreeLicense,
                    @JsonAlias("price_in_cents_with_discount") int priceInCentsWithDiscount
            ) {}
        }
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Platforms(boolean windows, boolean mac, boolean linux) {}
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Metacritic(int score, String url) {}
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Category(int id, String description) {}
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Genre(int id, String description) {}
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Screenshot(int id, @JsonAlias("path_thumbnail") String pathThumbnail,
                          @JsonAlias("path_full") String pathFull) {}
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Movie(
                int id, String name, String thumbnail, @JsonAlias("dash_av1") String dashAv1,
                @JsonAlias("dash_h264") String dashH264, @JsonAlias("hls_h264") String hlsH264, boolean highlight
        ) {}
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Recommendations(int total) {}
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Achievement(int total, Highlighted[] highlighted) {
            @JsonIgnoreProperties(ignoreUnknown = true)
            record Highlighted(String icon, @JsonAlias("localized_name") String localizedName, boolean archived,
                               boolean hidden, String name, String path) {}
        }
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record ReleaseDate(@JsonAlias("coming_soon") boolean comingSoon, String date) {}
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record SupportInfo(String url, String email) {}
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record ContentDescriptors(int[] ids, String note) {}
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Ratings(
                Rating esrb, Rating pegi, Rating bbfc, Rating usk, Rating cero, Rating kggrb, Rating fpb, Rating csrr,
                Rating crl, Rating agcom, Rating oflc, Rating nzoflc, Rating cadpa, Rating dejus,
                @JsonAlias("germany_rating") Rating germanyRating, Rating igrs,
                @JsonAlias("steam_australia") Rating steamAustralia, Rating mda, Rating gmedia) {
            @JsonIgnoreProperties(ignoreUnknown = true)
            public record Rating(
                    @JsonAlias("rating_generated") String ratingGenerated, String rating,
                    @JsonAlias("required_age") String requiredAge, String banned,
                    @JsonAlias("use_age_gate") String useAgeGate, String descriptors,
                    @JsonAlias("interactive_elements") String interactiveElements,
                    @JsonAlias("display_online_notice") String displayOnlineNotice,
                    @JsonAlias("display_online_music_notice") String displayOnlineMusicNotice) {}

            public List<Rating> entries() {
                return Stream.of(esrb, pegi, bbfc, usk, cero, kggrb, fpb, csrr, crl, agcom, oflc, nzoflc, cadpa,
                        dejus, germanyRating, igrs, steamAustralia, mda, gmedia).filter(Objects::nonNull)
                        .toList();
            }
        }
    }

    default Optional<SteamStorePage> fetchData(String appId, Locale locale) {
        return fetchData(appId, locale, true);
    }

    Optional<SteamStorePage> fetchData(String appId, Locale locale, boolean resolveEffectiveParent);
}
