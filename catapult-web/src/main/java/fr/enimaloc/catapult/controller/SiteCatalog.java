package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.dto.index.FeatureDto;
import fr.enimaloc.catapult.dto.index.PlatformDto;
import fr.enimaloc.catapult.dto.index.SpaPage;

import java.util.List;
import java.util.Optional;

/** The site's fixed content: landing-page features and platforms, and the SPA pages. */
public final class SiteCatalog {

    public static final List<FeatureDto> FEATURES = List.of(
            new FeatureDto("bolt", "features.detection"),
            new FeatureDto("link", "features.associations"),
            new FeatureDto("smart_toy", "features.realtime"),
            new FeatureDto("sell", "features.labels"),
            new FeatureDto("my_location", "features.default")
    );

    public static final List<PlatformDto> PLATFORMS = List.of(
            new PlatformDto(PlatformDto.IconType.TEXT, "⛏", "platforms.minecraft"),
            new PlatformDto("sports_esports", "platforms.steam"),
            new PlatformDto("sports_esports", "platforms.xbox")
    );

    /** The member's dashboards and the login and join pages have no description: they stay out of search engines. */
    public static final List<SpaPage> PAGES = List.of(
            new SpaPage("", "pages/landing", "landing", "/spa/landing", "page.title.landing", "page.description.landing"),
            new SpaPage("privacy", "pages/privacy", "privacy", "/spa/privacy", "page.title.privacy", "page.description.privacy"),
            // Served by ChangelogController, which fills its model (catapult-api's versions).
            new SpaPage("changelog", "pages/changelog", "changelog", "/spa/changelog", "page.title.changelog", "page.description.changelog"),
            new SpaPage("channel", "pages/channel", "channel", "/spa/channel", "page.title.channel", null, true),
            // The channel page as an OBS dock (ObsDock): only reached through /channel/{username}, never by its id.
            new SpaPage("channel-dock", "pages/channel-dock", "dock", "/spa/channel-dock", "page.title.channel-dock", null, true),
            // A card's help (HelpPageController); /spa/help/{cardId} is the side sheet's.
            new SpaPage("help", "pages/help", "help", "/spa/help-page", "page.title.help", null, true),
            new SpaPage("admin", "pages/admin", "admin", "/spa/admin", "page.title.admin", null),
            new SpaPage("notifications", "pages/notifications", "notifications", "/spa/notifications", "page.title.notifications", null),
            new SpaPage("login", "pages/login", "login", "/spa/login", "page.title.login", null),
            new SpaPage("join", "pages/join", "join", "/spa/join", "page.title.join", null)
    );

    private SiteCatalog() {
    }

    public static Optional<SpaPage> page(String id) {
        return PAGES.stream().filter(page -> page.getId().equals(id)).findFirst();
    }

    /** Whether {@code path} is one of the static SPA pages (dynamic ones have their own route). */
    public static boolean isPage(String path) {
        return PAGES.stream().map(SpaPage::getId).anyMatch(path::equals);
    }
}
