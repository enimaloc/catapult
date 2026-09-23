package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.dto.index.FeatureDto;
import fr.enimaloc.catapult.dto.index.PlatformDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

@Controller
@RequiredArgsConstructor
public class IndexController {
    public static final List<FeatureDto> FEATURES = List.of(
            new FeatureDto("bolt", "features.detection"),
            new FeatureDto("link", "features.associations"),
            new FeatureDto("smart_toy", "features.realtime"),
            new FeatureDto("sell", "features.labels"),
            new FeatureDto("my_location", "features.default")
//            new FeatureDto("bolt", "features.simple")
    );
    public static final List<PlatformDto> PLATFORMS = List.of(
            new PlatformDto(PlatformDto.IconType.TEXT, "⛏", "platforms.minecraft"),
            new PlatformDto("sports_esports", "platforms.steam"),
            new PlatformDto("sports_esports", "platforms.xbox")
    );
    public static final List<SPAPage> SPA = List.of(
            new SPAPage("", "pages/landing", "landing", "/spa/landing", "page.title.landing"),
            new SPAPage("privacy", "pages/privacy", "privacy", "/spa/privacy", "page.title.privacy")
    );

    private final ModelFiller filler;

    @GetMapping
    String index(Model model, Locale locale, HttpServletRequest request) throws IOException {
        return index(model, "", locale, request);
    }

    @GetMapping({"/{page}"})
    String index(Model model, @PathVariable String page, Locale locale, HttpServletRequest request) throws IOException {
        boolean unknownPage = SPA.stream().map(SPAPage::getId).noneMatch(page::equals);
        model.addAttribute("error", unknownPage);
        if (unknownPage) {
            filler.error(model, 404);
        }
        filler.fill(model, page, locale, request);
        return "index";
    }

    @Controller
    @RequestMapping("/spa")
    @RequiredArgsConstructor
    static class SPAPages {
        private final ModelFiller filler;

        @GetMapping("/landing")
        String landing(Model model) {
            filler.defaultAttr(model, "");
            return "pages/landing :: landing";
        }

        @GetMapping("/privacy")
        String privacy(Model model, Locale locale) throws IOException {
            filler.privacy(model, locale);
            return "pages/privacy :: privacy";
        }

        @GetMapping("/{page}")
        String unknown(Model model, HttpServletResponse response) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            filler.error(model, 404);
            return "pages/error :: error";
        }
    }

    @Data
    public static class SPAPage {
        private final String id;
        private final String template;
        private final String fragment;
        private final String templateUrl;
        private final String titleKey;

        public SPAPage(String id, String template, String fragment, String templateUrl, String titleKey) {
            this.id = id;
            this.template = template;
            this.fragment = fragment;
            this.templateUrl = templateUrl;
            this.titleKey = titleKey;
        }
    }
}
