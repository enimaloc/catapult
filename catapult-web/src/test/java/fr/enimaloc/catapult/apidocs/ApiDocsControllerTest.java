package fr.enimaloc.catapult.apidocs;

import fr.enimaloc.catapult.common.dto.binding.CustomBindingDto;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingsStateDto;
import fr.enimaloc.catapult.common.dto.channel.ChannelPageData;
import fr.enimaloc.catapult.common.dto.channel.ChannelUserDto;
import fr.enimaloc.catapult.common.dto.channel.ObsData;
import fr.enimaloc.catapult.common.dto.channel.PagedBindings;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatSettingsResponse;
import fr.enimaloc.catapult.config.I18nConfiguration;
import fr.enimaloc.catapult.config.TwitchatCatalogConfig;
import fr.enimaloc.catapult.controller.LocalInstanceBusConfig;
import fr.enimaloc.catapult.controller.ModelFiller;
import fr.enimaloc.catapult.controller.NavEventStreamController;
import fr.enimaloc.catapult.controller.SiteMetaTags;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiHealthProbe;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(controllers = ApiDocsController.class)
@Import({ModelFiller.class, SiteMetaTags.class, LocalInstanceBusConfig.class, WebSecurityConfig.class,
        TwitchatCatalogConfig.class, I18nConfiguration.class, ApiDocsAssets.class})
class ApiDocsControllerTest {
    static final String SPEC = "{\"openapi\":\"3.1.0\",\"servers\":[{\"url\":\"/\"}],\"paths\":{}}";
    static final Instant FETCHED_AT = Instant.parse("2026-10-27T09:30:00Z");

    @Autowired MockMvc mvc;
    @MockitoBean ApiDocsSpec spec;
    @MockitoBean ApiHealthProbe probe;
    @MockitoBean ApiService apiService;
    @MockitoBean NavEventStreamController navEvents;

    static final String LOGIN = "streamer";
    static final String WIDGET = "5f0c2a8e-9d3b-4c7a-8e21-6b4f1d2c9a70";

    String page(String path, Locale locale) throws Exception {
        return mvc.perform(get(path).locale(locale)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** A session JWT as catapult-api issues it: only its claims are read here. */
    static String jwt(String login) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return b64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + b64.encodeToString(("{\"sub\":\"" + UUID.randomUUID() + "\",\"username\":\"" + login + "\"}")
                .getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

    String memberPage(String path, Locale locale) throws Exception {
        return mvc.perform(get(path).locale(locale).sessionAttr(ApiClient.SESSION_JWT_KEY, jwt(LOGIN)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    static CustomBindingsStateDto customBindings(String... names) {
        return new CustomBindingsStateDto(true, Arrays.stream(names)
                .map(name -> new CustomBindingDto(UUID.randomUUID(), name, null, null, List.of(), List.of(), false))
                .toList(), null, null);
    }

    /**
     * The member's own channel page as catapult-api answers it: their OBS widget's settings when they have one (always,
     * for the owner, today), the custom bindings while the experiment is open to them.
     */
    static ChannelPageData ownChannel(ObsData obs, CustomBindingsStateDto customBindings) {
        return new ChannelPageData(new ChannelUserDto("1", "2", LOGIN, "img"), LOGIN, true, false, true,
                null, new PagedBindings(0, 1, 0, List.of()), List.of(), Set.of(), List.of(), Set.of(),
                null, null, null, null, null, obs, null, customBindings);
    }

    static final ObsData OBS = new ObsData(false, null, null, false);

    void docsAvailable() {
        when(spec.current()).thenReturn(Optional.of(new ApiDocsSpec.Cached(SPEC, FETCHED_AT, false)));
    }

    @Test
    void docsPage_member_showsKeysCard_withWidgetToken() throws Exception {
        docsAvailable();
        when(apiService.channelPage(LOGIN, 0, null, null)).thenReturn(ownChannel(OBS, null));
        when(apiService.twitchatSettings(LOGIN)).thenReturn(new TwitchatSettingsResponse(true, null, null, false, WIDGET));

        for (String html : List.of(memberPage("/docs/api", Locale.FRENCH), memberPage("/spa/docs/api", Locale.FRENCH))) {
            assertThat(html).contains("id=\"api-docs-keys-card\"", "id=\"api-docs-widget-value\"", WIDGET,
                    "id=\"api-docs-widget-copy-btn\"", "id=\"api-docs-read-token\"", "id=\"api-docs-read-token-status\"",
                    "id=\"api-docs-read-token-generate-btn\"", "Tes clés d&#39;API",
                    // The Account tab's labels, for api-token-cards.js.
                    "data-label-none=\"Aucun jeton de lecture pour le moment.\"", "data-label-since=\"Généré le {0}\"",
                    ">Générer un jeton de lecture<")
                    .doesNotContain("data-pending");
            // Above Swagger UI, and kept out of Sentry Replay with what it holds.
            assertThat(html.indexOf("id=\"api-docs-keys-card\"")).isLessThan(html.indexOf("id=\"swagger-ui\""));
            String card = html.substring(html.lastIndexOf("<mdui-card", html.indexOf("id=\"api-docs-keys-card\"")));
            assertThat(card.substring(0, card.indexOf('>'))).contains("sentry-block");
            assertThat(html.indexOf(WIDGET)).isGreaterThan(html.indexOf("id=\"api-docs-keys-card\""));
            assertThat(html).contains("api-docs-theme");
        }
        assertThat(memberPage("/spa/docs/api", Locale.ENGLISH)).contains("Your API keys", "OBS widget");
    }

    @Test
    void docsPage_member_withoutWidget_hasNoWidgetRow() throws Exception {
        docsAvailable();
        // No OBS on the channel page: nothing to put a widget's token in (catapult-api would still make one up).
        when(apiService.channelPage(LOGIN, 0, null, null)).thenReturn(ownChannel(null, null));
        when(apiService.twitchatSettings(LOGIN)).thenReturn(new TwitchatSettingsResponse(false, null, null, false, WIDGET));

        String html = memberPage("/docs/api", Locale.FRENCH);

        assertThat(html).contains("id=\"api-docs-keys-card\"", "id=\"api-docs-read-token\"")
                .doesNotContain("id=\"api-docs-widget\"", "id=\"api-docs-widget-unavailable\"", WIDGET);
        verify(apiService, never()).twitchatSettings(anyString());
    }

    @Test
    void docsPage_member_apiFailures_stillRenderTheCard_rowSaysUnavailable() throws Exception {
        docsAvailable();
        when(apiService.channelPage(LOGIN, 0, null, null)).thenReturn(ownChannel(OBS, customBindings()));
        when(apiService.twitchatSettings(anyString())).thenThrow(new IllegalStateException("down"));

        String widgetDown = memberPage("/docs/api", Locale.FRENCH);
        assertThat(widgetDown).contains("id=\"api-docs-keys-card\"", "id=\"api-docs-widget-unavailable\"",
                "id=\"api-docs-read-token\"", "id=\"api-docs-full-token\"").doesNotContain("id=\"api-docs-widget-value\"");

        when(apiService.channelPage(anyString(), anyInt(), any(), any())).thenThrow(new IllegalStateException("down"));
        String channelDown = memberPage("/spa/docs/api", Locale.FRENCH);
        assertThat(channelDown).contains("id=\"api-docs-keys-card\"", "id=\"api-docs-widget-unavailable\"",
                "id=\"api-docs-read-token\"").doesNotContain("id=\"api-docs-widget-value\"", "id=\"api-docs-full-token\"");
    }

    @Test
    void docsPage_anonymous_hasNoKeysCard_butThemedUi() throws Exception {
        docsAvailable();

        for (String html : List.of(page("/docs/api", Locale.FRENCH), page("/spa/docs/api", Locale.ENGLISH))) {
            assertThat(html).doesNotContain("id=\"api-docs-keys-card\"", "api-docs-read-token", "api-docs-widget")
                    .contains("id=\"swagger-ui\"", "class=\"api-docs-ui sentry-mask\"", "/css/api-docs-theme");
        }
        verify(apiService, never()).channelPage(anyString(), anyInt(), any(), any());
        verify(apiService, never()).twitchatSettings(anyString());
    }

    /** Swagger UI writes the token tried out into its curl and Request URL text: Replay records none of its text. */
    @Test
    void docsPage_swaggerUi_isMaskedInReplays() throws Exception {
        docsAvailable();

        String html = page("/docs/api", Locale.ENGLISH);
        String ui = html.substring(html.indexOf("id=\"swagger-ui\""));
        assertThat(ui.substring(0, ui.indexOf('>'))).contains("sentry-mask");
    }

    @Test
    void docsPage_fullTokenRow_onlyInCustomBindingsExperiment() throws Exception {
        docsAvailable();
        when(apiService.channelPage(LOGIN, 0, null, null)).thenReturn(ownChannel(OBS, null));

        String closed = memberPage("/docs/api", Locale.FRENCH);
        assertThat(closed).contains("id=\"api-docs-read-token\"").doesNotContain("id=\"api-docs-full-token\"");

        when(apiService.channelPage(LOGIN, 0, null, null)).thenReturn(ownChannel(OBS, customBindings("Speedrun")));
        for (String html : List.of(memberPage("/docs/api", Locale.FRENCH), memberPage("/spa/docs/api", Locale.FRENCH))) {
            assertThat(html).contains("id=\"api-docs-read-token\"", "id=\"api-docs-full-token\"",
                    "id=\"api-docs-full-token-generate-btn\"", "data-example-name=\"Speedrun\"",
                    "data-label-none=\"Aucun jeton pour le moment.\"", "id=\"api-docs-full-token-confirm-dialog\"");
        }
    }

    @Test
    void docsPage_prefetch_asksCatapultApiNothing_andRendersTheCardPending() throws Exception {
        docsAvailable();

        String html = mvc.perform(get("/spa/docs/api").locale(Locale.FRENCH).header("Purpose", "prefetch")
                        .sessionAttr(ApiClient.SESSION_JWT_KEY, jwt(LOGIN)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("id=\"api-docs-keys-card\"", "data-pending=\"true\"", "data-src=\"/spa/docs/api\"",
                        "data-label-unavailable=\"Tes clés sont illisibles", "id=\"swagger-ui\"")
                .doesNotContain("api-docs-read-token", "api-docs-widget", "api-docs-full-token");
        verify(apiService, never()).channelPage(anyString(), anyInt(), any(), any());
        verify(apiService, never()).twitchatSettings(anyString());
    }

    @Test
    void servesThePage_fullAndAsAFragment_withSwaggerUi() throws Exception {
        when(spec.current()).thenReturn(Optional.of(new ApiDocsSpec.Cached(SPEC, FETCHED_AT, false)));

        mvc.perform(get("/docs/api")).andExpect(view().name("index"));
        mvc.perform(get("/spa/docs/api")).andExpect(view().name("pages/api-docs :: api-docs"));
        String full = page("/docs/api", Locale.ENGLISH);
        String fragment = page("/spa/docs/api", Locale.ENGLISH);

        for (String html : List.of(full, fragment)) {
            assertThat(html).contains("id=\"swagger-ui\"", "data-spec=\"/docs/api/openapi.json\"",
                            "/_static/swagger-ui/5.34.0/swagger-ui-bundle.js", "/_static/swagger-ui/5.34.0/swagger-ui.css")
                    .contains("data-state=\"unknown\"", "Checking the API…", "data-label-up=\"API up\"")
                    .doesNotContain("id=\"api-docs-cached\"", "id=\"api-docs-unavailable\"");
        }
        assertThat(full).contains("<title>API documentation - ", "rel=\"canonical\"", "src=\"/js/api-docs-");
        // The page asks /api/health itself: rendering it never waits on the probe.
        verifyNoInteractions(probe);
    }

    @Test
    void tellsTheCopyIsCached_andFromWhen() throws Exception {
        when(spec.current()).thenReturn(Optional.of(new ApiDocsSpec.Cached(SPEC, FETCHED_AT, true)));
        String when = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault()).format(FETCHED_AT);

        String french = page("/docs/api", Locale.FRENCH);
        String english = page("/spa/docs/api", Locale.ENGLISH);

        assertThat(french).contains("id=\"api-docs-cached\"", "en cache, du " + when, "id=\"swagger-ui\"")
                .contains("data-fetched-at=\"2026-10-27T09:30:00Z\"", "data-label=\"API injoignable : documentation en cache, du {0}.\"")
                .contains("data-state=\"unknown\"", "Vérification de l&#39;API…");
        assertThat(english).contains("id=\"api-docs-cached\"", "cached, from " + when);
    }

    @Test
    void saysSoClearly_with200_whenThereIsNoSpec() throws Exception {
        when(spec.current()).thenReturn(Optional.empty());

        String french = page("/docs/api", Locale.FRENCH);
        String english = page("/spa/docs/api", Locale.ENGLISH);

        assertThat(french).contains("id=\"api-docs-unavailable\"").doesNotContain("id=\"swagger-ui\"");
        assertThat(english).contains("id=\"api-docs-unavailable\"", "be shown right now: the API")
                .doesNotContain("id=\"swagger-ui\"");
    }

    @Test
    void servesTheSpec() throws Exception {
        when(spec.current()).thenReturn(Optional.of(new ApiDocsSpec.Cached(SPEC, FETCHED_AT, true)));

        mvc.perform(get("/docs/api/openapi.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(content().string(SPEC));
    }

    @Test
    void answers503WithoutASpec() throws Exception {
        when(spec.current()).thenReturn(Optional.empty());

        mvc.perform(get("/docs/api/openapi.json"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "60"));
    }

    @Test
    void redirectsCatapultApisSwaggerUiToThePage() throws Exception {
        for (String path : List.of("/swagger-ui.html", "/swagger-ui/index.html")) {
            mvc.perform(get(path)).andExpect(status().isMovedPermanently()).andExpect(header().string("Location", "/docs/api"));
        }
    }

    @Test
    void servesSwaggerUisAssetsFromTheSite() throws Exception {
        for (String asset : List.of("swagger-ui-bundle.js", "swagger-ui.css")) {
            mvc.perform(get("/_static/swagger-ui/5.34.0/" + asset)).andExpect(status().isOk());
        }
    }

    @Test
    void servesNothingElseOfTheJar() throws Exception {
        for (String file : List.of("index.html", "swagger-initializer.js", "oauth2-redirect.html", "oauth2-redirect.js",
                "swagger-ui.js", "index.css")) {
            for (String root : List.of("/_static/swagger-ui/5.34.0/", "/webjars/swagger-ui/5.34.0/")) {
                int code = mvc.perform(get(root + file)).andReturn().getResponse().getStatus();
                assertThat(code).as(root + file).isNotEqualTo(200);
            }
        }
    }
}
