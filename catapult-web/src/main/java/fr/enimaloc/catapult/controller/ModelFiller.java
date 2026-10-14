package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.channel.ChannelDto;
import fr.enimaloc.catapult.common.dto.channel.ChannelListResponse;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatEventActionDto;
import fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms;
import fr.enimaloc.catapult.dto.index.FeatureDto;
import fr.enimaloc.catapult.dto.index.PlatformDto;
import fr.enimaloc.catapult.dto.index.SpaPage;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.MessageSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Fills the model every page template needs, shared by the full-page and fragment routes. */
@Component
@RequiredArgsConstructor
public class ModelFiller {
    /** Error codes with their own error.<code>.* messages; anything else uses error.generic.*. */
    private static final Set<Integer> DESCRIBED_ERRORS = Set.of(400, 401, 403, 404, 405, 429, 500, 502, 503);
    private static final String DEFAULT_PRIVACY_LANGUAGE = "en";

    /**
     * What index.html's head tells search engines and link previews: the page's title, and for
     * a public page its description and absolute canonical URL (null without a current request).
     */
    public record Seo(String title, String description, String canonicalUrl, boolean indexed) {}

    /** Static, page-independent data every template can read as ${app.*}. */
    public record AppModel(List<FeatureDto> features, List<PlatformDto> platforms, List<SpaPage> spa) {}

    private static final AppModel APP_MODEL =
            new AppModel(SiteCatalog.FEATURES, SiteCatalog.PLATFORMS, SiteCatalog.PAGES);

    private final Optional<BuildProperties> buildProperties;
    private final MessageSource messageSource;
    private final ApiService apiService;
    private final TwitchatEventForms twitchatEventForms;

    public void defaultAttr(Model model, String page) {
        model.addAttribute("app", APP_MODEL);
        model.addAttribute("page", page == null ? "" : page);
    }

    /**
     * Full-page variant: also the head's {@link Seo}, the localized titles spa.js swaps in on
     * client-side navigation, and the navbar's login state (its channels come after, see
     * {@link #navChannels(Model)}).
     */
    public void defaultAttr(Model model, String page, Locale locale) {
        defaultAttr(model, page);
        model.addAttribute("titles", titles(locale));
        model.addAttribute("seo", seo(model, page == null ? "" : page, locale));
        model.addAttribute("loggedIn", ApiClient.hasSession());
    }

    /**
     * The channels the member can act on, for the navbar; none when logged out or when the API
     * fails. Fetched by the page once shown, so a full load never waits on catapult-api for them.
     */
    public List<ChannelDto> navChannels(Model model) {
        ChannelListResponse list = ApiClient.hasSession() ? apiService.channelList() : null;
        List<ChannelDto> channels = list == null ? List.of() : list.channels();
        model.addAttribute("navChannels", channels);
        return channels;
    }

    /** An error, a member's page or an unknown one is not indexed; the channel's title names it. */
    private Seo seo(Model model, String page, Locale locale) {
        if (Boolean.TRUE.equals(model.getAttribute("error"))) {
            String error = message((String) model.getAttribute("errorTitle"), null, locale);
            return new Seo(message("page.title.error", new Object[]{error}, locale), null, null, false);
        }
        SpaPage spaPage = SiteCatalog.page(page).orElseThrow();
        Object[] titleArgs = spaPage.isDynamic() ? new Object[]{model.getAttribute("username")} : null;
        String title = message(spaPage.getTitleKey(), titleArgs, locale);
        if (!spaPage.isIndexed()) {
            return new Seo(title, null, null, false);
        }
        return new Seo(title, message(spaPage.getDescriptionKey(), null, locale), absoluteUrl("/" + page), true);
    }

    private String message(String key, Object[] args, Locale locale) {
        return messageSource.getMessage(key, args, key, locale);
    }

    /** {@code path} on this site's own origin, as the visitor (behind nginx) reached it; null outside a request. */
    static String absoluteUrl(String path) {
        if (RequestContextHolder.getRequestAttributes() == null) {
            return null;
        }
        return ServletUriComponentsBuilder.fromCurrentContextPath().path(path).toUriString();
    }

    private Map<String, String> titles(Locale locale) {
        return SiteCatalog.PAGES.stream().collect(Collectors.toMap(
                SpaPage::getId,
                page -> message(page.getTitleKey(), null, locale)));
    }

    /** The page-specific model of a static page, then the shared one. */
    public void fill(Model model, String page, Locale locale) throws IOException {
        switch (page) {
            case "privacy" -> privacy(model, locale);
            default -> { }
        }
        defaultAttr(model, page, locale);
    }

    /**
     * The privacy policy in the visitor's language (English when it has no translation), and
     * when that file last changed, as recorded in the build info (epoch when unknown).
     */
    public void privacy(Model model, Locale locale) throws IOException {
        String language = locale.getLanguage();
        Resource policy = privacyPolicy(language);
        if (!policy.exists()) {
            language = DEFAULT_PRIVACY_LANGUAGE;
            policy = privacyPolicy(language);
        }

        String lastUpdateKey = "privacy.%s.last-update".formatted(language);
        Instant lastUpdate = buildProperties
                .map(properties -> properties.get(lastUpdateKey))
                .map(Instant::parse)
                .orElse(Instant.EPOCH);

        model.addAttribute("privacy", new String(policy.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        model.addAttribute("lastUpdate", lastUpdate);
    }

    private static Resource privacyPolicy(String language) {
        return new ClassPathResource("lang/privacy/%s.html".formatted(language));
    }

    /** The dashboard model; settings, the DTDD mapping and the integration cards only for the channel's owner. */
    public void channel(Model model, String username, int page, String status, String source) {
        model.addAttribute("username", username);
        var channelPage = apiService.channelPage(username, page, status, source);
        model.addAttribute("channelPage", channelPage);
        if (channelPage.isOwner()) {
            model.addAttribute("channelSettings", apiService.channelSettings(username));
            model.addAttribute("dtddMapping", apiService.dtddMappingStatus(username));
            var twitchatEvents = channelPage.twitchat() == null ? Map.<String, List<TwitchatEventActionDto>>of()
                    : apiService.twitchatEvents(username);
            model.addAttribute("integrations", IntegrationCards.of(channelPage, twitchatEvents, twitchatEventForms));
        }
    }

    /** Error page model for the status the servlet container forwarded (500 when absent). */
    public void error(Model model, HttpServletRequest request) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        error(model, status instanceof Integer code ? code : HttpStatus.INTERNAL_SERVER_ERROR.value());
    }

    public void error(Model model, int code) {
        String prefix = DESCRIBED_ERRORS.contains(code) ? "error." + code : "error.generic";

        model.addAttribute("errorCode", code);
        model.addAttribute("errorEyebrow", "error.eyebrow");
        model.addAttribute("errorTitle", prefix + ".title");
        model.addAttribute("errorDescription", prefix + ".description");
    }
}
