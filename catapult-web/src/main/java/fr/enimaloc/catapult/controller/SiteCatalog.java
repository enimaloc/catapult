package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.dto.index.FeatureDto;
import fr.enimaloc.catapult.dto.index.PlatformDto;
import fr.enimaloc.catapult.dto.index.SpaPage;

import java.util.List;

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

    public static final List<SpaPage> PAGES = List.of(
            new SpaPage("", "pages/landing", "landing", "/spa/landing", "page.title.landing"),
            new SpaPage("privacy", "pages/privacy", "privacy", "/spa/privacy", "page.title.privacy"),
            new SpaPage("channels", "pages/channels", "channels", "/spa/channels", "page.title.channels"),
            new SpaPage("channel", "pages/channel", "channel", "/spa/channel", "page.title.channel", true)
    );

    private SiteCatalog() {
    }

    /** Whether {@code path} is one of the static SPA pages (dynamic ones have their own route). */
    public static boolean isPage(String path) {
        return PAGES.stream().map(SpaPage::getId).anyMatch(path::equals);
    }
}
