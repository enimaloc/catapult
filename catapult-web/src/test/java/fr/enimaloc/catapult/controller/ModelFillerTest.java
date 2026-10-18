package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.channel.ChannelDto;
import fr.enimaloc.catapult.common.dto.channel.ChannelListResponse;
import fr.enimaloc.catapult.common.dto.channel.ChannelPageData;
import fr.enimaloc.catapult.common.dto.channel.ChannelUserDto;
import fr.enimaloc.catapult.common.dto.channel.DtddMappingStatusDto;
import fr.enimaloc.catapult.common.dto.channel.PagedBindings;
import fr.enimaloc.catapult.common.dto.channel.UserSettingsDto;
import fr.enimaloc.catapult.common.twitchat.TwitchatCatalog;
import fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ModelFillerTest {

    private final ApiService api = mock(ApiService.class);
    private final StaticMessageSource messages = new StaticMessageSource();
    private ExtendedModelMap model;

    private ModelFiller filler(Optional<BuildProperties> buildProperties) {
        return new ModelFiller(buildProperties, messages, api, new TwitchatEventForms(TwitchatCatalog.load()));
    }

    private ModelFiller filler() {
        return filler(Optional.empty());
    }

    @BeforeEach
    void setUp() {
        model = new ExtendedModelMap();
    }

    private static ChannelPageData page(boolean owner) {
        return new ChannelPageData(new ChannelUserDto("1", "2", "enimaloc", "img"), "enimaloc", owner, false, true,
                null, new PagedBindings(0, 1, 0, List.of()), List.of(), Set.of(), List.of(), Set.of(),
                null, null, null, null, null, null, null, "uuid");
    }

    @Test
    void defaultAttr_exposesTheCatalogAndThePage() {
        filler().defaultAttr(model, "privacy");

        assertThat(model.get("page")).isEqualTo("privacy");
        assertThat(model.get("app")).isEqualTo(
                new ModelFiller.AppModel(SiteCatalog.FEATURES, SiteCatalog.PLATFORMS, SiteCatalog.PAGES));
    }

    @Test
    void defaultAttr_nullPageMeansTheLandingPage() {
        filler().defaultAttr(model, null);

        assertThat(model.get("page")).isEqualTo("");
    }

    @Test
    void defaultAttr_withLocale_addsEveryPageTitle_fallingBackToTheKey() {
        messages.addMessage("page.title.privacy", Locale.FRENCH, "Confidentialité");

        filler().defaultAttr(model, "", Locale.FRENCH);

        @SuppressWarnings("unchecked")
        Map<String, String> titles = (Map<String, String>) model.get("titles");
        assertThat(titles).containsEntry("privacy", "Confidentialité")
                .containsEntry("channel", "page.title.channel")
                .containsOnlyKeys("", "privacy", "channel", "admin", "notifications");
    }

    @Test
    void defaultAttr_withLocale_describesAPublicPageWithItsCanonicalUrl() {
        messages.addMessage("page.title.privacy", Locale.FRENCH, "Confidentialité");
        messages.addMessage("page.description.privacy", Locale.FRENCH, "Vos données.");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        try {
            filler().defaultAttr(model, "privacy", Locale.FRENCH);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }

        assertThat(model.get("seo")).isEqualTo(
                new ModelFiller.Seo("Confidentialité", "Vos données.", "http://localhost/privacy", true));
    }

    @Test
    void defaultAttr_withLocale_outsideARequest_hasNoCanonicalUrl() {
        filler().defaultAttr(model, "", Locale.FRENCH);

        assertThat(((ModelFiller.Seo) model.get("seo")).canonicalUrl()).isNull();
    }

    @Test
    void defaultAttr_withLocale_keepsTheChannelOutOfSearchEngines_namingIt() {
        messages.addMessage("page.title.channel", Locale.FRENCH, "{0} - Catapult");
        model.addAttribute("username", "enimaloc");

        filler().defaultAttr(model, "channel", Locale.FRENCH);

        assertThat(model.get("seo")).isEqualTo(new ModelFiller.Seo("enimaloc - Catapult", null, null, false));
    }

    @Test
    void defaultAttr_withLocale_titlesAnErrorByIt_keptOutOfSearchEngines() {
        messages.addMessage("error.404.title", Locale.FRENCH, "Page introuvable");
        messages.addMessage("page.title.error", Locale.FRENCH, "{0} - Catapult");
        model.addAttribute("error", true);
        filler().error(model, 404);

        filler().defaultAttr(model, "nope", Locale.FRENCH);

        assertThat(model.get("seo")).isEqualTo(new ModelFiller.Seo("Page introuvable - Catapult", null, null, false));
    }

    @Test
    void privacy_readsThePolicyInTheVisitorsLanguage() throws IOException {
        filler().privacy(model, Locale.FRENCH);

        assertThat((String) model.get("privacy")).isNotBlank()
                .isEqualTo(resource("/lang/privacy/fr.html"));
    }

    @Test
    void privacy_fallsBackToEnglish() throws IOException {
        filler().privacy(model, Locale.JAPANESE);

        assertThat((String) model.get("privacy"))
                .isEqualTo(resource("/lang/privacy/en.html"));
    }

    @Test
    void privacy_lastUpdateComesFromTheBuildInfoOfTheLanguageShown() throws IOException {
        Properties info = new Properties();
        info.setProperty("privacy.en.last-update", "2026-01-02T03:04:05Z");
        info.setProperty("privacy.fr.last-update", "2025-01-01T00:00:00Z");

        filler(Optional.of(new BuildProperties(info))).privacy(model, Locale.GERMAN);

        assertThat(model.get("lastUpdate")).isEqualTo(Instant.parse("2026-01-02T03:04:05Z"));
    }

    @Test
    void privacy_unknownLastUpdate_isTheEpoch() throws IOException {
        filler(Optional.of(new BuildProperties(new Properties()))).privacy(model, Locale.ENGLISH);
        assertThat(model.get("lastUpdate")).isEqualTo(Instant.EPOCH);

        filler().privacy(model, Locale.ENGLISH);
        assertThat(model.get("lastUpdate")).isEqualTo(Instant.EPOCH);
    }

    @Test
    void fill_addsThePageSpecificModelThenTheSharedOne() throws IOException {
        filler().fill(model, "privacy", Locale.ENGLISH);
        assertThat(model).containsKeys("privacy", "lastUpdate", "app", "titles").containsEntry("page", "privacy");

        model = new ExtendedModelMap();
        filler().fill(model, "", Locale.ENGLISH);
        assertThat(model).doesNotContainKeys("privacy").containsKey("app");
    }

    @Test
    void defaultAttr_withLocale_tellsWhetherTheVisitorIsLoggedIn_withoutCallingTheApi() {
        filler().defaultAttr(model, "", Locale.FRENCH);
        assertThat(model.get("loggedIn")).isEqualTo(false);

        inSession(() -> filler().defaultAttr(model, "", Locale.FRENCH));
        assertThat(model.get("loggedIn")).isEqualTo(true);
        verify(api).isAdmin(); // read from the session, not asked to catapult-api
        verifyNoMoreInteractions(api);
    }

    @Test
    void defaultAttr_withLocale_tellsWhetherTheMemberIsAnAdmin() {
        when(api.isAdmin()).thenReturn(true);
        filler().defaultAttr(model, "", Locale.FRENCH);
        assertThat(model.get("admin")).isEqualTo(false); // logged out

        inSession(() -> filler().defaultAttr(model, "", Locale.FRENCH));
        assertThat(model.get("admin")).isEqualTo(true);
    }

    @Test
    void navChannels_listsTheMembersChannels() {
        List<ChannelDto> channels = List.of(new ChannelDto(UUID.randomUUID(), "1", "enimaloc", "img", true));
        when(api.channelList()).thenReturn(new ChannelListResponse("1", channels));

        inSession(() -> filler().navChannels(model));

        assertThat(model.get("navChannels")).isEqualTo(channels);
    }

    @Test
    void navChannels_listsNone_whenLoggedOutOrTheApiFails() {
        filler().navChannels(model);
        assertThat(model.get("navChannels")).isEqualTo(List.of());
        verifyNoMoreInteractions(api);

        when(api.channelList()).thenReturn(null);
        inSession(() -> filler().navChannels(model));
        assertThat(model.get("navChannels")).isEqualTo(List.of());
    }

    /** Runs {@code action} within a request whose session is logged in. */
    private static void inSession(Runnable action) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setAttribute(ApiClient.SESSION_JWT_KEY, "jwt");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            action.run();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void channel_ownerGetsSettingsAndDtddMapping() {
        UserSettingsDto settings = mock(UserSettingsDto.class);
        DtddMappingStatusDto dtdd = new DtddMappingStatusDto(null, null, false, null);
        when(api.channelPage("enimaloc", 1, "AUTO", null)).thenReturn(page(true));
        when(api.channelSettings("enimaloc")).thenReturn(settings);
        when(api.dtddMappingStatus("enimaloc")).thenReturn(dtdd);

        filler().channel(model, "enimaloc", 1, "AUTO", null);

        assertThat(model).containsEntry("username", "enimaloc").containsEntry("channelPage", page(true))
                .containsEntry("channelSettings", settings).containsEntry("dtddMapping", dtdd);
    }

    @Test
    void channel_aModeratorGetsTheSettingsAndDtddMapping_butNotTheIntegrations() {
        UserSettingsDto settings = mock(UserSettingsDto.class);
        DtddMappingStatusDto dtdd = new DtddMappingStatusDto(null, null, false, null);
        when(api.channelPage("enimaloc", 0, null, null)).thenReturn(page(false));
        when(api.channelSettings("enimaloc")).thenReturn(settings);
        when(api.dtddMappingStatus("enimaloc")).thenReturn(dtdd);

        filler().channel(model, "enimaloc", 0, null, null);

        assertThat(model).containsEntry("channelSettings", settings).containsEntry("dtddMapping", dtdd)
                .doesNotContainKey("integrations");
        verify(api).channelPage("enimaloc", 0, null, null);
        verify(api).channelSettings("enimaloc");
        verify(api).dtddMappingStatus("enimaloc");
        verifyNoMoreInteractions(api);
    }

    @ParameterizedTest
    @CsvSource({"404, error.404", "429, error.429", "503, error.503", "418, error.generic", "504, error.generic"})
    void error_usesTheCodesOwnMessagesWhenItHasSome(int code, String prefix) {
        filler().error(model, code);

        assertThat(model).containsEntry("errorCode", code)
                .containsEntry("errorEyebrow", "error.eyebrow")
                .containsEntry("errorTitle", prefix + ".title")
                .containsEntry("errorDescription", prefix + ".description");
    }

    @Test
    void error_fromRequest_readsTheForwardedStatus_defaultingTo500() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        filler().error(model, request);
        assertThat(model).containsEntry("errorCode", 500);

        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 403);
        filler().error(model, request);
        assertThat(model).containsEntry("errorCode", 403).containsEntry("errorTitle", "error.403.title");
    }

    private String resource(String path) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            return new String(in.readAllBytes());
        }
    }
}
