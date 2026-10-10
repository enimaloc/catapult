package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.channel.ChannelListResponse;
import fr.enimaloc.catapult.common.dto.channel.ChannelPageData;
import fr.enimaloc.catapult.common.dto.channel.ChannelUserDto;
import fr.enimaloc.catapult.common.dto.channel.DtddMappingStatusDto;
import fr.enimaloc.catapult.common.dto.channel.PagedBindings;
import fr.enimaloc.catapult.common.dto.channel.UserSettingsDto;
import fr.enimaloc.catapult.service.ApiService;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.ExtendedModelMap;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

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
        return new ModelFiller(buildProperties, messages, api);
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
                null, null, null, null, null, null, "uuid");
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
                .containsEntry("channels", "page.title.channels")
                .containsOnlyKeys("", "privacy", "channels", "channel");
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
        when(api.channelList()).thenReturn(new ChannelListResponse("1", List.of()));

        filler().fill(model, "channels", Locale.ENGLISH);
        assertThat(model).containsKeys("channels", "app", "titles").containsEntry("page", "channels");

        model = new ExtendedModelMap();
        filler().fill(model, "privacy", Locale.ENGLISH);
        assertThat(model).containsKeys("privacy", "lastUpdate", "app");

        model = new ExtendedModelMap();
        filler().fill(model, "", Locale.ENGLISH);
        assertThat(model).doesNotContainKeys("privacy", "channels").containsKey("app");
    }

    @Test
    void channel_ownerAlsoGetsSettingsAndDtddMapping() {
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
    void channel_visitorGetsThePageOnly() {
        when(api.channelPage("enimaloc", 0, null, null)).thenReturn(page(false));

        filler().channel(model, "enimaloc", 0, null, null);

        assertThat(model).containsKeys("username", "channelPage").doesNotContainKeys("channelSettings", "dtddMapping");
        verify(api).channelPage("enimaloc", 0, null, null);
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
